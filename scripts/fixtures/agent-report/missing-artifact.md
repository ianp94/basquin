# Agent report — example task (fixture: FAILURE MODE 3 — claimed artifact absent)

Isolates check 3 (`scripts/check-agent-report.py`): the verification section below names an
artifact path that does not exist anywhere on disk — the refused-run shape the item 6 design
calls out directly ("a fix 'proven' by three exit codes; one run had refused and written no
artifact"). Every other check stays clean: same commit stamp as `valid.md`, run with the same
`--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran the evidence-completeness gate against the clean tree:

```
python3 scripts/check-evidence-complete.py; echo "exit=$?"
OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
exit=0
```

Artifact cited above: `bench-results/verify-DOES-NOT-EXIST-99999/RESULTS.md` — this directory was
never created; check 3 must find it missing.

Result: **exit=0**.
