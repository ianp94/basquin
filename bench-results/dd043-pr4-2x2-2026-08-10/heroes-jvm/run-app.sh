#!/usr/bin/env bash
set -euo pipefail
IMAGE="quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5"
APP_DIR=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-heroes
docker rm -f basquin-heroes-jvm-app >/dev/null 2>&1 || true
exec docker run -d --name basquin-heroes-jvm-app \
  --network basquin-heroes-jvm-net \
  -p 8083:8083 \
  -v "$APP_DIR":/w -w /w \
  -u "$(id -u):$(id -g)" \
  -e QUARKUS_DATASOURCE_REACTIVE_URL="postgresql://basquin-heroes-jvm-db:5432/heroes_database" \
  -e QUARKUS_DATASOURCE_USERNAME=superman \
  -e QUARKUS_DATASOURCE_PASSWORD=superman \
  -e QUARKUS_OTEL_SDK_DISABLED=true \
  --entrypoint java \
  "$IMAGE" -jar target/quarkus-app/quarkus-run.jar
