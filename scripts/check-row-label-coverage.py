#!/usr/bin/env python3
"""Harness row-label coverage (DD-045 item 4C) — closes item 3's declared residual FOR THE
`unit`/`jar`/`guards` STAGES ONLY, the ones the `verify-dd043-pr3` CI job actually runs
(`.github/workflows/ci.yml`'s `bash scripts/verify-dd043-pr3.sh unit jar guards` step). The
comment above GREEN_RUN_LABELS in scripts/verify-dd043-pr3-rows.sh says a label with no scenario
is a meta-run failure, computed from the scenario table itself, "never by parsing the harness's
bash". So a harness row ADDED to one of those three stages in scripts/verify-dd043-pr3.sh without
a matching kill scenario is invisible to that meta-check: the label sits in neither the scenario
table nor GREEN_RUN_LABELS, and nothing compares either against what the harness actually emits.

DISCLOSED RESIDUAL, not covered by the above: a row added to the `jvm` or `native` stage. Those
two need docker and take 15+ minutes (scripts/verify-dd043-pr3.sh's own header; `native` alone is
a serialized ~15-minute compile), so they stay manual-only and this checker never sees their
output in CI — GREEN_RUN_LABELS itself was never extended to them (scripts/verify-dd043-pr3-rows.sh
only mutation-tests `unit jar guards`). A `jvm`/`native` row added with no kill scenario is
invisible to BOTH this check and the rows meta-check, exactly the shape item 3's residual named —
this checker narrows the closed claim to the three stages it can actually see; the other two remain
open, disclosed debt, not silently swept in.

MECHANISM — read the run's OUTPUT, never the harness's bash (the same refusal item 3's own design
already made, honored here too):
  1. Extract PASS row labels from a GREEN `verify-dd043-pr3` run's own RESULTS.md — the
     `| PASS | `label` | ... |` table rows — and its `Stages run:` header line (the space-separated
     stage names the harness actually invoked, e.g. `unit jar guards` or, on a full run,
     `unit jar guards jvm native`).
  2. Extract scripts/verify-dd043-pr3-rows.sh's GREEN_RUN_LABELS list — read as DATA between its
     `[` and matching `]` (a literal, quoted-string Python list), never by importing or executing
     that script. That list only ever declares `unit`/`jar`/`guards` labels (IN_SCOPE_STAGES
     below) — it has no opinion about `jvm`/`native` at all.
  3. Partition the observed PASS labels by stage — the text before the first `:`, or the whole
     label for the stage-less `unit` row. A label whose stage is not in IN_SCOPE_STAGES is
     EXCLUDED from the comparison and counted out loud ("N row(s) excluded: out-of-scope stage(s)
     ..."), never silently dropped and never reported as a finding. This is what lets a full
     (`all`-stage) RESULTS.md — e.g. a promoted run of record — stop false-failing on its own
     `jvm:*`/`native:*` rows: those rows have no kill scenario BY DESIGN (residual, above), not by
     an extraction bug.
  4. Require set equality between the IN-SCOPE observed labels and GREEN_RUN_LABELS.
     Observed-but-undeclared -> "new harness row `X` has no kill scenario" -> red.
     Declared-but-unobserved -> red too — redundant with scripts/verify-dd043-pr3-rows.sh's own
     unmatched-label failure (the two jobs have different path filters, so the redundancy is
     disclosed here, not hidden: this one is the broader).

Only meaningful against a GREEN run whose in-scope stages all ran: label coverage is well-defined
only when every row in the run reached PASS or FAIL as designed, AND `unit`, `jar`, and `guards`
all appear in the run's `Stages run:` line (a run missing one of those three cannot have produced
that stage's labels at all, which would read as mass declared-but-unobserved rather than the
missing-stage refusal it actually is). REFUSES (exit 3) on a non-green RESULTS.md, on a
missing/unparseable `Stages run:` line, on a run whose in-scope stages are incomplete, and on a
missing/unparseable GREEN_RUN_LABELS list.

Exit 0: the two IN-SCOPE label sets are identical (out-of-scope rows, if any, were excluded and
        counted, never compared).
Exit 1: at least one observed-but-undeclared or declared-but-unobserved IN-SCOPE label.
Exit 2: usage error (no arguments).
Exit 3: REFUSED — the check could not be performed: non-green input, unparseable tally, zero
        rows, a missing RESULTS.md, a missing/unparseable `Stages run:` line, a run whose
        `Stages run:` line omits `unit`, `jar`, or `guards`, or GREEN_RUN_LABELS unreadable.
        Distinct from exit 1 on purpose: "I could not check" must never read as "I checked and it
        is fine", nor as "I checked and found a mismatch".

Run: python3 scripts/check-row-label-coverage.py <RESULTS.md> [scripts/verify-dd043-pr3-rows.sh]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ROW_RE = re.compile(r"^\|\s*(PASS|FAIL|SKIP)\s*\|\s*`([^`]+)`\s*\|")
TALLY_RE = re.compile(r"\*\*(\d+) passed, (\d+) failed, (\d+) skipped\.")
STAGES_RE = re.compile(r"^Stages run:\s*(.+?)\s*$", re.MULTILINE)

# The only stages scripts/verify-dd043-pr3.sh knows how to run (its own `case "$arg" in
# unit|jar|guards|jvm|native)` list) that GREEN_RUN_LABELS declares kill scenarios for. `jvm` and
# `native` are deliberately absent — see the module docstring's DISCLOSED RESIDUAL paragraph.
IN_SCOPE_STAGES = frozenset({"unit", "jar", "guards"})


def label_stage(label: str) -> str:
    """The stage a row label belongs to: the text before the first `:`, or the whole label for
    the stage-less `unit` row (scripts/verify-dd043-pr3.sh emits a bare `unit` label, never
    `unit:something`)."""
    return label.split(":", 1)[0]


def extract_results_labels(results_md: pathlib.Path):
    """-> (pass_labels: set[str], tally: (p, f, s) | None, stages_run: list[str] | None).
    stages_run is the space-separated stage list from the RESULTS.md's own `Stages run:` header
    line (scripts/verify-dd043-pr3.sh:949's `echo "Stages run: ${STAGES[*]}"`), in run order,
    or None if that line is missing or unparseable."""
    text = results_md.read_text(encoding="utf-8")
    pass_labels = set()
    for line in text.splitlines():
        m = ROW_RE.match(line)
        if m and m.group(1) == "PASS":
            pass_labels.add(m.group(2))
    tm = TALLY_RE.search(text)
    tally = tuple(int(x) for x in tm.groups()) if tm else None
    sm = STAGES_RE.search(text)
    stages_run = sm.group(1).split() if sm else None
    return pass_labels, tally, stages_run


class Refused(Exception):
    """A refusal: the check could not be performed at all, as distinct from performing it and
    finding a mismatch. Raised rather than exited, because `sys.exit("message")` prints the string
    and exits 1 — the SAME code this script uses for a real coverage mismatch. Four refusal paths
    were written that way and every one silently reported itself as a finding: 'the extraction
    regex drifted so I could not read the labels' was indistinguishable from 'I read them and one
    is missing'. That is a claim wider than its check, inside a checker built to close item 3's
    residual — the exact defect DD-045 exists to remove, found by review on PR #107."""


def extract_green_run_labels(rows_script: pathlib.Path) -> set[str]:
    """Read GREEN_RUN_LABELS as DATA — the literal list of quoted strings between the `[` that
    follows `GREEN_RUN_LABELS = ` and its matching `]` — never by importing or executing the
    script (scripts/verify-dd043-pr3-rows.sh's own design already refuses to parse the harness's
    bash; this extends the same refusal to reading the meta-checker's source, not running it)."""
    text = rows_script.read_text(encoding="utf-8")
    m = re.search(r"GREEN_RUN_LABELS\s*=\s*\[(.*?)\]", text, re.DOTALL)
    if not m:
        raise Refused(f"GREEN_RUN_LABELS list not found (as data) in {rows_script}")
    labels = set(re.findall(r'"([^"]+)"', m.group(1)))
    if not labels:
        raise Refused(f"GREEN_RUN_LABELS parsed to zero labels in {rows_script} — the "
                      f"extraction regex has drifted from the file's format")
    return labels


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print("usage: check-row-label-coverage.py <RESULTS.md> [rows-script]", file=sys.stderr)
        return 2
    results_md = pathlib.Path(args[0])
    rows_script = (pathlib.Path(args[1]) if len(args) > 1
                   else ROOT / "scripts" / "verify-dd043-pr3-rows.sh")

    if not results_md.exists():
        raise Refused(f"{results_md} does not exist")
    observed, tally, stages_run = extract_results_labels(results_md)
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
    if stages_run is None:
        print(f"check-row-label-coverage: REFUSED — {results_md} has no parseable "
              f"'Stages run: ...' header line; cannot determine which stages are in scope "
              f"for this comparison")
        return 3
    missing_in_scope = sorted(IN_SCOPE_STAGES - set(stages_run))
    if missing_in_scope:
        print(f"check-row-label-coverage: REFUSED — {results_md}'s 'Stages run: "
              f"{' '.join(stages_run)}' omits in-scope stage(s) {', '.join(missing_in_scope)}; "
              f"label coverage is only meaningful when every in-scope stage ran")
        return 3

    # Out-of-scope stages actually present in THIS run (e.g. `jvm`, `native` on a full/`all` run)
    # — order preserved from the header line, not sorted, so the message reads like the run's own
    # `Stages run:` line rather than an alphabetized rearrangement of it.
    out_of_scope_stages = [st for st in stages_run if st not in IN_SCOPE_STAGES]
    excluded = ({label for label in observed if label_stage(label) not in IN_SCOPE_STAGES}
                if out_of_scope_stages else set())
    in_scope_observed = observed - excluded

    if out_of_scope_stages:
        print(f"check-row-label-coverage: {len(excluded)} row(s) excluded: out-of-scope "
              f"stage(s) {', '.join(out_of_scope_stages)} (not gated by GREEN_RUN_LABELS — "
              f"disclosed residual, not a finding; see this script's module docstring)")

    declared = extract_green_run_labels(rows_script)

    undeclared = sorted(in_scope_observed - declared)
    unobserved = sorted(declared - in_scope_observed)

    print(f"check-row-label-coverage: {len(in_scope_observed)} in-scope PASS row label(s) "
          f"observed in {results_md}; {len(declared)} declared in {rows_script}'s "
          f"GREEN_RUN_LABELS")
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
    print(f"OK — {len(in_scope_observed)} in-scope row label(s) match GREEN_RUN_LABELS exactly.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Refused as e:
        print(f"check-row-label-coverage: REFUSED — {e}", file=sys.stderr)
        sys.exit(3)
