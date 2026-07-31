# Citation-checker run — 2026-07-30T21:47:00Z

This directory holds one real run of `scripts/check-citations.py`, committed as an artifact instead of
described in prose. It exists because `TODO.md` and `docs/ROADMAP.md` were each citing the checker's
live total to the script that computes it, not to a committed run — a number that changes every time
the corpus changes, including the edit that records it. That line was hand-retyped three times in one
review round chasing a moving target and was stale again by the next round (see
`.superpowers/sdd/pr103-r11-checker-report.md` §9 and `.superpowers/sdd/approver-pr103.md` blocking
finding 2 — both gitignored local files under `.superpowers/`, not present in this or any clone; cited
here for provenance only, same disclosure pattern as
`bench-results/dd043-pr3-citation-audit-2026-07-30/README.md`). This directory applies the fix the repo
already uses for `scripts/verify-dd043-pr3.sh`: a timestamped run directory under `bench-results/`
holding the tool's actual output, cited by artifact rather than by script.

## What was run, when, at what commit

```
python3 scripts/check-citations.py
```

stdout and stderr captured verbatim to `output.txt` (stderr was empty on this run — captured to a
separate stream and confirmed zero bytes before merging). Exit code: **0**.

- Run timestamp: `20260730T214700Z` — this directory's own name.
- HEAD commit at run time: `086fafc05c506a06c23c49c997a63f558add3a15` (`commit.txt`).
- The working tree was **not** clean at run time — `git-status.txt` records eight modified files,
  including `TODO.md` and `docs/ROADMAP.md` themselves (their pre-fix state, still carrying the stale
  hand-typed totals this run flags). That is deliberate, not noise: this run is the "before" evidence
  for the fix applied in the same pass that added this directory. Re-running against a clean or later
  tree will not reproduce these exact figures — see "Snapshot, not a bound" below.

## What the disposition classes mean

Every citation the tool parses lands in exactly one bucket (full authoritative definitions are the
docstring at the top of `scripts/check-citations.py`, not repeated here in full):

| Class | Meaning |
|---|---|
| `verified` | resolved against a confident target (exact/relative path, unique same-dir basename, or a passing pinned `file:line: expected-text` row) |
| `FAILED` | a real finding: dead path, gitignored path, stale line, or a carried value absent from every file the passage cites |
| `reported` | a FAILED finding in a file owned by another agent, suppressed but printed pending their fix (allowlist entry) |
| `allowlisted` | cited path deliberately exempted, one written reason per entry |
| `UNCHECKED-ambiguous` | several tracked files match the written name; not mechanically decidable |
| `UNCHECKED-guessed` | exactly one file matches by basename/suffix, but not at the written path — a guess, never counted verified |
| `disclosed-absence` | surrounding prose already says the path is gone/ignored, so a missing target is not a defect |
| `untracked-on-disk` | exists in the worktree but not yet in git — usually a file about to be committed alongside its citing doc |

Value quotes beside a citation (a bold or backticked number, or a bare thousands-separated
number/UTC-stamp on a citation-bearing line) get a second, narrower check: does the value appear in any
file the same prose unit cites? Buckets there include `verified in cited file(s)`, `verified as
line/row count of a cited file`, `SIBLING-BACKED` (right run directory, wrong sibling file — imprecise,
not failed), `found only in cited prose .md` (circular corroboration — a claim citing a claim, not an
artifact), and `reported` (the value-level equivalent of the citation-level bucket above). The tool also
counts, but does not check, several digit-bearing forms it deliberately does not examine (short bare
numbers, non-strong code/bold spans, year-shaped bare numbers) — see `output.txt` lines 20-26 for the
counted breakdown on this run.

## This run's figures (read out of `output.txt`, not retyped)

Header, `output.txt:1`:

> check-citations: 1689 citations parsed across 91 md + 2 pinned + 27 comment-scanned files (18 citing
> files frozen by allowlist, with reasons)

Final disposition line, `output.txt:372`:

> OK — 1241 of 1689 citations verified clean (dead paths, stale lines, carried values); 299 UNCHECKED,
> 136 allowlisted, 2 reported to owners, 13 disclosed absences, 0 untracked-on-disk — all listed above.

The 1241 verified splits (`output.txt:3-5`) as 1061 exact/relative-path + 75 unique-same-dir-basename +
105 pinned-row. The 299 UNCHECKED splits (`output.txt:9-10`) as 36 ambiguous + 263 guessed. The 2
`reported` are value-level, not citation-level (`output.txt:7` shows 0 citation-level reports;
`output.txt:17` shows 2 value-level) — detailed at `output.txt:366-370`: `TODO.md:1155` and
`TODO.md:1156` carrying values `1,577` and `1,175` that appear nowhere in the files that line cites.
Those two lines are exactly what this directory's creation fixes; the entries self-expire (their
needle is the stale figure itself) once `TODO.md` is edited to stop carrying them.

## Snapshot, not a bound — re-run rather than trust this file

**These totals describe one run against one, admittedly dirty, tree at one instant.** The corpus is
every tracked `.md`, `.txt` under `*/citations.txt`, and `#`-comment line in tracked `.sh`/workflow
files — which includes this README and `TODO.md`/`docs/ROADMAP.md` themselves. Editing any of those
files, including the edit that cites this run, changes the true current total. Nothing about this
number is stable across a doc edit, a merge, or a day passing. To get the real current figures:

```
python3 scripts/check-citations.py
```

and read its own final disposition line — do not carry forward the numbers quoted above. If a future
reader needs "how many citations does this repo have right now," that command is the only correct
answer; this directory answers "what did one specific run at one specific commit report," which is a
narrower and permanently-true question.
