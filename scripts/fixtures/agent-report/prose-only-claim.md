# Agent report — example task (fixture: FAILURE MODE 4 — prose claim never pasted)

Isolates check 4 (`scripts/check-agent-report.py`): the prose result line below asserts a tally
("5 passed, 0 failed, 0 skipped") that never appears, verbatim, inside any fenced block in this
file — a claim wider than its check, the exact defect class this whole thread exists to remove.
Every other check stays clean: same commit stamp and cited artifact as `valid.md`, run with the
same `--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran the evidence-completeness gate against the clean tree:

```
python3 scripts/check-evidence-complete.py; echo "exit=$?"
OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
exit=0
```

Artifact cited above: `bench-results/verify-20260730T215842Z/RESULTS.md`, which exists on disk.

Result: **5 passed, 0 failed, 0 skipped**, exit=0 — but the "5 passed, 0 failed, 0 skipped" tally
was never actually pasted anywhere above; only `exit=0` was.
