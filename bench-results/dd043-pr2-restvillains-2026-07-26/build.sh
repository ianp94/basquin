#!/usr/bin/env bash
# The containerized Maven build entrypoint for DD-043 PR-2's rest-villains acceptance.
# Usage: build.sh <maven args...>      e.g. build.sh clean package -DskipTests
#
# Same pattern as bench-results/dd043-spikes-2026-07-24/env/build.sh (see that script's own
# comments for the two deviations it discovered: the Mandrel image's ENTRYPOINT is native-image,
# not a shell, and the mapped host UID has no /etc/passwd entry so $HOME resolves to "/" and mvnw
# dies trying to mkdir "//.m2"). Both fixes are reused verbatim here.
#
# The one deliberate difference from the spike script: APP_DIR points OUTSIDE this repository, at
# a sibling clone of quarkusio/quarkus-super-heroes (see README.md — cloned to a sibling
# directory, never committed here), and the bind-mounted Maven local
# repo is the HOST's real ~/.m2, not a scratch directory scoped to one spike. That is a spec
# requirement (DD-043 PR-2 brief): the extension was published via `publishToMavenLocal` into
# ~/.m2, not the Phase-0 fixture's dd043Spike repository, so only the host's real ~/.m2 makes
# com.basquin:basquin-quarkus:0.3.0 resolvable from a build running outside this tree.
set -euo pipefail

APP_DIR="${APP_DIR:?set APP_DIR to the rest-villains checkout, e.g. .../quarkus-super-heroes/rest-villains}"

# Same digest Task 1 validated in the Phase-0 spikes (env/ENVIRONMENT.md): JDK 25 + native-image in
# one image, pinned by digest rather than the `jdk-25` tag so this build cannot silently drift onto
# a different image than the one already proven to work.
IMAGE="${BASQUIN_SPIKE_IMAGE:-quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5}"

# Bind the HOST's real ~/.m2 one level under an arbitrary container "home" directory, then point
# both the wrapper's shell-level $HOME (used only to find/cache the Maven distribution mvnw
# downloads) and the JVM's -Duser.home (used by the real `mvn` process to resolve its default local
# repository, ${user.home}/.m2/repository) at that same container directory. The result:
# $CONTAINER_HOME/.m2/repository inside the container IS $HOME/.m2/repository on the host — the
# same repository `./gradlew :basquin-quarkus:{runtime,deployment}:publishToMavenLocal` wrote to,
# so basquin-quarkus resolves for a build that runs entirely outside this repo's tree.
CONTAINER_HOME=/home/builder

exec docker run --rm \
  --entrypoint bash \
  -v "$APP_DIR":/w -w /w \
  -v "$HOME/.m2":"$CONTAINER_HOME/.m2" \
  -u "$(id -u):$(id -g)" \
  -e HOME="$CONTAINER_HOME" \
  -e MAVEN_OPTS="-Duser.home=$CONTAINER_HOME ${EXTRA_MAVEN_OPTS:-}" \
  "$IMAGE" -c './mvnw -B "$@"' bash "$@"
