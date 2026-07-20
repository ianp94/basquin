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

die() { echo "ERROR: $*" >&2; exit 1; }

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
# Query the version through the SAME stripped wrapper, BEFORE removing it — otherwise on a CRLF
# checkout (the case this handling exists for) the query would run the broken original gradlew, fail
# silently, and TAG would wrongly fall back to "dev".
VERSION="$("$GRADLEW" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' || true)"
[ -f .gradlew.lf ] && rm -f .gradlew.lf || true
TAG="${1:-${VERSION:-dev}}"
KIND_CLUSTER="${2:-}"

echo "==> Staging agents into $CTX/agents/"
rm -rf "$CTX/agents"
mkdir -p "$CTX/agents"
# Explicit checks so a missing artifact is a clear message, not a bare `cp: cannot stat`.
for f in build/stage/closurejvm-agent.jar build/stage/closurejvm-valve.jar \
         build/jacoco/jacocoagent.jar build/native/libclosurejvmti.so; do
  [ -f "$f" ] || die "expected artifact not built: $f (native .so needs a C compiler + JDK headers)"
  cp "$f" "$CTX/agents/"
done

echo "==> docker build $IMAGE:$TAG (+ :latest)"
docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$CTX"

if [ -n "$KIND_CLUSTER" ]; then
  echo "==> kind load docker-image $IMAGE:$TAG into cluster '$KIND_CLUSTER'"
  kind load docker-image "$IMAGE:$TAG" "$IMAGE:latest" --name "$KIND_CLUSTER"
fi

echo "==> Done: $IMAGE:$TAG"
docker image inspect "$IMAGE:$TAG" --format '    size={{.Size}} bytes  id={{.Id}}' 2>/dev/null || true
