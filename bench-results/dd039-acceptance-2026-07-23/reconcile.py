#!/usr/bin/env python3
"""Re-derive every number in DD-039's Task-7 acceptance block from the committed artifacts.

Reads only files in this directory -- no cluster, no network. Run:

    python3 bench-results/dd039-acceptance-2026-07-23/reconcile.py

The point is that the acceptance run's numbers stop being prose. It prints the two criteria and the
residual, and fails loudly if any figure has drifted from what docs/DESIGN-DECISIONS.md claims.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).parent
RUN = HERE / "roller-explore-dd039"

# The claims this script pins, exactly as the DD-039 Task-7 block states them.
EXPECTED = {
    "db_rows": 84,          # Criterion 1: weblogentry rows written by login_publish in-window
    "reported": 1656,       # targetViolations (/__basquin/violations delta)
    "misses": 0,
    "corpus": 81,           # retained cost-ranked corpus (CoverageGuidedRun done line)
    "pod_lines": 1704,      # [Basquin][Invariant] lines in the window
    "gap": 48,              # pod_lines - reported
}


def main() -> int:
    got = {}

    summary = (RUN / "driver-summary.txt").read_text()
    got["reported"] = int(re.search(r"targetViolations=(\d+)", summary).group(1))
    got["misses"] = int(re.search(r"misses=(\d+)", summary).group(1))
    got["corpus"] = int(re.search(r"CoverageGuidedRun done: corpus=(\d+)", summary).group(1))

    # Every line in this slice is already inside the driver-container window [20:02:16, 20:07:17);
    # the pod had restartCount 0 so the slice is the whole population, not a sample. See README.
    lines = [l for l in (RUN / "target-invariants-window.log").read_text().splitlines()
             if "[Basquin][Invariant]" in l]
    got["pod_lines"] = len(lines)
    got["gap"] = got["pod_lines"] - got["reported"]

    # Criterion 1: the row count the psql query recorded, and its distinctness.
    db = (RUN / "db-login_publish-rows.txt").read_text()
    # the psql summary row: total | distinct_ids | distinct_anchors | creators | min | max
    m = re.search(r"^\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|", db, re.M)
    got["db_rows"] = int(m.group(1))
    distinct_ids, distinct_anchors, creators = int(m.group(2)), int(m.group(3)), int(m.group(4))

    print("Criterion 1 (DB rows by login_publish):")
    print(f"  rows={got['db_rows']} distinct_ids={distinct_ids} distinct_anchors={distinct_anchors}"
          f" creators={creators}")
    if got["db_rows"] == 0:
        print("  FAIL: zero rows -> cookie carry does not work")
        return 1
    if not (distinct_ids == got["db_rows"] == distinct_anchors and creators == 1):
        print("  FAIL: rows are not distinct single-author authenticated publishes")
        return 1
    print("  ok   84 distinct authenticated publishes, single author -> cookie carry works")

    print("\nCriterion 2 (reported vs pod-log):")
    bad = 0
    for k, want in EXPECTED.items():
        ok = got[k] == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {k:12} = {got[k]}" + ("" if ok else f"  (claimed {want})"))
    print(f"\nresidual: {got['gap']}/{got['pod_lines']} = {100 * got['gap'] / got['pod_lines']:.1f}%"
          f"  (DD-040 was 189/1602 = 11.8%)")
    print("MISMATCHES:", bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
