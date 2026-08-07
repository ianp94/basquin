# scripts/ — verification-integrity tooling (DD-045)

An index, not a second copy of any tool's contract — each script's own module docstring is the
normative text for what it checks and why; this file only points at them so a reader can find the
right one.

| Script | Checks | Wired in CI |
|---|---|---|
| `check-citations.py` | Whole-tree citation resolution, carried-value drift, commit-SHA reachability (item 4B). | `citation-integrity` job, `.github/workflows/ci.yml`. |
| `check-removed-deps.py` | A removed path has no surviving dependent, code included (item 4A). | Same job. |
| `check-row-label-coverage.py` | A harness row without a matching kill scenario is visible (item 4C). | `verify-dd043-pr3` job. |
| `check-evidence-complete.py` | Every tracked `bench-results/` evidence directory is a complete record, not a truncated or `NON-CITABLE` one (item 6a). | `citation-integrity` job. |
| `check-agent-report.py` | One report file: a pasted verification block, a matching HEAD-SHA stamp, artifacts that exist, prose claims backed by a paste (item 6b). | `citation-integrity` job, run against `scripts/fixtures/agent-report/` on every push/PR. |
| `agent-report-hook.py` | Optional `SubagentStop` hook wiring for the same contract check-agent-report.py enforces, this-checkout-only (item 6b, conditional piece). | Not CI — `.claude/settings.json`, local harness only. |

`scripts/check-citations-allowlist.txt` is the one shared allowlist file; its header comment lists
every entry kind and which script owns each.

## THE CONTRACT (DD-045 item 6c)

Normative text lives in `check-evidence-complete.py`'s and `check-agent-report.py`'s own
docstrings — the one place it can change in the same commit as the check that enforces it, which
is this whole thread's thesis. Restated here only as a pointer, not a copy:

**A report is accepted only with the pasted output of its own verification. Partial evidence is
never committed.**

`check-agent-report.py` grades the REPORT that makes a claim (form only — see its own printed
limit); `check-evidence-complete.py` grades the EVIDENCE once it reaches `bench-results/` (bytes,
CI-enforced). Between them: verification artifacts to disk first, the report last, so a killed
session leaves artifacts without claims — recoverable — rather than claims without artifacts.
Waits watch a terminal artifact (an `exit=` line, a file that appears), never a process-name poll.
