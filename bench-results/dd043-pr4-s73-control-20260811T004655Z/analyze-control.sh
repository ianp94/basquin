#!/usr/bin/env bash
# jacoco-cli 0.8.15 analysis of the control run's dumps.
#  - report: t0.exec and t1.exec against the SAME build's preserved pre-instrumentation
#    originals (target/generated-classes/jacoco of the reused Task-4 build; local build
#    tree, not in this repo) -> per-method XML t0.xml and t1.xml, plus cli-t0.out and
#    cli-t1.out
#  - execinfo: the raw execution-data records of both dumps -> execinfo-t0.out /
#    execinfo-t1.out. t1's shows the class's execution-data record directly (obligation 3's
#    "live record"); t0's shows that record did NOT exist before the sibling drive, i.e. a
#    t0 read would have been exactly the vacuous zero obligation 3 exists to exclude.
# CLI jar: the Phase-0 pinned org.jacoco.cli-0.8.15-nodeps.jar (sha1
# 1da22eb914b9176037589aaaac612b3f9b65f7ea, matching its repo-pinned .sha1 file; lives in the
# gitignored spike .m2 mirror, so cited here by content hash rather than tracked path).
set -euo pipefail
cd "$(dirname "$0")"
CLI=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM/bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar
ORIGINALS=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains/target/generated-classes/jacoco
echo "cli sha1: $(sha1sum "$CLI" | cut -d' ' -f1)"
for t in t0 t1; do
  java -jar "$CLI" report "$t.exec" --classfiles "$ORIGINALS" --xml "$t.xml" > "cli-$t.out" 2>&1
  echo "=== report $t rc=$? ==="
  cat "cli-$t.out"
done
for t in t0 t1; do
  java -jar "$CLI" execinfo "$t.exec" > "execinfo-$t.out" 2>&1
  echo "=== execinfo $t rc=$? ==="
done
