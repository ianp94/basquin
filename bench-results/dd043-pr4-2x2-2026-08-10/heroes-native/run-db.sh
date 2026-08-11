#!/usr/bin/env bash
set -euo pipefail
docker rm -f basquin-heroes-native-db >/dev/null 2>&1 || true
exec docker run -d --name basquin-heroes-native-db \
  -p 55433:5432 \
  -e POSTGRES_USER=superman -e POSTGRES_PASSWORD=superman -e POSTGRES_DB=heroes_database \
  postgres:18
