#!/usr/bin/env bash
# Runs jacoco-cli 0.8.15 report analysis for each dump point (t0/t1/t2) in a cell directory.
# Usage: analyze.sh <cell-dir>
set -euo pipefail
CELL="$1"
CLI=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM/bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar
for t in t0 t1 t2; do
  if [ -f "$CELL/$t.exec" ]; then
    java -jar "$CLI" report "$CELL/$t.exec" \
      --classfiles "$CELL/originals" \
      --xml "$CELL/$t.xml" \
      > "$CELL/cli-$t.out" 2>&1
    echo "=== $t rc=$? ==="
    tail -5 "$CELL/cli-$t.out"
  fi
done
