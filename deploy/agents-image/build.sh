#!/usr/bin/env bash
# Build the basquin/agents image the operator injects (docs/OPERATOR-DESIGN.md §4, DD-024).
# Stages the agent artifacts from the Gradle build into ./agents/, then docker-builds the image.
# Optionally loads it into a kind cluster for local e2e.
#
# Usage:
#   deploy/agents-image/build.sh [TAG] [KIND_CLUSTER]
#     TAG           image tag (default: the Gradle project version, e.g. 0.3.0). Also tags :latest.
#     KIND_CLUSTER  if set, `kind load docker-image` into that cluster after building.
#
# Examples:
#   deploy/agents-image/build.sh                     # basquin/agents:<version> + :latest
#   deploy/agents-image/build.sh 0.3.0 basquin    # ...and load into the `basquin` kind cluster
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$(dirname "${BASH_SOURCE[0]}")"
IMAGE="basquin/agents"

cd "$REPO_ROOT"

# The checked-in gradlew can carry CRLF on Windows checkouts, which breaks its shebang under WSL;
# run a stripped copy to be safe.
GRADLEW="./gradlew"
if head -1 ./gradlew | grep -q $'\r'; then
  GRADLEW_TMP=".gradlew.$$.lf"
  tr -d '\r' < ./gradlew > "$GRADLEW_TMP" && chmod +x "$GRADLEW_TMP" && GRADLEW="./$GRADLEW_TMP"
fi

echo "==> Building agent jars"
# The native .so is no longer built here — it's compiled per-arch inside the Dockerfile (DD-027), so
# `docker buildx --platform ...` gets a correct .so for each target arch. We only stage the jars +
# the native SOURCE into the build context.
"$GRADLEW" stageAgents copyJacocoAgent -q
# Query the version through the SAME stripped wrapper, BEFORE removing it — otherwise on a CRLF
# checkout (the case this handling exists for) the query would run the broken original gradlew, fail
# silently, and TAG would wrongly fall back to "dev".
VERSION="$("$GRADLEW" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' || true)"
rm -f ".gradlew.$$.lf" 2>/dev/null || true
TAG="${1:-${VERSION:-dev}}"
KIND_CLUSTER="${2:-}"

echo "==> Staging agents into $CTX/agents/ (+ native source into $CTX/native/)"
rm -rf "$CTX/agents" "$CTX/native"
mkdir -p "$CTX/agents" "$CTX/native"
# Explicit checks so a missing artifact is a clear message, not a bare `cp: cannot stat`.
for f in build/stage/basquin-agent.jar build/stage/basquin-valve.jar \
         build/jacoco/jacocoagent.jar; do
  [ -f "$f" ] || die "expected artifact not built: $f"
  cp "$f" "$CTX/agents/"
done
# Native SOURCE (not the built .so): the Dockerfile compiles it per-arch (DD-027).
for f in native/basquinjvmti.c native/Makefile; do
  [ -f "$f" ] || die "expected native source missing: $f"
  cp "$f" "$CTX/native/"
done

# STAGE_ONLY=1: stage the context and stop, so a caller (release.yml) can drive `docker buildx` for a
# multi-arch push itself. The default path below builds a single host-arch image for local/kind use.
if [ "${STAGE_ONLY:-}" = "1" ]; then
  echo "==> STAGE_ONLY=1: context staged at $CTX (skipping docker build)"
  echo "    TAG=$TAG"
  exit 0
fi

echo "==> docker build $IMAGE:$TAG (+ :latest)"
docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$CTX"

if [ -n "$KIND_CLUSTER" ]; then
  echo "==> kind load docker-image $IMAGE:$TAG into cluster '$KIND_CLUSTER'"
  kind load docker-image "$IMAGE:$TAG" "$IMAGE:latest" --name "$KIND_CLUSTER"
fi

echo "==> Done: $IMAGE:$TAG"
docker image inspect "$IMAGE:$TAG" --format '    size={{.Size}} bytes  id={{.Id}}' 2>/dev/null || true
