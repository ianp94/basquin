# Agent report — example task (fixture: FAILURE MODE 2 — stale commit stamp)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo` capture, not a paraphrase of any real repo tool's
output (see `valid.md`'s header note for why that distinction matters).

Isolates check 2: the header below stamps `cafebabe`, a DIFFERENT placeholder from `valid.md`'s
`deadbeef` — deliberately, so that running this fixture with the SAME `--ref deadbeef` used for
every other fixture in this directory produces a mismatch. This is the "verified against a commit
that is no longer HEAD" class: a report reused across sessions after the tree moved on. Every
other check stays clean.

Commit: `cafebabe`

## Verification

Ran a trivially reproducible synthetic command:

```
echo "synthetic output line"; echo "exit=$?"
synthetic output line
exit=0
```

Artifact cited above: `scripts/check-agent-report.py`, which exists on disk.

Result: **exit=0**.
