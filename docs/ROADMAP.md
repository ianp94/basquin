# Roadmap

**This file is an index, not a source of truth.** Every fact here lives somewhere else — a spec, a
plan, `TODO.md`, or a design decision — and this page only records *status, ordering, and why the
order is what it is*. If you find a detail stated here and nowhere else, it is in the wrong file.
(The benchmark page had exactly this drift and it is what `deploy/bench/render_page.py` now exists to
prevent.)

Last reviewed: 2026-07-26. DD-043 PR-1 (#100) and PR-2 (#102) are **merged**; **PR-3 is open as
[#103](https://github.com/ianp94/basquin/pull/103)**, awaiting approval and the human's merge. A new
follow-on — **DD-044 / PR-3.5** — is specced and reviewed but not started. The DD-040→DD-039 arc was
completed and merged 2026-07-23. See "Start here next".

---

## The current thread

Everything in flight traces to one root cause found on 2026-07-23: **the tool was reporting `0` for
things it never measured.** Most invariant violations were evaluated inside the target JVM, logged,
and then discarded because the response had already committed before the boundary could attach its
reporting header — 97.3% of Roller's responses, 75% of JSPWiki's, 0% of JPetStore's. That last figure
is why the defect hid for so long: JPetStore is the outlier that made the tool look functional.

Evidence: `bench-results/header-loss-2026-07-23/`, `bench-results/violation-logs-2026-07-23/`
(9,023 recovered violations), and the "Follow-ups from the 2026-07-23 benchmark campaign" section of
`TODO.md`.

**That thread is now closed.** DD-040 recovered the discarded violations; DD-039 carried the fix
across redirects and reached authenticated write paths; and the benchmarks were re-run on the fixed
channel. The answer to the question that started it — "Roller looks underwhelming" — was **backwards**:
on the trustworthy channel Roller's explore `findInvariant` went **0 → 1,402** (highest coverage of
the three, 30.5%), JSPWiki **1 → 2,918**, JPetStore 342 → 421. Roller was the most productive target
all along; every finding was being discarded at the last hop. What remains (DD-041, DD-042) is new
work, not cleanup of this thread.

## Ladder

| | What | State | Blocked by | Detail |
|---|---|---|---|---|
| **DD-040** | Trustworthy measurement — a reported zero means "checked and clean" | **merged** as [#94](https://github.com/ianp94/basquin/pull/94) (2026-07-23); all 7 tasks done, acceptance run recorded as **FAILED** with two documented residuals now owned by DD-039 | — | [spec](superpowers/specs/2026-07-23-trustworthy-measurement-design.md) · [plan](superpowers/plans/2026-07-23-trustworthy-measurement.md) · [evidence](../bench-results/dd040-acceptance-2026-07-23/) |
| **DD-039** | Multi-step exploration — session carry across redirects | **merged** as [#96](https://github.com/ianp94/basquin/pull/96) (2026-07-23); Task-7 acceptance **PASSED** (84 `login_publish` DB rows, gap 189→48/2.8%). One residual it ships with: the multi-replica same-method-hop merge is unexercised (single-replica acceptance) → DD-041's entry point | — | [spec](superpowers/specs/2026-07-23-redirect-session-carry-design.md) · [plan](superpowers/plans/2026-07-23-redirect-session-carry.md) · [evidence](../bench-results/dd039-acceptance-2026-07-23/) |
| **Benchmark re-run** | All three apps re-measured on the trustworthy channel | **merged** as [#97](https://github.com/ianp94/basquin/pull/97) (2026-07-23). This is the payoff — first benchmarks whose finding counts are real | — | `docs/benchmarks.html` (generated), `bench-results/*/‌*-bench3-explore/` |
| **DD-041** | Clustered exploration across replicas — the one you asked for (service-backed apps) | **next up**, not specced. DD-039 leaves it a clean seam (the same-method-hop merge) | nothing (DD-040/039 merged) | `TODO.md` "Next after DD-040" |
| **DD-042** | A load-mode concurrency oracle — load counts but never *asserts* | designed, not specced; independent, can precede or follow DD-041 | nothing | `TODO.md` "Future: DD-042" |
| **DD-043** | Native + reactive targets — build-time instrumentation of a GraalVM-native Quarkus app | **Phase 0 done, gate PASSED** — **merged** as [#98](https://github.com/ianp94/basquin/pull/98) (2026-07-24). All four spikes resolved; S1 REFUTED as specified then CONFIRMED via S1b; 8 spec amendments forced, none voiding a section, plus a round-2 fix pass from the whole-branch review (spec ledger, "Round 2"). **PR-1 (#100), PR-2 (#102) merged; PR-3 open as
[#103](https://github.com/ianp94/basquin/pull/103)** — build-time injection with zero edits to the
target's tree, both halves of §5.2 passed (JVM on `rest-villains`, native on the fixture), 390 tests
(`bench-results/RUN-OF-RECORD/suite-counts.txt:1`).
§8.1 **resolved**: Apicurio's server has no native build on the 3.x line, so row 5 needs a substitute —
ranked Debezium Server (Quarkus 3.33.1.1) > Eclipse Hono HTTP adapter (3.27.4.1, reactive, heavier
infra) > Apicurio 2.6.x, the last only behind a compatibility spike since `basquin-quarkus` is pinned to
3.37.3. A **new row-5 gate** follows: the Quarkus version range over which one `basquin-quarkus` build
augments is untested, since heroes and villains are both pinned at the toolchain's own version.
**PR-4's entry gate §8.2 (plugin-execution injection) is still UNMEASURED** and must be settled before
PR-4 starts; **§6.2** (native JFR streaming) still gates PR-5. A follow-on **DD-044 / PR-3.5** is
[specced](superpowers/specs/2026-07-26-preinstrumented-targets-design.md) but not started | nothing (Phase 0 cleared it) | [spec](superpowers/specs/2026-07-24-native-reactive-targets-design.md) · [plan](superpowers/plans/2026-07-24-dd043-phase0-spikes.md) · [evidence](../bench-results/dd043-spikes-2026-07-24/REPORT.md) |

### Why that order

- **DD-040 first** because nothing else can be *measured* until it lands. Re-running benchmarks,
  proving DD-039 works, or trusting a DD-042 finding all depend on the reporting channel being
  honest. It also has to land before DD-039 mechanically: both rewrite the core of
  `CoverageGuidedRun.request()`, and DD-039's per-hop records are only retrievable once the channel
  exists — the final hop of a redirect chain is exactly the committed-risk hop.
- **DD-039 now also owns DD-040's two measured residuals**, which is why it follows immediately: a
  method-changing redirect strips `X-Basquin-Req` (11.8% of Roller's violations went unreported), and
  a same-method hop across replicas re-uses the id on two pods so the §A.6 fan-out can return the
  wrong hop's measurement. Both dissolve once every hop carries its own id. See DD-040's
  `**Verified.**` block.
- **DD-041 after DD-040** because a distributed driver cannot rely on a response header it may not be
  the one to receive. DD-040 §A.6 already pre-empts the specific trap (the result store is per-JVM, so
  a poll through a Service VIP reaches a pod that never saw the request).
- **DD-042 is independent** and could jump the queue. Its latency-budget half is already inside
  DD-040's item B, so start there regardless.
- **DD-043 is independent of DD-041/DD-042** — a new *target class* (build-time-injected, event-loop)
  rather than a driver change. It is sequenced by its own six-PR ladder inside its spec, and Phase 0
  was deliberately run first: it is the reason PR-1 can start without redesign risk. Its one hard
  ordering claim is internal — nothing below PR-1 starts until the core extraction lands.

### The division of labour these are converging on

- **Explore** finds serialized, per-request defects — invariant breaches, expensive inputs, cold
  cliffs — because it runs one clean iteration at a time under `ITERATION_LOCK`.
- **Load** is the only mode that produces real interleaving, so it is the only mode that can *expose*
  concurrency defects. DD-042 is what makes it able to *detect* them; today it counts and never
  asserts.

## Start here next

One PR is open ([#100](https://github.com/ianp94/basquin/pull/100) — DD-043 PR-1, the `basquin-core`
extraction; scope under "Open PRs" below). Four threads are ready to pick up, in rough priority:

0. **DD-043 PR-3 — `basquin-maven-injector` — done, open as
   [#103](https://github.com/ianp94/basquin/pull/103), awaiting approval and the human's merge.**
   PR-1 (`basquin-core` extraction, #100) and PR-2 (`basquin-quarkus` extension, #102) are **merged**.

   PR-3 delivers build-time injection: a Maven core extension on `-Dmaven.ext.class.path` that adds the
   Quarkus extension to a target application's build with **zero edits to that application's tree**.
   Spec §5.2's two-artifact bar passed both halves — JVM on the real `rest-villains` app
   (`bench-results/dd043-pr3-restvillains-2026-07-26/`) and native on the Phase-0 fixture
   (`bench-results/dd043-pr3-native-2026-07-26/`). 390 tests, 0 failures — that count comes from the
   run of record, not from either acceptance directory: `bench-results/RUN-OF-RECORD/`
   (`suite-counts.txt:1` reads `390 0`; `RESULTS.md:10`).

   **Both acceptances are single-module, and no multi-module Maven reactor was ever built end-to-end
   with the injector.** `rest-villains` is a standalone pom; so is the Phase-0 fixture. `inject()`
   mutates a `MavenProject` instance per reactor member (`session.getProjects()`), and the two unit
   tests that pin per-project freshness build a synthetic three-project list rather than driving a real
   Maven reactor — parent-pom inheritance into the effective model and Maven's own per-module lifecycle
   sequencing are paths only a live reactor exercises. The spec states the stakes directly: "the real
   targets are multi-module" (`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`,
   the paragraph beginning "The injector must construct a fresh `Dependency` per `MavenProject`"). The
   run of record's own "What a pass here does and does not establish" section
   (`bench-results/RUN-OF-RECORD/RESULTS.md`) omits this gap; that file is fixed evidence and
   cannot be amended, so it is recorded here and in the spec instead.

   **What a fresh agent most needs to know about this branch.** Two mechanisms fail *silently* if
   touched carelessly, and both are load-bearing:
   - The repository injection is **two-level**. A `Repository` on the `Model` alone is a no-op at
     `afterProjectsRead`; `setRemoteArtifactRepositories(...)` is what refreshes the list Maven and
     quarkus-maven-plugin actually read. Measured by spike S5.
   - `META-INF/sisu/javax.inject.Named` is how Maven discovers the participant. Gradle does not
     generate it. Delete it and the jar builds, every test passes, and the target ships uninstrumented.
     A Gradle task `finalizedBy('jar')` guards it — deliberately not only `check`, since the release
     path publishes without running `check`.

   **Eight silent-bypass shapes are closed** across **four** guard methods, and the shape of that
   history is the lesson: **only one shipped with the original guard commit** — verified at the time
   directly against that commit's own tree with `git show <sha>:basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java`,
   which contained exactly one guard method (`failOnConflictingManagedVersion`) and one `throw` site,
   not three; that commit predates PR #103's squash-merge and the command is no longer re-runnable
   from main, so the fact is recorded here rather than re-cited by SHA. **The other seven arrived
   after implementation — each added by review, or by measurement
   review prompted — none by the design or a self-review**, derived at the time from
   `git log --reverse -S'<method>' -- basquin-maven-injector/.../BasquinInjector.java` (a command that
   is itself no longer re-runnable against those pre-squash commits, for the same reason): the
   declared-version conflict ("Task 4's review found this as a Minor");
   the declared-scope shape (its own javadoc: "Found by review of PR #103, which was asked
   to look for exactly this shape after two similar asymmetries had already been fixed"); the type and
   classifier shapes, folded into a whitelist ("scope (Claude review), type (approver),
   classifier (same)"); the exclusions shape added to that same whitelist (an unsteered
   approver round); managed exclusions (the same approver's next round, "SIXTH bypass
   shape"); the sibling-declaration guard ("found by measuring", spike S2, "SEVENTH silent
   bypass"); managed scope (round 7, "EIGHTH bypass"). The code keeps its own running
   count, so read it there rather than here — it is drift-proof against line-number churn a further
   guard addition causes; a line number pinned in this doc is not, and neither is a commit SHA once
   the branch that carried it is pruned.
   - `failOnUnusableDeclaration` closes four shapes on the *declared* `basquin-quarkus` as a
     **whitelist** of usable declarations — scope, type, classifier, exclusions — rather
     than a list of known-bad values, because narrowing the guard to specific bad values kept shipping
     the next variant. `optional=true` is the one field it deliberately accepts, measured rather than
     reasoned.
   - `failOnConflictingManagedVersion` closes three: the sixth — `<exclusions>` on a *managed* (not
     declared) `com.basquin:*` entry — which surfaced only after that whitelist shipped, when
     review showed the declared-path fix had been half a fix; the managed-version
     conflict, the shape the original design had; and the eighth — a managed `<scope>` on a
     `com.basquin` sibling reaching `basquin-core` at dependency-resolution depth 2, found in round 7
     after the same "half a fix" pattern repeated once more.
   - `failOnUnusableSiblingDeclaration` closes the seventh: a *sibling* `com.basquin` artifact
     declared directly — `basquin-core` above all — which every guard above missed, because they match
     either `basquin-quarkus` specifically or `dependencyManagement`. It fails on a scope that cannot
     reach the application and on a conflicting version, and deliberately accepts
     `type`, `classifier` and `exclusions` there because those were measured not to be hazards.
   - `failOnConflictingDeclaredVersion` is the fourth method — the declared-path counterpart of
     the managed-version conflict.

   **Every branch that can throw carries its own mutation row — there is no uncovered guard.**
   `scripts/verify-dd043-pr3.sh` runs **eight** `_mutate` checks: one per `throw` site
   (`managed-version`, `managed-exclusions`, `managed-scope`, `declared-version`,
   `declaration-usability`, `sibling-scope`, `sibling-version` — seven, matching the seven
   `throw new MavenExecutionException` sites in `BasquinInjector.java`), plus `skip` for the operator
   opt-out, each proven able to fail when its own branch is neutered. All eight PASS in
   `bench-results/RUN-OF-RECORD/RESULTS.md` as one `guards:<label>` row apiece, alongside
   `guards:restored` recording the source restored and the module suite green. Cited by **row key, not
   line**: mid-table insertions in rounds 6 and 7 invalidated that line range twice, and any further
   guard renumbers it again. Mind the arithmetic when restating this — eight *shapes* are closed by
   seven *throws*, and the two counts do not line up method-by-method, so "8 shapes" and "7 throws"
   are the only two figures worth restating. The throws distribute
   1 / 1 / 3 / 2 across `failOnUnusableDeclaration`, `failOnConflictingDeclaredVersion`,
   `failOnConflictingManagedVersion` and `failOnUnusableSiblingDeclaration` (count them with
   `awk '/private void failOn/{m=$3} /throw new MavenExecutionException/{print m}'` over
   `BasquinInjector.java`) — three offsetting mismatches, not one:
   `failOnUnusableDeclaration` bundles four shapes behind one `throw`; the sibling guard's one
   numbered shape (the seventh) splits across two `throw`s, scope and version; and
   `failOnConflictingDeclaredVersion` is a `throw` with no shape number of its own, being the
   declared-path counterpart of the managed-version conflict rather than a distinct bypass. Quoting
   only the first of the three makes 8 look like it should reduce to 5. Mutation
   coverage is per-throw (one neutered branch per row), not per shape. **Four shapes, not one, are what
   §5.2's banner acceptance cannot detect, and the class keeps growing because each is a different route
   to the same resolved state — `basquin-quarkus` still loads, `basquin-core` doesn't:** a declared
   `basquin-quarkus` with `<exclusions>` stripping its own transitive `basquin-core`; a managed
   `dependencyManagement` entry on `com.basquin:*` carrying `<exclusions>`; a managed
   `dependencyManagement` entry pinning a `com.basquin` sibling to an unusable `<scope>`; and a directly
   declared `com.basquin` sibling (`basquin-core` above all) pinned to an unusable `<scope>`. Do not
   restate this as a bare count elsewhere — the checkable enumeration is the javadoc on
   `failOnUnusableDeclaration`, `failOnConflictingManagedVersion` and `failOnUnusableSiblingDeclaration`
   in `BasquinInjector.java`, cross-referenced by `BasquinInjectorGuardsTest.java`'s matching tests, both
   of which change before this doc can.

   **Next: PR-4** (coverage — offline-JaCoCo execution injection, `/__basquin/coverage`, the native 2×2
   cells). Its **entry gate is spec §8.2** — whether `afterProjectsRead` model mutation reaches the
   per-project *execution plan*, not just resolution — and that is **still unmeasured**. S4 injected a
   dependency; nobody has injected a plugin *execution*. Settle it the way S4 and S5 settled their
   halves — inject a plugin execution with an observable side effect into the spike fixture, build, and
   show the effect in the log — before building on the assumption.

   **DD-044 / PR-3.5 is specced but not started**
   (`docs/superpowers/specs/2026-07-26-preinstrumented-targets-design.md`): the operator cannot today
   drive a build-time-instrumented target without patching it, because `buildAgentArgs` appends
   `-javaagent`, `-Xbootclasspath/a` and `-Dbasquin.boundary=agent` **unconditionally** — "inject
   nothing" is unrepresentable, and a no-agent target still reports `Phase=Injected` for an app the
   operator never modified. It went through a review round that found five blocking defects; read §2.2a
   and §2.2b before implementing, as they cover the two windows where the new code could still emit the
   dishonest signal the whole feature exists to prevent. Slots **before PR-4** only if the in-cluster
   verification matters sooner than coverage does; otherwise after.

1. **DD-045 — verification-integrity tooling. Start here once PR-3 merges** (user-directed,
   2026-07-30: build these immediately after the merge).

   **Why this is next, and not a cleanup task.** PR-3 took **eight** approver rounds. The feature's thesis
   held in every one; almost every finding was in the machinery that certifies it, or in the bookkeeping
   around it. Five shapes recurred, and every one is mechanically detectable:

   | Shape | What it cost on PR-3 |
   |---|---|
   | A check that cannot fail | `jvm:not-from-central`'s sentinel wrong **three times**; `jar:baked-version` never passed on a CRLF checkout; `guards:managed-exclusions` could pass off the previous mutation's stale XML; `jvm:zero-edits` passed on a directory it never examined; `purge_basquin`'s proof written and never graded in **both** stages; the `jar` stage passing against a stale jar because the build's exit code was discarded |
   | A citation that no longer resolves | **135 wrong of 547** checked repo-wide — roughly 1 in 4 — and wrong citations were findings in rounds 5, 6, 7 **and** 8 |
   | A derived number restated in prose | guard counts stale in **seven** documents across **three** consecutive rounds; then a shapes-vs-throw-sites conflation (8 shapes closed by 7 throws) produced a fresh wrong count in round 8 |
   | Parallel agents each correct alone, contradictory together | round 5's B3 (one change deleted a run directory while another, same commit, cited it); two agents disagreeing whether the `managed-scope` mutation row existed; and a false debt removed from `TODO.md` while the identical false claim was left in `ROADMAP.md` |
   | Evidence whose *name* changes every time it is regenerated | each run of record is `verify-<UTC timestamp>/`, so producing a new one stales **every** citation to the old one at once — **10, 10, 13 and 18** occurrences repointed by four supersession commits during PR #103, each count taken from that commit's own message at the time (those commits are now folded into the `b32b394` squash-merge and their individual messages are no longer separately reachable); counted as occurrences, not matching lines, because `grep -c` undercounted the first one |

   All five are one defect — **a claim and its check drifting apart** — attacked at five layers.

   **The pieces, in priority order:**

   0. **Run the verification harness in CI. Delivered.** The `verify-dd043-pr3` job
      (`.github/workflows/ci.yml:109`) runs `bash scripts/verify-dd043-pr3.sh unit jar guards` on the
      existing `push`/`pull_request` path filters — `jvm` and `native` are deliberately out of scope
      (docker, 15+ minutes), stated in a comment on the job — and uploads
      `bench-results/verify-*/` via `actions/upload-artifact@v4` when it fails, so a red run is
      diagnosable without reproducing it locally. Manual-only was the root cause behind most of the
      findings in row 1 of the table: `jar:baked-version` could not pass on a CRLF checkout for three
      rounds because nothing ran it, and the `jar` stage passed against a jar it never built until
      round 8 executed it deliberately. Proved this can actually fail, not merely reasoned about: an
      unmodified run passed 13/13, then naming a nonexistent class in
      `basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named` — a stale Sisu
      index, the exact silent-non-discovery shape the `jar` stage exists to catch — dropped it to 9
      passed / 2 failed / 0 skipped (exit 1: `unit` failed via `verifyInjectorIsDiscoverable`, `jar`
      reported `UNMEASURED: the jar build FAILED`), and restoring the file brought it back to 13/13,
      exit 0. Everything below is detection; this was the one that changed when detection happens.

   1. **A citation-resolution CI check, whole-tree scoped. Built** as `scripts/check-citations.py` plus
      `scripts/check-citations-allowlist.txt`, wired into `.github/workflows/ci.yml` as the
      `citation-integrity` job (runs on both `push` and `pull_request`; both path-filter lists carry
      `scripts/**`, `**/*.md`, `**/citations.txt` and `bench-results/**`, so the script, the allowlist,
      and the files it checks all trigger it). The fix already existed for one format and was
      generalised: `citations.txt` uses a pinned `file:line: expected-text` format and re-verified
      **66/66**, while free-form line references failed 1-in-4. It resolves each citation against the
      file's *current text*, not merely the path existing, and runs over the **whole tree**, not the
      diff — a diff-scoped check was run in round 7 and missed four dead citations — including the PR's
      central "app tree pristine" claim — because they lived in files that commit did not modify.
      Measured 2026-07-30 and committed as an artifact, not restated as a live total: the corpus this
      tool scans includes this sentence, so a bare number quoted here — as an earlier version of this
      paragraph did, and as `TODO.md`'s copy of the same line separately did — goes stale the moment
      either file is edited, including by the edit that records the number. (Those two copies were
      also circular: this paragraph's total was backed only by `TODO.md`'s identical hand-typed
      figure, not by any artifact — fixed here by citing the run directly instead of routing through
      `TODO.md`.) One real run is committed at `bench-results/citations-20260730T214700Z/`, the same
      pattern this document already uses for `scripts/verify-dd043-pr3.sh`'s evidence:
      `bench-results/citations-20260730T214700Z/output.txt` is the tool's raw stdout, and its
      `README.md` explains the disposition classes and states plainly that
      the numbers are a snapshot of a corpus that changes with every doc edit. That run's header
      (`bench-results/citations-20260730T214700Z/output.txt:1`) parsed 1689 citations across 91 md + 2
      pinned + 27 comment-scanned files; its final disposition line
      (`bench-results/citations-20260730T214700Z/output.txt:372`) reports 1241 verified, 299
      UNCHECKED, 136 allowlisted, 2 reported to owners, 13 disclosed absences, 0 untracked-on-disk —
      six buckets summing to the parsed total, asserted by the tool itself — exit 0. To get today's
      real figures, run `python3 scripts/check-citations.py` and read its own final disposition line;
      never carry a total forward from this paragraph, `TODO.md`, or a prior run directory. It found a
      defect class hand-checking could
      not: a citation to agents.md in `docs/DESIGN-DECISIONS.md`'s DD-021 entry, a file that exists on
      the authoring machine but is gitignored, so it resolved for its author and was dead on a fresh
      clone (fixed in the same pass).
   2. **A stable pointer to the run of record. Delivered.** Timestamped directory names were why
      citations rotted in bulk. Added `bench-results/RUN-OF-RECORD` — a tracked pointer **file**, not a
      symlink (this checkout has `core.symlinks=false`, on which a tracked symlink materialises as a
      plain-text file naming its target rather than resolving), holding one non-comment line naming the
      run directory. `scripts/check-citations.py`'s `resolve()` substitutes a cited
      `bench-results/RUN-OF-RECORD/...` prefix with the directory the pointer names before matching the
      tracked tree, so a supersession now edits one file instead of repointing every citation; a
      dangling, missing, or malformed (0 or 2+ non-comment-line) pointer fails the checker loudly
      (`exit=1`, a `DEAD PATH` finding naming the reason) rather than passing silently — proved
      empirically for all three modes. `scripts/verify-dd043-pr3.sh` never writes this file; promoting a
      run is a deliberate, manual edit in the same commit that adds the new run directory. **Correction
      (review found this):** that proof only ever exercised a **sub-path** citation
      (`bench-results/RUN-OF-RECORD/RESULTS.md`); a **bare** `bench-results/RUN-OF-RECORD/` citation —
      the exact form this file cites at `docs/ROADMAP.md:102`, and TODO.md cites twice — reached
      "exact" via the generic tracked-path check before the substitution ever ran, because the pointer
      is itself a tracked file, so all three bare citations verified clean against a deliberately
      broken pointer. Fixed by checking the substitution first. Full account in `TODO.md`'s DD-045
      item 2.
   3. **Mutation-test the harness, not only the guards. Delivered.** Built as
      `scripts/verify-dd043-pr3-rows.sh` plus its own CI job
      (`.github/workflows/verify-dd043-pr3-rows.yml`, narrow path filter — not `**/*.md`), per
      `docs/superpowers/specs/2026-07-31-dd045-item3-harness-mutation-design.md`. Seeds one mechanical
      defect per targeted row into a throwaway `git worktree` at HEAD (never the working tree), runs
      `scripts/verify-dd043-pr3.sh`'s own stage subset there, and asserts the targeted row(s) came back
      `FAIL` by exact label in a COPY of that run's own `RESULTS.md` — never from the exit code alone,
      the same discriminator discipline `_mutate` already applies one level down. Core tier: 8 scenarios
      kill all 13 green-run row labels plus the `jar` stage-refusal row and the full-run dirty gate, each
      with embedded PASS-row controls where the row shape allows one. Proved empirically, with captured
      exit codes: a full run passed all 8 scenarios and all 13 labels, `exit=0`; then three of the
      meta-check's own assertions were broken one at a time, in scratch copies, never the committed
      script, and each correctly reported failure instead of a false pass. Related hazard — the guards
      stage mutating **tracked source in place** — is resolved as an accepted workaround, not new code:
      the harness-only Gradle-property mechanism considered for redirecting it to a copy would have
      silently redirected an ORDINARY build's compiled source if the property or an inherited
      environment variable ever leaked into `release.yml`'s publish job, a worse hazard than the
      local-only commit-timing one it would have fixed. Full account in `TODO.md`'s DD-045 item 3.
   4. **A "what depended on this?" pre-commit pass, and its mirror image.** Not "is the diff internally
      consistent" — that is the narrower question, and asking it is what let items above through. For
      every path, row key, or claim a commit **removes**, search the whole tree for anything that still
      depends on it. Round 8 found two instances the diff-scoped version could not see. The mirror image
      is **resolving open debt against the code**: round 9 swept all 91 `- [ ]` entries in `TODO.md`
      against the tree and ten were already satisfied, two of them false for the whole span of DD-043,
      one of them refuted by an already-`[x]` entry in the same file. An unchecked box is a claim about
      the current tree exactly like a citation is, so item 1's checker should resolve both. Full account
      in `TODO.md`'s DD-045 item 4.
   5. **Stop restating derived numbers in prose.** Generate the fragment from the code, or state the class
      and point at one authoritative enumeration. Standing rule adopted in round 7: a count that must be
      updated in N places when the code changes is a defect generator, not documentation. Where a count is
      genuinely useful, name the items so drift is visible, and say what unit is being counted — the
      round-8 miscount came from three documents counting shapes, throw sites, and methods without saying
      which.
   6. **An agent completion contract.** Three subagents were killed mid-work by a session limit; one had
      already reported success on work whose verification step never ran. A report is accepted only when
      it contains the pasted output of its own verification, and partial evidence is never committed.

   **Entry condition:** none — this is tooling over the existing tree, and it is independently useful
   before PR-4 begins. 0, 1 and 2 are now built; 3 is the remaining structural piece. 4-6 are cheap once
   that exists.

   **Also worth noting for whoever picks this up:** `scripts/verify-dd043-pr3.sh` is *inside*
   `ci.yml`'s path filters — `'scripts/**'` is in both lists
   (`.github/workflows/ci.yml:51`, `:79`) — and, since item 0, a job actually runs it: the
   `verify-dd043-pr3` job (`.github/workflows/ci.yml:109`) invokes `unit jar guards` on both
   `push` and `pull_request`.

2. **DD-041 — clustered exploration across replicas (the one the user asked for, for service-backed
   apps).** Not specced yet — so the next step is *brainstorm → spec → plan*, NOT code. DD-039 leaves
   the clean entry point: its single residual is that `hops > 1` re-uses one id across pods behind a
   Service, so the §A.6 fan-out can return the wrong hop's measurement (documented in the DD-039
   record and `TODO.md` "Next after DD-040"). The lesson from DD-039: **author the spec/plan by
   reading the code, and spike the risky integration before committing to a full build** — three
   plans written from memory were rejected before one written from the code worked.

3. **DD-042 — a load-mode failure oracle.** Independent of DD-041; could go first. Load mode counts
   but never *asserts* — a JSPWiki with two pinned cores and a dead Poller was marked **Completed**.
   Designed in `TODO.md` "Future: DD-042" (an out-of-band `/__basquin/threads` census, analysis in
   the driver). Its latency-budget half already exists inside DD-040.

4. **Small, cheap wins** — the follow-up sections in `TODO.md`: the three PR-97 prose tidies in
   `render_page.py`, and the redaction min-length guard from PR #96. Good warm-up work.
   (This bullet used to lead with "wire `check_claims.py`/`test_redact.py` into CI (they exist but
   only fire by hand)". That is false and has been since `a0595b9`: `.github/workflows/ci.yml`'s
   `Bench artifact drift guards` step runs both on every push. Found by the round-9 sweep of open
   debt against the code — the same shape as the row above, a false debt surviving in `ROADMAP.md`
   after being fixed in the tree.)

Also standing, not on the critical path: send the **JSPWiki `WeakHashMap` spin** report upstream
(`bench-results/jspwiki/incident-2026-07-23-login-hang/ANALYSIS.md` — reproduced, publishable), and
the JPetStore `listOrders` double-NPE.

**Working rules for whoever picks this up** are in memory (`agent-manager-playbook`): start by reading
this file; subagent reports go to files and return short; fable for adversarial/diagnostic work and
the fresh-per-PR approver; **only the human merges**. The cluster is single-node — one campaign at a
time, nothing CPU-heavy during a run.

## Open PRs

**[#103](https://github.com/ianp94/basquin/pull/103) — DD-043 PR-3, `basquin-maven-injector`.**
Build-time injection with zero edits to the target's source; both halves of spec §5.2 passed; 390 tests,
0 failures (`bench-results/RUN-OF-RECORD/suite-counts.txt:1`). Labelled `ready-for-approver`.
Six follow-ups are recorded in `TODO.md` under "DD-043 PR-3 follow-ups", of which **two** are now
resolved and **four** remain open — count the `- [ ]`/`- [x]` boxes in that section rather than
trusting this sentence, which has gone stale twice (round 7 added the sixth; round 9 closed the
second). The two resolved: the verify script's `jvm` and `native` stages had never been executed —
both now ran end-to-end in the run of record, stamped `20260730T215842Z`
(`bench-results/RUN-OF-RECORD/RESULTS.md`, its `Stages run:` and
`27 passed, 0 failed, 0 skipped` header lines — cited by label, not line number, because the header
gains lines) — and `jvm:boundary` no longer accepts an error page, since it now runs `curl -sf` and
requires `ResultStore.format`'s CSV shape.

#100 (PR-1, `basquin-core` extraction) and #102 (PR-2, `basquin-quarkus` extension) are **merged**.

[#99](https://github.com/ianp94/basquin/pull/99) (this file's previous post-merge sync) merged
2026-07-25 as `42790aa`. Everything else is merged to `main`.

[#98](https://github.com/ianp94/basquin/pull/98) (DD-043 spec + Phase-0 spike evidence, evidence-only,
no product code) merged 2026-07-24 as `6aa16fc`. It was a **squash** merge, so the branch's individual
commits are not ancestors of `main` — checking for them by SHA reports "not in main" and is the wrong
test; verify by content. Its durable record is the spec's own amendment ledger plus
[`REPORT.md`](../bench-results/dd043-spikes-2026-07-24/REPORT.md); the review artifacts that drove its
round-2 fix pass lived in `.superpowers/sdd/`, which is **untracked session scratch** — its ignore rule
is a nested `.gitignore` containing `*`, which ignores itself, so a fresh clone has no rule *and* no
directory. **DD-043's own documents therefore cite nothing under that path.** Five other committed files
still do (six citations, audited); that debt is recorded in `TODO.md`.

The prior session shipped #92–#97 (DD-038 classifier fix, Roller target + generated page, DD-040,
DD-039, the two follow-up PRs, and the benchmark re-run).

PR flow is in memory (`claude-reviews-every-pr`): bot PR → `@claude` review → address → label
`ready-for-approver` → notify via `scripts/agent-bus/send`. **Only the human merges.**

Watch PRs with `scripts/agent-bus/watch-prs` in the background — it blocks until something is
actionable rather than waking on a timer.

## Standing debts worth not forgetting

These are recorded in `TODO.md` with full evidence; listed here only so they are visible from one
place.

- Load mode has **no failure oracle** — two campaigns ran against a JSPWiki with two cores pinned and
  a dead NIO Poller, reported 2.1 rps / p50 5003 ms, and were marked **Completed**.
- `fireR` returns `-1` on a transport failure, which increments **neither** error counter.
- `heapDriftKb` is GC-phase noise (+381 MB on one run, **−194 MB** on another) — no drift figure is
  published, deliberately.
- The seeded JSPWiki pages are not actually served (`jspwiki.fileSystemPath` vs
  `jspwiki.fileSystemProvider.pageDir`).

## Findings we owe upstream

- **Apache JSPWiki 2.12.4** — unsynchronized `WeakHashMap` in `DefaultUserManager` causes a permanent
  100%-CPU spin; root-caused from bytecode and reproduced in ~60 s at concurrency 96. Report is
  written and ready to send: `bench-results/jspwiki/incident-2026-07-23-login-hang/ANALYSIS.md`.
- **MyBatis JPetStore** — `/actions/Order.action?listOrders=` NPEs for an unauthenticated caller, then
  NPEs again inside Stripes' own exception handler.
