#!/usr/bin/env python3
"""Harness row-label coverage (DD-045 item 4C) — closes item 3's declared residual: the comment
above GREEN_RUN_LABELS in scripts/verify-dd043-pr3-rows.sh says a label with no scenario is a
meta-run failure, computed from the scenario table itself, "never by parsing the harness's bash".
So a harness row ADDED to scripts/verify-dd043-pr3.sh without a matching kill scenario is
invisible to that meta-check: the label sits in neither the scenario table nor GREEN_RUN_LABELS,
and nothing compares either against what the harness actually emits.

MECHANISM — read the run's OUTPUT, never the harness's bash (the same refusal item 3's own design
already made, honored here too):
  1. Extract PASS row labels from a GREEN `verify-dd043-pr3` run's own RESULTS.md — the
     `| PASS | `label` | ... |` table rows.
  2. Extract scripts/verify-dd043-pr3-rows.sh's GREEN_RUN_LABELS list — read as DATA between its
     `[` and matching `]` (a literal, quoted-string Python list), never by importing or executing
     that script.
  3. Require set equality. Observed-but-undeclared -> "new harness row `X` has no kill scenario"
     -> red. Declared-but-unobserved -> red too — redundant with
     scripts/verify-dd043-pr3-rows.sh's own unmatched-label failure (the two jobs have different
     path filters, so the redundancy is disclosed here, not hidden: this one is the broader).

Only meaningful against a GREEN run: label coverage is well-defined only when every row in the run
reached PASS or FAIL as designed. REFUSES (exit 3) on a non-green RESULTS.md rather than silently
comparing a partial/crashed run's rows, and on a missing/unparseable GREEN_RUN_LABELS list.

Exit 0: the two label sets are identical.
Exit 1: at least one observed-but-undeclared or declared-but-unobserved label.
Exit 3: REFUSED — non-green input, unparseable tally, zero rows, or GREEN_RUN_LABELS not found.

Run: python3 scripts/check-row-label-coverage.py <RESULTS.md> [scripts/verify-dd043-pr3-rows.sh]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ROW_RE = re.compile(r"^\|\s*(PASS|FAIL|SKIP)\s*\|\s*`([^`]+)`\s*\|")
TALLY_RE = re.compile(r"\*\*(\d+) passed, (\d+) failed, (\d+) skipped\.")


def extract_results_labels(results_md: pathlib.Path):
    """-> (pass_labels: set[str], tally: (p, f, s) | None)."""
    text = results_md.read_text(encoding="utf-8")
    pass_labels = set()
    for line in text.splitlines():
        m = ROW_RE.match(line)
        if m and m.group(1) == "PASS":
            pass_labels.add(m.group(2))
    tm = TALLY_RE.search(text)
    tally = tuple(int(x) for x in tm.groups()) if tm else None
    return pass_labels, tally


def extract_green_run_labels(rows_script: pathlib.Path) -> set[str]:
    """Read GREEN_RUN_LABELS as DATA — the literal list of quoted strings between the `[` that
    follows `GREEN_RUN_LABELS = ` and its matching `]` — never by importing or executing the
    script (scripts/verify-dd043-pr3-rows.sh's own design already refuses to parse the harness's
    bash; this extends the same refusal to reading the meta-checker's source, not running it)."""
    text = rows_script.read_text(encoding="utf-8")
    m = re.search(r"GREEN_RUN_LABELS\s*=\s*\[(.*?)\]", text, re.DOTALL)
    if not m:
        sys.exit(f"check-row-label-coverage: GREEN_RUN_LABELS list not found (as data) in "
                  f"{rows_script}")
    labels = set(re.findall(r'"([^"]+)"', m.group(1)))
    if not labels:
        sys.exit(f"check-row-label-coverage: GREEN_RUN_LABELS parsed to zero labels in "
                  f"{rows_script} — the extraction regex has drifted from the file's format")
    return labels


def main() -> int:
    args = sys.argv[1:]
    if not args:
        sys.exit("usage: check-row-label-coverage.py <RESULTS.md> [rows-script]")
    results_md = pathlib.Path(args[0])
    rows_script = (pathlib.Path(args[1]) if len(args) > 1
                   else ROOT / "scripts" / "verify-dd043-pr3-rows.sh")

    if not results_md.exists():
        sys.exit(f"check-row-label-coverage: {results_md} does not exist")
    observed, tally = extract_results_labels(results_md)
    if tally is None:
        print(f"check-row-label-coverage: REFUSED — {results_md} has no parseable "
              f"'P passed, F failed, S skipped.' tally line")
        return 3
    p, f, s = tally
    if f != 0 or s != 0:
        print(f"check-row-label-coverage: REFUSED — {results_md} is not a green run "
              f"({p} passed, {f} failed, {s} skipped); label coverage is only meaningful "
              f"against a run where every row reached PASS or FAIL as designed")
        return 3
    if not observed:
        print(f"check-row-label-coverage: REFUSED — {results_md} has zero parseable PASS rows")
        return 3

    declared = extract_green_run_labels(rows_script)

    undeclared = sorted(observed - declared)
    unobserved = sorted(declared - observed)

    print(f"check-row-label-coverage: {len(observed)} PASS row label(s) observed in "
          f"{results_md}; {len(declared)} declared in {rows_script}'s GREEN_RUN_LABELS")
    if undeclared:
        print(f"\n{len(undeclared)} FINDINGS (observed but undeclared):")
        for label in undeclared:
            print(f"  FAIL new harness row `{label}` has no kill scenario "
                  f"({rows_script}'s GREEN_RUN_LABELS)")
    if unobserved:
        print(f"\n{len(unobserved)} FINDINGS (declared but unobserved):")
        for label in unobserved:
            print(f"  FAIL GREEN_RUN_LABELS declares `{label}` but it did not appear as a PASS "
                  f"row in {results_md} (redundant with {rows_script}'s own unmatched-label "
                  f"failure — disclosed, not hidden: the two jobs have different path filters, "
                  f"and this one is the broader)")
    if undeclared or unobserved:
        return 1
    print(f"OK — {len(observed)} row label(s) match GREEN_RUN_LABELS exactly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
