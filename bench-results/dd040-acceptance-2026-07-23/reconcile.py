#!/usr/bin/env python3
"""Re-derive every number in DD-040's Verified block from the two committed artifacts.

Reads only files in this directory -- no cluster, no network. Run:

    python3 bench-results/dd040-acceptance-2026-07-23/reconcile.py

The point of this script is that the acceptance run's numbers stop being prose. It prints the
reported/logged pair, the residual, and the shape of the unmatched population, and it fails loudly
if any of them has drifted from what docs/DESIGN-DECISIONS.md claims.
"""
import collections
import csv
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).parent / "roller-explore-dd040"

# The claims this script pins, exactly as the DD-040 record states them.
EXPECTED = {
    "reported": 1413, "misses": 0, "corpus": 99,
    "pod_lines": 1602, "gap": 189,
    "findings": 1235, "finding_violations": 1394,
    "violating_iterations": 1442, "matched_iterations": 1235, "unmatched_iterations": 207,
    "unmatched_single_heap": 206, "unmatched_1_2mb": 176,
}


def main() -> int:
    got = {}

    summary = (HERE / "driver-summary.txt").read_text()
    got["reported"] = int(re.search(r"targetViolations=(\d+)", summary).group(1))
    got["misses"] = int(re.search(r"misses=(\d+)", summary).group(1))
    got["corpus"] = int(re.search(r"corpus=(\d+)", summary).group(1))

    # Every line in this file is already inside the driver's [13:33:30Z, 13:38:31Z] window; the
    # window was cut by timestamp from a pod with restartCount 0, so the slice is the whole
    # population and not a sample. See README.md for the cut.
    per_iteration = collections.OrderedDict()
    for line in (HERE / "target-invariants-window.log").read_text().splitlines():
        m = re.search(r"Iteration (\d+) violated '([^']+)': (.+)$", line)
        per_iteration.setdefault(int(m.group(1)), []).append(f"{m.group(2)}: {m.group(3)}")
    got["pod_lines"] = sum(len(v) for v in per_iteration.values())
    got["violating_iterations"] = len(per_iteration)
    got["gap"] = got["pod_lines"] - got["reported"]

    rows = list(csv.reader(
        (HERE / "findings-invariant-remote.tsv").read_text().splitlines(), delimiter="\t"))[1:]
    got["findings"] = len(rows)
    got["finding_violations"] = sum(int(r[2]) for r in rows)

    # Claim: every detail the driver reported is a string the target actually logged.
    logged = {d for v in per_iteration.values() for d in v}
    fabricated = sorted({r[3] for r in rows} - logged)
    if fabricated:
        print(f"FAIL: {len(fabricated)} reported detail(s) never appear in the target log:")
        for d in fabricated[:10]:
            print("   ", d)
        return 1
    print(f"ok   all {len(rows)} reported detail= strings appear verbatim in the target log")

    # Match iterations by their (violation count, first violation) shape -- the finding does not
    # carry the target's iteration number, so this is the strongest available correspondence.
    pod_shapes = collections.Counter((len(v), v[0]) for v in per_iteration.values())
    find_shapes = collections.Counter((int(r[2]), r[3]) for r in rows)
    got["matched_iterations"] = sum(min(pod_shapes[k], n) for k, n in find_shapes.items())
    got["unmatched_iterations"] = got["violating_iterations"] - got["matched_iterations"]

    unmatched = pod_shapes - find_shapes
    got["unmatched_single_heap"] = sum(
        n for (cnt, first), n in unmatched.items() if cnt == 1 and first.startswith("heapDelta"))
    got["unmatched_1_2mb"] = sum(
        n for (cnt, first), n in unmatched.items()
        if cnt == 1 and first.startswith("heapDelta")
        and 1000 <= int(re.search(r"(\d+)KB", first).group(1)) < 2000)

    bad = 0
    for k, want in EXPECTED.items():
        ok = got[k] == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {k:24} = {got[k]}" + ("" if ok else f"  (claimed {want})"))
    print(f"\nresidual: {got['gap']}/{got['pod_lines']} = {100 * got['gap'] / got['pod_lines']:.1f}%")
    print("MISMATCHES:", bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
