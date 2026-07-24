#!/usr/bin/env bash
# The single containerized build entrypoint for all DD-043 Phase-0 spikes.
# Usage: env/build.sh <maven args...>      e.g. env/build.sh package -Dnative
#
# Image: quay.io/quarkus/ubi9-quarkus-mandrel-builder-image, jdk-25 lineage
# (Candidate A), pinned below by digest rather than the `jdk-25` tag. This
# image carries JDK 25 *and* native-image, so no Maven step ever touches
# the JDK-17 host and native builds need no docker socket.
#
# Deviation from the brief: the image's ENTRYPOINT is `native-image`, not a
# shell, so plain `docker run ... "$IMAGE" ./mvnw -B "$@"` would hand mvnw's
# argv to native-image instead of running it. We override the entrypoint to
# bash and use the `bash -c 'script "$@"' bash "$@"` idiom so the caller's
# maven args still land in build.sh's own "$@" inside the container.
#
# Second deviation: the container's arbitrary non-root UID (-u host-uid:gid)
# has no /etc/passwd entry, so $HOME resolves to "/" rather than /m2. mvnw
# uses $HOME (not -Duser.home) to pick its wrapper-distribution cache dir, so
# without an explicit HOME it tries to mkdir "//.m2" and dies with EACCES.
# -e HOME=/m2 fixes it; MAVEN_OPTS's -Duser.home=/m2 covers Maven's own local
# repo once mvnw has bootstrapped the real Maven binary.
set -euo pipefail
SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pinned by digest, not the `jdk-25` tag: the tag is a moving target, so
# pinning it would let Tasks 3-5 silently build against a different image
# than the one Task 1 validated (see env/ENVIRONMENT.md). BASQUIN_SPIKE_IMAGE
# still overrides this for a later spike that needs a different image.
IMAGE="${BASQUIN_SPIKE_IMAGE:-quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5}"
mkdir -p "$SPIKE_DIR/.m2"
# EXTRA_DOCKER_ARGS below is expanded unquoted on purpose, so it word-splits
# into multiple docker args (Task 3 depends on this). That means callers must
# not pass paths containing spaces or glob characters — an unquoted expansion
# both word-splits and glob-expands.
exec docker run --rm \
  --entrypoint bash \
  -v "$SPIKE_DIR/fixture":/w -w /w \
  -v "$SPIKE_DIR/.m2":/m2 \
  -u "$(id -u):$(id -g)" \
  -e HOME=/m2 \
  -e MAVEN_OPTS="-Duser.home=/m2 ${EXTRA_MAVEN_OPTS:-}" \
  ${EXTRA_DOCKER_ARGS:-} \
  "$IMAGE" -c './mvnw -B "$@"' bash "$@"
