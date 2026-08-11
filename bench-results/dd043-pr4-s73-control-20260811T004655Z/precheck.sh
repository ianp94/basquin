#!/usr/bin/env bash
# Obligation-2 pre-check: the withheld method's Quarkus REST invoker class must be present in
# the COMMITTED class-initialization report of the build whose binary this control reuses
# (bench-results/dd043-pr4-2x2-2026-08-10/villains-native/class_initialization_report.csv).
# Run BEFORE the control run; absent invoker => control VOID, stop.
# Output goes to class-init-excerpt.txt beside this script.
set -euo pipefail
cd "$(dirname "$0")"
REPORT=../dd043-pr4-2x2-2026-08-10/villains-native/class_initialization_report.csv
{
  echo "== obligation-2 pre-check, run BEFORE the control run =="
  echo "report: $REPORT (committed Task-4 build output)"
  echo "checked at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "== count of invoker rows for the withheld method (must be >= 1) =="
  echo "grep -c 'quarkusrestinvoker\$getVillain_' => $(grep -c 'quarkusrestinvoker$getVillain_' "$REPORT")"
  echo
  echo "== withheld + declared-sibling invoker rows, verbatim (CR stripped) =="
  grep -e 'quarkusrestinvoker$getVillain_' \
       -e 'quarkusrestinvoker$getRandomVillain_' \
       -e 'quarkusrestinvoker$getAllVillains_' "$REPORT" | tr -d '\r'
} > class-init-excerpt.txt
cat class-init-excerpt.txt
