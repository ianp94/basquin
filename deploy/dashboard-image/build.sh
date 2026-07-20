#!/usr/bin/env bash
# Build the closurejvm/dashboard image the operator runs as a per-campaign dashboard (P5b,
# docs/CAMPAIGN-DESIGN.md, DD-025). Stages the harness fat jar from the Gradle build into ./, then
# docker-builds the image. Optionally loads it into a kind cluster for local e2e.
#
# Usage:
#   deploy/dashboard-image/build.sh [TAG] [KIND_CLUSTER]
#     TAG           image tag (default: the Gradle project version, e.g. 0.2.0). Also tags :latest.
#     KIND_CLUSTER  if set, `kind load docker-image` into that cluster after building.
#
# Examples:
#   deploy/dashboard-image/build.sh                     # closurejvm/dashboard:<version> + :latest
#   deploy/dashboard-image/build.sh 0.2.0 closurejvm    # ...and load into the `closurejvm` kind cluster
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$(dirname "${BASH_SOURCE[0]}")"
IMAGE="closurejvm/dashboard"

cd "$REPO_ROOT"

# The checked-in gradlew can carry CRLF on Windows checkouts, which breaks its shebang under WSL;
# run a stripped copy to be safe.
GRADLEW="./gradlew"
if head -1 ./gradlew | grep -q $'\r'; then
  tr -d '\r' < ./gradlew > .gradlew.lf && chmod +x .gradlew.lf && GRADLEW="./.gradlew.lf"
fi

echo "==> Building harness jar (jar)"
"$GRADLEW" jar -q
# Query the version through the SAME stripped wrapper, BEFORE removing it (see agents-image/build.sh).
VERSION="$("$GRADLEW" -q properties 2>/dev/null | awk -F': ' '/^version:/{print $2}' || true)"
[ -f .gradlew.lf ] && rm -f .gradlew.lf || true
TAG="${1:-${VERSION:-dev}}"
KIND_CLUSTER="${2:-}"

echo "==> Staging harness jar into $CTX/"
JAR="build/libs/closurejvm-${VERSION:-0.2.0}.jar"
[ -f "$JAR" ] || JAR="$(ls build/libs/closurejvm-*.jar 2>/dev/null | grep -v -- '-runner.jar' | head -1)"
[ -n "$JAR" ] && [ -f "$JAR" ] || die "harness jar not built (expected build/libs/closurejvm-*.jar)"
cp "$JAR" "$CTX/closurejvm.jar"

echo "==> docker build $IMAGE:$TAG (+ :latest)"
docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$CTX"
# The staged jar is a build product; don't leave it in the source tree.
rm -f "$CTX/closurejvm.jar"

if [ -n "$KIND_CLUSTER" ]; then
  echo "==> kind load docker-image $IMAGE:$TAG into cluster '$KIND_CLUSTER'"
  kind load docker-image "$IMAGE:$TAG" "$IMAGE:latest" --name "$KIND_CLUSTER"
fi

echo "==> Done: $IMAGE:$TAG"
docker image inspect "$IMAGE:$TAG" --format '    size={{.Size}} bytes  id={{.Id}}' 2>/dev/null || true
