# Agent report — example task (fixture: FAILURE MODE 2 — stale commit stamp)

Isolates check 2 (`scripts/check-agent-report.py`): the header below stamps `cafebabe`, a
DIFFERENT placeholder from `valid.md`'s `deadbeef` — deliberately, so that running this
fixture with the SAME `--ref deadbeef` used for every other fixture in this directory
produces a mismatch. This is the "verified against a commit that is no longer HEAD" class: a
report reused across sessions after the tree moved on. Every other check stays clean.

Commit: `cafebabe`

## Verification

Ran the evidence-completeness gate against the clean tree:

```
python3 scripts/check-evidence-complete.py; echo "exit=$?"
OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
exit=0
```

Artifact cited above: `bench-results/verify-20260730T215842Z/RESULTS.md`, which exists on disk.

Result: **exit=0**.
