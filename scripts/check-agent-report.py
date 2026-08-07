#!/usr/bin/env python3
"""Agent-report form validator (DD-045 item 6b) — one report file in, four FORM checks out.

Design: `docs/superpowers/specs/2026-08-04-dd045-items-4-6-design.md`, item 6b. Built against four
OBSERVED failures (that design's evidence table), each mapped to exactly one check below:
  * ~70 citations "hand-verified accurate"; the gate failed 4 paths on the same file immediately
    -> check 1: a verification section must contain the TOOL'S OWN pasted output, not an assertion
    of diligence.
  * a fix "proven" by three exit codes; one run had refused and written no artifact
    -> check 3: every artifact path named in the verification section must exist on disk.
  * killed mid-work after reporting success; verification never ran
    -> check 1 again (nothing can gate a killed session; the contract's mitigation is
    artifact-first ordering, a convention, stated in this docstring's CONTRACT section) — the
    stale-commit class this leaves open is what check 2 closes.
  * a report reused across sessions, verified against a commit that is no longer HEAD
    -> check 2: the header's commit stamp must match the ref actually being checked against.

THE CONTRACT (DD-045 item 6c). Normative text, so it changes in the same commit as the check that
enforces it (this thread's whole thesis — a claim and its check must not drift apart):

  A report is accepted only when it contains the PASTED, VERBATIM output of its OWN verification
  run — not a description of having run it, not a summary of the result. Concretely: a
  `## Verification` section with at least one fenced block that shows the command AND its captured
  `exit=$?` line (this repo's idiom — see e.g. `TODO.md`'s DD-045 item 0, "run the harness in CI",
  cited by item rather than `file:line` since a line-number citation inside a `.py` docstring is
  not covered by `check-citations.py`'s citation gate, see `scripts/README.md`); a header stamp
  naming the commit the verification ran against; every artifact path the section names existing
  on disk; and any
  tally or exit code repeated in prose also appearing, verbatim, inside a pasted block. Partial
  evidence is never committed — `scripts/check-evidence-complete.py` is this contract's CI-side
  half, grading the bytes once they reach `bench-results/`; this script grades the REPORT that
  claims them, before that.

  CONVENTION, not mechanically checked by this script or `check-evidence-complete.py` (labeled as
  such per the design doc's explicit "Contract clause / convention" distinction — see
  `docs/superpowers/specs/2026-08-04-dd045-items-4-6-design.md`, item 6c): verification artifacts
  land on disk FIRST, the report LAST, so a killed session leaves artifacts without a claim
  (recoverable) rather than a claim without artifacts. Waits watch terminal artifacts (an `exit=`
  line, a file that appears), never a process-name poll. Acceptance means the parent/dispatcher
  actually ran this validator — one command, whose own output is then pasteable into the parent's
  report under the same rule. Nothing in this script or in CI enforces any of the three; they are
  habits, named so the habit is visible rather than dressed up as a mechanism.

ITS LIMIT — printed in this tool's own output on every run, not buried in this docstring: it
validates FORM, never TRUTH. A fabricated paste (a hand-typed "exit=0" that no command produced)
passes every check here. Truth is enforced one layer down, at the moment evidence reaches the
repo (`scripts/check-evidence-complete.py`, items 0-3), and by the parent re-running the pasted
one-liner when stakes warrant. A form gate is still worth having because every incident above was
FORMLESS — no pasted output, no artifact, a stale commit — and form is what a machine can hold.

THE FOUR CHECKS.

  1. VERIFICATION SECTION. A heading whose text contains "Verification" (case-insensitive, any
     `#` depth) must exist; its section runs to the next heading of equal-or-shallower depth, or
     EOF. Within it, at least one fenced (```) block must contain a line matching `exit=<N>`
     (the repo's `; echo "exit=$?"` idiom) AND at least one other non-blank line (a crude but
     deliberately unambitious "there is a command here too" heuristic — this tool does not
     attempt to parse what a shell command IS, only that the block is not JUST an exit line).

  2. HEAD-SHA HEADER STAMP. The FIRST line anywhere in the file matching `Commit: \`<hex>\`` (the
     same phrase `scripts/verify-dd043-pr3.sh`'s own RESULTS.md header uses) is compared, as a
     PREFIX, against the ref actually being checked — `git rev-parse HEAD` by default, or the
     literal string passed via `--ref` (see WHY --ref EXISTS below). Mismatch -> red: the
     stale-task-N-reuse class this check exists to automate.

  3. ARTIFACTS EXIST. Every backtick-quoted, path-shaped span (contains `/`, or ends in a
     recognised evidence extension: md/txt/log/csv/json/py/sh/yml/yaml) inside the verification
     section — prose or fenced block — must exist on disk, resolved relative to the repo root (or
     absolute as written). Deliberately narrower than `scripts/check-citations.py`'s bare-token
     scanning: an unquoted path in prose is not recognised here — disclosed, not silently claimed
     covered. Existence only, on-disk — this does not check the path is TRACKED, since a report
     may legitimately point at a gitignored scratch run directory.

  4. PROSE CLAIMS ARE BACKED BY A PASTE, SCOPED TO THE VERIFICATION SECTION. Every `exit=<N>`
     occurrence OUTSIDE a fenced block (i.e. in prose) must match a REAL captured `exit=<N>` line
     (the same `EXIT_LINE_RE` shape check 1 requires, same number) inside a fenced block WITHIN the
     `## Verification` section found by check 1 — not a bare substring anywhere in the block, and
     not a fenced block living elsewhere in the file. Every `<P> passed, <F> failed, <S> skipped`
     prose occurrence must likewise occur, as an exact substring, inside a fenced block within that
     same section. Both halves of this fix close one observed false-clean: the original
     implementation joined every fenced block in the WHOLE FILE and did a bare substring test, so a
     decoy fence anywhere in the document containing the claimed text — unrelated to the actual
     verification run — made a stale or fabricated prose claim look backed. A prose-only tally or
     exit code, or one backed only by a paste outside Verification — the claims-match-their-check
     rule in checkable form — is red.

WHY --ref EXISTS. Check 2 compares against the ACTUAL current HEAD by default, which is exactly
right for a real report (a report claiming a stale commit IS the defect this check exists to
catch) but makes a literal, tracked "compliant fixture" impossible to construct: any hard-coded
SHA in a committed fixture is falsified by the very next commit — including the commit that adds
the fixture, since a commit cannot embed its own SHA. `--ref <literal>` lets
`scripts/fixtures/agent-report/` and the CI step that runs them (see that directory, and
`.github/workflows/ci.yml`'s `citation-integrity` job) hold FIXED placeholder SHAs and compare
against a matching fixed value, independent of when the check runs — the sha-mismatch LOGIC is
tested deterministically, while real, unflagged usage (no `--ref`) still means "the real current
HEAD", never a frozen historical value.

`--ref` IS GATED, ON PURPOSE. An unrestricted `--ref` would neutralize check 2 in real use: any
caller could pass `--ref <the report's own stale stamp>` and make a genuinely stale report compare
clean against itself — exactly the defect check 2 exists to catch, defeated by the tool's own
escape hatch. So `--ref` is honored ONLY when the report path being checked resolves under
`scripts/fixtures/` (this directory); anywhere else, a supplied `--ref` is REFUSED (exit 3, not
silently ignored and not silently honored) — "I could not check" must never read as either "I
checked and it is fine" or as a way to launder a stale stamp. Real, unflagged usage against a real
report is unaffected: it never passes `--ref` and always compares against actual `git rev-parse
HEAD`.

Exit 0: all four checks pass.
Exit 1: at least one finding — printed with which check it came from.
Exit 2: usage error (no report-file argument).
Exit 3: REFUSED — the report file does not exist or could not be read; `git rev-parse HEAD` failed
        with no `--ref` override; or `--ref` was supplied for a report path outside
        `scripts/fixtures/` (see "`--ref` IS GATED" above). Distinct from exit 1: "I could not
        check" must never read as "I checked and it is fine."

Run: python3 scripts/check-agent-report.py <report.md>
     python3 scripts/check-agent-report.py --ref <sha> <report.md>   (testing only — see above)
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES_DIR = ROOT / "scripts" / "fixtures"

HEADING_RE = re.compile(r"^(#{1,6})\s*(.+?)\s*$", re.MULTILINE)
FENCE_RE = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)
EXIT_LINE_RE = re.compile(r"^\s*exit=(\d+)\s*$", re.MULTILINE)
EXIT_TOKEN_RE = re.compile(r"\bexit=(\d+)\b")
TALLY_TOKEN_RE = re.compile(r"\b(\d+)\s+passed,\s*(\d+)\s+failed,\s*(\d+)\s+skipped\b")
COMMIT_LINE_RE = re.compile(r"^Commit:\s*`([0-9a-f]{4,40})`", re.MULTILINE)
INLINE_CODE_RE = re.compile(r"`([^`\n]+)`")
PATH_EXT_RE = re.compile(r"\.(?:md|txt|log|csv|json|py|sh|yml|yaml)$")


class Refused(Exception):
    """The check could not be performed at all — distinct from exit 1 (a form finding), the same
    discipline every other checker in this thread keeps (see e.g.
    scripts/check-row-label-coverage.py's own Refused class)."""


def looks_like_path(token: str) -> bool:
    t = token.rstrip("/")
    return "/" in t or bool(PATH_EXT_RE.search(t))


def find_section(text: str, heading_needle: str) -> str | None:
    """-> the text of the first heading whose title contains `heading_needle`
    (case-insensitive), from just after that heading line to the next heading of
    equal-or-shallower `#` depth, or EOF. None if no such heading exists."""
    headings = list(HEADING_RE.finditer(text))
    for i, m in enumerate(headings):
        depth, title = len(m.group(1)), m.group(2)
        if heading_needle.lower() not in title.lower():
            continue
        start = m.end()
        end = len(text)
        for later in headings[i + 1:]:
            if len(later.group(1)) <= depth:
                end = later.start()
                break
        return text[start:end]
    return None


def fenced_blocks(text: str) -> list[str]:
    return [m.group(1) for m in FENCE_RE.finditer(text)]


def check_verification_section(text: str, findings: list[str]) -> None:
    section = find_section(text, "verification")
    if section is None:
        findings.append("check 1 (verification section): no heading containing "
                         "'Verification' found — a report is accepted only with the pasted "
                         "output of its own verification (see this script's CONTRACT)")
        return
    for block in fenced_blocks(section):
        lines = [ln for ln in block.splitlines() if ln.strip()]
        has_exit = any(EXIT_LINE_RE.match(ln) for ln in lines)
        has_other = any(not EXIT_LINE_RE.match(ln) for ln in lines)
        if has_exit and has_other:
            return  # at least one compliant block found
    findings.append("check 1 (verification section): the 'Verification' section has no fenced "
                     "block containing both a command line and a captured `exit=<N>` line — "
                     "\"reported success, verification never ran\" is exactly this shape")


def check_commit_stamp(text: str, ref: str, findings: list[str]) -> None:
    m = COMMIT_LINE_RE.search(text)
    if not m:
        findings.append("check 2 (commit stamp): no `Commit: `<sha>`` header line found")
        return
    stamped = m.group(1)
    if not ref.startswith(stamped):
        findings.append(f"check 2 (commit stamp): report stamps `{stamped}`, but the ref being "
                         f"checked against is `{ref}` — stale report (verified against a commit "
                         f"that is no longer the one being checked)")


def check_artifacts_exist(text: str, findings: list[str]) -> None:
    section = find_section(text, "verification")
    if section is None:
        return  # already a check-1 finding; do not double-report
    seen = set()
    for m in INLINE_CODE_RE.finditer(section):
        tok = m.group(1).strip()
        if not looks_like_path(tok) or tok in seen:
            continue
        seen.add(tok)
        p = pathlib.Path(tok)
        target = p if p.is_absolute() else (ROOT / tok)
        if not target.exists():
            findings.append(f"check 3 (artifacts exist): `{tok}` is named in the verification "
                             f"section but does not exist on disk (resolved: {target})")


def check_prose_backed_by_paste(text: str, findings: list[str]) -> int:
    """Check 4 (see THE FOUR CHECKS). Scoped to the `## Verification` section's OWN fenced blocks
    — a decoy fence living anywhere else in the file must never back a prose claim (the false-
    clean this function used to have: it joined every fenced block in the WHOLE FILE and did a
    bare substring test, so an unrelated fence elsewhere containing the claimed text made a stale
    or fabricated claim look backed). An `exit=<N>` claim must additionally match a REAL
    `EXIT_LINE_RE`-shaped captured line for that same N inside the section's pasted blocks — not a
    bare substring occurrence, which a hand-typed aside ("see exit=0 above") could satisfy without
    ever being an actual captured shell exit line. A tally claim keeps the substring rule (no
    dedicated line-shape regex exists for it) but is scoped the same way."""
    section = find_section(text, "verification")
    pasted = "\n".join(fenced_blocks(section)) if section is not None else ""
    captured_exits = {m.group(1) for m in EXIT_LINE_RE.finditer(pasted)}
    prose = FENCE_RE.sub("", text)
    n_checked = 0
    for m in EXIT_TOKEN_RE.finditer(prose):
        n_checked += 1
        claim, num = m.group(0), m.group(1)
        if num not in captured_exits:
            findings.append(f"check 4 (prose backed by paste): prose asserts `{claim}` but the "
                             f"Verification section has no pasted, captured `exit={num}` line "
                             f"backing it (a decoy fence elsewhere in the file, or a bare "
                             f"substring match, does not count)")
    for m in TALLY_TOKEN_RE.finditer(prose):
        n_checked += 1
        claim = m.group(0)
        if claim not in pasted:
            findings.append(f"check 4 (prose backed by paste): prose asserts `{claim}` but no "
                             f"fenced block inside the Verification section contains that exact "
                             f"text")
    return n_checked


def main() -> int:
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--ref", default=None,
                    help="compare the report's Commit: stamp against this literal string "
                         "instead of `git rev-parse HEAD` — testing only, honored ONLY for a "
                         "report path under scripts/fixtures/, see WHY --ref EXISTS")
    ap.add_argument("report", nargs="?")
    args = ap.parse_args()
    if not args.report:
        print("usage: check-agent-report.py [--ref <sha>] <report.md>", file=sys.stderr)
        return 2

    report_path = pathlib.Path(args.report)
    if not report_path.is_file():
        raise Refused(f"{report_path} does not exist or is not a file")
    try:
        text = report_path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        raise Refused(f"could not read {report_path}: {e}")

    if args.ref is not None:
        # GATE (see "`--ref` IS GATED, ON PURPOSE" above): an unrestricted --ref would let a real
        # invocation pass the report's OWN stale stamp and defeat check 2 entirely. Honored only
        # for a report living under scripts/fixtures/ — anywhere else, refuse rather than silently
        # ignore (a silently-ignored --ref could read as "I checked with your ref" when it didn't)
        # or silently honor (which is the defect this gate exists to close).
        try:
            report_path.resolve().relative_to(FIXTURES_DIR.resolve())
        except ValueError:
            raise Refused(f"--ref was supplied but {report_path} does not resolve under "
                          f"{FIXTURES_DIR} — --ref is honored only for fixtures (testing only, "
                          f"see WHY --ref EXISTS); refusing rather than letting a real "
                          f"invocation launder a stale commit stamp past check 2")
        ref = args.ref
    else:
        proc = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"],
                              capture_output=True, text=True)
        if proc.returncode != 0 or not proc.stdout.strip():
            raise Refused(f"`git rev-parse HEAD` failed and no --ref override was given: "
                          f"{proc.stderr.strip()}")
        ref = proc.stdout.strip()

    findings: list[str] = []
    check_verification_section(text, findings)
    check_commit_stamp(text, ref, findings)
    check_artifacts_exist(text, findings)
    n_prose_claims = check_prose_backed_by_paste(text, findings)

    print(f"check-agent-report: {report_path} checked against ref `{ref}`; "
          f"{n_prose_claims} prose tally/exit claim(s) examined for a matching pasted block")
    print("LIMIT: this tool validates FORM only — a fabricated paste passes every check here. "
          "Truth is enforced at the repo gate (scripts/check-evidence-complete.py, items 0-3) "
          "and by the parent re-running the pasted command when stakes warrant.")
    if findings:
        print(f"\n{len(findings)} FINDING(S):")
        for f in findings:
            print(f"  FAIL {f}")
        return 1
    print("\nOK — verification section present with a pasted command+exit block, commit stamp "
          "matches the checked ref, every named artifact exists on disk, and every prose "
          "tally/exit claim is backed by a pasted block.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Refused as e:
        print(f"check-agent-report: REFUSED — {e}", file=sys.stderr)
        sys.exit(3)
