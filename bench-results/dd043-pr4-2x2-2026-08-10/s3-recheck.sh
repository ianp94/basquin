#!/usr/bin/env bash
# DD-043 spec §7.2's S3 re-check entry condition, for a native cell. Reproduces S3's four
# dispositions under SubstrateVM using ONLY existing app/extension routes (no X-Basquin-Req
# gating on any response header — the spec explicitly forbids that criterion):
#   1. /ok-equivalent 200      -> any real app route, X-Basquin-Req present, addEndHandler fires,
#                                  ar.succeeded()==true, ResultStore publishes a real (non-"miss") entry.
#   2. an app 500              -> /__basquin/control/defect/error5xx (defect routes enabled),
#                                  X-Basquin-Req present, published entry's invariantCount/detail line
#                                  present, never silently dropped.
#   3. a 3xx                   -> GET /q/swagger-ui (no trailing slash) — a genuine platform redirect
#                                  present in both targets (Installed features includes swagger-ui),
#                                  X-Basquin-Req present, addEndHandler fires on the redirect response.
#   4. mid-response disconnect -> /__basquin/control/defect/slow with a short curl --max-time,
#                                  aborted before the sleep completes; addEndHandler fires with
#                                  ar.succeeded()==false (logged, never published).
#
# Usage: s3-recheck.sh <base-url e.g. http://localhost:8084> <out-dir>
set -uo pipefail
BASE="$1"
OUT="$2"
mkdir -p "$OUT"

echo "=== 1. app 200 disposition ==="
curl -s -o /dev/null -w 'http_code=%{http_code}\n' -H 'X-Basquin-Req: s3-disp-200' "$BASE/api/villains/hello" 2>&1 || \
curl -s -o /dev/null -w 'http_code=%{http_code}\n' -H 'X-Basquin-Req: s3-disp-200' "$BASE/api/heroes/hello" 2>&1
curl -s "$BASE/__basquin/result?id=s3-disp-200"; echo

echo "=== 3. app 3xx disposition (swagger-ui redirect) ==="
curl -s -o /dev/null -D - -H 'X-Basquin-Req: s3-disp-3xx' "$BASE/q/swagger-ui" | grep -i 'HTTP/\|location'
curl -s "$BASE/__basquin/result?id=s3-disp-3xx"; echo

echo "=== 2. app 500 disposition (defect route) ==="
curl -s -o /dev/null -w 'http_code=%{http_code}\n' -H 'X-Basquin-Req: s3-disp-500' "$BASE/__basquin/control/defect/error5xx"
curl -s "$BASE/__basquin/result?id=s3-disp-500"; echo

echo "=== 4. mid-response disconnect disposition ==="
curl -s -m 1 -H 'X-Basquin-Req: s3-disp-disconnect' "$BASE/__basquin/control/defect/slow?ms=5000" -o /dev/null -w 'http_code=%{http_code} (expect a timeout/curl error, not 200)\n' || echo "curl aborted as expected (exit=$?)"
sleep 2
echo "-- result poll for disconnect id (expect miss/never-published) --"
curl -s "$BASE/__basquin/result?id=s3-disp-disconnect"; echo
