# Agent report — example task (fixture: FAILURE MODE 4 — prose claim never pasted)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo` capture, not a paraphrase of any real repo tool's
output (see `valid.md`'s header note for why that distinction matters).

Isolates check 4: the prose result line below asserts a tally ("5 passed, 0 failed, 0 skipped")
that never appears, verbatim, inside any fenced block in this file — a claim wider than its
check, the exact defect class this whole thread exists to remove. Every other check stays clean:
same commit stamp and cited artifact as `valid.md`, run with the same `--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran a trivially reproducible synthetic command:

```
echo "synthetic output line"; echo "exit=$?"
synthetic output line
exit=0
```

Artifact cited above: `scripts/check-agent-report.py`, which exists on disk.

Result: **5 passed, 0 failed, 0 skipped**, exit=0 — but the "5 passed, 0 failed, 0 skipped" tally
was never actually pasted anywhere above; only `exit=0` was.
