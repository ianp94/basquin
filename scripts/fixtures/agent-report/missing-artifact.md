# Agent report — example task (fixture: FAILURE MODE 3 — claimed artifact absent)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo` capture, not a paraphrase of any real repo tool's
output (see `valid.md`'s header note for why that distinction matters).

Isolates check 3: the verification section below names an artifact path that does not exist
anywhere on disk — the refused-run shape the item 6 design calls out directly ("a fix 'proven' by
three exit codes; one run had refused and written no artifact"). Every other check stays clean:
same commit stamp as `valid.md`, run with the same `--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran a trivially reproducible synthetic command:

```
echo "synthetic output line"; echo "exit=$?"
synthetic output line
exit=0
```

Artifact cited above: `scripts/fixtures/agent-report/DOES-NOT-EXIST.md` — this path was
never created; check 3 must find it missing.

Result: **exit=0**.
