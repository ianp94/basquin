#!/usr/bin/env bash
set -euo pipefail
docker rm -f basquin-villains-native-db >/dev/null 2>&1 || true
exec docker run -d --name basquin-villains-native-db \
  -p 55432:5432 \
  -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
  postgres:18
