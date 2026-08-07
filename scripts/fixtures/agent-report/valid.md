# Agent report — example task (fixture: compliant report)

This fixture is a TRACKED example of a report that satisfies every check in
`scripts/check-agent-report.py`. It is invoked with `--ref deadbeef` (see
`.github/workflows/ci.yml`'s citation-integrity job and this directory's own note below) —
a placeholder commit, not a real one, because a literal SHA embedded in a tracked file would be
falsified by the very next commit (see check-agent-report.py's "WHY --ref EXISTS").

Commit: `deadbeef`

## Verification

Ran the evidence-completeness gate against the clean tree:

```
python3 scripts/check-evidence-complete.py; echo "exit=$?"
check-evidence-complete: 1 verify-<UTC>/ dir(s), 0 verify-rows-<UTC>/ dir(s), 1 citations-<UTC>/
dir(s) examined; 25 bench-results dir(s) out of scope for completeness (free-form hand-authored
evidence, no mechanical definition of 'complete')
  1 run(s) predate the `Tree:` stamp (item 4B) — not enforced retroactively, per this script's
docstring

OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
exit=0
```

Artifact cited above: `bench-results/verify-20260730T215842Z/RESULTS.md` — the run of record's
own evidence, which exists on disk (checked by `scripts/check-agent-report.py`'s check 3).

Result: **exit=0**.
