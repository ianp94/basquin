# Agent report — example task (fixture: FAILURE MODE 5 — decoy paste elsewhere in the document)

Isolates check 4 (`scripts/check-agent-report.py`): the real Verification section below captures a
NON-ZERO exit from an actual run, but the prose result line claims success. A DECOY fenced block
outside the Verification section — an unrelated appendix, e.g. an old log excerpt kept for context
— happens to contain the literal text the false prose claim needs. Before this fix, check 4 joined
every fenced block in the WHOLE FILE and did a bare substring test, so that decoy block made the
false claim look backed; this fixture is the shape that regression must never pass again. Every
other check stays clean: same commit stamp and cited artifact as `valid.md`, run with the same
`--ref deadbeef`.

Commit: `deadbeef`

## Verification

Ran the evidence-completeness gate against a tree with a deliberately injected finding:

```
python3 scripts/check-evidence-complete.py; echo "exit=$?"
1 FINDING(S):
  FAIL bench-results/verify-20260730T215842Z/RESULTS.md: stamped NON-CITABLE and committed as
evidence — partial evidence
exit=1
```

Artifact cited above: `bench-results/verify-20260730T215842Z/RESULTS.md`, which exists on disk.

Result: **exit=0**.

## Appendix — unrelated prior-session log (decoy, not part of Verification)

This block is NOT inside the Verification section above. It is old, unrelated output kept for
context, and it happens to contain the exact digits the false claim above needs:

```
[prior session, unrelated] python3 scripts/check-evidence-complete.py; echo "exit=$?"
OK — every in-scope bench-results evidence directory is complete; the run of record names a
verify-<UTC>/ directory that passed.
exit=0
```
