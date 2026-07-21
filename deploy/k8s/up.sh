#!/usr/bin/env bash
# Bring up the Basquin kind demo: JPetStore (with all agents) as a pod in a local
# Kubernetes-in-Docker cluster. Then drive it with the coverage-guided HTTP runner.
#
#   JPETSTORE_WAR=/abs/jpetstore.war deploy/k8s/up.sh
#
# Requires: docker, kind, kubectl. Build the JPetStore WAR first (see docs/THIRD-PARTY-APPS.md).
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${JPETSTORE_WAR:?set JPETSTORE_WAR to a built jpetstore.war}"
CLUSTER="${KIND_CLUSTER:-basquin}"
# Every kubectl call is pinned to the kind context. `kind create cluster` is what sets
# current-context, and it is skipped when the cluster already exists -- so on a re-run an
# unpinned kubectl would deploy into whatever context happens to be active (possibly a
# shared or staging cluster).
KCTX="kind-${CLUSTER}"
# Unique tag per build: with a fixed :latest tag the manifest is byte-identical between runs,
# `kubectl apply` is a no-op, and `rollout status` reports success while the pod keeps the
# previously loaded image -- i.e. you measure a stale build and are told it deployed.
TAG="$(date +%Y%m%d%H%M%S)"
IMAGE="basquin/jpetstore-demo:${TAG}"

echo "==> Building agents"
./gradlew jar :tomcat-valve:jar copyJacocoAgent -q

echo "==> Staging image build context"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JPETSTORE_WAR"                                     "$STAGE/jpetstore.war"
cp build/libs/basquin-0.2.0.jar                     "$STAGE/basquin-agent.jar"
cp tomcat-valve/build/libs/basquin-valve-0.1.0.jar  "$STAGE/basquin-valve.jar"
cp build/jacoco/jacocoagent.jar                        "$STAGE/jacocoagent.jar"
cp deploy/valve/context.xml                            "$STAGE/context.xml"
cp deploy/k8s/Dockerfile.jpetstore                     "$STAGE/Dockerfile"

echo "==> Building image $IMAGE"
docker build -q -t "$IMAGE" "$STAGE" >/dev/null

echo "==> Ensuring kind cluster '$CLUSTER'"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"

echo "==> Loading image into the cluster"
kind load docker-image "$IMAGE" --name "$CLUSTER"

echo "==> Applying manifests (context: $KCTX)"
kubectl --context "$KCTX" apply -f deploy/k8s/jpetstore.yaml
kubectl --context "$KCTX" set image deploy/jpetstore jpetstore="$IMAGE"
kubectl --context "$KCTX" rollout status deploy/jpetstore --timeout=180s

echo
echo "JPetStore is running in kind. To drive it with coverage:"
echo "  kubectl --context $KCTX port-forward svc/jpetstore 8080:8080 6300:6300 &"
echo "  (cd \$(mktemp -d) && unzip -q '$JPETSTORE_WAR' 'WEB-INF/classes/*' && echo \$PWD)"
echo "  ./gradlew runHttpDriveCoverage -Dexamples.http.baseUrl=http://localhost:8080 \\"
echo "    -Dbasquin.coverage.jacoco=localhost:6300 -Dbasquin.coverage.classes=<dir>/WEB-INF/classes \\"
echo "    -Dbasquin.invariant.latency.maxMs=25 -Dbasquin.invariant.mode=soft"
