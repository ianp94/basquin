#!/usr/bin/env bash
# Manifest pass-criterion 4: the run must contain no request to any /api/villains/{id}-shaped
# path. drive.sh is the only driver of this run and logs every request it sends on a
# "REQUEST N ...: GET <path>" line (application routes) or an explicit dump/READY line
# (non-application routes), so those lines are the run's request set. Prose mentions of the
# withheld template elsewhere in the log (e.g. the t1 line SAYING "/api/villains/{id} was
# never sent") are not requests and are keyed out by matching request lines only — an earlier
# version of this check grepped the whole log for URL-shaped tokens and false-positived on
# exactly that prose mention.
# Output committed as withheld-check.txt.
set -euo pipefail
cd "$(dirname "$0")"
{
  echo "== application-route request lines in drive.log (the complete set) =="
  grep 'REQUEST [0-9]' drive.log
  echo
  echo "== non-application requests (readiness probe + coverage dumps) =="
  grep -e 'READY' -e 'dump: GET' drive.log
  echo
  echo "== application-route URLs actually requested =="
  URLS=$(grep -o 'REQUEST [0-9][^>]*GET /api[^ ]*' drive.log | grep -o '/api[^ ]*' | sort -u)
  echo "$URLS"
  echo
  echo "== withheld-shaped among them: /api/villains/<segment> with <segment> != random =="
  HITS=$(echo "$URLS" | grep '^/api/villains/' | grep -v '^/api/villains/random$' || true)
  if [ -z "$HITS" ]; then
    echo "NONE — no withheld-shaped request was sent in this run"
  else
    echo "FOUND (control FAILS):"
    echo "$HITS"
    exit 1
  fi
} | tee withheld-check.txt
