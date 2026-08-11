#!/usr/bin/env bash
# Runs the rest-villains native binary DIRECTLY ON THE HOST (PR-3's proven method for this
# artifact family: no loader/glibc incompatibility on this Ubuntu/WSL2 host), against a
# containerized postgres reachable on localhost via published port.
set -euo pipefail
BIN=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains/target/rest-villains-1.0-runner
test -x "$BIN" || { echo "native binary missing: $BIN"; exit 1; }
export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:55432/villains_database"
export QUARKUS_DATASOURCE_USERNAME=superbad
export QUARKUS_DATASOURCE_PASSWORD=superbad
export QUARKUS_OTEL_SDK_DISABLED=true
export QUARKUS_HTTP_PORT=8084
exec "$BIN"
