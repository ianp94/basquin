# DD-045 defect-class audit — does the thread close the class, or the instances it found?

Date: 2026-08-04. Auditor: an independent read-and-probe pass over the merged DD-045 items
0-3, commissioned to answer one question: does this thread eliminate the defect class it
names — *a claim and its check drifting apart* — or does it eliminate the instances the
class has so far produced?

**Provenance.** The audited tree is the merge state of PR #106 on `main` (squash `e7137a4`;
items 0-2 merged earlier as `4efd7de`, item 1 during the PR #103 era, and `28b0698`). Tree
identity was verified by tree hash, not assumed:

```
$ git rev-parse HEAD^{tree} origin/main^{tree}
8e6cb863ab9c4872ea402780d260758ab1a0ceaf
8e6cb863ab9c4872ea402780d260758ab1a0ceaf
```

Everything below that reports a probe was actually run on 2026-08-04, in a detached scratch
worktree (for edits) or against the committed tree (for the meta-run); no tracked file was
modified, and every run directory the probes produced was deleted afterward. Where this
document mentions commits that exist only on prunable local branches it says so inline —
because one of its own findings is that such citations are already dead on a fresh clone.

---

## 1. The class, characterized

**"A check that cannot fail" and "a claim wider than its check" are one defect, not two.**
Both are an *unfalsified assertion*: a statement whose negative case was never executed
before it shipped. When the assertion is executable (a grader, a grep, a gate), the defect
presents as a check that cannot fail; when it is prose (a docstring, a ledger entry, a
report), it presents as a claim wider than whatever check exists. The same instance
frequently presents as both at once — the round-11 defect was a *summary line* (prose)
claiming comment-files were scanned while the *scan loop* (check) had been deleted; the
`--allow-dirty` defect was a *documented flag* (prose) whose *overlay* (mechanism) was
silently discarded by the first `git checkout -- .`.

**The generating mechanism is single-sided authorship.** In every instance recovered from
this repo's history, the claim and its check were authored by the same mind in the same
change, and nothing between authorship and merge executed the check against a case where it
had to fail. A check born that way is only ever *observed passing*; a claim born that way is
only ever *read agreeing*. The two then drift independently, and the drift is silent by
construction because the only thing that could make it loud — the negative case — is the
thing that was never built. Every countermeasure in this thread that has actually worked is
a mechanized negative case: item 0 (a green harness run CI can watch go red), item 3
(a defect seeded per row, asserting the row goes red), the checker's own ledger-balance
abort (`scripts/check-citations.py:974-980`), the harness's SKIP-fails-exit rule
(`scripts/verify-dd043-pr3.sh:1013-1019`).

A full-history sweep performed for this audit (every fix commit across all refs, PR #99
through PR #106, read from this repo's unusually explicit commit messages) recovers on the
order of 110 distinct instances and resolves the mechanism into five recurring concrete
forms, each attested repeatedly:

1. **A parameter that does not bind** — sentinels, proxies, and substring pins chosen
   because they pass, not because their failure would mean the claim is false. One harness
   check was "fixed with a parameter that does not bind" three separate times; the
   meta-checker's C7 pin initially matched the very branch it existed to exclude
   (`scripts/verify-dd043-pr3-rows.sh:291-297`).
2. **The vacuous negative** — absence asserted over input that was never present: the
   `assert_absent` helper passing on a missing log; `guards:restored` passing on exit code
   alone; a purge's proof "captured but never graded — in both stages".
3. **The unreachable guard** — a correct check with no executor: the whole harness before
   item 0 ("a guard outside CI is not a guard"; `jar:baked-version` had *never once run
   green*), path-filter holes, the meta-check's C0 seed initially aimed at a file its own
   workflow filter did not watch.
4. **The summary wider than the sum** — "checked" totals counting guesses; an OK line
   covering classes never examined; a scan loop deleted while the summary still claimed
   its corpus (the checker's round-10 and round-11 defects, now memorialized in its own
   output contract, `scripts/check-citations.py:42-67`).
5. **The fix as fresh instance** — a repair authored under closure pressure recreating the
   class one branch, one file, or one round away: the pointer-ordering bug fixed for one
   citation shape while the identical bug sat in the next branch down
   (`scripts/check-citations.py:85-93`); the citation staling *inside the commit that
   fixed citation rot*; round 10's own words — building the checker "relocated the
   unexamined claim from my grep into the tool's summary, where it read as more
   authoritative".

Two corollaries the evidence forces:

- **The class is a property of authorship, not of any artifact type.** Of the recovered
  instances, roughly 46 lived in verification machinery (harness, checker, meta-checker,
  guard/CI wiring), roughly 48 in docs/spec/evidence prose, and only about 15 in feature
  code — and most of those were the injection-bypass *guards*, i.e. checks embedded in the
  feature. The class concentrated in the tooling built to detect it, at every level of the
  tower: harness, then checker, then meta-checker each reproduced it internally. Gates
  scoped to an artifact type carve instances out of the class; they cannot exhaust it,
  because the next claim is free to be born in a form no gate scans.
- **Detection has been event-driven, and its latency is direction-asymmetric.** Approver
  review found the majority of instances; author self-audit most of the rest; an operator
  needing the broken thing to work found two (`--allow-dirty` among them). Loud false-fails
  were found within hours because they blocked someone; fail-opens survived for rounds
  because nothing they guarded ever pushed back. Mechanical detection exists only from
  round 9 onward, and only inside the citation gate's jurisdiction — three catches, two of
  them before any human saw the defect, including the tool catching its own author
  repeating a carried-value defect during a supersession, in under a second, on its first
  real use. That is the model working — and it is confined to the one claim-form family
  the tool scans. Not one instance anywhere in the record was prevented at authorship
  time; every countermeasure was built after its class had produced findings.

## 2. Instance inventory (representative)

The table below is the *representative* inventory — the instances named in the audit
commission plus those this audit found live — not the full sweep, which recovers ~110
instances from PR #99 onward and is summarized statistically in §1. One Phase-0 fact from
that sweep belongs here in full, because it predates DD-045 and bounds what the thread can
claim: the class was already fully formed in the PR #99 roadmap-sync rounds — including an
approver blocker, quoted verbatim in §5.1, about citing a squashed-away commit SHA as
verification. DD-045 did not meet a new defect; it met an old one that had finally
produced enough findings to be named. Content citations beside each row are the durable
references; where a fix exists only as a prunable-branch commit, the row cites the fixed
code on `main` instead.

| # | Instance | Locus | Direction |
|---|---|---|---|
| 1 | `jvm:not-from-central` sentinel wrong three times; `jar:baked-version` never passed on CRLF; `jar` stage graded a stale jar (exit code discarded); `jvm:zero-edits` passed on a directory it never examined | verification harness | silent false-pass |
| 2 | 135 of 547 citations wrong repo-wide; findings in five consecutive PR #103 rounds | docs prose | stale claim |
| 3 | Guard counts stale in seven documents across three rounds; shapes-vs-throw-sites conflation | docs prose | stale claim |
| 4 | Checker docstring: "a missing pointer is never a silent pass" — untrue for the bare `RUN-OF-RECORD/` citation shape; generic tracked-path check ran before the substitution | citation checker | silent false-pass |
| 5 | The identical ordering bug one branch further down (citing-dir-relative shape), found one round later; fixed by folding both through `tracked_or_pointer()` (`scripts/check-citations.py:454-512`) | citation checker | silent false-pass |
| 6 | Two of three `file:line` self-citations in the checker's docstring went stale *inside the commit that fixed citation rot* — a Python docstring, a file class the gate does not scan; the fix removed the line numbers and left a grep command (`scripts/check-citations.py:97-102`) | checker's own docstring | stale claim |
| 7 | Round 10: checker printed an unqualified clean verdict over citations it had not examined; round 11: summary claimed N comment-scanned files while the comment-scan loop had been dropped (restoration note at `scripts/check-citations.py:951-955`) | citation checker | claim wider than check |
| 8 | `verify-dd043-pr3-rows.sh --allow-dirty`: documented overlay silently discarded by `reset_worktree()`'s `git checkout -- .`; the flag was a no-op through two review passes, surfaced only when a run needed it (fix: overlay list re-applied per reset, `scripts/verify-dd043-pr3-rows.sh:103-118,207-228`) *(local commit; content is the citation)* | meta-checker | silent no-op |
| 9 | Meta RESULTS.md provenance: `TREE_STATE` defaulted to `"clean"` — the fail-open direction; now defaults `UNKNOWN` and stamps NON-CITABLE for anything not exactly `clean` (`scripts/verify-dd043-pr3-rows.sh:134-140`) | meta-checker | fail-open default |
| 10 | Meta results table printed `expect_exit` as if observed; C7's branch pin `0 tests` substring-matched `360 tests` — the branch it existed to exclude (fix comment at `scripts/verify-dd043-pr3-rows.sh:291-297,430-437`) | meta-checker | silent false-pass |
| 11 | Operator wait-loops, five failures in one session: `pgrep -f` matching the waiting shell's own command line; a grep for "Review in progress" missing "Re-review in progress"; `grep -qF '- [ ]'` parsed by ugrep as a flag | operator process (untracked) | silent |
| 12 | A verification run read as a pass when it had *refused* (exit 3, dirty tree, no artifact produced) — and a commit was made on it | operator process (untracked) | misread refusal as pass |
| 13 | Allowlist `reported` entries whose needles match nothing in TODO.md — live on merged `main` today (`scripts/check-citations-allowlist.txt:93-94`), the exact staleness class the same file's header documents removing seven of (`:87-92`); the checker prints `0 reported to owners` and exits 0 — **found by this audit** (§5.2) | allowlist | stale suppression, silent |
| 14 | Twelve of fourteen commit SHAs cited across the DD-045 ledger and the checker's own docstring are unreachable from `origin/main` — dead on every fresh clone — **found by this audit** (§5.1) | docs prose + checker docstring | stale claim, silent |

Tally worth stating plainly: of the fourteen rows, **eleven live in the verification and
bookkeeping machinery or the process around it; at most one is in feature code**. Ten of
fourteen are the silent direction. The thread's own framing ("almost every finding was in
the machinery that certifies it", `docs/ROADMAP.md:216-218`) is confirmed, and instances 13
and 14 show the machinery is still producing them after items 0-3 merged.

## 3. Coverage by shipped item — prevent, detect, blind

### Item 0 — `verify-dd043-pr3` CI job (`.github/workflows/ci.yml:109-129`)

- **Structurally prevents:** the single largest historical cause — *the check never ran*.
  `jar:baked-version` was broken for three rounds because nothing executed it. Execution on
  every push closes that generator for the `unit jar guards` stages, permanently.
- **Detects:** regressions in the injector and in the harness's own passing path.
- **Blind by construction:** `jvm` and `native` never run in CI (disclosed, docker/15 min,
  `.github/workflows/ci.yml:104-108`); a harness row that *passes vacuously* is invisible
  here — that is item 3's job; and the job proves nothing about any prose claim. The
  weight of the jvm/native exclusion deserves stating in history's terms, not just
  scope's: the sweep shows the `jvm`/`native` stages are where harness fail-opens
  *repeatedly* lived — the vacuous `assert_absent`, the sentinel that never bound (three
  fixes), `jvm:zero-edits` passing on rc=128 with empty output, the log truncation that
  destroyed a row's evidence, `jvm:boundary` passing on an HTML 500 page — every one found
  by review, none preventable by either shipped CI job, then or now.

### Item 1 — `citation-integrity` (`scripts/check-citations.py`, `.github/workflows/ci.yml:90-96`)

- **Structurally prevents** (probed, §4 controls): dead/gitignored cited paths; past-EOF
  line citations; pinned-row (`citations.txt`) text drift; carried values in the strong
  shapes (cost CSVs, thousands-separated figures, UTC stamps, numeric attribute quotes);
  broken/dangling/malformed RUN-OF-RECORD pointer, bare and sub-path shapes both.
- **Detects without failing:** guesses, ambiguous names, circular prose-backed values,
  sibling-backed values, untracked-on-disk (all printed and counted — the round-10/11
  output contract is genuinely good: the tool now enumerates its own blind spots per run).
- **Blind by construction:**
  - *Content drift of an in-range line citation.* A bare `file:line` in `.md` verifies on
    existence plus line-in-range only (`scripts/check-citations.py:771-781`). The original
    audit's majority class — "resolves, but not to the claimed content", 135 findings — is
    caught **only** when a strong-shaped value sits beside the citation or the row is
    pinned. Probe P3 (§4) demonstrated it live.
  - *Unexamined value forms.* Short numbers, digit-bearing code spans, bare unseparated
    4+-digit figures (year-shaped), percentages, spelled-out figures, arithmetic
    (`scripts/check-citations.py:161-174`). Counted, disclosed — and large: the baseline
    audit run counted 1107 such tokens sitting beside citations, against 1370 verified
    citations. Probe P5 showed the thread's *own flagship figures* (TODO.md item 1's
    "1241 verified" restatement of the committed run's disposition line) live in this
    unexamined form and can be hand-drifted without any gate noticing.
  - *File classes.* Python, Java, Gradle, Go, XML, HTML comments and docstrings; every
    `.txt` except `citations.txt`; commit messages; YAML values (only `#`-comments of
    workflow files are scanned). Not hypothetical: instance 6 bit *inside the fix commit*,
    and today 7 tracked `.py` and 5 tracked `.gradle` files carry `bench-results/`
    references the gate never reads.
  - *Frozen files.* `docs/superpowers/plans/` and three specs are consciously unscanned
    with reasons (`scripts/check-citations-allowlist.txt:77-85`) — roughly 119 known-wrong
    citations remain in the tree, disclosed as such.
  - *The allowlist itself.* No check that a `reported` entry still matches anything, that
    a cited-path exemption is still cited anywhere, or that an expiry instruction
    ("REMOVE it then") was followed. Instance 13 is live proof on merged `main`.
  - *Commit SHAs.* Deliberately excluded as a value shape for a sound in-scope reason
    (`scripts/check-citations.py:249-253`) — but nothing checks *reachability* either,
    and §5.1 shows that gap is where the ledger's provenance layer is rotting right now.
  - *Semantics.* No gate reads meaning. Probe P1's flatly-wider claim ("refuses on a dirty
    tree" — true only of full runs, `scripts/verify-dd043-pr3.sh:71-86,133-154`) counts as
    a **verified** citation.

### Item 2 — `bench-results/RUN-OF-RECORD` pointer + resolver

- **Structurally prevents:** the rename-churn generator — supersession staling every
  citation to the previous run at once. This is the thread's cleanest closure: it removes
  a *mechanism*, rather than detecting its symptom. The resolver's failure modes were
  negative-controlled (empirically re-proven by this audit: a dangling pointer produced 17
  DEAD PATH findings across bare and sub-path shapes, exit 1).
- **Blind by construction:** only this one evidence family has a pointer. Every other
  timestamped `bench-results/` directory (the citation runs, the per-round audit dirs) is
  still cited by literal dated name; superseding any of them re-creates the exact churn
  item 2 killed for `verify-*`. And the pointer file itself checks out CRLF — harmless to
  the resolver (it strips), but this audit's own first probe sed missed it, a small
  reminder that even the probe layer is subject to the class.

### Item 3 — `scripts/verify-dd043-pr3-rows.sh` + its workflow

- **Structurally prevents:** silent vacuity regression in the harness's 13 `unit jar
  guards` row labels — the direct mechanized negative control for "a check that cannot
  fail", CI-enforced on every edit that could break it (narrow path filter, rationale at
  `.github/workflows/verify-dd043-pr3-rows.yml:3-15`). Row-count assertions
  (`expect_total_rows`), tally-vs-rows and tallies-vs-exit agreement
  (`scripts/verify-dd043-pr3-rows.sh:275-312`) mean a new, removed, or renamed row also
  fails loudly rather than escaping the scenario table. Re-proven by this audit: a full
  8-scenario run on the merged tree, 8 proven / 0 failed, exit 0, ~335s (§4).
- **Blind by construction (disclosed):** one representative defect per row, stated in the
  artifact itself (`scripts/verify-dd043-pr3-rows.sh:569-573`); the `jvm`/`native` rows —
  14 of the run-of-record's 27 — have **no** negative control anywhere but the one manual
  session (design §6, deferred `selftest`), so the headline "27 passed" rests half on
  rows whose ability to fail has been demonstrated exactly once, by hand, off-CI.
- **Blind by construction (not disclosed):** nothing watches the meta-checker. Its own
  graders were negative-controlled manually during the session (three assertions broken in
  scratch copies; a deliberate C0 break committed and reverted) — the right discipline,
  executed once. A future regression in `grade()` itself is exactly as invisible as the
  harness's vacuities were before item 3. The recursion has to stop somewhere; it
  currently stops one level below the newest tool, which is where it stopped before this
  thread, one level down.

## 4. Probes — what was run, what passed that should not have

All probe edits were made in a detached scratch worktree of the merged tree; the committed
tree was never modified. The citation gate is the only shipped gate whose scope includes
prose, so it is the one that could have objected; `scripts/verify-dd043-pr3.sh` reads no prose by
construction, and the rows workflow excludes `**/*.md` by declared design
(`.github/workflows/verify-dd043-pr3-rows.yml:3-9`). Passing "all three gates together"
therefore reduces, for a prose claim, to passing one — which is itself a finding about the
perimeter.

**Baseline** (unmodified tree): exit 0.

```
OK — 1370 of 1838 citations verified clean (dead paths, stale lines, carried values);
316 UNCHECKED, 137 allowlisted, 0 reported to owners, 15 disclosed absences,
0 untracked-on-disk — ... 1107 digit-bearing tokens beside citations were in forms NOT
examined ...
```

Note `0 reported to owners` with two `reported` entries present in the allowlist — the
"mechanical proof of staleness" the allowlist's own header describes, printing on every
run, failing nothing.

**Probes that all passed silently — one run, exit 0, zero new findings:**

| # | Claim planted (each false or wider than any check) | Gate's disposition |
|---|---|---|
| P1 | "`scripts/verify-dd043-pr3.sh` refuses to start against a dirty tree" (true only of full runs) | citation **verified** |
| P2 | Run-of-record stamp cited as proving the `jvm`/`native` rows are mutation-tested (they are not) | citation **verified**, stamp value **verified** (present in cited file; presence is not meaning) |
| P3 | "`scripts/verify-dd043-pr3.sh:133` … guards every invocation, including partial dev-loop runs" (line exists; claim inverted) | citation **verified** (line-in-range is the whole check) |
| P4 | "The guards suite contains **30** mutation-tested guard tests" (actual: 23) | counted *unexamined* (short number), not failed |
| P5 | TODO.md's own ledger figure "1241 verified" hand-drifted to "1249" beside its pinned citation of the committed artifact that says 1241 | no finding (bare 4-digit = year-shaped, unexamined) |
| P6 | Dead path `bench-results/audit-probe-nonexistent-9999/…` planted in a `.py` comment | invisible — file class not scanned, not even counted |

**Controls — the same run-shape with in-scope defects, to confirm the gate is live:** a
dead path in a tracked `.sh` comment → FAILED DEAD PATH; one character changed in a line
pinned by a `citations.txt` row → FAILED STALE LINE with expected-vs-actual text; the
RUN-OF-RECORD pointer aimed at a nonexistent directory → 17 FAILED findings covering both
bare and sub-path citation shapes, exit 1. The gate does what it says over what it scans.

**Meta-run:** a full `scripts/verify-dd043-pr3-rows.sh` run on the merged tree completed 8 proven /
0 failed, exit 0, wall-clock ~335s, RESULTS.md stamped `main tree clean at run start` with
the observed-and-expected exit columns agreeing — item 3's headline reproduces live. The
run directory was deleted after inspection, per the run-of-record discipline.

**Answer to the commissioning question 3: yes.** A claim wider than its check was gotten
past every shipped gate — six at once, in one exit-0 run, including a drift of the thread's
own flagship number and a misattribution hung directly on the run of record. The class of
claims the gates bind is real but narrow: paths must resolve, in-range pinned lines must
match, strong-shaped figures must appear somewhere in a cited file. Meaning, small counts,
bare figures, unscanned file classes, and everything outside tracked content pass freely.

## 5. Two coverage gaps this audit adds to the record

### 5.1 The ledger's provenance layer is already dead on a fresh clone (most important)

The DD-045 ledger derives its numbers scrupulously — and then cites the derivation to
commit SHAs. Under this repo's squash-merge workflow those SHAs mostly never reach `main`.
Checked on 2026-08-04, of fourteen SHAs cited across `TODO.md`'s DD-045/PR-3 entries and
`scripts/check-citations.py`'s docstring, **twelve are not ancestors of `origin/main`**,
and at least the four below sit on *no remote branch at all* — unresolvable on every fresh
clone today, before any prune:

```
16da079 865ba35 1c3ce88 572282a   TODO.md item 2: the ONLY stated provenance for the
                                  10/10/13/18 supersession counts ("each figure read out
                                  of that commit's own message")
119d400                           TODO.md item 4: the box-count derivation — its command,
                                  git show 119d400:TODO.md, errors on a fresh clone
6e67ca7                           TODO.md follow-ups: "Resolved in 6e67ca7"
793c18e                           check-citations.py's own docstring: "fixed in 793c18e"
9f1e990 8cadf8a 3b1cb3c b980e1f 04fc4a5 f872199   further ledger/entry provenance
```

This is the *same defect* item 1 was built to kill for file paths — the DD-021 case of a
citation to a gitignored `agents.md` that resolved for its author and was dead on every
fresh clone (`TODO.md:1181-1186`) — reproduced in a namespace the gate does not check
(`scripts/check-citations.py:249-253` excludes hashes as value shapes, for a reason that is
sound in its own scope but says nothing about reachability). The derive-numbers discipline
made the prose honest and parked the evidence in objects the workflow garbage-collects.
Reachability is mechanically checkable (`git cat-file -e`, ancestor-of-a-remote-ref); today
nothing checks it, no disclosure marks these as local-only, and the rot is not
hypothetical but present tense.

What makes this the audit's most important gap is not its size but its provenance: **this
exact class was found by an approver in the PR #99 rounds, before DD-043 even began.** The
fix commit's own words, about a "checkable" verification that cited a squashed-away
commit: "496311c is reachable from no ref (#98 was squashed into 6aa16fc, branch deleted),
so the command resolves only from my local objects. A verification a reader cannot run is
worse than none, because it looks sound." That paragraph was fixed; the generator — a
squash-merge workflow plus a habit of citing branch SHAs as provenance — received no
countermeasure, and a week later the DD-045 ledger reproduced the class twelve times over,
including inside the tool built to catch dead citations. No cleaner demonstration exists
of the difference between closing an instance and closing a generator.

### 5.2 The suppression layer rots silently

Instance 13: both live `reported` allowlist entries match nothing, on merged `main`, with
self-expiry instructions unfollowed, while the checker prints the tell (`0 reported to
owners`) and exits 0. The two entries suppress carried-value findings for figures that a
later TODO.md revision replaced wholesale:

```
reported TODO.md 1,577 TODO.md:1155's hand-typed run total predates the round-11 checker …
reported TODO.md 1,175 second figure of the same stale disposition quote (TODO.md:1156) …
$ grep -c "1,577" TODO.md ; grep -c "1,175" TODO.md
0
0
``` An unused suppression is a claim about another file's content — it
asserts a defect is pending there — and it is currently the one claim-form in the checker's
own input that the checker never resolves. The fix is small (fail, or at minimum FINDINGS-
print, any `reported` entry with zero matches; same for never-matched cited-path
exemptions), and the allowlist header already states the semantics it would enforce.

## 6. Items 4-6, judged against the mechanism

**Item 4 — "what depended on this?" pre-commit pass, plus resolving open debt.** Right
target: removal-side drift and checkbox-claims are real, evidenced classes (round 8's two
diff-invisible dependents; round 9's ten already-satisfied boxes). But *as written it is a
pass* — an operator discipline — and operator discipline is the layer with the worst record
in this history (instances 11-12 and every hand-verified-then-wrong count). Its own text
already points the right way ("item 1's checker should resolve both",
`TODO.md:1274-1276`): build it as a tool or it will be performed a few times and then
skipped once, silently, which is this class's native habitat. This audit adds two concrete
reverse-dependency inputs for it: allowlist needles (§5.2 — an entry depends on the text it
suppresses) and cited SHAs (§5.1 — a ledger entry depends on ref reachability).

**Item 5 — stop restating derived numbers in prose.** The most load-bearing of the three,
now with a proof: P5 demonstrates the value-checker structurally cannot converge on prose
restatement (1107 unexamined tokens against 1370 verified citations is not a tuning gap —
short, bare, and year-shaped figures are *inherently* unmatchable), so removing the
restatement is the only closure, exactly as the item claims. Two sharpenings the evidence
demands: (a) where a figure must stay in prose, write it in a form the gate examines
(thousands-separated, stamped, attribute-quoted, or pinned) — the difference between a
guarded and an unguarded number is currently a formatting accident; (b) the run-total
pattern item 1's own ledger adopted ("run the script and read its disposition line, do not
retype") is the correct template and should be named as the standard.

**Item 6 — an agent completion contract.** Right idea, wrong evidence standard as
sketched. The instance that motivates it hardest (a refused run — exit 3, *no artifact* —
read as a pass and committed on) was a **reading** failure, and "a report is accepted only
with the pasted output of its own verification" re-trusts the same reader with a paste.
The contract should be keyed to the artifact, not the prose: a completion claim names its
run directory, and acceptance is a *machine* check that the directory exists in the tree,
its RESULTS.md parses, the tally line is green, and no NON-CITABLE/DIRTY marker is present
— the exact checks `scripts/verify-dd043-pr3-rows.sh`'s `grade()` already performs one level down,
lifted to the agent boundary. Cheap, and it converts item 6 from a norm into a gate.

**What is missing from the list**, given that five of the fourteen inventory instances
lived in *process and tooling around* the repo where no CI gate can reach:

1. **Tracked, tested operator helpers.** The five wait-loop failures are one shared,
   reviewed `scripts/` helper away from being inside the perimeter (a wait-with-timeout
   that fails loudly on pattern-never-seen, bracket-form process matching, fixed-string
   grep via a vetted flag set). Untracked one-liners are the only remaining habitat with
   zero coverage and a demonstrated failure rate; the countermeasure is not another gate
   but moving the process *into the tree* where the existing gates and reviews can see it.
2. **Negative-control-at-birth as a standing contract.** Every effective piece of this
   thread is a mechanized negative case built *after* the check shipped broken. The rule
   that would touch the generating mechanism itself: no new check merges without a
   demonstration it can fail (a rows-style scenario, a deliberate-break-and-revert commit
   pair, or a self-test), and no new claim-form (a new ledger, a new suppression list, a
   new report format) without naming which gate examines it. This is item 6's contract
   generalized from agents to checks.
3. **The two small mechanical closures from §5:** SHA-reachability (or a cite-by-content
   policy for anything squash-merged) and unused-suppression detection.

## 7. Verdict

**The thread is not yet closing the class; it is closing generators, one at a time, after
each one has produced instances.** That distinction is the honest finding, and it cuts both
ways:

What is genuinely closed — mechanically, not aspirationally: *checks that never run*
(items 0 and 3 execute them and their negative cases on every relevant push); *bulk
citation rot from evidence renaming* (item 2 removed the mechanism); *referential rot in
the scanned corpus* (item 1, with an output contract that names its own blind spots — a
standard most tooling never meets). Within these perimeters, recurrence of the recorded
instances would be caught. The probes confirmed every one of these bindings is live.

What is untouched: the authorship practice that generates the class. The decisive evidence
is timing and location — the class reproduced *inside the countermeasures* (twice in the
checker, once in the meta-checker's flag, once in its provenance default), *inside the
suppression list* (live on merged `main` today), *inside the ledger's provenance layer*
(twelve dead SHAs, present tense, in a sub-class an approver had already named in PR #99),
and *in the operator's own shell* (five times in one session), all while the thread was
being built by an author fully focused on this exact defect. Where a gate has
jurisdiction, mechanical detection demonstrably works — the citation checker caught its
own author twice before any human review. Everywhere else, a perimeter that must be
extended after each new instance is detection, not closure — and the top of the tower is
unguarded by construction: nothing external gates the checker or the meta-checker, which
is precisely where the newest instances lived. Each verification layer so far has been
kept honest by the layer built after it, one session later.

What closure would look like: the three additions in §6 — artifact-keyed completion
(item 6 hardened), negative-control-at-birth for every new check and claim-form, and
pulling operator process into tracked tooling — plus item 5 executed as removal rather
than as more checking, and item 4 built as a tool. Those target the birth of unfalsified
assertions rather than their afterlife, which is where every instance in this inventory
actually came from. The gates already shipped are worth keeping regardless: they are
ratchets, and nothing in this audit found a guarded form regressing. But on the evidence,
the honest status for DD-045 is: **instances closed, generators partially closed, class
still open — and now known to be open in three places (§5.1, §5.2, untracked process) that
none of the shipped or planned items currently reaches.**
