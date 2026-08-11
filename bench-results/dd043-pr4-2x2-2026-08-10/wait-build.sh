#!/usr/bin/env bash
# Foreground poll loop: waits for a Maven build.log to show BUILD SUCCESS/FAILURE, short sleeps.
# Usage: wait-build.sh <log-file> <max-iterations (5s each)>
set -uo pipefail
LOG="$1"
MAX="${2:-110}"
i=0
while ! grep -qE 'BUILD SUCCESS|BUILD FAILURE' "$LOG" 2>/dev/null; do
  sleep 5
  i=$((i+1))
  if [ $((i % 12)) -eq 0 ]; then
    echo "still waiting (${i}x5s)... $(tail -1 "$LOG")"
  fi
  if [ "$i" -gt "$MAX" ]; then
    echo "TIMEOUT after $((MAX*5))s in this call, re-invoke to keep waiting"
    exit 2
  fi
done
echo "=== build finished ==="
tail -15 "$LOG"
