#!/usr/bin/env bash
# End-to-end test of the Basquin operator IN-CLUSTER (kind): build + load every image, deploy the
# operator as a real pod with its scoped RBAC, deploy a RAW (un-instrumented) app, apply a
# BasquinTarget, and assert the operator injects the agents and the app comes up with them loaded.
# Then apply a BasquinCampaign and assert the operator drives a coverage run to a non-zero %,
# brings up a per-campaign dashboard (P5b), and the driver's pushes land on it.
#
# This is the test that envtest structurally can't do: it exercises the built operator image, the
# namespaced ServiceAccount/Role/RoleBinding (not admin creds), the injected initContainer pulling
# the real agents image, the agents actually loading in a live JVM, the campaign driver Job reading
# live coverage back from the target and reporting it as status, and the per-campaign dashboard.
#
# Usage:
#   deploy/e2e/e2e.sh [--teardown]
# Env (all optional):
#   CLUSTER=basquin            kind cluster name (created if missing)
#   NS=basquin-system          namespace for the operator + target
#   TAG=0.2.0                     image tag for agents + operator
#   RAW_APP_IMAGE=basquin/jpetstore-raw:0.2.0   raw app image (built if absent, see below)
#   JPETSTORE_WAR=/path/to.war    used to build the raw image if RAW_APP_IMAGE isn't present;
#                                 falls back to extracting ROOT.war from basquin/jpetstore-demo.
set -euo pipefail

CLUSTER="${CLUSTER:-basquin}"
NS="${NS:-basquin-system}"
TAG="${TAG:-0.2.0}"
RAW_APP_IMAGE="${RAW_APP_IMAGE:-basquin/jpetstore-raw:0.2.0}"
AGENTS_IMAGE="basquin/agents:${TAG}"
OPERATOR_IMAGE="basquin/operator:${TAG}"
RUNNER_IMAGE="basquin/runner:${TAG}"
DASHBOARD_IMAGE="basquin/dashboard:${TAG}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K="kubectl --context kind-${CLUSTER}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

if [ "${1:-}" = "--teardown" ]; then
  say "Tearing down"
  $K delete basquincampaign --all -n "$NS" --ignore-not-found --timeout=60s || true
  $K delete basquintarget --all -n "$NS" --ignore-not-found --timeout=60s || true
  $K delete -f "$ROOT/operator/config/crd/bases" --ignore-not-found || true
  $K delete namespace "$NS" --ignore-not-found --timeout=90s || true
  echo "Done."; exit 0
fi

command -v docker >/dev/null || die "docker not found"
command -v kind   >/dev/null || die "kind not found"
command -v kubectl >/dev/null || die "kubectl not found"

# --- build everything in parallel with cluster creation (pipeline-speed, TODO "E2e pipeline speed").
# One gradle invocation stages every jar up front, so the build.sh scripts' own gradle calls are
# up-to-date no-ops and the four docker builds are genuinely independent.
say "Pre-build all jars (single gradle invocation)"
(
  cd "$ROOT"
  GRADLEW=./gradlew
  if head -1 ./gradlew | grep -q $'\r'; then
    tr -d '\r' < ./gradlew > ".gradlew.e2e.$$.lf" && chmod +x ".gradlew.e2e.$$.lf" && GRADLEW="./.gradlew.e2e.$$.lf"
  fi
  "$GRADLEW" stageAgents copyJacocoAgent runnerJar jar -q
  rm -f ".gradlew.e2e.$$.lf"
)

say "Ensure kind cluster '$CLUSTER' (parallel with image builds)"
( kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" ) \
  > /tmp/e2e-cluster.log 2>&1 & cluster_pid=$!

# PREBUILT_OPERATOR=1: CI pre-builds the operator image with a persistent buildx layer cache
# (operator-e2e.yml) — skip the local, uncached rebuild if that image is already in the daemon.
build_operator() {
  if [ "${PREBUILT_OPERATOR:-}" = "1" ] && docker image inspect "$OPERATOR_IMAGE" >/dev/null 2>&1; then
    echo "operator image prebuilt ($OPERATOR_IMAGE) — skipping docker build"
    return 0
  fi
  docker build -t "$OPERATOR_IMAGE" "$ROOT/operator"
}

say "Build images in parallel (agents, operator, runner, dashboard)"
bash "$ROOT/deploy/agents-image/build.sh"    "$TAG" > /tmp/e2e-build-agents.log    2>&1 & agents_pid=$!
build_operator                                      > /tmp/e2e-build-operator.log  2>&1 & operator_pid=$!
bash "$ROOT/deploy/runner-image/build.sh"    "$TAG" > /tmp/e2e-build-runner.log    2>&1 & runner_pid=$!
bash "$ROOT/deploy/dashboard-image/build.sh" "$TAG" > /tmp/e2e-build-dashboard.log 2>&1 & dashboard_pid=$!
build_pids=("$agents_pid" "$operator_pid" "$runner_pid" "$dashboard_pid")
build_names=(agents operator runner dashboard)
build_name_of() { local i; for i in "${!build_pids[@]}"; do [ "${build_pids[$i]}" = "$1" ] && { echo "${build_names[$i]}"; return; }; done; echo "unknown"; }
build_failed() {
  local name; name="$(build_name_of "$1")"
  echo "--- $name image build failed; full log: ---"; cat "/tmp/e2e-build-$name.log" 2>/dev/null || true
  kill "${build_pids[@]}" 2>/dev/null || true
  die "$name image build failed"
}
# Fail fast on the FIRST failing build, not the first in list order (#49 review). `wait -n -p`
# needs bash >= 5.1 (CI has it); older shells fall back to ordered waits — same result, just
# reported later when a fast failure sits behind a slower build.
if [ "${BASH_VERSINFO[0]}" -gt 5 ] || { [ "${BASH_VERSINFO[0]}" -eq 5 ] && [ "${BASH_VERSINFO[1]}" -ge 1 ]; }; then
  left=${#build_pids[@]}
  while [ "$left" -gt 0 ]; do
    done_pid=""
    wait -n -p done_pid "${build_pids[@]}" || build_failed "$done_pid"
    left=$((left-1))
  done
else
  for pid in "${build_pids[@]}"; do wait "$pid" || build_failed "$pid"; done
fi
wait "$cluster_pid" || { cat /tmp/e2e-cluster.log; die "kind cluster create failed"; }

say "Load images into kind (parallel)"
load_pids=(); load_names=()
for img in "$AGENTS_IMAGE" "$OPERATOR_IMAGE" "$RUNNER_IMAGE" "$DASHBOARD_IMAGE"; do
  lname="${img##*/}"; lname="${lname%%:*}"
  kind load docker-image "$img" --name "$CLUSTER" > "/tmp/e2e-load-$lname.log" 2>&1 & load_pids+=($!); load_names+=("$lname")
done
for i in "${!load_pids[@]}"; do
  wait "${load_pids[$i]}" || { cat "/tmp/e2e-load-${load_names[$i]}.log" 2>/dev/null || true; die "kind load failed for ${load_names[$i]}"; }
done

say "Ensure raw app image ($RAW_APP_IMAGE)"
if ! docker image inspect "$RAW_APP_IMAGE" >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  if [ -n "${JPETSTORE_WAR:-}" ]; then
    cp "$JPETSTORE_WAR" "$tmp/jpetstore.war"
  elif docker image inspect basquin/jpetstore-demo:latest >/dev/null 2>&1; then
    echo "  (extracting ROOT.war from basquin/jpetstore-demo:latest)"
    cid="$(docker create basquin/jpetstore-demo:latest)"
    docker cp "$cid:/usr/local/tomcat/webapps/ROOT.war" "$tmp/jpetstore.war"; docker rm "$cid" >/dev/null
  else
    die "no raw app image and no way to build it: set JPETSTORE_WAR=/path/to/jpetstore.war"
  fi
  cat > "$tmp/Dockerfile" <<'DOCKER'
FROM tomcat:9.0-jdk17-temurin
# Explode the war into an exploded webapp (ROOT/) rather than shipping ROOT.war. Two reasons:
#   1) Tomcat serves the exploded dir directly (no runtime unpack).
#   2) The campaign driver's initContainer copies WEB-INF/classes OUT OF THIS IMAGE to compute
#      coverage (CAMPAIGN-DESIGN §7b) — those .class files must exist as files in the image, which a
#      war-only image doesn't provide (the exploded dir is otherwise runtime-only/ephemeral).
COPY jpetstore.war /tmp/ROOT.war
RUN mkdir -p /usr/local/tomcat/webapps/ROOT \
 && cd /usr/local/tomcat/webapps/ROOT \
 && jar xf /tmp/ROOT.war \
 && rm -f /tmp/ROOT.war
# Raw: NO agents, NO agent CATALINA_OPTS. The operator instruments this at deploy time.
DOCKER
  docker build -t "$RAW_APP_IMAGE" "$tmp"; rm -rf "$tmp"
fi
kind load docker-image "$RAW_APP_IMAGE" --name "$CLUSTER"

if [ "${INSTALL:-kustomize}" = "helm" ]; then
  say "Install operator via HELM CHART (namespaced RBAC) into '$NS'"
  command -v helm >/dev/null || die "helm not found (INSTALL=helm)"
  # Clear any prior KUSTOMIZE-style install that would collide — Helm refuses to adopt resources it
  # doesn't own (managed-by: kustomize), and the chart's namespaced Role vs kustomize's ClusterRole
  # makes the RoleBinding roleRef immutable-incompatible anyway. Remove the kustomize-owned operator
  # resources (by name — labels aren't on all of them) so Helm gets a clean install. A prior *Helm*
  # release is handled by `upgrade --install`; skip the wipe if this namespace is already Helm-owned.
  if [ "$($K -n "$NS" get sa basquin-controller-manager -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}' 2>/dev/null)" = "kustomize" ]; then
    $K -n "$NS" delete deploy,sa,role,rolebinding basquin-controller-manager basquin-manager-rolebinding \
      basquin-leader-election-role basquin-leader-election-rolebinding --ignore-not-found >/dev/null 2>&1 || true
    $K delete clusterrole basquin-manager-role --ignore-not-found >/dev/null 2>&1 || true
  fi
  # Helm installs CRDs from crds/ but NEVER upgrades them (documented caveat). Apply them explicitly so
  # an iterating e2e picks up CRD changes (new fields like spec.mode) instead of strict-decoding errors.
  $K apply -f "$ROOT/operator/config/crd/bases/"
  helm --kube-context "kind-${CLUSTER}" upgrade --install basquin "$ROOT/deploy/helm/basquin-operator" \
    --namespace "$NS" --create-namespace \
    --set fullnameOverride=basquin \
    --set imageTag="$TAG" \
    --set image.repository="${OPERATOR_IMAGE%:*}" \
    --set images.agents="${AGENTS_IMAGE%:*}" \
    --set images.runner="${RUNNER_IMAGE%:*}" \
    --set images.dashboard="${DASHBOARD_IMAGE%:*}" \
    --wait --timeout=150s
  # Same image tag across runs => the Deployment spec is unchanged => helm won't restart the pod, so a
  # freshly `kind load`ed image wouldn't be picked up. Force a restart (the kustomize path does too).
  $K -n "$NS" rollout restart deploy/basquin-controller-manager
  $K -n "$NS" rollout status  deploy/basquin-controller-manager --timeout=120s
else
  say "Install CRDs + deploy operator (kustomize, namespaced RBAC) into '$NS'"
  $K apply -f "$ROOT/operator/config/crd/bases/basquin.dev_basquintargets.yaml"
  $K apply -f "$ROOT/operator/config/crd/bases/basquin.dev_basquincampaigns.yaml"
  $K create namespace "$NS" --dry-run=client -o yaml | $K apply -f -
  install="$(mktemp)"
  $K kustomize "$ROOT/operator/config/default" | sed "s#image: controller:latest#image: ${OPERATOR_IMAGE}#" > "$install"
  $K apply -f "$install"; rm -f "$install"
  # Tell the operator which agents image to inject (fixed tag => initContainer uses the kind-loaded
  # one). Idempotent: only append the flag if it isn't already there, so repeated runs (without
  # --teardown) don't accumulate duplicate --agents-image args.
  if ! $K -n "$NS" get deploy basquin-controller-manager \
        -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null | grep -q -- '--agents-image'; then
    $K -n "$NS" patch deploy basquin-controller-manager --type=json \
      -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--agents-image=${AGENTS_IMAGE}\"}]"
  fi
  # Likewise pin the runner image the campaign driver Job runs (fixed tag => kind-loaded one).
  if ! $K -n "$NS" get deploy basquin-controller-manager \
        -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null | grep -q -- '--runner-image'; then
    $K -n "$NS" patch deploy basquin-controller-manager --type=json \
      -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--runner-image=${RUNNER_IMAGE}\"}]"
  fi
  # ...and the per-campaign dashboard image (P5b).
  if ! $K -n "$NS" get deploy basquin-controller-manager \
        -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null | grep -q -- '--dashboard-image'; then
    $K -n "$NS" patch deploy basquin-controller-manager --type=json \
      -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--dashboard-image=${DASHBOARD_IMAGE}\"}]"
  fi
  $K -n "$NS" rollout restart deploy/basquin-controller-manager
  $K -n "$NS" rollout status  deploy/basquin-controller-manager --timeout=120s
fi

say "Clean slate (revert+remove any prior target, then the app) so each run starts from RAW"
# The operator (now running) reverts and releases the finalizer, so this blocks until fully gone.
# Without this, re-applying a raw manifest over an already-injected Deployment leaves the operator's
# annotations + initContainer but resets the env, which reads as 'already injected' and isn't re-fixed.
# Delete the campaign first so its owned driver Job is GC'd before the target it depends on goes away.
$K -n "$NS" delete basquincampaign jpetstore-campaign jpetstore-load --ignore-not-found --timeout=60s
$K -n "$NS" delete configmap jpetstore-grammar jpetstore-corpus --ignore-not-found
$K -n "$NS" delete basquintarget jpetstore --ignore-not-found --timeout=60s
$K -n "$NS" delete deploy jpetstore --ignore-not-found --timeout=90s

say "Deploy RAW app (no agents; CATALINA_OPTS set, to prove append-not-replace)"
$K apply -f - <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: { name: jpetstore, namespace: ${NS}, labels: { app: jpetstore-raw } }
spec:
  replicas: 1
  selector: { matchLabels: { app: jpetstore-raw } }
  template:
    metadata: { labels: { app: jpetstore-raw } }
    spec:
      containers:
        - name: jpetstore
          image: ${RAW_APP_IMAGE}
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          env: [{ name: CATALINA_OPTS, value: "-Xmx512m" }]
          readinessProbe: { httpGet: { path: /, port: 8080 }, initialDelaySeconds: 10, periodSeconds: 5 }
YAML
$K -n "$NS" rollout status deploy/jpetstore --timeout=150s

say "Expose the app on a ClusterIP Service (the campaign driver's baseURL)"
$K apply -f - <<YAML
apiVersion: v1
kind: Service
metadata: { name: jpetstore-app, namespace: ${NS} }
spec:
  selector: { app: jpetstore-raw }
  ports: [{ port: 8080, targetPort: 8080 }]
YAML

say "Apply BasquinTarget"
$K apply -f - <<YAML
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata: { name: jpetstore, namespace: ${NS} }
spec:
  deploymentRef: { name: jpetstore }
  container: jpetstore
  jvmOptsVar: CATALINA_OPTS
  agents: { threadTracker: true, coverage: { enabled: true, port: 6300, includes: "org.mybatis.jpetstore.*" } }
  invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }
  coverageService: true
YAML

say "Wait for injection to roll out"
# Key on the initContainer actually being present on the Deployment (proof the operator injected
# THIS generation), not on status.phase — which can be a stale 'Injected' from a prior run while the
# just-applied raw template hasn't been re-injected yet. Then wait for that generation to roll out.
for i in $(seq 1 60); do
  ic="$($K -n "$NS" get deploy jpetstore -o jsonpath='{.spec.template.spec.initContainers[0].name}' 2>/dev/null || true)"
  [ "$ic" = "basquin-agents" ] && break
  sleep 3
done
[ "$ic" = "basquin-agents" ] || die "operator did not inject the initContainer within timeout"
$K -n "$NS" rollout status deploy/jpetstore --timeout=180s

say "ASSERT"
fail=0
check() { if eval "$2"; then printf '  \033[1;32mPASS\033[0m %s\n' "$1"; else printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; fail=1; fi; }

phase="$($K -n "$NS" get basquintarget jpetstore -o jsonpath='{.status.phase}')"
initc="$($K -n "$NS" get deploy jpetstore -o jsonpath='{.spec.template.spec.initContainers[0].image}')"
opts="$($K -n "$NS" get deploy jpetstore -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="CATALINA_OPTS")].value}')"
# Newest Running operator pod — a rollout-restart can leave the previous one briefly Terminating,
# and label list order isn't creation-ordered.
opod="$($K -n "$NS" get pod -l control-plane=controller-manager --field-selector=status.phase=Running \
        --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}')"
forbidden="$($K -n "$NS" logs "$opod" 2>/dev/null | grep -c forbidden || true)"

# Live-pod checks: pick the NEWEST Running pod (the injected one; an old raw pod may still be
# terminating right after the rollout) and retry through the settle so a transient miss doesn't
# read as failure.
cmdline=""; http=""
for i in $(seq 1 20); do
  apod="$($K -n "$NS" get pod -l app=jpetstore-raw --field-selector=status.phase=Running \
          --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}' 2>/dev/null || true)"
  [ -n "$apod" ] || { sleep 3; continue; }
  cmdline="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c 'cat /proc/1/cmdline | tr "\0" " "' 2>/dev/null || true)"
  http="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c 'curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/' 2>/dev/null || true)"
  if echo "$cmdline" | grep -q libbasquinjvmti.so && [ "$http" = "200" ]; then break; fi
  sleep 3
done

endpoint="$($K -n "$NS" get basquintarget jpetstore -o jsonpath='{.status.coverageEndpoint}')"
svcip="$($K -n "$NS" get svc jpetstore-basquin-jacoco -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
epaddr="$($K -n "$NS" get endpoints jpetstore-basquin-jacoco -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"

check "target status is Injected"                         "[ '$phase' = 'Injected' ]"
check "operator injected the agents initContainer"        "echo '$initc' | grep -q 'basquin/agents'"
check "CATALINA_OPTS appended, original -Xmx512m kept"    "echo '$opts' | grep -q '^-Xmx512m ' && echo '$opts' | grep -q basquin-agent.jar"
check "operator ran with NO RBAC forbidden errors"        "[ '${forbidden:-0}' = '0' ]"
check "agents loaded on the live app JVM"                 "echo '$cmdline' | grep -q 'agentpath:/basquin/libbasquinjvmti.so' && echo '$cmdline' | grep -q 'javaagent:/basquin/basquin-agent.jar'"
check "app serves HTTP 200 with agents loaded"            "[ '$http' = '200' ]"
check "coverage Service is headless (clusterIP None)"    "[ '$svcip' = 'None' ]"
check "coverage Service has the pod as an endpoint"      "[ -n '$epaddr' ]"
check "status.coverageEndpoint published (DD-023 flag)"  "echo '$endpoint' | grep -q 'jpetstore-basquin-jacoco.*:6300'"

# ---------------------------------------------------------------------------------------------------
# Campaign (P5a): now that the target is Injected and exporting coverage, run a real BasquinCampaign
# end-to-end — the operator launches the driver Job (runner image), which drives HTTP traffic through
# the app's routes and reads live coverage back from the target's JaCoCo Service, then writes a summary
# the operator surfaces as status.coveragePct. Only meaningful once the injection checks above pass.
# ---------------------------------------------------------------------------------------------------
if [ "$phase" = "Injected" ] && [ -n "$endpoint" ]; then
  say "Create grammar + corpus ConfigMaps + apply BasquinCampaign (drives traffic, reads coverage)"
  $K -n "$NS" create configmap jpetstore-grammar \
    --from-file=jpetstore.grammar="$ROOT/examples/grammar/jpetstore.grammar" \
    --dry-run=client -o yaml | $K apply -f -
  # Flat corpus: route-seed files (top level) + value files (values/) as basename keys. --from-file on
  # a dir is non-recursive, so pass both levels. loadSeeds walks it for /-prefixed routes; the grammar's
  # @../corpus/... value refs fall back to <corpusDir>/<basename> (DD-018).
  $K -n "$NS" create configmap jpetstore-corpus \
    --from-file="$ROOT/examples/corpus/jpetstore/" \
    --from-file="$ROOT/examples/corpus/jpetstore/values/" \
    --dry-run=client -o yaml | $K apply -f -
  $K apply -f - <<YAML
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata: { name: jpetstore-campaign, namespace: ${NS} }
spec:
  targetRef: { name: jpetstore }
  baseURL: http://jpetstore-app.${NS}.svc.cluster.local:8080
  driver:
    iterations: 200
    grammarConfigMap: jpetstore-grammar
    corpusConfigMap: jpetstore-corpus
    classesPath: /usr/local/tomcat/webapps/ROOT/WEB-INF/classes
YAML

  say "Wait for the campaign to reach a terminal phase"
  cphase=""
  for i in $(seq 1 120); do   # up to ~6 min: driver Job build + 200 coverage-guided iterations
    cphase="$($K -n "$NS" get basquincampaign jpetstore-campaign -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$cphase" in Completed|Failed) break;; esac
    sleep 3
  done
  cpct="$($K -n "$NS" get basquincampaign jpetstore-campaign -o jsonpath='{.status.coveragePct}' 2>/dev/null || true)"
  cjob="$($K -n "$NS" get basquincampaign jpetstore-campaign -o jsonpath='{.status.driverJob}' 2>/dev/null || true)"
  cowner="$($K -n "$NS" get job "$cjob" -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null || true)"
  if [ "$cphase" != "Completed" ]; then
    echo "  (campaign phase=$cphase; driver Job logs for triage:)"
    $K -n "$NS" logs "job/$cjob" --tail=40 2>/dev/null | sed 's/^/    /' || true
  fi

  # coveragePct is a string like "12.4"; reduce to yes/no HERE (awk quoting is fragile through eval).
  cpos="$(awk 'BEGIN{exit !('"${cpct:-0}"'+0 > 0)}' >/dev/null 2>&1 && echo yes || echo no)"
  # Corpus propagation: with a grammar present the routes come from grammar.expandAll(), so the corpus
  # matters as *values* — the grammar's @-value-file refs resolve via the corpusDir fallback. Assert
  # that positively from the driver log (robust vs. coverage's run-to-run wobble): at least one value
  # file resolved via corpusDir, and none was reported missing.
  dlog="$(mktemp)"; $K -n "$NS" logs "job/$cjob" > "$dlog" 2>/dev/null || true
  valresolved="$(grep -q 'resolved values .* via corpusDir' "$dlog" && echo yes || echo no)"
  valsmissing="$(grep -q 'values file not found' "$dlog" && echo yes || echo no)"
  check "campaign reached Completed"                         "[ '$cphase' = 'Completed' ]"
  check "operator owns the driver Job (GC wired)"            "[ '$cowner' = 'BasquinCampaign' ]"
  check "campaign reported non-zero coverage %"              "[ '$cpos' = 'yes' ]"
  check "grammar resolved corpus value files via corpusDir"  "[ '$valresolved' = 'yes' ]"
  check "no corpus value file was missing"                   "[ '$valsmissing' = 'no' ]"
  echo "  (campaign coveragePct=${cpct:-<none>}; $(grep -c 'via corpusDir' "$dlog") value-file(s) resolved from corpus)"
  rm -f "$dlog"

  # --- DD-026 PR 1: the run emits its interesting "replay corpus" as a campaign-owned ConfigMap ---
  ccorpus="$($K -n "$NS" get basquincampaign jpetstore-campaign -o jsonpath='{.status.corpusConfigMap}' 2>/dev/null || true)"
  ccount=0; cowner2=""
  if [ -n "$ccorpus" ]; then
    ccount="$($K -n "$NS" get configmap "$ccorpus" -o jsonpath='{.data.corpus\.txt}' 2>/dev/null | grep -c '^/' || true)"
    cowner2="$($K -n "$NS" get configmap "$ccorpus" -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null || true)"
  fi
  check "campaign emitted status.corpusConfigMap"            "echo '$ccorpus' | grep -q 'jpetstore-campaign-corpus-out'"
  check "replay-corpus ConfigMap has route entries"          "[ '${ccount:-0}' -ge 1 ]"
  check "replay-corpus ConfigMap owned by the campaign (GC)" "[ '$cowner2' = 'BasquinCampaign' ]"
  echo "  (corpusConfigMap=${ccorpus:-<none>}; ${ccount} route(s))"

  # DD-030: the agent-installed boundary (no valve mounted by the operator) intercepts requests on the
  # app's own port. Proof it's live in-cluster: the /__basquin control surface responds. A 3-field CSV
  # (heapKb,threads,epochMs) from /__basquin/drift is only possible if premain instrumented
  # StandardHostValve — i.e. the same code path that runs Agent.begin/end server-side in explore.
  sdrift=""; smode=""
  if [ -n "$apod" ]; then
    sdrift="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s http://localhost:8080/__basquin/drift" 2>/dev/null || true)"
    smode="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s 'http://localhost:8080/__basquin/mode?to=explore'" 2>/dev/null || true)"
  fi
  check "DD-030: agent boundary serves /__basquin/drift in-cluster (CSV)" "echo '$sdrift' | grep -qE '^[0-9]+,[0-9]+,[0-9]+$'"
  check "DD-030: agent boundary serves /__basquin/mode control"          "[ '$smode' = 'ok:explore' ]"
  echo "  (server-side boundary: drift=${sdrift:-<none>}, mode-toggle=${smode:-<none>})"

  # DD-031: cost-ranked replay corpus. (a) the target-side boundary emits the per-request cost channel
  # (X-Basquin-Cost: latencyMs,heapDeltaKb,threadDelta) on the same explore boundary path exercised
  # above — proof the cost signal is live in-cluster via the DD-030 boundary, agent or valve alike.
  scost=""
  if [ -n "$apod" ]; then
    scost="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s -D - -o /dev/null http://localhost:8080/ | tr -d '\r' | awk -F': ' '/^X-Basquin-Cost/{print \$2}'" 2>/dev/null || true)"
  fi
  check "DD-031: boundary emits X-Basquin-Cost (latency,heap,thread) in-cluster" "echo '$scost' | grep -qE '^[0-9-]+,[0-9-]+,[0-9-]+$'"
  echo "  (cost header: ${scost:-<none>})"

  # (b) the explore driver Job (its pod persists until campaign GC) logged the cost-ranked replay
  # summary (Task 6): proof the corpus was actually ranked by cost, not just that the header exists.
  # $cjob is status.driverJob, captured above right after the campaign reached Completed.
  dcost="$($K -n "$NS" logs "job/$cjob" 2>/dev/null | grep -m1 'replay cost-ranked' || true)"
  check "DD-031: driver emitted a cost-ranked replay (top routes + costs)" "echo '$dcost' | grep -qE 'replay cost-ranked \(top [0-9]+\): .+='"
  echo "  ($dcost)"

  # --- DD-026 PR 2: replay that corpus as a LOAD campaign, watch invariants under sustained traffic --
  if [ -n "$ccorpus" ]; then
    say "Apply a mode:load BasquinCampaign replaying the emitted corpus"
    $K apply -f - <<YAML
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata: { name: jpetstore-load, namespace: ${NS} }
spec:
  mode: load
  targetRef: { name: jpetstore }
  baseURL: http://jpetstore-app.${NS}.svc.cluster.local:8080
  driver:
    duration: 45s
    concurrency: 20
    corpusConfigMap: ${ccorpus}
YAML
    lphase=""
    for i in $(seq 1 60); do   # 45s run + build/startup
      lphase="$($K -n "$NS" get basquincampaign jpetstore-load -o jsonpath='{.status.phase}' 2>/dev/null || true)"
      case "$lphase" in Completed|Failed) break;; esac
      sleep 3
    done
    lreq="$($K -n "$NS" get basquincampaign jpetstore-load -o jsonpath='{.status.load.requests}' 2>/dev/null || true)"
    lrps="$($K -n "$NS" get basquincampaign jpetstore-load -o jsonpath='{.status.load.throughputRps}' 2>/dev/null || true)"
    lp99="$($K -n "$NS" get basquincampaign jpetstore-load -o jsonpath='{.status.load.latencyMs.p99}' 2>/dev/null || true)"
    ljob="$($K -n "$NS" get basquincampaign jpetstore-load -o jsonpath='{.status.driverJob}' 2>/dev/null || true)"
    linit="$($K -n "$NS" get job "$ljob" -o jsonpath='{.spec.template.spec.initContainers[*].name}' 2>/dev/null || true)"
    if [ "$lphase" != "Completed" ]; then
      echo "  (load phase=$lphase; driver logs:)"; $K -n "$NS" logs "job/$ljob" --tail=30 2>/dev/null | sed 's/^/    /' || true
    fi
    # The agent-installed boundary (Task: DD-030) now serves /__basquin on the operator target — asserted
    # above. Here we only assert the load run's own client-side outputs.
    check "load campaign reached Completed"                    "[ '$lphase' = 'Completed' ]"
    check "load run reported requests > 0"                     "[ '${lreq:-0}' -ge 1 ]"
    check "load run reported throughput + p99 latency"         "[ -n '$lrps' ] && [ -n '$lp99' ]"
    check "load driver Job has NO coverage initContainers"     "echo ':$linit:' | grep -qv extract-classes"
    echo "  (load: requests=${lreq:-<none>}, throughputRps=${lrps:-<none>}, p99=${lp99:-<none>}ms)"
    $K -n "$NS" delete basquincampaign jpetstore-load --ignore-not-found --timeout=60s >/dev/null 2>&1 || true
  fi

  # --- P5b: the operator brought up a per-campaign dashboard and the driver pushed to it ---------
  say "Assert the per-campaign dashboard (P5b)"
  durl="$($K -n "$NS" get basquincampaign jpetstore-campaign -o jsonpath='{.status.dashboardURL}' 2>/dev/null || true)"
  dname="jpetstore-campaign-dashboard"
  dsvc="$($K -n "$NS" get svc "$dname" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || true)"
  downer="$($K -n "$NS" get deploy "$dname" -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null || true)"
  $K -n "$NS" rollout status deploy/"$dname" --timeout=90s || true
  davail="$($K -n "$NS" get deploy "$dname" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || true)"
  # Reachability + data: curl the dashboard Service from inside the cluster (the app pod has curl).
  # Reads are token-gated (DD-028): a bare read must 401, a token-bearing read must return the
  # campaign id — proof both that auth is enforced and that the driver's push actually landed.
  dtok="$($K -n "$NS" get secret jpetstore-campaign-dashboard-token -o jsonpath='{.data.token}' 2>/dev/null | base64 -d || true)"
  dapi=""; dunauth=""
  if [ -n "$apod" ]; then
    dunauth="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c \
      "curl -s -o /dev/null -w '%{http_code}' http://${dname}.${NS}.svc.cluster.local:7070/api/campaigns" 2>/dev/null || true)"
    dapi="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c \
      "curl -s -H 'X-Basquin-Token: ${dtok}' http://${dname}.${NS}.svc.cluster.local:7070/api/campaigns" 2>/dev/null || true)"
  fi

  check "status.dashboardURL points at the dashboard Service"  "echo '$durl' | grep -q '${dname}.*:7070'"
  check "dashboard Service exposes port 7070"                  "[ '$dsvc' = '7070' ]"
  check "operator owns the dashboard Deployment (GC wired)"    "[ '$downer' = 'BasquinCampaign' ]"
  check "dashboard Deployment is available"                    "[ '${davail:-0}' -ge 1 ]"
  check "dashboard reads reject an unauthenticated request"    "[ '$dunauth' = '401' ]"
  check "dashboard is reachable and saw the campaign's pushes" "echo '$dapi' | grep -q 'jpetstore-campaign'"
  echo "  (dashboardURL=${durl:-<none>}; /api/campaigns=${dapi:-<none>})"
else
  echo "  (skipping campaign checks — injection did not complete, nothing to drive)"
  fail=1
fi

echo
if [ "$fail" = 0 ]; then printf '\033[1;32mE2E PASSED\033[0m — operator instrumented a raw app, ran a coverage campaign, and stood up its dashboard in-cluster.\n'; else die "one or more checks failed"; fi
