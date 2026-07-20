#!/usr/bin/env bash
# Build the closurejvm/agents image the operator injects (docs/OPERATOR-DESIGN.md §4, DD-024).
# Stages the agent artifacts from the Gradle build into ./agents/, then docker-builds the image.
# Optionally loads it into a kind cluster for local e2e.
#
# Usage:
#   deploy/agents-image/build.sh [TAG] [KIND_CLUSTER]
#     TAG           image tag (default: the Gradle project version, e.g. 0.2.0). Also tags :latest.
#     KIND_CLUSTER  if set, `kind load docker-image` into that cluster after building.
#
# Examples:
#   deploy/agents-image/build.sh                     # closurejvm/agents:<version> + :latest
#   deploy/agents-image/build.sh 0.2.0 closurejvm    # ...and load into the `closurejvm` kind cluster
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$(dirname "${BASH_SOURCE[0]}")"
IMAGE="closurejvm/agents"

cd "$REPO_ROOT"

# The checked-in gradlew can carry CRLF on Windows checkouts, which breaks its shebang under WSL;
# run a stripped copy to be safe.
GRADLEW="./gradlew"
if head -1 ./gradlew | grep -q $'\r'; then
  tr -d '\r' < ./gradlew > .gradlew.lf && chmod +x .gradlew.lf && GRADLEW="./.gradlew.lf"
fi

echo "==> Building agent artifacts (jars + native .so)"
"$GRADLEW" stageAgents copyJacocoAgent buildNativeAgent -q
[ -f .gradlew.lf ] && rm -f .gradlew.lf || true

VERSION="$("$REPO_ROOT/gradlew" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' || true)"
TAG="${1:-${VERSION:-dev}}"
KIND_CLUSTER="${2:-}"

if [ ! -f build/native/libclosurejvmti.so ]; then
  echo "ERROR: build/native/libclosurejvmti.so not built (need a C compiler + JDK headers). Aborting." >&2
  exit 1
fi

echo "==> Staging agents into $CTX/agents/"
rm -rf "$CTX/agents"
mkdir -p "$CTX/agents"
cp build/stage/closurejvm-agent.jar   "$CTX/agents/"
cp build/stage/closurejvm-valve.jar   "$CTX/agents/"
cp build/jacoco/jacocoagent.jar       "$CTX/agents/"
cp build/native/libclosurejvmti.so    "$CTX/agents/"

echo "==> docker build $IMAGE:$TAG (+ :latest)"
docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$CTX"

if [ -n "$KIND_CLUSTER" ]; then
  echo "==> kind load docker-image $IMAGE:$TAG into cluster '$KIND_CLUSTER'"
  kind load docker-image "$IMAGE:$TAG" "$IMAGE:latest" --name "$KIND_CLUSTER"
fi

echo "==> Done: $IMAGE:$TAG"
docker image inspect "$IMAGE:$TAG" --format '    size={{.Size}} bytes  id={{.Id}}' 2>/dev/null || true
