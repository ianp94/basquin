#!/usr/bin/env bash
set -euo pipefail
BIN=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-heroes/target/rest-heroes-1.0-runner
test -x "$BIN" || { echo "native binary missing: $BIN"; exit 1; }
export QUARKUS_DATASOURCE_REACTIVE_URL="postgresql://localhost:55433/heroes_database"
export QUARKUS_DATASOURCE_USERNAME=superman
export QUARKUS_DATASOURCE_PASSWORD=superman
export QUARKUS_OTEL_SDK_DISABLED=true
export QUARKUS_HTTP_PORT=8083
exec "$BIN" -Dbasquin.quarkus.control.defectsEnabled=true
