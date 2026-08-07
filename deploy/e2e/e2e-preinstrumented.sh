#!/usr/bin/env bash
# End-to-end test of the "pre-instrumented target" path (DD-044 / PR-3.5) IN-CLUSTER (kind).
#
# Unlike deploy/e2e/e2e.sh (which proves the operator INJECTS agents into a raw app), this script
# proves the opposite: given an app that already carries Basquin's instrumentation at BUILD TIME
# (basquin-maven-injector, DD-043 §5) and a BasquinTarget{preInstrumented: true}, the operator
# observes it, NEVER mutates its pod template, and a mode:load BasquinCampaign can still drive it
# end-to-end through the DD-040 result channel (/__basquin/result).
#
# Stages (spec 2026-07-26-preinstrumented-targets-design.md §5):
#   1. Build the injected NATIVE image: basquin-core + basquin-quarkus published to a scratch Maven
#      repo, basquin-maven-injector built, and the DD-043 Phase-0 fixture built native
#      (-Dmaven.ext.class.path=<injector jar> -Dbasquin.inject.repo.url=<scratch repo>) via
#      containerized Mandrel (bench-results/dd043-spikes-2026-07-24/env/build.sh — no local
#      native-image binary is required or used).
#   2. Package the native binary into a container image (fixture's own Dockerfile.native).
#   3. kind load; deploy the operator (namespaced RBAC) + the runner image (no agents image needed —
#      the preInstrumented path never calls injection.go).
#   4. Deploy the already-instrumented app Deployment + Service; snapshot the Deployment BEFORE
#      applying any BasquinTarget.
#   5. Apply BasquinTarget{preInstrumented: true}; wait for Phase=Observed.
#   6. The TRIPLE NEGATIVE ASSERTION: snapshot the Deployment again and require (a) the pod template
#      unchanged (spec-hash equality), (b) metadata.generation unchanged, (c) no basquin.dev/* label
#      or annotation appeared on the Deployment metadata.
#   7. The channel probe: a request carrying X-Basquin-Req, then /__basquin/result?id=... expecting
#      a cost line, not "miss". Deliberately NOT /__basquin/drift or /__basquin/mode (basquin-quarkus's
#      BasquinControlHandler serves only result/violations/control/defect/*; see the design spec §5).
#   8. A mode:load BasquinCampaign (explicit "mode: load" — an omitted mode defaults to explore and
#      is rejected for a preInstrumented target) run to completion, reading its findings via the same
#      /__basquin/result channel the app already serves.
#
# This script uses its OWN dedicated kind cluster (default "basquin-dd044"), distinct from any
# pre-existing "basquin" cluster, so it never disturbs unrelated workloads.
#
# Usage:
#   deploy/e2e/e2e-preinstrumented.sh              # build + run the full pipeline
#   deploy/e2e/e2e-preinstrumented.sh --teardown   # delete the dedicated kind cluster
# Env (all optional):
#   CLUSTER=basquin-dd044       dedicated kind cluster name (created if missing)
#   NS=basquin-system           namespace for the operator + target + campaign
#   TAG=0.3.0                   image tag for the operator + runner images
#   INJ_PORT=8010                port the scratch Maven repo is served on (127.0.0.1)
#   EVID=bench-results/dd044-e2e-<UTC>   evidence directory (logs, snapshots, assertion output)
set -euo pipefail

CLUSTER="${CLUSTER:-basquin-dd044}"
NS="${NS:-basquin-system}"
TAG="${TAG:-0.3.0}"
INJ_PORT="${INJ_PORT:-8010}"
OPERATOR_IMAGE="basquin/operator:${TAG}"
RUNNER_IMAGE="basquin/runner:${TAG}"
FIXTURE_IMAGE="basquin/dd044-fixture-native:${TAG}"
CURL_IMAGE="curlimages/curl:8.11.1"
APP_NAME="dd044-fixture"
TARGET_NAME="dd044-fixture"
CAMPAIGN_NAME="dd044-fixture-load"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPIKE_DIR="$ROOT/bench-results/dd043-spikes-2026-07-24"
FIXTURE_DIR="$SPIKE_DIR/fixture"
K="kubectl --context kind-${CLUSTER}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

# Handled BEFORE the evidence dir is created (below) — a teardown run must not leave a stray empty
# bench-results/dd044-e2e-<UTC>/ behind.
if [ "${1:-}" = "--teardown" ]; then
  say "Tearing down dedicated cluster '$CLUSTER'"
  kind delete cluster --name "$CLUSTER" || true
  echo "Done."; exit 0
fi

EVID="${EVID:-$ROOT/bench-results/dd044-e2e-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$EVID"
echo "Evidence directory: $EVID"

command -v docker  >/dev/null || die "docker not found"
command -v kind    >/dev/null || die "kind not found"
command -v kubectl >/dev/null || die "kubectl not found"
command -v jq      >/dev/null || die "jq not found"
command -v python3 >/dev/null || die "python3 not found"

# The checked-in gradlew can carry CRLF on Windows checkouts, which breaks its shebang under WSL.
GRADLEW="$ROOT/gradlew"
if head -1 "$GRADLEW" | grep -q $'\r'; then
  TMP_GRADLEW="$ROOT/.gradlew.e2e-pi.$$.lf"
  tr -d '\r' < "$GRADLEW" > "$TMP_GRADLEW" && chmod +x "$TMP_GRADLEW" && GRADLEW="$TMP_GRADLEW"
fi
cleanup_gradlew() { if [ -n "${TMP_GRADLEW:-}" ]; then rm -f "$TMP_GRADLEW"; fi; }
trap cleanup_gradlew EXIT

# ==================================================================================================
# Stage 1: build the injected NATIVE image (DD-043 §5's chain, reused verbatim).
# ==================================================================================================
say "Build basquin-maven-injector jar"
( cd "$ROOT" && "$GRADLEW" :basquin-maven-injector:jar -q )
INJECTOR_VERSION="$(cd "$ROOT" && "$GRADLEW" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}')"
INJECTOR_VERSION="${INJECTOR_VERSION:-0.3.0}"
INJECTOR_JAR="$ROOT/basquin-maven-injector/build/libs/basquin-maven-injector-${INJECTOR_VERSION}.jar"
[ -f "$INJECTOR_JAR" ] || die "injector jar not built (expected $INJECTOR_JAR)"

say "Publish basquin-core + basquin-quarkus to a scratch Maven repo"
PAGES_DIR="$ROOT/build/tmp/dd044-e2e-pages"
rm -rf "$PAGES_DIR"
( cd "$ROOT" && "$GRADLEW" -PbasquinPagesDir="$PAGES_DIR" \
    :basquin-core:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository -q )
[ -d "$PAGES_DIR/com/basquin" ] || die "scratch Maven repo was not populated at $PAGES_DIR"

say "Serve the scratch repo on 127.0.0.1:${INJ_PORT}"
HTTP_LOG="/tmp/dd044-e2e-http.$$.log"
python3 -u -m http.server "$INJ_PORT" --bind 127.0.0.1 -d "$PAGES_DIR" > "$HTTP_LOG" 2>&1 &
HTTP_PID=$!
stop_http() { kill "$HTTP_PID" 2>/dev/null || true; }
trap 'stop_http; cleanup_gradlew' EXIT
sleep 1
curl -sf -o /dev/null "http://localhost:${INJ_PORT}/com/basquin/basquin-quarkus/${INJECTOR_VERSION}/basquin-quarkus-${INJECTOR_VERSION}.pom" \
  || die "scratch Maven repo did not come up on 127.0.0.1:${INJ_PORT} (see $HTTP_LOG)"

say "Stage the injector jar for the containerized build"
INJ_STAGE="$ROOT/build/tmp/dd044-e2e-inj"
mkdir -p "$INJ_STAGE"
cp "$INJECTOR_JAR" "$INJ_STAGE/"

say "Ambiguity control: purge com.basquin from the fixture's local Maven repo"
rm -rf "$SPIKE_DIR/.m2/.m2/repository/com/basquin"

say "Native build via containerized Mandrel (env/build.sh; no local native-image binary is used)"
set +e
EXTRA_DOCKER_ARGS="--network host -v ${INJ_STAGE}:/inj" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-${INJECTOR_VERSION}.jar -Dbasquin.inject.repo.url=http://localhost:${INJ_PORT}/" \
  "$SPIKE_DIR/env/build.sh" clean package -DskipTests -Dnative 2>&1 | tee "$EVID/build-native.log"
BUILD_STATUS="${PIPESTATUS[0]}"
set -e

stop_http; trap cleanup_gradlew EXIT
[ "$BUILD_STATUS" = "0" ] || die "native build (env/build.sh clean package -DskipTests -Dnative) exited $BUILD_STATUS. See $EVID/build-native.log"
grep -q '\[basquin-injector\] instrumented fixture' "$EVID/build-native.log" \
  || die "the injector never announced itself in the native build log — the injected native image was NOT built. See $EVID/build-native.log"

NATIVE_BIN="$FIXTURE_DIR/target/fixture-1.0.0-SNAPSHOT-runner"
[ -x "$NATIVE_BIN" ] || die "native binary missing or not executable at $NATIVE_BIN"
say "Native binary built: $(stat -c '%s bytes' "$NATIVE_BIN")"

# ==================================================================================================
# Stage 2: package the native binary into a container image.
# ==================================================================================================
say "docker build the fixture's native image ($FIXTURE_IMAGE)"
docker build -f "$FIXTURE_DIR/src/main/docker/Dockerfile.native" -t "$FIXTURE_IMAGE" "$FIXTURE_DIR" \
  2>&1 | tee "$EVID/docker-build-fixture.log"

# ==================================================================================================
# Stage 3: dedicated kind cluster + build/load the operator + runner images (no agents image — the
# preInstrumented path never calls injection.go) + a curl probe image (for the channel probe, since
# the fixture's ubi9-minimal base is not guaranteed to carry curl).
# ==================================================================================================
say "Ensure dedicated kind cluster '$CLUSTER'"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"

say "Build the operator image ($OPERATOR_IMAGE)"
docker build -t "$OPERATOR_IMAGE" "$ROOT/operator" 2>&1 | tee "$EVID/docker-build-operator.log"

say "Build the runner image ($RUNNER_IMAGE)"
bash "$ROOT/deploy/runner-image/build.sh" "$TAG" 2>&1 | tee "$EVID/docker-build-runner.log"

say "docker pull the curl probe image ($CURL_IMAGE)"
docker pull "$CURL_IMAGE" >/dev/null

say "Load images into kind"
for img in "$FIXTURE_IMAGE" "$OPERATOR_IMAGE" "$RUNNER_IMAGE" "$CURL_IMAGE"; do
  kind load docker-image "$img" --name "$CLUSTER"
done

say "Install CRDs + deploy operator (kustomize, namespaced RBAC) into '$NS'"
$K apply -f "$ROOT/operator/config/crd/bases/basquin.dev_basquintargets.yaml"
$K apply -f "$ROOT/operator/config/crd/bases/basquin.dev_basquincampaigns.yaml"
$K create namespace "$NS" --dry-run=client -o yaml | $K apply -f -
INSTALL_YAML="$(mktemp)"
$K kustomize "$ROOT/operator/config/default" | sed "s#image: controller:latest#image: ${OPERATOR_IMAGE}#" > "$INSTALL_YAML"
$K apply -f "$INSTALL_YAML"; rm -f "$INSTALL_YAML"
if ! $K -n "$NS" get deploy basquin-controller-manager \
      -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null | grep -q -- '--runner-image'; then
  $K -n "$NS" patch deploy basquin-controller-manager --type=json \
    -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--runner-image=${RUNNER_IMAGE}\"}]"
fi
$K -n "$NS" rollout restart deploy/basquin-controller-manager
$K -n "$NS" rollout status  deploy/basquin-controller-manager --timeout=120s

# ==================================================================================================
# Stage 4: clean slate, deploy the already-instrumented app, snapshot BEFORE applying the CR.
# ==================================================================================================
say "Clean slate (remove any prior campaign/target/app from an earlier run)"
$K -n "$NS" delete basquincampaign "$CAMPAIGN_NAME" --ignore-not-found --timeout=60s
$K -n "$NS" delete configmap "${APP_NAME}-corpus" --ignore-not-found
$K -n "$NS" delete basquintarget "$TARGET_NAME" --ignore-not-found --timeout=60s
$K -n "$NS" delete deploy "$APP_NAME" --ignore-not-found --timeout=90s
$K -n "$NS" delete pod dd044-probe --ignore-not-found --timeout=30s

say "Deploy the already-instrumented app (single replica, no agents, no operator involvement yet)"
$K apply -f - <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: { name: ${APP_NAME}, namespace: ${NS}, labels: { app: ${APP_NAME} } }
spec:
  replicas: 1
  selector: { matchLabels: { app: ${APP_NAME} } }
  template:
    metadata: { labels: { app: ${APP_NAME} } }
    spec:
      containers:
        - name: ${APP_NAME}
          image: ${FIXTURE_IMAGE}
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          readinessProbe: { httpGet: { path: /ok, port: 8080 }, initialDelaySeconds: 2, periodSeconds: 2 }
YAML
$K -n "$NS" rollout status deploy/"$APP_NAME" --timeout=120s

$K apply -f - <<YAML
apiVersion: v1
kind: Service
metadata: { name: ${APP_NAME}, namespace: ${NS} }
spec:
  selector: { app: ${APP_NAME} }
  ports: [{ port: 8080, targetPort: 8080 }]
YAML

say "Snapshot the Deployment BEFORE applying any BasquinTarget"
$K -n "$NS" get deploy "$APP_NAME" -o json > "$EVID/deployment-before.json"
jq -S '.spec.template' "$EVID/deployment-before.json" | sha256sum | awk '{print $1}' > "$EVID/before.template-hash.txt"
jq -r '.metadata.generation' "$EVID/deployment-before.json" > "$EVID/before.generation.txt"
BEFORE_HASH="$(cat "$EVID/before.template-hash.txt")"
BEFORE_GEN="$(cat "$EVID/before.generation.txt")"
echo "  template-hash=$BEFORE_HASH generation=$BEFORE_GEN"

# ==================================================================================================
# Stage 5: apply BasquinTarget{preInstrumented: true}; wait for Phase=Observed.
# ==================================================================================================
say "Apply BasquinTarget{preInstrumented: true}"
$K apply -f - <<YAML
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata: { name: ${TARGET_NAME}, namespace: ${NS} }
spec:
  deploymentRef: { name: ${APP_NAME} }
  container: ${APP_NAME}
  preInstrumented: true
YAML

say "Wait for Phase=Observed"
phase=""
for i in $(seq 1 40); do
  phase="$($K -n "$NS" get basquintarget "$TARGET_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  echo "  [$i] phase=${phase:-<none>}"
  [ "$phase" = "Observed" ] && break
  sleep 3
done
{ echo "phase transitions observed during wait: (see poll log above; final=$phase)"; } > "$EVID/target-phase-final.txt"
[ "$phase" = "Observed" ] || die "BasquinTarget never reached Phase=Observed (last seen: '$phase')"
$K -n "$NS" get basquintarget "$TARGET_NAME" -o yaml > "$EVID/basquintarget-observed.yaml"

# ==================================================================================================
# Stage 6: THE TRIPLE NEGATIVE ASSERTION.
# ==================================================================================================
say "Snapshot the Deployment AFTER Phase=Observed"
$K -n "$NS" get deploy "$APP_NAME" -o json > "$EVID/deployment-after.json"
jq -S '.spec.template' "$EVID/deployment-after.json" | sha256sum | awk '{print $1}' > "$EVID/after.template-hash.txt"
jq -r '.metadata.generation' "$EVID/deployment-after.json" > "$EVID/after.generation.txt"
AFTER_HASH="$(cat "$EVID/after.template-hash.txt")"
AFTER_GEN="$(cat "$EVID/after.generation.txt")"
BASQUIN_KEYS="$(jq -r '((.metadata.labels // {}) + (.metadata.annotations // {})) | keys[]' "$EVID/deployment-after.json" | grep -c '^basquin\.dev/' || true)"

fail=0
check() { if eval "$2"; then printf '  \033[1;32mPASS\033[0m %s\n' "$1"; echo "PASS $1" >> "$EVID/assertions.txt"; else printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; echo "FAIL $1" >> "$EVID/assertions.txt"; fail=1; fi; }

echo "  before.template-hash=$BEFORE_HASH after.template-hash=$AFTER_HASH"
echo "  before.generation=$BEFORE_GEN after.generation=$AFTER_GEN"
echo "  basquin.dev/* keys on Deployment metadata: ${BASQUIN_KEYS:-0}"
check "target reached Phase=Observed"                          "[ '$phase' = 'Observed' ]"
check "TRIPLE NEGATIVE 1/3: pod-template spec-hash unchanged"  "[ '$BEFORE_HASH' = '$AFTER_HASH' ]"
check "TRIPLE NEGATIVE 2/3: metadata.generation unchanged"     "[ '$BEFORE_GEN' = '$AFTER_GEN' ]"
check "TRIPLE NEGATIVE 3/3: no basquin.dev/* label or annotation appeared" "[ '${BASQUIN_KEYS:-0}' = '0' ]"

# ==================================================================================================
# Stage 7: the channel probe. A curl-capable probe pod (NOT relying on the app's minimal base image
# carrying curl) sends X-Basquin-Req, then polls /__basquin/result expecting a cost line, not "miss".
# Deliberately does NOT touch /__basquin/drift or /__basquin/mode (design spec §5: basquin-quarkus's
# BasquinControlHandler serves only result/violations/control/defect/*; those two routes are
# DD-030/DD-035 Tomcat-valve-only checks that would fail here for reasons unrelated to this feature).
# ==================================================================================================
say "Start a curl probe pod"
$K -n "$NS" run dd044-probe --image="$CURL_IMAGE" --image-pull-policy=IfNotPresent \
  --restart=Never --command -- sleep 3600
$K -n "$NS" wait --for=condition=Ready pod/dd044-probe --timeout=60s

APP_URL="http://${APP_NAME}.${NS}.svc.cluster.local:8080"
REQID="dd044-e2e-probe-1"
say "Send a request carrying X-Basquin-Req, then poll /__basquin/result"
$K -n "$NS" exec dd044-probe -- curl -s -H "X-Basquin-Req: ${REQID}" -o /dev/null "${APP_URL}/ok"
RESULT=""
for i in $(seq 1 10); do
  RESULT="$($K -n "$NS" exec dd044-probe -- curl -s "${APP_URL}/__basquin/result?id=${REQID}" 2>/dev/null || true)"
  [ -n "$RESULT" ] && [ "$RESULT" != "miss" ] && break
  sleep 1
done
echo "  /__basquin/result -> ${RESULT:-<empty>}" | tee "$EVID/channel-probe.txt"
check "channel probe: /__basquin/result returned a cost line, not 'miss'" \
  "[ -n '$RESULT' ] && [ '$RESULT' != 'miss' ] && echo '$RESULT' | grep -qE '^[0-9-]+,[0-9-]+,[0-9-]+\\|'"

# ==================================================================================================
# Stage 8: a mode:load BasquinCampaign to completion, reading /__basquin/result.
# ==================================================================================================
say "Create the corpus ConfigMap (hand-authored: pure load replay, no grammar)"
$K -n "$NS" create configmap "${APP_NAME}-corpus" --from-literal=corpus.txt="/ok" \
  --dry-run=client -o yaml | $K apply -f -

say "Apply BasquinCampaign{mode: load} (mode spelled EXPLICITLY — an omitted mode defaults to explore and is rejected)"
$K apply -f - <<YAML
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata: { name: ${CAMPAIGN_NAME}, namespace: ${NS} }
spec:
  mode: load
  targetRef: { name: ${TARGET_NAME} }
  baseURL: ${APP_URL}
  dashboard: { enabled: false }
  driver:
    duration: 20s
    concurrency: 2
    corpusConfigMap: ${APP_NAME}-corpus
YAML

say "Wait for the campaign to reach a terminal phase"
cphase=""
for i in $(seq 1 60); do
  cphase="$($K -n "$NS" get basquincampaign "$CAMPAIGN_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  echo "  [$i] campaign phase=${cphase:-<none>}"
  case "$cphase" in Completed|Failed) break;; esac
  sleep 3
done
$K -n "$NS" get basquincampaign "$CAMPAIGN_NAME" -o yaml > "$EVID/basquincampaign-final.yaml"
cjob="$($K -n "$NS" get basquincampaign "$CAMPAIGN_NAME" -o jsonpath='{.status.driverJob}' 2>/dev/null || true)"
if [ -n "$cjob" ]; then
  $K -n "$NS" logs "job/$cjob" > "$EVID/driver-job.log" 2>&1 || true
fi
if [ "$cphase" != "Completed" ]; then
  echo "  (campaign phase=$cphase; driver Job logs for triage:)"
  $K -n "$NS" logs "job/$cjob" --tail=60 2>/dev/null | sed 's/^/    /' || true
fi
lreq="$($K -n "$NS" get basquincampaign "$CAMPAIGN_NAME" -o jsonpath='{.status.load.requests}' 2>/dev/null || true)"
cowner="$($K -n "$NS" get job "$cjob" -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null || true)"
echo "  campaign requests=${lreq:-<none>} driverJob=${cjob:-<none>} owner=${cowner:-<none>}"
check "load campaign reached Completed"          "[ '$cphase' = 'Completed' ]"
check "load run reported requests > 0"           "[ '${lreq:-0}' -ge 1 ]"
check "operator owns the driver Job (GC wired)"  "[ '$cowner' = 'BasquinCampaign' ]"

echo
if [ "$fail" = 0 ]; then
  printf '\033[1;32mE2E PASSED\033[0m — a build-time-instrumented native target reached Observed with its Deployment provably untouched, and a mode:load campaign drove it to completion via /__basquin/result.\n'
else
  die "one or more checks failed"
fi
