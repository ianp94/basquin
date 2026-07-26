#!/usr/bin/env bash
# Runs the packaged rest-villains fast-jar in a container, JVM mode only (spec §3.1: maven.compiler.release=25
# means the class files are Java-25 bytecode, so even running — not just building — needs a JDK 25 runtime;
# the host JDK is 17). Reuses the same Mandrel builder image the containerized Maven build already used
# (build.sh), since it carries a real `java` 25 and was already validated end-to-end in Phase 0.
#
# Usage: DB_CONTAINER=<name> DB_NETWORK=<network> run-app.sh
set -euo pipefail

APP_DIR="${APP_DIR:?set APP_DIR to the rest-villains checkout}"
DB_CONTAINER="${DB_CONTAINER:?set DB_CONTAINER to the running postgres container name}"
DB_NETWORK="${DB_NETWORK:?set DB_NETWORK to the docker network shared with the db container}"
APP_CONTAINER="${APP_CONTAINER:-basquin-restvillains-app}"

IMAGE="${BASQUIN_SPIKE_IMAGE:-quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5}"

docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true

exec docker run -d --name "$APP_CONTAINER" \
  --network "$DB_NETWORK" \
  -p 8084:8084 \
  -v "$APP_DIR":/w -w /w \
  -u "$(id -u):$(id -g)" \
  -e QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://${DB_CONTAINER}:5432/villains_database" \
  -e QUARKUS_DATASOURCE_USERNAME=superbad \
  -e QUARKUS_DATASOURCE_PASSWORD=superbad \
  -e QUARKUS_OTEL_SDK_DISABLED=true \
  --entrypoint java \
  "$IMAGE" -jar target/quarkus-app/quarkus-run.jar
