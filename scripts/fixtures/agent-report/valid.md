# Agent report — example task (fixture: compliant report)

Synthetic form exemplar for `scripts/check-agent-report.py` — every command pasted below is a
trivial, byte-for-byte reproducible `echo` capture, not a paraphrase of any real repo tool's
output. (The previous version of this fixture set paraphrased real `check-evidence-complete.py`
output by hand; PR #108's round-1 review found those paraphrases had drifted from what the real
tool actually prints — a fabricated dir-count, dropped lines, a mid-token hard-wrap — the defect
class a synthetic, self-verifying capture removes at the root.) Re-run any command shown to
confirm its output matches verbatim.

This fixture is a TRACKED example of a report that satisfies every check in
`scripts/check-agent-report.py`. It is invoked with `--ref deadbeef` (see
`.github/workflows/ci.yml`'s citation-integrity job and this directory's own note below) —
a placeholder commit, not a real one, because a literal SHA embedded in a tracked file would be
falsified by the very next commit (see check-agent-report.py's "WHY --ref EXISTS").

Commit: `deadbeef`

## Verification

Ran a trivially reproducible synthetic command:

```
echo "synthetic output line"; echo "exit=$?"
synthetic output line
exit=0
```

Artifact cited above: `scripts/check-agent-report.py` — the validator this fixture exercises,
which exists on disk (checked by this same tool's check 3).

Result: **exit=0**.
