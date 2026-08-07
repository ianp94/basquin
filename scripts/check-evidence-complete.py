#!/usr/bin/env python3
"""Evidence-completeness gate (DD-045 item 6a) — partial evidence never *merges*.

Design: `docs/superpowers/specs/2026-08-04-dd045-items-4-6-design.md`, item 6a. Motivated by two
observed failures (that design's evidence table): "~70 citations hand-verified accurate" where the
gate found four dead paths on the same file immediately, and a fix "proven" by three exit codes
where one run had actually refused and written no artifact. Items 0-3 already grade the BYTES of
tracked evidence (citations resolve, the run-of-record pointer resolves, the harness's own rows are
mutation-tested). This script closes a narrower gap: a tracked evidence directory can satisfy every
one of those checks while still being an incomplete RECORD of a run — a truncated `RESULTS.md`, a
run stamped `NON-CITABLE` committed anyway, a `citations-*/` directory whose own disposition ledger
no longer balances (hand-edited after capture) — because nothing before this asserted the SHAPE of
"this directory is a complete record" the way `scripts/verify-dd043-pr3-rows.sh` already asserts
the shape of a green run.

THE CONTRACT (DD-045 item 6c; see also scripts/check-agent-report.py's docstring, its report-side
counterpart): a report is accepted only with the pasted output of its own verification; partial
evidence is never committed. This script is the CI-enforceable half — the moment evidence is
committed to `bench-results/`, it must already be complete, and this gate is what says so
mechanically rather than by review alone.

SCOPE — machine-produced evidence shapes only, because "complete" has a mechanical definition only
for those. Free-form hand-authored evidence READMEs (every other tracked top-level directory under
`bench-results/`) have no such definition and are OUT OF SCOPE — counted and printed
("N bench-results dir(s) out of scope for completeness"), never silently skipped:

  * `bench-results/verify-<UTC>/`        — a `scripts/verify-dd043-pr3.sh` run;
  * `bench-results/verify-rows-<UTC>/`   — a `scripts/verify-dd043-pr3-rows.sh` meta-run (a
                                            DIFFERENT top-level shape from the one above, despite
                                            sharing the `verify-` prefix; see VERIFY-ROWS SHAPE);
  * `bench-results/citations-<UTC>/`     — a captured `scripts/check-citations.py` run;
  * `bench-results/RUN-OF-RECORD`        — the tracked pointer FILE (DD-045 item 2), not a
                                            directory; checked against the verify-*/ dirs above.

Loose top-level files that are not evidence directories at all (`bench-results/.gitignore`,
`bench-results/RUN-OF-RECORD` itself, `bench-results/campaigns.json`, `bench-results/report-data.json`,
`bench-results/benchmark-report.html`) are neither in-scope nor counted-out-of-scope: they are not
directories, so "complete" does not even ask the question. Only entries that resolve to a real
tracked directory (i.e. have at least one nested tracked file) are classified at all.

RULES, PER SHAPE.

`verify-<UTC>/RESULTS.md` (also the per-scenario copies nested under a `verify-rows-<UTC>/`
directory — see VERIFY-ROWS SHAPE below; both go through `check_run_results()`):
  * the file must exist — its absence is exactly the "no RESULTS.md, only git-status.txt" shape a
    refused (dirty-gate) run produces, per the design's motivating incident;
  * a `Commit:` header line (backticked hex token) must be present — MANDATORY;
  * a `Tree:` header line is checked ONLY IF PRESENT (backticked hex token; malformed is a
    finding). It is NOT mandatory, though item 4B's harness change
    (`scripts/verify-dd043-pr3.sh`'s `GIT_TREE` stamp) means every run captured from now on carries
    one: the one directory tracked today (`bench-results/verify-20260730T215842Z/`) was captured
    BEFORE that stamp existed, and the design's own "zero grandfathering" promise for item 6a's
    corpus forbids either failing that directory outright or adding it to an allowlist just to
    launder a gap this checker can instead simply disclose. Runs missing it are counted
    ("N run(s) predate the `Tree:` stamp (item 4B) — not enforced retroactively") rather than
    silently passed or failed;
  * the `**P passed, F failed, S skipped.**` tally must parse, AND `P+F+S` must equal the number of
    `| PASS|FAIL|SKIP | ... |` rows actually in the table — a truncated file (tally present, rows
    cut off, or vice versa) fails here even though `Commit:` and the tally both parse individually;
  * the `NON-CITABLE` tag must be ABSENT from the file's text. A dirty or gate-refused run stamped
    `NON-CITABLE` and then committed as evidence is the definition of partial evidence — override
    only via a `non-citable <path> <reason>` entry in `scripts/check-citations-allowlist.txt` (the
    shared allowlist; this script parses only that one kind out of it, mirroring
    `scripts/check-removed-deps.py`'s `removed-ok`-only parse of the same shared file).

VERIFY-ROWS SHAPE — deliberately NOT a literal copy of the rule above, and that deviation is
disclosed rather than silent. `scripts/verify-dd043-pr3-rows.sh`'s OWN top-level
`bench-results/verify-rows-<UTC>/RESULTS.md` (`META_OUT/RESULTS.md`, written at that script's
lines ~517-575) is a DIFFERENT shape from a `verify-dd043-pr3.sh` run: it has no
`**P passed, F failed, S skipped.**` tally at all — its rows are `| Scenario | Status | Exit
(observed; expect) | Wall-clock (s) | Problems |`, `Status` is `PROVEN`/not, never `PASS`/`FAIL`.
Requiring the tally there, as an over-literal reading of the design text would, would make this
checker permanently red against the harness's own real output — a checker that is wrong is worse
than a gap disclosed. So the top-level file is checked for what it ACTUALLY carries: a `Commit:`
header, `NON-CITABLE` absence (same two rules as above), and the "observed-exit column" the design
names — a table header row containing both "Exit" and "observed" (case-insensitive), matching that
script's own `| ... | Exit (observed; expect) | ...` column literally. Meanwhile every SCENARIO's
copied run directory nested underneath (`<scenario>/run/RESULTS.md`, written by that same script's
`shutil.copytree(new_dirs[0], copy_dir)` at line ~468) IS a literal copy of a `verify-dd043-pr3.sh`
run and DOES carry the tally — those nested files are checked with the exact same
`check_run_results()` the plain `verify-<UTC>/` shape uses above. A gate-refusal scenario
legitimately has NO nested RESULTS.md (`git-status.txt` only) by that harness's own design; this
checker does not require one to exist per scenario subdirectory — it checks whichever nested
RESULTS.md files ARE tracked, the same "read the run's output, not the script's text" refusal
`scripts/check-row-label-coverage.py`'s docstring already states for item 4C. No `verify-rows-*/`
directory is tracked as of this writing (Appendix in the item 4-6 design), so this whole shape is
proven only against a scratch fixture, never real committed data — disclosed here, not hidden.

`citations-<UTC>/` directories:
  * both `output.txt` and `README.md` must exist;
  * `output.txt`'s header line (`check-citations: N citations parsed ...`) must parse to a total;
  * `output.txt`'s FINAL DISPOSITION LINE (`OK — V of N citations verified clean ...; U UNCHECKED,
    A allowlisted, R reported to owners, D disclosed absences, K untracked-on-disk — all listed
    above.`) must be present and parse. Its absence is itself a finding: that line is printed by
    `scripts/check-citations.py`'s `main()` only on the success return path — a run that found
    unsuppressed FAILED citations prints `N FINDINGS:` instead and returns 1 BEFORE ever reaching
    it, so a `citations-*/` directory capturing such a run has no disposition line to re-assert
    the ledger of, and is incomplete evidence for the same reason a truncated RESULTS.md is;
  * `V + U + A + D + K` (verified + UNCHECKED + allowlisted + disclosed absences + untracked-on-disk)
    must equal the header's own parsed total N. The `R` (reported-to-owners) figure printed on that
    same line is DELIBERATELY EXCLUDED from this sum: `scripts/check-citations.py:1242`'s own
    `n_rep = stats[K_REP] + stats["values: reported (suppressed FAIL)"]` sums a CITATION-level
    counter with a VALUE-level one — two different ledgers — into that one printed figure, so it is
    not a term of the citation-count ledger the rest of the line balances. This is verified
    against the one real captured run today (`bench-results/citations-20260730T214700Z/`:
    1241+299+136+13+0 = 1689, the header's own total, exactly; adding the printed `2 reported to
    owners` would overshoot by 2) — re-run `python3 scripts/check-citations.py` if this ever stops
    holding and re-derive rather than trusting this comment.

`bench-results/RUN-OF-RECORD`:
  * the pointer file must exist, contain exactly one non-comment non-blank line (the same
    malformed-pointer modes `scripts/check-citations.py`'s `resolve_run_of_record()` already
    refuses), and name a directory that is one of the plain `verify-<UTC>/` directories this run
    found (NOT a `verify-rows-*/` one — the run of record is a `verify-dd043-pr3.sh` run, per
    every existing citation of it);
  * that directory must itself be COMPLETE by the rules above, and its tally's `F` (failed) must be
    `0` — "one of the verify dirs that passed", per the design text.

Exit 0: no finding remains (out-of-scope dirs and disclosed Tree:-stamp gaps are counted and
        printed, never failed).
Exit 1: at least one finding — a file:line / path is printed for each.
Exit 2: ABORTED — a git command failed, or the allowlist is malformed. Distinct from exit 1 for the
        same reason every other checker in this thread keeps the two apart: "I could not check"
        must never read as "I checked and it is fine" OR as "I checked and found a defect".

Run: python3 scripts/check-evidence-complete.py
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWLIST_FILE = ROOT / "scripts" / "check-citations-allowlist.txt"
BENCH = "bench-results"

COMMIT_RE = re.compile(r"^Commit:\s*`([0-9a-f]{4,40})`", re.MULTILINE)
TREE_RE = re.compile(r"^Tree:\s*`([0-9a-f]{4,64})`", re.MULTILINE)
TALLY_RE = re.compile(r"\*\*(\d+) passed, (\d+) failed, (\d+) skipped\.")
ROW_RE = re.compile(r"^\|\s*(?:PASS|FAIL|SKIP)\s*\|", re.MULTILINE)
OBSERVED_EXIT_HEADER_RE = re.compile(r"^\|.*\bexit\b.*\bobserved\b.*\|", re.MULTILINE | re.IGNORECASE)

CITATIONS_HEADER_RE = re.compile(r"^check-citations:\s*(\d+)\s+citations parsed", re.MULTILINE)
# Mirrors scripts/check-citations.py:1243-1256's own f-string, field for field — see this script's
# docstring's "citations-<UTC>/ directories" section for why `reported to owners` is parsed but
# deliberately not one of the summed terms.
CITATIONS_OK_RE = re.compile(
    r"OK — (\d+) of (\d+) citations verified clean[^\n;]*; "
    r"(\d+) UNCHECKED, (\d+) allowlisted, (\d+) reported to owners, "
    r"(\d+) disclosed absences?, (\d+) untracked-on-disk"
)

VERIFY_ROWS_PREFIX = "verify-rows-"
VERIFY_PREFIX = "verify-"
CITATIONS_PREFIX = "citations-"


class Aborted(Exception):
    """The check could not be performed at all — a git command failed, or the allowlist is
    malformed. Raised, not `sys.exit("msg")` (which exits 1, this script's FINDING code) — the
    same discipline `scripts/check-removed-deps.py`'s own `Aborted` class documents: a run that
    could not compute its inputs must never look like a run that computed them and found nothing
    wrong."""


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)


def load_non_citable_allow() -> list[tuple[str, str]]:
    """Parse ONLY `non-citable <path> <reason>` entries out of the shared allowlist file — every
    other kind (frozen/reported/sha/removed-ok/cited-path) belongs to scripts/check-citations.py
    or scripts/check-removed-deps.py, and is skipped here without validation, the same one-kind
    parse `check-removed-deps.py` already does for `removed-ok`."""
    out = []
    if not ALLOWLIST_FILE.exists():
        return out
    for n, raw in enumerate(ALLOWLIST_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if parts[0] != "non-citable":
            continue
        sub = parts[1].split(None, 1) if len(parts) > 1 else []
        if len(sub) < 2:
            raise Aborted(f"allowlist:{n}: non-citable entry needs <path> <reason>")
        out.append((sub[0], sub[1]))
    return out


def tracked_files(prefix: str) -> list[str]:
    proc = git("ls-files", "--", f"{prefix}")
    if proc.returncode != 0:
        raise Aborted(f"git ls-files {prefix} failed: {proc.stderr.strip()}")
    return [ln for ln in proc.stdout.splitlines() if ln.strip()]


def top_level_dirs(paths: list[str]) -> set[str]:
    """Names directly under bench-results/ that are DIRECTORIES — i.e. have at least one nested
    tracked file — never a bare top-level FILE (RUN-OF-RECORD, .gitignore, campaigns.json, ...),
    which this function's caller must not misclassify as an evidence directory."""
    out = set()
    for p in paths:
        parts = p.split("/")
        if len(parts) >= 3:  # bench-results / <dir> / <something inside it>
            out.add(parts[1])
    return out


def read(rel: str) -> str | None:
    try:
        return (ROOT / rel).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def non_citable_allowed(path: str, allow: list[tuple[str, str]]) -> str | None:
    for pat, reason in allow:
        if path == pat or (pat.endswith("/") and path.startswith(pat)):
            return reason
    return None


def check_run_results(results_path: str, allow: list[tuple[str, str]], findings: list[str],
                       stats: dict) -> None:
    """Apply the `verify-<UTC>/RESULTS.md` rules (docstring) to ONE tracked RESULTS.md path —
    reused verbatim for plain verify-*/ dirs AND for every nested per-scenario copy under a
    verify-rows-*/ directory (see VERIFY-ROWS SHAPE)."""
    text = read(results_path)
    if text is None:
        findings.append(f"{results_path}: MISSING — no RESULTS.md at this tracked path "
                         f"(the refused-run shape: a dirty-gate refusal writes git-status.txt "
                         f"but never RESULTS.md)")
        return
    m = COMMIT_RE.search(text)
    if not m:
        findings.append(f"{results_path}: no `Commit:` header line found — incomplete evidence")
    tm = TREE_RE.search(text)
    if tm is None:
        stats["no-tree-stamp"] += 1
    elif not tm.group(1):
        findings.append(f"{results_path}: `Tree:` header line present but unparseable")
    tally = TALLY_RE.search(text)
    if not tally:
        findings.append(f"{results_path}: no parseable '**P passed, F failed, S skipped.**' "
                         f"tally line")
    else:
        p, f, s = (int(x) for x in tally.groups())
        n_rows = len(ROW_RE.findall(text))
        if n_rows != p + f + s:
            findings.append(f"{results_path}: tally says {p}+{f}+{s}={p + f + s} rows but the "
                             f"table has {n_rows} PASS/FAIL/SKIP row(s) — truncated or "
                             f"hand-edited evidence")
    if "NON-CITABLE" in text:
        reason = non_citable_allowed(results_path, allow)
        if reason is not None:
            stats["non-citable-allowed"] += 1
        else:
            findings.append(f"{results_path}: stamped NON-CITABLE and committed as evidence — "
                             f"partial evidence, per this script's docstring (override: a "
                             f"`non-citable <path> <reason>` entry in "
                             f"scripts/check-citations-allowlist.txt)")


def check_verify_dir(name: str, tracked: set[str], allow: list[tuple[str, str]],
                      findings: list[str], stats: dict) -> tuple[str, bool]:
    """Plain verify-<UTC>/ (never verify-rows-*): -> (results_path, is_complete). is_complete is
    used only by the RUN-OF-RECORD check below — a directory this call already found a finding
    for is never eligible to be named as the passing run of record."""
    results_path = f"{BENCH}/{name}/RESULTS.md"
    before = len(findings)
    if results_path in tracked:
        check_run_results(results_path, allow, findings, stats)
    else:
        findings.append(f"{BENCH}/{name}/: MISSING RESULTS.md — no tracked RESULTS.md under "
                         f"this verify-<UTC>/ directory (the refused-run shape)")
    return results_path, len(findings) == before


def check_verify_rows_dir(name: str, tracked: set[str], allow: list[tuple[str, str]],
                          findings: list[str], stats: dict) -> None:
    """verify-rows-<UTC>/ — see VERIFY-ROWS SHAPE in the module docstring: the top-level
    RESULTS.md is checked for a DIFFERENT shape (Commit:, NON-CITABLE, observed-exit column, no
    tally requirement); every OTHER tracked RESULTS.md nested under this directory is checked
    with the plain verify-*/ rules via check_run_results()."""
    top = f"{BENCH}/{name}/RESULTS.md"
    if top not in tracked:
        findings.append(f"{BENCH}/{name}/: MISSING RESULTS.md — no tracked top-level RESULTS.md "
                         f"under this verify-rows-<UTC>/ directory")
    else:
        text = read(top)
        if text is None:
            findings.append(f"{top}: MISSING — could not read")
        else:
            if not COMMIT_RE.search(text):
                findings.append(f"{top}: no `Commit:` header line found — incomplete evidence")
            if not OBSERVED_EXIT_HEADER_RE.search(text):
                findings.append(f"{top}: no table header column naming an observed exit code "
                                 f"('Exit (observed...)') — see this script's docstring, "
                                 f"VERIFY-ROWS SHAPE")
            if "NON-CITABLE" in text:
                reason = non_citable_allowed(top, allow)
                if reason is not None:
                    stats["non-citable-allowed"] += 1
                else:
                    findings.append(f"{top}: stamped NON-CITABLE and committed as evidence — "
                                     f"partial evidence (override: a `non-citable <path> "
                                     f"<reason>` allowlist entry)")
    prefix = f"{BENCH}/{name}/"
    for path in sorted(tracked):
        if (path.startswith(prefix) and path.endswith("/RESULTS.md") and path != top):
            check_run_results(path, allow, findings, stats)
            stats["verify-rows-nested-checked"] += 1


def check_citations_dir(name: str, tracked: set[str], findings: list[str], stats: dict) -> None:
    output_path = f"{BENCH}/{name}/output.txt"
    readme_path = f"{BENCH}/{name}/README.md"
    if output_path not in tracked:
        findings.append(f"{BENCH}/{name}/: MISSING output.txt")
    if readme_path not in tracked:
        findings.append(f"{BENCH}/{name}/: MISSING README.md")
    if output_path not in tracked:
        return
    text = read(output_path)
    if text is None:
        findings.append(f"{output_path}: MISSING — could not read")
        return
    hm = CITATIONS_HEADER_RE.search(text)
    if not hm:
        findings.append(f"{output_path}: no parseable 'check-citations: N citations parsed' "
                         f"header line")
        return
    total = int(hm.group(1))
    ok = CITATIONS_OK_RE.search(text)
    if not ok:
        findings.append(f"{output_path}: no parseable final disposition line ('OK — V of N "
                         f"citations verified clean; ...') — a captured run that ended in "
                         f"'N FINDINGS:' instead (unresolved FAILED citations) has no such line "
                         f"and is incomplete evidence for the same reason a truncated RESULTS.md "
                         f"is; see this script's docstring")
        return
    verified, n_in_line, unchecked, allowlisted, _reported, disclosed, untracked = (
        int(x) for x in ok.groups())
    if n_in_line != total:
        findings.append(f"{output_path}: header total {total} disagrees with the disposition "
                         f"line's own 'of {n_in_line}' — hand-edited or truncated evidence")
    bucket_sum = verified + unchecked + allowlisted + disclosed + untracked
    if bucket_sum != total:
        findings.append(f"{output_path}: disposition buckets sum to {bucket_sum} "
                         f"({verified} verified + {unchecked} UNCHECKED + {allowlisted} "
                         f"allowlisted + {disclosed} disclosed + {untracked} untracked-on-disk) "
                         f"but the parsed total is {total} — the ledger this tool asserts at "
                         f"runtime no longer balances on the committed copy")


def check_run_of_record(tracked: set[str], verify_status: dict[str, bool], findings: list[str],
                        stats: dict) -> None:
    ptr_path = f"{BENCH}/RUN-OF-RECORD"
    if ptr_path not in tracked:
        findings.append(f"{ptr_path}: MISSING — no tracked run-of-record pointer")
        return
    text = read(ptr_path)
    if text is None:
        findings.append(f"{ptr_path}: MISSING — could not read")
        return
    content = [ln.strip() for ln in text.splitlines() if ln.strip() and not ln.strip().startswith("#")]
    if len(content) != 1:
        findings.append(f"{ptr_path}: has {len(content)} non-comment, non-blank line(s) "
                         f"(expected exactly 1): {content!r}")
        return
    name = content[0]
    if name not in verify_status:
        findings.append(f"{ptr_path}: names {name!r}, which is not one of the tracked plain "
                         f"verify-<UTC>/ directories this run examined — dangling or "
                         f"out-of-scope pointer")
        return
    results_path = f"{BENCH}/{name}/RESULTS.md"
    rtext = read(results_path) if results_path in tracked else None
    tally = TALLY_RE.search(rtext) if rtext else None
    if not verify_status[name]:
        findings.append(f"{ptr_path}: names {name!r}, which this run already found incomplete "
                         f"(see the finding(s) above for {BENCH}/{name}/) — the run of record "
                         f"must be one of the verify dirs that PASSED")
    elif not tally:
        findings.append(f"{ptr_path}: names {name!r}, whose RESULTS.md has no parseable tally — "
                         f"cannot confirm it passed")
    elif int(tally.group(2)) != 0:
        findings.append(f"{ptr_path}: names {name!r}, whose tally shows {tally.group(2)} "
                         f"failed — the run of record must be one of the verify dirs that PASSED")


def main() -> int:
    all_paths = tracked_files(f"{BENCH}/")
    tracked = set(all_paths)
    dirs = top_level_dirs(all_paths)
    allow = load_non_citable_allow()

    findings: list[str] = []
    stats: dict = {"no-tree-stamp": 0, "non-citable-allowed": 0, "verify-rows-nested-checked": 0}

    verify_dirs = sorted(d for d in dirs if d.startswith(VERIFY_PREFIX)
                         and not d.startswith(VERIFY_ROWS_PREFIX))
    verify_rows_dirs = sorted(d for d in dirs if d.startswith(VERIFY_ROWS_PREFIX))
    citations_dirs = sorted(d for d in dirs if d.startswith(CITATIONS_PREFIX))
    in_scope = set(verify_dirs) | set(verify_rows_dirs) | set(citations_dirs)
    out_of_scope = sorted(dirs - in_scope)

    verify_status: dict[str, bool] = {}
    for name in verify_dirs:
        _, complete = check_verify_dir(name, tracked, allow, findings, stats)
        verify_status[name] = complete
    for name in verify_rows_dirs:
        check_verify_rows_dir(name, tracked, allow, findings, stats)
    for name in citations_dirs:
        check_citations_dir(name, tracked, findings, stats)
    check_run_of_record(tracked, verify_status, findings, stats)

    print(f"check-evidence-complete: {len(verify_dirs)} verify-<UTC>/ dir(s), "
          f"{len(verify_rows_dirs)} verify-rows-<UTC>/ dir(s), {len(citations_dirs)} "
          f"citations-<UTC>/ dir(s) examined; {len(out_of_scope)} bench-results dir(s) out of "
          f"scope for completeness (free-form hand-authored evidence, no mechanical definition "
          f"of 'complete')")
    if out_of_scope:
        for d in out_of_scope:
            print(f"  OUT-OF-SCOPE {BENCH}/{d}/")
    if stats["no-tree-stamp"]:
        print(f"  {stats['no-tree-stamp']} run(s) predate the `Tree:` stamp (item 4B) — not "
              f"enforced retroactively, per this script's docstring")
    if stats["non-citable-allowed"]:
        print(f"  {stats['non-citable-allowed']} NON-CITABLE run(s) allowlisted with a reason")
    if stats["verify-rows-nested-checked"]:
        print(f"  {stats['verify-rows-nested-checked']} nested per-scenario RESULTS.md checked "
              f"under verify-rows-<UTC>/ directories")

    if findings:
        print(f"\n{len(findings)} FINDING(S):")
        for f in findings:
            print(f"  FAIL {f}")
        return 1
    print("\nOK — every in-scope bench-results evidence directory is complete; the run of "
          "record names a verify-<UTC>/ directory that passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Aborted as e:
        print(f"check-evidence-complete: ABORTED — {e}", file=sys.stderr)
        sys.exit(2)
