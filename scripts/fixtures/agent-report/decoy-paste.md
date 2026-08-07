# Agent report — example task (fixture: FAILURE MODE 5 — decoy paste elsewhere in the document)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo`/`true`/`false` capture, not a paraphrase of any real
repo tool's output. The previous version of this fixture paraphrased a real
`check-evidence-complete.py` NON-CITABLE-injection scenario by hand and claimed it produces
`1 FINDING(S)`; PR #108's round-1 review found the real tool actually emits 2 (a RUN-OF-RECORD
cascade), because the injected directory is the run of record. A synthetic scenario has no such
domain behavior to get wrong, which is why this rewrite uses one.

Isolates check 4: the real Verification block below captures a NON-ZERO exit from an actual run,
but the prose result line claims success. A DECOY fenced block outside the Verification section —
an unrelated appendix, e.g. an old log excerpt kept for context — happens to contain the literal
text the false prose claim needs. Before the fix this fixture proves, check 4 joined every fenced
block in the WHOLE FILE and did a bare substring test, so that decoy block made the false claim
look backed; this fixture is the shape that regression must never pass again. Every other check
stays clean: same commit stamp and cited artifact as `valid.md`, run with the same
`--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran a trivially reproducible synthetic command that fails on purpose:

```
echo "synthetic failure"; false; echo "exit=$?"
synthetic failure
exit=1
```

Artifact cited above: `scripts/check-agent-report.py`, which exists on disk.

Result: **exit=0**.

## Appendix — unrelated prior-session log (decoy, not part of Verification)

This block is NOT inside the Verification section above. It is old, unrelated output kept for
context, and it happens to contain the exact digits the false claim above needs:

```
echo "unrelated"; true; echo "exit=$?"
unrelated
exit=0
```
