#!/usr/bin/env bash
# Same as run-native.sh but with the negative-control defect routes enabled (spec §7.3), for the
# §7.2 S3 re-check entry condition ONLY. Never used for the published coverage cell run.
set -euo pipefail
BIN=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains/target/rest-villains-1.0-runner
test -x "$BIN" || { echo "native binary missing: $BIN"; exit 1; }
export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:55432/villains_database"
export QUARKUS_DATASOURCE_USERNAME=superbad
export QUARKUS_DATASOURCE_PASSWORD=superbad
export QUARKUS_OTEL_SDK_DISABLED=true
export QUARKUS_HTTP_PORT=8084
exec "$BIN" -Dbasquin.quarkus.control.defectsEnabled=true
