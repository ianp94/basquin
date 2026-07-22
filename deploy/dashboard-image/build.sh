#!/usr/bin/env bash
# Build the basquin/dashboard image the operator runs as a per-campaign dashboard (P5b,
# docs/CAMPAIGN-DESIGN.md, DD-025). Stages the harness fat jar from the Gradle build into ./, then
# docker-builds the image. Optionally loads it into a kind cluster for local e2e.
#
# Usage:
#   deploy/dashboard-image/build.sh [TAG] [KIND_CLUSTER]
#     TAG           image tag (default: the Gradle project version, e.g. 0.3.0). Also tags :latest.
#     KIND_CLUSTER  if set, `kind load docker-image` into that cluster after building.
#
# Examples:
#   deploy/dashboard-image/build.sh                     # basquin/dashboard:<version> + :latest
#   deploy/dashboard-image/build.sh 0.3.0 basquin    # ...and load into the `basquin` kind cluster
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$(dirname "${BASH_SOURCE[0]}")"
IMAGE="basquin/dashboard"

cd "$REPO_ROOT"

# The checked-in gradlew can carry CRLF on Windows checkouts, which breaks its shebang under WSL;
# run a stripped copy to be safe.
GRADLEW="./gradlew"
if head -1 ./gradlew | grep -q $'\r'; then
  GRADLEW_TMP=".gradlew.$$.lf"
  tr -d '\r' < ./gradlew > "$GRADLEW_TMP" && chmod +x "$GRADLEW_TMP" && GRADLEW="./$GRADLEW_TMP"
fi

echo "==> Building harness jar (jar)"
"$GRADLEW" jar -q
# Query the version through the SAME stripped wrapper, BEFORE removing it (see agents-image/build.sh).
VERSION="$("$GRADLEW" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' || true)"
rm -f ".gradlew.$$.lf" 2>/dev/null || true
TAG="${1:-${VERSION:-dev}}"
KIND_CLUSTER="${2:-}"

echo "==> Staging harness jar into $CTX/"
JAR="build/libs/basquin-${VERSION:-0.3.0}.jar"
[ -f "$JAR" ] || JAR="$(ls build/libs/basquin-*.jar 2>/dev/null | grep -v -- '-runner.jar' | head -1)"
[ -n "$JAR" ] && [ -f "$JAR" ] || die "harness jar not built (expected build/libs/basquin-*.jar)"
cp "$JAR" "$CTX/basquin.jar"

# STAGE_ONLY=1: stage the jar and stop, so a caller (release.yml) can drive `docker buildx` for a
# multi-arch push itself (the jar is arch-independent, so no per-arch work here). Leaves the staged
# jar in place for buildx; the default path below builds a single host-arch image and cleans up.
if [ "${STAGE_ONLY:-}" = "1" ]; then
  echo "==> STAGE_ONLY=1: context staged at $CTX (skipping docker build)"
  echo "    TAG=$TAG"
  exit 0
fi

echo "==> docker build $IMAGE:$TAG (+ :latest)"
docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$CTX"
# The staged jar is a build product; don't leave it in the source tree.
rm -f "$CTX/basquin.jar"

if [ -n "$KIND_CLUSTER" ]; then
  echo "==> kind load docker-image $IMAGE:$TAG into cluster '$KIND_CLUSTER'"
  kind load docker-image "$IMAGE:$TAG" "$IMAGE:latest" --name "$KIND_CLUSTER"
fi

echo "==> Done: $IMAGE:$TAG"
docker image inspect "$IMAGE:$TAG" --format '    size={{.Size}} bytes  id={{.Id}}' 2>/dev/null || true
