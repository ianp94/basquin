#!/usr/bin/env bash
# The §7.3 coverage-control run. Executes EXACTLY the manifest's run plan:
#   t0 dump -> drive the two declared sibling routes (and NOTHING else) -> t1 dump.
# The withheld route GET /api/villains/{id} is never requested; every request this script
# sends is logged with a timestamp into drive.log. Readiness probes use /q/health/ready
# (not an application route). DB + app are torn down on exit.
# Recipe: Task-4's villains-native run method (host-run binary, containerized postgres on
# published port 55432, QUARKUS_HTTP_PORT=8084) — see
# bench-results/dd043-pr4-2x2-2026-08-10/villains-native/run-db.sh and run-native.sh.
set -euo pipefail
cd "$(dirname "$0")"

BIN=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains/target/rest-villains-1.0-runner
DB=basquin-s73-control-db
PORT=8084
LOG=drive.log

log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)] $*" | tee -a "$LOG"; }

test -x "$BIN" || { log "FATAL native binary missing: $BIN"; exit 1; }
log "binary: $BIN"
log "binary sha256: $(sha256sum "$BIN" | cut -d' ' -f1)"

APP_PID=""
cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    log "app pid $APP_PID stopped"
  fi
  docker rm -f "$DB" >/dev/null 2>&1 && log "db container $DB removed" || true
}
trap cleanup EXIT

log "starting postgres container $DB (postgres:18, published 55432)"
docker rm -f "$DB" >/dev/null 2>&1 || true
docker run -d --name "$DB" \
  -p 55432:5432 \
  -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
  postgres:18 >/dev/null
for i in $(seq 1 60); do
  if docker exec "$DB" pg_isready -U superbad -d villains_database >/dev/null 2>&1; then break; fi
  sleep 1
  [ "$i" = 60 ] && { log "FATAL postgres never became ready"; exit 1; }
done
log "postgres ready"

export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://localhost:55432/villains_database"
export QUARKUS_DATASOURCE_USERNAME=superbad
export QUARKUS_DATASOURCE_PASSWORD=superbad
export QUARKUS_OTEL_SDK_DISABLED=true
export QUARKUS_HTTP_PORT=$PORT
"$BIN" > app.log 2>&1 &
APP_PID=$!
log "app started, pid $APP_PID (fresh process for this control run — distinct from the published explore run)"

for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/q/health/ready" || true)
  if [ "$code" = 200 ]; then break; fi
  sleep 1
  [ "$i" = 60 ] && { log "FATAL app never became ready (last /q/health/ready code: $code)"; exit 1; }
done
log "READY /q/health/ready -> 200 (readiness probe; not an application route)"

log "t0 dump: GET /__basquin/coverage (before any application route)"
curl -sS -D t0-headers.txt -o t0.exec "http://localhost:$PORT/__basquin/coverage"
log "t0: $(head -1 t0-headers.txt | tr -d '\r'), $(stat -c%s t0.exec) bytes, first-4-bytes $(head -c4 t0.exec | od -An -tx1 | tr -s ' ')"

log "REQUEST 1 (declared sibling): GET /api/villains/random"
code=$(curl -sS -w '%{http_code}' -o req-random.txt "http://localhost:$PORT/api/villains/random")
log "REQUEST 1 -> HTTP $code, body $(stat -c%s req-random.txt) bytes (req-random.txt)"

log "REQUEST 2 (declared sibling): GET /api/villains"
code=$(curl -sS -w '%{http_code}' -o req-all.txt "http://localhost:$PORT/api/villains")
log "REQUEST 2 -> HTTP $code, body $(stat -c%s req-all.txt) bytes (req-all.txt)"

log "t1 dump: GET /__basquin/coverage (after the two sibling requests; the withheld route GET /api/villains/{id} was never sent)"
curl -sS -D t1-headers.txt -o t1.exec "http://localhost:$PORT/__basquin/coverage"
log "t1: $(head -1 t1-headers.txt | tr -d '\r'), $(stat -c%s t1.exec) bytes, first-4-bytes $(head -c4 t1.exec | od -An -tx1 | tr -s ' ')"

log "drive complete; tearing down"
