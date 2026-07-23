#!/usr/bin/env python3
"""Fail if a committed doc quotes a coverage figure that disagrees with the artifacts.

Four consecutive review rounds on this branch found the same defect: a number written by hand into
prose, which then outlived the run it came from. The generated page solved that for itself by
deriving every figure; the hand-written docs around it did not, and one misquote survived four
rounds of review. This is the cheap check that would have caught it.

Run: python3 deploy/bench/check_claims.py
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCS = ["deploy/bench/roller/README.md", "deploy/bench/ONBOARDING.md", "docs/ROADMAP.md"]


def main() -> int:
    idx = ROOT / "bench-results" / "campaigns.json"
    if not idx.exists():
        print("no campaigns.json; nothing to check")
        return 0
    truth = {r["app"]: r.get("coveragePct") for r in json.loads(idx.read_text())
             if r["mode"] == "explore" and r.get("coveragePct")}
    known = {str(v) for v in truth.values()}
    print("artifact coverage figures:", ", ".join(f"{k}={v}%" for k, v in sorted(truth.items())))

    bad = 0
    for rel in DOCS:
        p = ROOT / rel
        if not p.exists():
            continue
        fenced = False
        for n, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            # A fenced block is a verbatim transcript of what a run printed -- evidence of that
            # run, not a claim about current truth. Prose is where a stale number does damage,
            # because a reader takes it as describing the artifacts beside it.
            if line.lstrip().startswith("```"):
                fenced = not fenced
                continue
            if fenced:
                continue
            # A percentage in the 15-40 band next to the word "coverage"/"reached" is a coverage
            # claim; anything outside the artifact set is either stale or unbacked.
            for m in re.finditer(r"\b(\d{2}\.\d)\s*%", line):
                if not re.search(r"coverage|reached|explore", line, re.I):
                    continue
                if m.group(1) not in known:
                    print(f"  MISQUOTE {rel}:{n}: {m.group(1)}% is not any collected run "
                          f"({', '.join(sorted(known))})")
                    print(f"           {line.strip()[:110]}")
                    bad += 1
    print("\nMISQUOTES:", bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
