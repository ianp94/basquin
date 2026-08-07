# Agent report — example task (fixture: FAILURE MODE 1 — no captured exit code)

Isolates check 1 (`scripts/check-agent-report.py`): the fenced block below shows a command and
its output, but never a captured `exit=$?` line — "reported success, verification never ran" is
structurally indistinguishable from this shape, which is exactly what check 1 exists to catch.
Every other check stays clean: same commit stamp and cited artifact as `valid.md`, run with the
same `--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran the evidence-completeness gate against the clean tree:

```
python3 scripts/check-evidence-complete.py
OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
```

Artifact cited above: `bench-results/verify-20260730T215842Z/RESULTS.md`, which exists on disk.

Result: passed.
