# DD-045 item 3 — design: a fault-injection meta-check that proves the harness's own rows can fail

2026-07-31, branch `dd045-run-of-record`. Design only — nothing here is implemented. Item text:
`TODO.md:1225-1228`. Every `file:line` below was read against this branch's working tree on this
date; timing figures are derived from one named CI run (Appendix), not typed from memory.

**What this design builds.** A scenario-based fault-injection meta-check
(`scripts/verify-dd043-pr3-rows.sh`, name negotiable) that seeds one defect per harness row into an
isolated worktree copy of the subject, runs the harness's own stages there, and asserts the
targeted rows go `FAIL` in that run's `RESULTS.md` while untargeted rows stay `PASS`. Core tier: 8
scenarios, **370 s of measured stage time (~6.2 min), ~7 min wall-clock in CI** once observed
checkout/JDK overhead is added (§4 derivation) — versus ~40 min for the naive one-full-run-per-row
shape. It runs as its own CI job on a **narrow path filter** (harness, meta-script,
`basquin-maven-injector/**`, the build files) — not on every push and not on doc-only pushes.

TODO item 3 also names a second problem: the guards stage mutates tracked source in place, which
made a mid-run commit unsafe once. This design does not fix that with new code — it is handled by
an accepted workaround, for the reasons laid out next.

---

## Considered and rejected: redirecting `sourceSets.main.java` to a mutated copy

**The hazard.** `run_guards` mutates
`basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java` in place
(`scripts/verify-dd043-pr3.sh:491,507-515`), backs it up to `$OUT/BasquinInjector.java.orig`
(`:492-499`, gitignored via `.gitignore:24`), and restores it after each mutation (`:543`). For
most of the guards stage's ~80 s the tracked tree does not match HEAD. TODO item 3 records the
consequence happening once: "the guards stage mutates tracked source in place, so a commit during
a run is unsafe — one was observed mid-mutation" (`TODO.md:1225-1228`).

**Mechanism considered.** A harness-only Gradle property, `-PbasquinInjectorMainSrc=<dir>`, added
to `basquin-maven-injector/build.gradle` to redirect `sourceSets.main.java` to a mutated *copy*
under `build/tmp/`, so the guards stage would touch no tracked file. (Other mechanisms were weighed
and set aside for independent reasons before this one was picked as the candidate: a git worktree
for the guards stage breaks the documented dirty-tree fast loop — it would silently grade HEAD
instead of the developer's in-progress edits; a whole-module temp copy breaks the module's
`rootProject`-reaching lines; a lock file plus pre-commit hook is unenforceable across clones and
agents, fails stale on a crashed run, and only fences the mutation rather than removing it.)

**How it failed.** `providers.gradleProperty(...)` does not distinguish how a project property was
set. Testing confirmed a plain `-P` flag, an inherited `ORG_GRADLE_PROJECT_basquinInjectorMainSrc`
environment variable, and a `gradle.properties` entry each silently redirect an ordinary,
unmodified `:jar` build's compiled source — no flag beyond the property itself, no warning
printed. `.github/workflows/release.yml`'s "Publish the extension chain into the Pages Maven repo"
step runs this exact module through a plain `./gradlew --no-daemon ...
:basquin-maven-injector:publishAllPublicationsToPagesRepository` invocation, with no isolation
beyond what any ordinary build gets. Had the property or an equivalent environment variable ever
been present in that job's environment, the jar published to GitHub Pages would have been built
from an arbitrary alternate source tree, silently. The mechanism's own proposed mitigation — the
guards stage refusing to run if the property is *absent* from `build.gradle` — only guards against
the switch being removed; it does nothing to guard a normal or release build against the switch
being *present*. A code comment reading "used only by the harness" is not a technical control.
Fixing a local-only commit hazard by adding a source-redirection switch to the publish path is a
bad trade, so this design does not build it.

**Accepted workaround.** Do not commit while a local `guards` run is mid-mutation; the risk window
is the stage's own ~80 s, and `guards:restored` (`scripts/verify-dd043-pr3.sh:617-622`) is the
harness's own signal that the tree came back clean. CI is unaffected: the checkout there is
ephemeral and single-writer, so the in-place mutation is already harmless there — nothing commits
mid-job, and the checkout is discarded either way.

---

## 1. What exactly is the unit being mutated (Q1, first half)

"Mutation-test the harness" is ambiguous between two units, and the choice is the whole design:

1. **Mutate the harness script's text** (delete a `bad` call, invert a predicate) and require
   something to kill the mutant.
2. **Mutate the harness's *subject*** — the injector module, its build files, the init script —
   and require the harness's corresponding row to go red.

This design chooses (2), for the same reason the guards stage itself works that way: the property
item 3 names is "nothing proves the script's own rows *can fail*", and (2) establishes it
directly. The guards stage proves each injector guard's *test* can fail by neutering the *guard*
(`scripts/verify-dd043-pr3.sh:489-624`); one level up, the meta-check proves each harness *row*
can fail by neutering the *thing the row grades*. Option (1) is redundant machinery on top: any
script-text mutant worth killing (a row whose `bad` branch is unreachable, a predicate that can
never be false) is killed by the same evidence — under the seeded defect, the expected `FAIL` row
does not appear, and the meta-check goes red on its absence. A mutant-generation layer for bash
would add generation and equivalence-triage cost without adding discrimination.

**Honest limit, stated now rather than discovered in review:** subject-level fault injection
proves each row can go red against *one representative defect per row/branch*, not against every
defect the row claims to catch. That is the same bounded claim the harness's own epilogue already
makes for the guards stage (`scripts/verify-dd043-pr3.sh:981-982`), and the meta-check's summary
must carry the symmetric disclaimer.

## 2. Row inventory and kill map (Q1, second half; Q4 inputs)

The CI-scoped invocation (`unit jar guards`, `.github/workflows/ci.yml:122`) emits **13 rows on a
green run** (confirmed: `TODO.md:1146` records 13/13; the row emitters are `ok()`/`bad()`/`skip()`
at `scripts/verify-dd043-pr3.sh:157-159`; exit status is `[ "$FAIL" -eq 0 ] && [ "$SKIP" -eq 0 ]`,
`scripts/verify-dd043-pr3.sh:1019`): `unit`; `jar:sisu-index`, `jar:baked-version`,
`jar:gradle-init-version`; eight `guards:<label>` rows (`scripts/verify-dd043-pr3.sh:561-597`);
`guards:restored` (`scripts/verify-dd043-pr3.sh:617-622`). Red-only branches (stage refusals,
UNMEASURED rows) are extra rows that exist only under a defect; they are enumerated below where
killable and in §6 where not.

This 13-label set, and every row's name, does not depend on the dropped source-redirection
mechanism above: `guards:restored` keeps its name, and the coreutils-failure refusal branches
— including the backup-cp refusal and `RESTORE FAILED`, which that mechanism would have deleted —
remain in the harness, declared out of reach in §6. Dropping that mechanism adds, removes, or
renames nothing in the row set below.

Each seed below is a mechanical string replacement `(file, old, new)` applied by **one shared
helper** with `_mutate`'s exact anchor discipline (`scripts/verify-dd043-pr3.sh:508-516`): anchor
not found → the scenario reports UNPROVEN and the meta-run fails loudly. No scenario is a
hand-edit; the scenario set is a data table, and adding a row to the harness without adding a
scenario is caught by a count check (§3). This inherits `_mutate`'s known failure mode — anchor
drift when the subject is refactored — in the loud direction, which is the direction this repo
accepts.

| # | Row (verdict logic) | Seed (worktree copy only) | Expected red branch | Cost |
|---|---|---|---|---|
| C0 | full-run dirty gate, exit 3 (`scripts/verify-dd043-pr3.sh:133-153`) | any tracked-file edit, then invoke `all` | stderr `REFUSED`, exit 3, run dir has `git-status.txt` but **no** `RESULTS.md` (gate fires after `mkdir` at `:88`, before any stage) | ~2 s |
| C1a | `unit` fail branch (`scripts/verify-dd043-pr3.sh:397-401`) | insert an always-failing `@Test` before the final `}` of `BasquinInjectorTest.java` | `bad "unit" "N tests, ≥1 failures"` | shares C1's run |
| C1b | `guards:restored` not-green branch (`scripts/verify-dd043-pr3.sh:622`) | same single seed as C1a; one invocation `unit guards` | `guards:restored` FAIL with `rfail ≥ 1`; **the eight `guards:*` rows stay PASS** — the seeded test fails alongside each mutation's expected test, and `_mutate` asserts membership, not exclusivity (`scripts/verify-dd043-pr3.sh:501-506`) — eight embedded PASS controls in the same run | ~171 s (`unit` 91.5 s + `guards` 79.5 s) |
| C2 | all eight `guards:<label>` guard-is-dead branch (`scripts/verify-dd043-pr3.sh:552-553`) | neuter **every** `@Test` method in both test files: regex-insert `if (true) return;` after each method's opening `{` (multiline-tolerant — `BasquinInjectorGuardsTest.java` has signatures that wrap, e.g. line 204-205; the helper must assert substitution count == that file's `@Test` count, 23 + 8 = derived, not typed) | each `_mutate` finds the module suite GREEN under its mutation → `guard is dead` ×8; `guards:restored` stays PASS (31 vacuous tests, 0 failures) as the in-run control | ~80 s |
| C3 | `jar` stage-refusal row (`scripts/verify-dd043-pr3.sh:414-417`) | drift `basquin-init.gradle:22`'s `'0.3.0'` literal | the `:jar` invocation at `:412` fails via `finalizedBy verifyGradleInitScriptVersion` (`basquin-maven-injector/build.gradle:228`) → `bad "jar" "UNMEASURED: the jar build FAILED"` — this kill *also* empirically validates the finalizer wiring claim at `build.gradle:223-228` | ~14 s |
| C4 | `jar:baked-version` (`scripts/verify-dd043-pr3.sh:444-449`) | re-quote `basquin-maven-injector/build.gradle:14` from `version = '0.3.0'` to `version = "0.3.0"` | the harness's `sed` (`:444`) matches only single quotes → `declared` empty → FAIL. Note honestly: this kills the row via its parse seam, not the semantic divergence the row nominally exists to catch — a non-interpolated double-quoted Groovy string literal behaves identically to a single-quoted one, so `project.version.toString()` is unaffected and the build succeeds (`verifyInjectorIsDiscoverable` does not fail this seed). True baked≠declared divergence is architecturally unreachable for *any* seed, because the baked value is *causally derived* from `project.version` by `writeVersionResource` (`build.gradle:49-54`), not compared against a second, independently-editable source | ~14 s |
| C5 | `jar:gradle-init-version` UNMEASURED branch (`scripts/verify-dd043-pr3.sh:478-479`) | change the sentinel prefix in the task's lifecycle message, `basquin-maven-injector/build.gradle:219` (`"verifyGradleInitScriptVersion: ..."` → other text) | task passes (rc 0) but `grep -o 'verifyGradleInitScriptVersion:.*'` at `:477` finds nothing (derived from the line shape, not re-verified empirically: Gradle's `> Task :...:verifyGradleInitScriptVersion` header carries no colon *after* the task name, so it cannot satisfy the grep; the harness's own comment at `:470-473` records the empirically-verified sentinel asymmetry this row rests on) → `UNMEASURED` FAIL | ~14 s |
| C6 | `jar:sisu-index` MISSING branch (`scripts/verify-dd043-pr3.sh:433-434`) | delete `src/main/resources/META-INF/sisu/javax.inject.Named` **and** sever `finalizedBy verifyInjectorIsDiscoverable` (`basquin-maven-injector/build.gradle:172`) | jar builds green, index absent → FAIL. The compound seed is the point, not a wart: since DD-045 item 0 the in-build guard shadows this row, so the only way to prove the row is live defense-in-depth (rather than dead code behind the guard) is to knock the first line of defense out | ~14 s |
| C7 | `unit` anti-vacuity branch: `tot > 0` (`scripts/verify-dd043-pr3.sh:397`) | append `allprojects { tasks.withType(Test).configureEach { it.enabled = false } }` to the root `build.gradle` | `check` exits 0, no JUnit XML anywhere → `bad "unit" "0 tests, 0 failures (gradle rc=0)"` — this is the "reported zero that does not mean checked-and-clean" branch, the repo's defining defect class, proven able to fire | ~60 s (estimate — compile without test execution; flagged, not measured) |

Core tier = C0–C7: **row-level kills for all 13 green-run rows** (C1a unit, C4/C5/C6 the three jar
rows, C2 the eight guards rows, C1b restored) **plus** the stage-refusal row (C3) and the full-run
gate (C0). This mapping holds unchanged with the source-redirection mechanism dropped: nothing
above depends on it having been built.

**Optional tier** (each cheap, each kills a *branch* not otherwise exercised; recommend landing
core first and these in the same PR only if review appetite allows):

| Opt | Branch | Seed | Cost |
|---|---|---|---|
| O1 | `guards:*` target-not-found (`scripts/verify-dd043-pr3.sh:516`) | whitespace-drift all 8 anchors in `BasquinInjector.java` — no Gradle run per `_mutate`, ×8 red | ~15 s |
| O2 | `guards:*` + `guards:restored` UNMEASURED no-fresh-XML (`scripts/verify-dd043-pr3.sh:550-551,617-618`) | append an unbalanced token to `BasquinInjector.java` end (anchors intact, compile fails) — 9 fast-fail Gradle runs | ~50 s |
| O3 | `guards:skip` expected-test-not-among-failures (`scripts/verify-dd043-pr3.sh:556-557`) | rename `injectsNothingWhenSkipIsSet` in its test file | ~80 s |
| O4 | `jar` no-jar-produced (`scripts/verify-dd043-pr3.sh:424`) | change `archiveBaseName` (`basquin-maven-injector/build.gradle:43`) so the glob at `:422` misses. **Pre-scrub required:** the worktree's `build/libs` keeps earlier scenarios' correctly-named jar, and the build here *succeeds* (rc 0), so a stale jar would be graded — the exact trap `run_jar`'s own comment records (`scripts/verify-dd043-pr3.sh:405-411`). The scenario table carries a per-scenario pre-scrub field for artifact-absence kills | ~14 s |
| O5 | `jvm` preflight SKIP rows (`scripts/verify-dd043-pr3.sh:629-682`) | three docker-free invocations of the `jvm` stage: `APP_DIR=/nonexistent` (not-found skip, `:630`), `APP_DIR=<plain non-repo dir>` (UNMEASURED skip, `:667-669`), `APP_DIR=<dir inside the basquin repo>` (nested skip, `:671-673`) — all fire before the docker check at `:684`, each also proves SKIP fails the exit status (`:1019`) | ~5 s each |

## 3. Mechanics: isolation, scenario table, reset (Q1 mechanical half)

The meta-check seeds defects into **tracked files** (`build.gradle` ×2, `basquin-init.gradle`,
both test files, `BasquinInjector.java`, a resource) — the same in-place tracked-file hazard
`run_guards` has (see "Considered and rejected" above), one level up. So the meta-run isolates
itself:

- `git worktree add --detach build/tmp/verify-rows-wt HEAD` (fixed name; remove leftovers first;
  `git worktree remove --force` + prune on `trap EXIT`). Worktrees use a `.git` *file*, not a
  symlink, so `core.symlinks=false` (verified on this checkout) is irrelevant here. Works on a CI
  `fetch-depth: 1` detached checkout.
- The harness executed is **the worktree's copy** — the committed state is what gets certified.
  The meta refuses a dirty main tree by default (mirroring the harness's own full-run gate
  doctrine at `scripts/verify-dd043-pr3.sh:119-153`), with `--allow-dirty` overlaying
  `git status --porcelain`-listed modified tracked files into the worktree for exploratory runs.
- Scenario invocations are **named stage subsets** (`jar`, `guards`, `unit guards`) — deliberately
  riding the fast-loop exemption the harness documents (`scripts/verify-dd043-pr3.sh:74-79`): a
  seeded (dirty) worktree is never refused for a subset run, only annotated. C0 alone invokes
  `all` *because* it wants the refusal.
- Reset between scenarios: `git -C <wt> checkout -- .` plus deleting the file-deletion seeds'
  paths as needed. Untracked `build/` and `.gradle/` in the worktree survive the reset — that is
  the warm-build cache that makes the cost figures in §4 achievable. Scenario-created
  `bench-results/verify-*` dirs inside the worktree are copied out (below) then removed, so
  "exactly one new run dir" stays assertable per scenario.
- The scenario set is **data**: one table of `(name, seeds[], pre-scrub[], stages, expected FAIL
  labels, expected PASS labels, expected exit)` consumed by one runner loop and one seed-applying
  helper — not 13 bespoke code paths. A guard on the table itself: the runner derives the set of
  green-run row labels it expects coverage for by parsing the harness's emitters is *not*
  proposed (parsing bash is a fragility trade this repo has been burned by); instead the meta's
  summary states, per green-run row label, which scenario killed it, and a row label with no
  killing scenario is a meta-run FAILURE. The 13-label list lives in the scenario table once, and
  drift between it and the harness shows up as either an unmatched-label failure (label removed
  or renamed in the harness) or an uncovered-row failure at review time (label added — see §8's
  note on TODO item 4 covering the residual).

## 4. Cost, and where it runs (Q2)

All hard figures are derived from CI run 30645036655, job `verify-dd043-pr3` (id 91204208841), by
subtracting the job log's own stage-banner timestamps (Appendix): **unit 91.5 s, jar 14.2 s,
guards 79.5 s (~8.8 s per `_mutate` cycle), job total 3 m 13 s including checkout and JDK setup.**
All of these come from a **single** CI run; no second run has confirmed them, and GitHub-hosted
runner timing has known variance (dependency-cache state, contention). Treat the figures below as
directional until a second sample exists.

- Naive shape (re-run `unit jar guards` once per row): 13 × ~3 min ≈ **~40 min**, plus the guards
  stage's 8 recompiles ×13. Nobody would run it; correctly rejected.
- This design, core tier: C0 (2 s) + C1 (171 s) + C2 (80 s) + C3–C6 (4 × 14 s) + C7 (~60 s)
  ≈ **370 s ≈ 6.2 min of stage time**. Adding the ~35 s of CI checkout/JDK overhead observed on
  the verify job: 370 s + 35 s = 405 s ≈ 6.75 min, rounded up to **~7 min wall-clock in CI**. This
  does not yet include the meta-check's own worktree setup and bookkeeping, which have no measured
  cost of their own — an implementer's first prototype run should confirm the total holds near
  ~7 min rather than assume it does. Full optional tier adds ~3 min → **~10 min**. Local
  wall-clock on the authoring machine (WSL2 on NTFS `/mnt/c`) will be worse than CI; the CI figure
  is the one that decides whether it runs.
- The dominant single cost is C1's `unit` leg (91.5 s). It is not separable below one
  `./gradlew check` — the stage *is* that invocation.

**Where:** a separate CI job (same workflow file or its own) with its **own narrow path filter**:
`scripts/verify-dd043-pr3.sh`, the meta-script, `basquin-maven-injector/**`,
`basquin-init.gradle`, `build.gradle`, `settings.gradle`, `gradle/**`, `gradlew`, `gradlew.bat`
(dual-listed alongside `gradlew` for parity with `ci.yml`'s existing pairing, `.github/workflows/ci.yml:23-24`,
even though the job runs on `ubuntu-latest` only), and the workflow file itself. Rationale: "each
row can fail" changes only when the harness or its subject changes; every file the scenarios touch
is on that list, so the guard is reachable for exactly the edits that could break it (the guard
tracks its target). Explicitly **not** on `**/*.md` — the existing `ci.yml` filter
(`.github/workflows/ci.yml:49`) deliberately runs the Gradle jobs on doc-only pushes; extending
that to a ~7-min meta job would buy nothing, because a doc edit cannot change whether a harness row
can fail. No cron: the subjects are all in-repo, so push-triggering covers every change that
matters; environment-only rot (a runner image change) would surface in the existing every-push
`verify-dd043-pr3` job first. On failure, upload the meta run directory as an artifact, same
pattern as `.github/workflows/ci.yml:123-129`.

The existing `verify-dd043-pr3` job is untouched: it remains the standing **all-green control**
at every push. The meta job is its red-capability complement; neither replaces the other, and the
meta needs no baseline scenario of its own because (a) every scenario embeds PASS-row controls,
and (b) the green control already runs on the same commit.

## 5. How a neutered row proves it went red (Q3)

`_mutate`'s lesson — an exit code cannot distinguish "assertion failed" from "the run never
happened" (`scripts/verify-dd043-pr3.sh:545-546`) — recurs one level up: the harness exiting 1
cannot distinguish "the targeted row went red" from "the harness crashed before emitting rows",
and exiting 3 cannot distinguish the dirty gate from an unrelated `exit 3`. The meta-verdict is
therefore graded from artifacts that prove the run happened, never from the exit code alone:

1. **Fresh-run discriminator** (the fresh-XML analogue): snapshot the worktree's
   `bench-results/verify-*` set before the invocation; after it, exactly one new directory must
   exist, else the scenario is UNMEASURED and the meta-run fails. The new directory and the logs
   the rows cite are copied into the meta's own results dir
   (`bench-results/verify-rows-<TS>/<scenario>/`) and **the verdict is read from the copy** —
   same bytes graded and committed, the doctrine at `scripts/verify-dd043-pr3.sh:178-181`.
2. **Row assertions by exact label**, against the copied `RESULTS.md` row table (format:
   `| $res | \`$chk\` | detail |`, `scripts/verify-dd043-pr3.sh:962-967`): every expected-FAIL
   label must be present as a FAIL row (fixed-string match on `| FAIL | \`label\` |`), every
   expected-PASS control label present as a PASS row, and the total row count must equal the
   scenario's expectation. Presence-of-FAIL is the assertion — a missing row is a meta failure,
   never a pass; there is no absence-shaped check to go vacuous.
3. **Tally cross-check:** the headline `**P passed, F failed, S skipped**` line
   (`scripts/verify-dd043-pr3.sh:960`) must agree with the counted table rows — both derive from
   `ROWS`, so disagreement means a truncated or corrupted artifact.
4. **Exit-status agreement:** the recorded harness exit must be 1 for kill scenarios and 3 for
   C0, *and* must agree with the parsed tallies per the rule at
   `scripts/verify-dd043-pr3.sh:1013-1019`. This cross-check is also the only kill available for
   the exit-status line itself, which is a claim like any row but not a row.
5. **Branch pinning, sparingly:** where the branch is the point (C2's `guard is dead`, C7's
   `0 tests`), the meta additionally requires a short stable substring of the expected detail
   text. Elsewhere it pins only the label + FAIL, because over-pinning detail prose would make
   every harmless wording edit a meta failure. C2's eight rows are asserted FAIL-with-
   `guard is dead`; note that if the neuter set were ever narrowed to only the eight expected
   tests, gate-sharing non-neutered tests (e.g. `failsLoudlyWhenOurArtifactIsDeclaredWithAnUnusableTypeOrClassifier`
   behind the `problem == null` gate) would still fail and flip some rows to the
   `expected-to-fail` branch — legal row-kills, wrong branch pin. Neutering *all* `@Test` methods
   is what makes the branch uniform, which is why C2 is specified that way.

**What checks the meta-checker?** The regress is declared to stop here, deliberately: the meta's
assertions are presence-based (absence of an expected row is red), its scenario runner is one
loop over a data table with two shared helpers (seed-apply, row-assert), and it should be small
enough to review whole (target under ~300 lines). A third level of mutation testing is in the
do-not-build list (§9).

## 6. Rows structurally out of reach (Q4)

Declared, not papered over:

- **Coreutils-failure refusal branches** — `unit`'s test-results clear refusal
  (`scripts/verify-dd043-pr3.sh:390-391`), `_mutate`'s clear refusal (`:526-529`), the restore-run
  clear refusal (`:606-608`), the backup-cp refusal (`:496-499`), and `RESTORE FAILED` (`:548-549`).
  Killing these requires making `rm`/`cp` fail mid-run; a PATH shim that fakes coreutils failures
  would also intercept the harness's evidence-copying and become a hazard of its own. Not testable
  at acceptable risk. All five stay in the harness permanently: the source-redirection mechanism
  that would have deleted the backup-cp refusal and `RESTORE FAILED` (no backup, no restore) is
  the one rejected above, so this is not a placeholder pending a follow-up PR.
- **`TREE_STATE=UNMEASURED` header branch** (`scripts/verify-dd043-pr3.sh:111-112`): needs
  `git status` itself to fail at run start. Corrupting the worktree's `.git` file would also break
  the meta's own reset machinery. An optional cheap variant exists — copy the tracked files to a
  plain directory with no `.git` and run a `jar` subset there, asserting the NON-CITABLE/UNMEASURED
  stamping — but it duplicates isolation machinery for one annotation branch; recommended only if
  an implementer finds it nearly free.
- **`jvm`/`native` verdict rows** (`jvm:build` through `jvm:zero-edits`,
  `scripts/verify-dd043-pr3.sh:716-808`; `native:*`, `:859-911`): need docker, an external clone,
  and a ~15-minute serialized native compile — the same reasons they are excluded from CI
  (`.github/workflows/ci.yml:105-108`). Not killable by this meta-check. Their preflight SKIP rows
  *are* cheaply killable (O5). For the shared graders those stages route through —
  `assert_absent` (`scripts/verify-dd043-pr3.sh:226-239`), `assert_resolved_from_injected`
  (`:264-276`), `boundary_poll_shape_ok` (`:294-299`) — the honest cheap treatment is a `selftest`
  entry point in the harness itself that feeds crafted fixture files through each helper and
  asserts its full verdict matrix (pass / fail / UNMEASURED), at ~1 s. That is adjacent scope, not
  item 3 proper; it is listed as an optional deferred tier for this design's PR and is legitimate
  to defer. Until built, the jvm/native rows' red-capability rests on the manual full-run exercise
  only, and the meta's summary must say so.
- **`--allow-dirty` NON-CITABLE stamping on a full run** (`scripts/verify-dd043-pr3.sh:931-958`):
  reachable only via a 5-stage invocation (~3+ min for a cosmetic stamp, docker-less SKIPs
  included). Not worth a scenario; C0 covers the load-bearing half of the gate (the refusal).

---

## 7. Is this worth building (Q7)

Yes, for the meta-check described above. The only existing proof that any harness row can fail is
item 0's ad hoc three-run exercise (`TODO.md:1146-1154`) — two rows, uncommitted evidence, already
stale against subsequent harness edits. The harness is the certification authority for PR-3's run
of record; "a check that cannot fail" inside *it* is the exact defect class DD-045 exists to
eliminate, and at ~7 CI minutes on a narrow trigger (§4) the standing check clears the
cheaper-and-actually-run bar by a wide margin.

TODO item 3 also names the tracked-tree mutation hazard ("Mutate a copy or hold a lock"). That half
is not being built: see "Considered and rejected" above for why the mechanism weighed for it failed
review, and the accepted workaround in its place.

## 8. PR shape and a residual risk

One PR: the meta-script, its CI job, and this design doc. Optional tiers O1–O5 and the `selftest`
entry point (§6) are separable if review weight demands it.

Residual risk worth naming: the meta's 13-label scenario table (§2) is a hand-maintained mirror of
the harness's row set. A new harness row added without a matching scenario is caught only at
review time, not automatically. That is TODO item 4's territory (the repo-wide "what depended on
this?" pre-commit pass), not grounds to grow this item's scope.

## 9. Not worth building (explicit)

- **Script-text mutant generation for the harness** — redundant with subject-level fault
  injection (§1) plus bash-mutant equivalence triage nobody will maintain.
- **PATH-shim coreutils failure injection** for the refusal branches (§6) — the shim endangers
  the evidence pipeline it runs inside.
- **A meta-meta level** proving the meta-checker's assertions can fail — the regress stops at a
  reviewable ~300-line script with presence-based assertions (§5).
- **Running the meta job on every push / doc pushes, or on cron** — §4's trigger analysis.
- **A NON-CITABLE-stamp scenario via `--allow-dirty` full run** — 3+ minutes for an annotation
  branch whose load-bearing sibling (the refusal) C0 covers.
- **The source-redirection mechanism for the tracked-tree mutation hazard**, and the other
  mechanisms weighed alongside it (git worktree for the guards stage, whole-module temp copy, lock
  file + pre-commit hook) — see "Considered and rejected" above.

---

## Appendix — timing derivation (Q2's evidence)

Source: CI run 30645036655 (branch `dd045-run-of-record`, 2026-07-31, conclusion success), job
`verify-dd043-pr3` (id 91204208841), retrieved via `gh api .../actions/jobs/91204208841/logs`.
Stage banners and row lines carry the runner's timestamps:

```
15:56:43              job start                      (job metadata)
15:56:47.618  == unit — whole test suite
15:58:19.133  == jar — injector discoverability …    → unit  = 91.5 s
15:58:33.368  == guards — mutation checks            → jar   = 14.2 s
15:59:52.851  == results                             → guards = 79.5 s
15:59:56              job end                        → total = 3 m 13 s
```

Per-`_mutate` cycle ≈ 8.8 s (successive `guards:*` PASS rows at 15:58:42.0, :51.0, :59.9,
15:59:08.6, :17.1, :26.2, :35.1, :43.9; `guards:restored` at :52.8). Green run: 390 repo-wide
tests (`unit` row), 31 module tests (`guards:restored` row). Scenario costs in §2/§4 are sums
of these measured stage figures; the two flagged estimates (C7's tests-disabled `check`, O2's
fail-fast compiles) are estimates and say so. Local WSL2/NTFS wall-clock was deliberately not used
to derive any figure. The run-of-record directory's file mtimes were checked as an alternative
timeline source and rejected — checkout flattened them to a single timestamp.

Uncertainties, explicitly: every hard figure above comes from this **one** CI run — no second run
has confirmed them, and GitHub-hosted-runner timing has known variance (dependency-cache state,
contention), so treat §4's cost figures as directional pending a second sample. C7's cost and the
cold-start penalty of the meta worktree's first Gradle configuration are estimated, not measured;
the C2 branch-uniformity claim (all eight rows red via `guard is dead`) is derived from the
gate-sharing analysis in `scripts/verify-dd043-pr3.sh:501-506,587-597` and the test inventory, and
must be confirmed empirically by the implementer's first run rather than trusted from this
document.
