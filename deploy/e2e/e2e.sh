#!/usr/bin/env bash
# End-to-end test of the ClosureJVM operator IN-CLUSTER (kind): build + load every image, deploy the
# operator as a real pod with its scoped RBAC, deploy a RAW (un-instrumented) app, apply a
# ClosureJVMTarget, and assert the operator injects the agents and the app comes up with them loaded.
#
# This is the test that envtest structurally can't do: it exercises the built operator image, the
# namespaced ServiceAccount/Role/RoleBinding (not admin creds), the injected initContainer pulling
# the real agents image, and the agents actually loading in a live JVM.
#
# Usage:
#   deploy/e2e/e2e.sh [--teardown]
# Env (all optional):
#   CLUSTER=closurejvm            kind cluster name (created if missing)
#   NS=closurejvm-system          namespace for the operator + target
#   TAG=0.2.0                     image tag for agents + operator
#   RAW_APP_IMAGE=closurejvm/jpetstore-raw:0.2.0   raw app image (built if absent, see below)
#   JPETSTORE_WAR=/path/to.war    used to build the raw image if RAW_APP_IMAGE isn't present;
#                                 falls back to extracting ROOT.war from closurejvm/jpetstore-demo.
set -euo pipefail

CLUSTER="${CLUSTER:-closurejvm}"
NS="${NS:-closurejvm-system}"
TAG="${TAG:-0.2.0}"
RAW_APP_IMAGE="${RAW_APP_IMAGE:-closurejvm/jpetstore-raw:0.2.0}"
AGENTS_IMAGE="closurejvm/agents:${TAG}"
OPERATOR_IMAGE="closurejvm/operator:${TAG}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K="kubectl --context kind-${CLUSTER}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

if [ "${1:-}" = "--teardown" ]; then
  say "Tearing down"
  $K delete closurejvmtarget --all -n "$NS" --ignore-not-found --timeout=60s || true
  $K delete -f "$ROOT/operator/config/crd/bases" --ignore-not-found || true
  $K delete namespace "$NS" --ignore-not-found --timeout=90s || true
  echo "Done."; exit 0
fi

command -v docker >/dev/null || die "docker not found"
command -v kind   >/dev/null || die "kind not found"
command -v kubectl >/dev/null || die "kubectl not found"

say "Ensure kind cluster '$CLUSTER'"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"

say "Build + load agents image ($AGENTS_IMAGE)"
"$ROOT/deploy/agents-image/build.sh" "$TAG" "$CLUSTER"

say "Build + load operator image ($OPERATOR_IMAGE)"
docker build -t "$OPERATOR_IMAGE" "$ROOT/operator"
kind load docker-image "$OPERATOR_IMAGE" --name "$CLUSTER"

say "Ensure raw app image ($RAW_APP_IMAGE)"
if ! docker image inspect "$RAW_APP_IMAGE" >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  if [ -n "${JPETSTORE_WAR:-}" ]; then
    cp "$JPETSTORE_WAR" "$tmp/jpetstore.war"
  elif docker image inspect closurejvm/jpetstore-demo:latest >/dev/null 2>&1; then
    echo "  (extracting ROOT.war from closurejvm/jpetstore-demo:latest)"
    cid="$(docker create closurejvm/jpetstore-demo:latest)"
    docker cp "$cid:/usr/local/tomcat/webapps/ROOT.war" "$tmp/jpetstore.war"; docker rm "$cid" >/dev/null
  else
    die "no raw app image and no way to build it: set JPETSTORE_WAR=/path/to/jpetstore.war"
  fi
  cat > "$tmp/Dockerfile" <<'DOCKER'
FROM tomcat:9.0-jdk17-temurin
COPY jpetstore.war /usr/local/tomcat/webapps/ROOT.war
# Raw: NO agents, NO agent CATALINA_OPTS. The operator instruments this at deploy time.
DOCKER
  docker build -t "$RAW_APP_IMAGE" "$tmp"; rm -rf "$tmp"
fi
kind load docker-image "$RAW_APP_IMAGE" --name "$CLUSTER"

say "Install CRD + deploy operator (namespaced RBAC) into '$NS'"
$K apply -f "$ROOT/operator/config/crd/bases/closurejvm.dev_closurejvmtargets.yaml"
$K create namespace "$NS" --dry-run=client -o yaml | $K apply -f -
install="$(mktemp)"
$K kustomize "$ROOT/operator/config/default" | sed "s#image: controller:latest#image: ${OPERATOR_IMAGE}#" > "$install"
$K apply -f "$install"; rm -f "$install"
# Tell the operator which agents image to inject (fixed tag => initContainer uses the kind-loaded
# one). Idempotent: only append the flag if it isn't already there, so repeated runs (without
# --teardown) don't accumulate duplicate --agents-image args.
if ! $K -n "$NS" get deploy closurejvm-controller-manager \
      -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null | grep -q -- '--agents-image'; then
  $K -n "$NS" patch deploy closurejvm-controller-manager --type=json \
    -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--agents-image=${AGENTS_IMAGE}\"}]"
fi
$K -n "$NS" rollout restart deploy/closurejvm-controller-manager
$K -n "$NS" rollout status  deploy/closurejvm-controller-manager --timeout=120s

say "Clean slate (revert+remove any prior target, then the app) so each run starts from RAW"
# The operator (now running) reverts and releases the finalizer, so this blocks until fully gone.
# Without this, re-applying a raw manifest over an already-injected Deployment leaves the operator's
# annotations + initContainer but resets the env, which reads as 'already injected' and isn't re-fixed.
$K -n "$NS" delete closurejvmtarget jpetstore --ignore-not-found --timeout=60s
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

say "Apply ClosureJVMTarget"
$K apply -f - <<YAML
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMTarget
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
  [ "$ic" = "closurejvm-agents" ] && break
  sleep 3
done
[ "$ic" = "closurejvm-agents" ] || die "operator did not inject the initContainer within timeout"
$K -n "$NS" rollout status deploy/jpetstore --timeout=180s

say "ASSERT"
fail=0
check() { if eval "$2"; then printf '  \033[1;32mPASS\033[0m %s\n' "$1"; else printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; fail=1; fi; }

phase="$($K -n "$NS" get closurejvmtarget jpetstore -o jsonpath='{.status.phase}')"
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
  if echo "$cmdline" | grep -q libclosurejvmti.so && [ "$http" = "200" ]; then break; fi
  sleep 3
done

endpoint="$($K -n "$NS" get closurejvmtarget jpetstore -o jsonpath='{.status.coverageEndpoint}')"
svcip="$($K -n "$NS" get svc jpetstore-cjvm-jacoco -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
epaddr="$($K -n "$NS" get endpoints jpetstore-cjvm-jacoco -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"

check "target status is Injected"                         "[ '$phase' = 'Injected' ]"
check "operator injected the agents initContainer"        "echo '$initc' | grep -q 'closurejvm/agents'"
check "CATALINA_OPTS appended, original -Xmx512m kept"    "echo '$opts' | grep -q '^-Xmx512m ' && echo '$opts' | grep -q closurejvm-agent.jar"
check "operator ran with NO RBAC forbidden errors"        "[ '${forbidden:-0}' = '0' ]"
check "agents loaded on the live app JVM"                 "echo '$cmdline' | grep -q 'agentpath:/closurejvm/libclosurejvmti.so' && echo '$cmdline' | grep -q 'javaagent:/closurejvm/closurejvm-agent.jar'"
check "app serves HTTP 200 with agents loaded"            "[ '$http' = '200' ]"
check "coverage Service is headless (clusterIP None)"    "[ '$svcip' = 'None' ]"
check "coverage Service has the pod as an endpoint"      "[ -n '$epaddr' ]"
check "status.coverageEndpoint published (DD-023 flag)"  "echo '$endpoint' | grep -q 'jpetstore-cjvm-jacoco.*:6300'"

echo
if [ "$fail" = 0 ]; then printf '\033[1;32mE2E PASSED\033[0m — operator instrumented a raw app in-cluster.\n'; else die "one or more checks failed"; fi
