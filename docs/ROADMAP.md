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
target's tree, both halves of §5.2 passed (JVM on `rest-villains`, native on the fixture), 380 tests.
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
   (`bench-results/dd043-pr3-native-2026-07-26/`). 380 tests, 0 failures.

   **What a fresh agent most needs to know about this branch.** Two mechanisms fail *silently* if
   touched carelessly, and both are load-bearing:
   - The repository injection is **two-level**. A `Repository` on the `Model` alone is a no-op at
     `afterProjectsRead`; `setRemoteArtifactRepositories(...)` is what refreshes the list Maven and
     quarkus-maven-plugin actually read. Measured by spike S5.
   - `META-INF/sisu/javax.inject.Named` is how Maven discovers the participant. Gradle does not
     generate it. Delete it and the jar builds, every test passes, and the target ships uninstrumented.
     A Gradle task `finalizedBy('jar')` guards it — deliberately not only `check`, since the release
     path publishes without running `check`.

   **Five silent-bypass shapes are closed** by four guards, and the shape of that history is the lesson:
   three of the five were found *after* implementation, by review. Guard 4 is written as a **whitelist**
   of usable declarations (scope, type, classifier, exclusions) rather than a list of known-bad values,
   because enumerating bad values shipped the next variant twice. `<exclusions>` is the one shape
   **§5.2's banner acceptance cannot detect** — the extension still appears in `Installed features`
   while a stripped `basquin-core` leaves it unusable.

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

1. **DD-041 — clustered exploration across replicas (the one the user asked for, for service-backed
   apps).** Not specced yet — so the next step is *brainstorm → spec → plan*, NOT code. DD-039 leaves
   the clean entry point: its single residual is that `hops > 1` re-uses one id across pods behind a
   Service, so the §A.6 fan-out can return the wrong hop's measurement (documented in the DD-039
   record and `TODO.md` "Next after DD-040"). The lesson from DD-039: **author the spec/plan by
   reading the code, and spike the risky integration before committing to a full build** — three
   plans written from memory were rejected before one written from the code worked.

2. **DD-042 — a load-mode failure oracle.** Independent of DD-041; could go first. Load mode counts
   but never *asserts* — a JSPWiki with two pinned cores and a dead Poller was marked **Completed**.
   Designed in `TODO.md` "Future: DD-042" (an out-of-band `/__basquin/threads` census, analysis in
   the driver). Its latency-budget half already exists inside DD-040.

3. **Small, cheap wins** — the follow-up sections in `TODO.md`: wire `check_claims.py`/`test_redact.py`
   into CI (they exist but only fire by hand), the three PR-97 prose tidies in `render_page.py`, and
   the redaction min-length guard from PR #96. Good warm-up work; the CI-guard one has real value
   (it would have caught several review rounds automatically).

Also standing, not on the critical path: send the **JSPWiki `WeakHashMap` spin** report upstream
(`bench-results/jspwiki/incident-2026-07-23-login-hang/ANALYSIS.md` — reproduced, publishable), and
the JPetStore `listOrders` double-NPE.

**Working rules for whoever picks this up** are in memory (`agent-manager-playbook`): start by reading
this file; subagent reports go to files and return short; fable for adversarial/diagnostic work and
the fresh-per-PR approver; **only the human merges**. The cluster is single-node — one campaign at a
time, nothing CPU-heavy during a run.

## Open PRs

**[#103](https://github.com/ianp94/basquin/pull/103) — DD-043 PR-3, `basquin-maven-injector`.**
Build-time injection with zero edits to the target's source; both halves of spec §5.2 passed; 380 tests,
0 failures. Labelled `ready-for-approver`. Five follow-ups are recorded in `TODO.md` under "DD-043 PR-3
follow-ups", each with why it was deferred — most notably that the verify script's `jvm` and `native`
stages have never been executed.

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
