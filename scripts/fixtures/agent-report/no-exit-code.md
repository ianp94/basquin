# Agent report — example task (fixture: FAILURE MODE 1 — no captured exit code)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo` capture, not a paraphrase of any real repo tool's
output (see `valid.md`'s header note for why that distinction matters).

Isolates check 1: the fenced block below shows a command and its output, but never a captured
`exit=$?` line — "reported success, verification never ran" is structurally indistinguishable
from this shape, which is exactly what check 1 exists to catch. Every other check stays clean:
same commit stamp and cited artifact as `valid.md`, run with the same `--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran a trivially reproducible synthetic command:

```
echo "synthetic output line"
synthetic output line
```

Artifact cited above: `scripts/check-agent-report.py`, which exists on disk.

Result: passed.
