#!/usr/bin/env bash
# Bring up the ClosureJVM kind demo: JPetStore (with all agents) as a pod in a local
# Kubernetes-in-Docker cluster. Then drive it with the coverage-guided HTTP runner.
#
#   JPETSTORE_WAR=/abs/jpetstore.war deploy/k8s/up.sh
#
# Requires: docker, kind, kubectl. Build the JPetStore WAR first (see docs/THIRD-PARTY-APPS.md).
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${JPETSTORE_WAR:?set JPETSTORE_WAR to a built jpetstore.war}"
CLUSTER="${KIND_CLUSTER:-closurejvm}"
IMAGE="closurejvm/jpetstore-demo:latest"

echo "==> Building agents"
./gradlew jar :tomcat-valve:jar copyJacocoAgent -q

echo "==> Staging image build context"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JPETSTORE_WAR"                                     "$STAGE/jpetstore.war"
cp build/libs/closurejvm-0.2.0.jar                     "$STAGE/closurejvm-agent.jar"
cp tomcat-valve/build/libs/closurejvm-valve-0.1.0.jar  "$STAGE/closurejvm-valve.jar"
cp build/jacoco/jacocoagent.jar                        "$STAGE/jacocoagent.jar"
cp deploy/valve/context.xml                            "$STAGE/context.xml"
cp deploy/k8s/Dockerfile.jpetstore                     "$STAGE/Dockerfile"

echo "==> Building image $IMAGE"
docker build -q -t "$IMAGE" "$STAGE" >/dev/null

echo "==> Ensuring kind cluster '$CLUSTER'"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"

echo "==> Loading image into the cluster"
kind load docker-image "$IMAGE" --name "$CLUSTER"

echo "==> Applying manifests"
kubectl apply -f deploy/k8s/jpetstore.yaml
kubectl rollout status deploy/jpetstore --timeout=180s

echo
echo "JPetStore is running in kind. To drive it with coverage:"
echo "  kubectl port-forward svc/jpetstore 8080:8080 6300:6300 &"
echo "  (cd \$(mktemp -d) && unzip -q '$JPETSTORE_WAR' 'WEB-INF/classes/*' && echo \$PWD)"
echo "  ./gradlew runHttpDriveCoverage -Dexamples.http.baseUrl=http://localhost:8080 \\"
echo "    -Dclosurejvm.coverage.jacoco=localhost:6300 -Dclosurejvm.coverage.classes=<dir>/WEB-INF/classes \\"
echo "    -Dclosurejvm.invariant.latency.maxMs=25 -Dclosurejvm.invariant.mode=soft"
