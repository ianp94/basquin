#!/usr/bin/env bash
set -euo pipefail
IMAGE="quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5"
APP_DIR=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains
docker rm -f basquin-villains-jvm-app >/dev/null 2>&1 || true
exec docker run -d --name basquin-villains-jvm-app \
  --network basquin-villains-jvm-net \
  -p 8084:8084 \
  -v "$APP_DIR":/w -w /w \
  -u "$(id -u):$(id -g)" \
  -e QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://basquin-villains-jvm-db:5432/villains_database" \
  -e QUARKUS_DATASOURCE_USERNAME=superbad \
  -e QUARKUS_DATASOURCE_PASSWORD=superbad \
  -e QUARKUS_OTEL_SDK_DISABLED=true \
  --entrypoint java \
  "$IMAGE" -jar target/quarkus-app/quarkus-run.jar
