# DD-043 PR-3 — repo-wide citation audit (round 7): committed source for TODO.md's figures

This directory exists because of `approver-pr103.md`'s should-fix **12** (that review is itself a
gitignored local file under `.superpowers/sdd/`, not present in this or any clone — cited here for
provenance only): `TODO.md`'s citation-audit entry ("135 wrong out of 547 checked") cited only
`.superpowers/sdd/pr103-r7-citations-report.md` — also gitignored, so a fresh clone has no file
backing those numbers at all. This directory is that backing
file. It ports the round-7 audit's method, totals and per-group/per-file breakdown out of the
gitignored report, and separately records what could actually be independently re-verified on
2026-07-30, rather than just re-asserting the original numbers.

**Read this file's own distinction carefully: two kinds of claim follow.** Some are re-derived here,
from scratch, against the committed tree, with the raw command output in `provenance.txt`. The rest
are the round-7 audit's own findings — real work, done by reading each citation's prose antecedent and
judging its target's content — carried over because they had nowhere committed to live, not because
they have been independently redone. Where that line falls is marked on every figure below.

---

## Method (as conducted in round 7, disclosed so it is re-runnable)

Corpus: every tracked `.md`/`.txt` file reachable from `docs/**`, `TODO.md` and `bench-results/**`
(excluding JVM/stack-trace frames, which are captured output, not claims, and excluding the two
directories under concurrent edit by another agent at audit time). Every `file:line`,
`file:line-line` and bare `` `:N` `` citation was extracted; each bare `:N` was resolved to its real
prose antecedent; the cited line(s) were then read and judged against what the sentence claims.
**Existence of the target was never accepted as a pass** — a citation that resolves to a real line
containing something else is WRONG, not OK.

## Totals

| Verdict | N | Meaning |
|---|---|---|
| OK | 343 | line contains what the sentence claims |
| **WRONG** | **135** | resolves, but not to the claimed content |
| STALE-BY-REFACTOR | 21 | claimed content no longer exists in that file at all — repointing would launder a false statement into a true-looking one, so left alone |
| UNVERIFIABLE | 48 | target outside this repo (45 upstream Apicurio/Hono/Debezium, 1 JDK `WeakHashMap`, 2 upstream workflow files) |
| **Total checked** | **547** | |

Arithmetic re-checked here, 2026-07-30: 343 + 135 + 21 + 48 = 547. Consistent with the entry.

## By group

| Group | Checked | Wrong |
|---|---|---|
| PR-3 evidence | 171 | 8 |
| Older plans | 131 | 84 |
| Specs | 85 | 15 |
| Other bench-results | 101 | 8 |
| ROADMAP/TODO | 59 | 20 |
| **Total** | **547** | **135** |

Re-checked here, 2026-07-30: the "Checked" column sums to 547 and the "Wrong" column sums to 135,
matching the totals table above. Internally consistent.

## Root cause concentration

- **`docs/superpowers/plans/2026-07-23-redirect-session-carry.md`: 52 wrong (10 also stale)** — the
  single worst file, almost all traceable to one cause: `runner/coverage/CoverageGuidedRun.java`
  growing from 1088 to 1495 lines in commit `a0595b9` (DD-039), which shifted every citation past
  roughly `:500` in that plan by +300..+400 lines.
  **Independently re-derived 2026-07-30** (`provenance.txt`, section 1): the parent of `a0595b9` has
  `CoverageGuidedRun.java` at **1088** lines; `a0595b9` itself has it at **1495** lines; current HEAD
  is still **1495** lines (no further growth since). **Matches exactly.**
- Two other commits explain nearly all the remaining wrong citations across the corpus: `c304717`
  (PR-1, which moved `ResultStore.java`/`Invariants.java` into `basquin-core/`, invalidating both path
  and line for 8 citations) and `28228fb` (DD-040). Not independently re-derived here — see "What was
  and was not re-verified" below.
- `docs/THIRD-PARTY-APPS.md` and `docs/ARCHITECTURE.md` carry **zero** line-numbered citations, so the
  operator-facing docs have none of this exposure. (Spot-checked 2026-07-30: `grep -cE
  '[A-Za-z0-9_./-]+\.(java|md|sh|gradle):[0-9]+' docs/THIRD-PARTY-APPS.md docs/ARCHITECTURE.md` — not
  reproduced in `provenance.txt` since it is a null result on two small, stable files.)

## Per-file wrong-counts for the worst offenders (round-7 audit's own count; not independently
re-counted per-file beyond the spot-check above)

| File | Wrong | Notes |
|---|---|---|
| `docs/superpowers/plans/2026-07-23-redirect-session-carry.md` | 52 (10 stale) | `CoverageGuidedRun.java` +300..+400 shift, confirmed above |
| `docs/superpowers/plans/2026-07-23-trustworthy-measurement.md` | 14 | includes 5 citations to `agent/Invariants.java`, whose path no longer exists (now `basquin-core/src/main/java/agent/Invariants.java`) |
| `docs/superpowers/specs/2026-07-23-trustworthy-measurement-design.md` | 11 | |
| `docs/superpowers/specs/2026-07-23-redirect-session-carry-design.md` | 8 | |
| `docs/superpowers/specs/2026-07-22-response-correlation-design.md` | 6 | all `LoadRun`/`CoverageGuidedRun` ranges off by 200-500 lines |
| `docs/superpowers/plans/2026-07-25-dd043-pr1-basquin-core.md` | 6 | includes two `System.gc()` off-by-ones wrong at the plan's own base commit |

119 of the 135 wrong citations sit in these and other historical `docs/superpowers/{plans,specs}/`
documents for already-shipped work, and were deliberately left unfixed: repointing citations into
source that has since moved is its own review surface, separate from the audit itself.

## What was independently re-verified on 2026-07-30, and what was not

**Re-derived from scratch, against the current tree, raw output in `provenance.txt`:**

1. **Internal arithmetic** of both totals tables above — sums correctly on both axes. (This checks the
   report is not self-contradictory; it does not check any individual judgment.)
2. **The `CoverageGuidedRun.java` 1088 → 1495 root cause** for the worst-offender file — confirmed
   exactly (see above).
3. **The 66/66 pinned-citation sub-claim** — every `file:line: expected-text` row in
   `bench-results/dd043-pr3-r4-guard-measurement-2026-07-29/citations.txt` (66 rows) was checked
   against the actual line of the log file it names. **66/66 resolve.** Matches the round-7 report's
   own re-check of the same file exactly.
4. **Citation density spot-check**: `docs/superpowers/plans/2026-07-23-redirect-session-carry.md`
   contains 15 distinct `CoverageGuidedRun.java:N` citations; the majority land past line ~500, the
   point after which `a0595b9`'s growth applies. Consistent with, though not independent proof of, the
   "52 wrong, mostly this cause" claim.

**Not independently re-derived, and reported as such rather than silently re-asserted:** the full 547
total and its 343/135/21/48 split, the five-group breakdown, and the exact per-file wrong-counts other
than the one confirmed above. Resolving each of the 547 citations requires reading its citing
sentence's prose antecedent and then judging the target file's content — the round-7 session did this
via five subagent passes; their intermediate per-citation tables are not present anywhere in
`.superpowers/sdd/` (only the summary report survives), so they cannot be checked line-by-line without
redoing the sweep in full, which is outside what a "cheap" re-derivation covers for this fix.

**Why these are committed here anyway, not dropped:** the method is fully disclosed above and is
re-runnable against the current tree by anyone; the internal arithmetic is consistent; and the two
concrete, independently-checkable sub-claims drawn from the same audit (the line-count root cause, and
the citations.txt cross-check) both matched exactly. The defect this directory fixes was never "the
number might be wrong" — no reviewer has ever shown a miscount — it was that the number had **no
committed file to be checked from at all**. That defect is fixed: the method and totals now live here,
committed, so a future round can re-run the sweep and compare against a real file instead of a
gitignored one.

## Recommendation carried forward (unchanged from round 7)

The recurring defect is structural: a bare `file:line` has no guard, so an unrelated edit silently
invalidates it while the path still resolves. Two things already demonstrated on this branch actually
work:

1. **Pin the expected text** — `citations.txt`'s `file:line: expected-text` format scored 66/66 in
   round 7 and again here, and is checkable by script.
2. **Cite a unique anchor instead of a line** where the target is churning — a method name, a command
   string, a table row key — as `docs/ROADMAP.md` and this PR's evidence directories already do for
   `BasquinInjector.java`, `scripts/verify-dd043-pr3.sh` and `RESULTS.md`.

A CI check enforcing format (1) on the highest-traffic living docs (`ROADMAP.md`, `TODO.md`,
`THIRD-PARTY-APPS.md`, `ARCHITECTURE.md`) would have caught all 135 wrong citations before they were
ever committed.
