# DD-043 — Native and reactive targets

**Status:** **Phase 0 spikes RAN (2026-07-24). Gate PASSED — PR-1 is cleared to start.** Amended
against the evidence; see the amendment ledger below. Previously: designed, not yet planned
(2026-07-24), revised after adversarial review — `reviews/2026-07-24-dd043-fable-review.md`
(2 blockers, 8 majors; all addressed here).
**Depends on:** DD-040 (trustworthy measurement channel), DD-012/DD-023 (coverage over HTTP)
**Related:** DD-002 (`gcBeforeMeasure`), DD-004 (JFR sampling is soft-only), DD-009/DD-011 (why the
valve exists), DD-019/DD-040 (log scraping rejected, twice), DD-029 (closure's thesis stated),
DD-005/DD-010 (why the iteration lock exists)

## Phase-0 amendment ledger

Phase 0 ran on 2026-07-24 under `docs/superpowers/plans/2026-07-24-dd043-phase0-spikes.md`. All four
spikes resolved; the consolidated verdicts, evidence citations and scope qualifiers are in
`bench-results/dd043-spikes-2026-07-24/REPORT.md`.

**Gate result: PASSED.** §7.1 named two outcomes that would have voided design sections and **neither
occurred.** S1 was REFUTED *as specified* and CONFIRMED via S1b once the read path was corrected, so
§6.4 is amended rather than void and §2's full-parity goal does not reopen. S4 was CONFIRMED in JVM
and native for the **dependency half** of §5's mechanism — the half it exercised — so §5.1's
degradation stays a contingency. S4 never injected a **plugin execution**, which §5 requires in equal
measure; that half is unmeasured and is now §8.2, a PR-4 entry gate. *(Written when §5 named two
injections. §5 has since gained a third — the repository, measured by spike S5 during PR-3 — and the
plugin execution remains the only unmeasured one.)*

| Spike | Verdict | Sections it forced changes in |
|---|---|---|
| **S1** | **REFUTED** as specified (reflective read stripped by AOT) | §6.4, §7.1 |
| **S1b** | **CONFIRMED** (direct typed call) | §6.4, §7.1, §7.4 |
| **S2** | **CONFIRMED** — scoped to allocations above the quantum; quiescence half DEFERRED | §5, §6.1 |
| **S3** | **CONFIRMED** — **JVM mode only**; no native build was run, so §4.3/§6's disposition table is unmeasured under AOT (§7.2 carries the re-check) | §6, §7.3 |
| **S4** | **CONFIRMED** — scoped to *dependency* resolution + augmentation; extension *discovery* inferred, not measured; **plugin-execution injection never exercised** (§8.2) | §5, §5.1 |

Eight amendments were made, each traceable to committed evidence:

1. **§6.4** — the coverage read is a direct compile-time-typed call, `RT.getAgent().getExecutionData(false)`,
   never reflection. Also decouples the design from JaCoCo's shaded package name. *(S1, S1b)*
2. **§6.4 / §7.4** — the native coverage denominator differs from the JVM's; native's ceiling is capped
   below 100% and the two percentages are not like-for-like comparable. *(S1b)*
3. **§6 / §7.3** — the 5xx/crash signal is gated on `ar.succeeded()`; `disconnected` becomes a third
   disposition, and §7.3 gains a control asserting a disconnect does **not** increment the crash
   counter. *(S3)*
4. **§6.1** — the measurement floor is stated: 524,288 B instrument resolution, ~1 MiB practical
   per-request minimum. *(S2)*
5. **§6.1** — `System.gc()` works under SubstrateVM, so `basquin.heap.gcBeforeMeasure` is recommended
   on native targets, not merely noted as portable. *(S2)*
6. **§5** — the `nmt` hedge is dropped; `-Dquarkus.native.monitoring=jfr,nmt` is verified and the
   `additional-build-args` fallback is withdrawn. *(S2)*
7. **§5** — the injector must construct a fresh `Dependency` per `MavenProject`; a shared instance
   aliases across a multi-module reactor and passes every single-module test. *(S4 fix round)*
8. **§7.1** — S1's failure signatures are rewritten: signature (0) is the read path itself, and
   `NeverCalled` cannot be the signature-(ii) instrument on native because reachability analysis
   deletes it. The working instrument is a registered-but-never-called JAX-RS route. *(S1, S1b)*

A ninth edit records S4's result in **§5.1**, whose stated hypothesis the spike refuted; the section is
retained because it is why S4 existed, but it no longer reads as a live unmeasured hazard.

**Where no amendment was needed, stated explicitly** so a silent absence is not mistaken for an
unchecked section:

- **§4.3** (which end hook to use) — S3 confirmed `addEndHandler` fires on all four dispositions, and
  that `addHeadersEndHandler` reaches the client on every completed response including the 500 —
  measured against **S3's probe fixture**, which wrote a response header. The shipped extension does
  not use that hook at all (§4.3), so only the `addEndHandler` half of this bears on it. The table
  was correct **for the question Phase 0 asked**, and only the *consumer* of that signal in §6 was wrong
  at the time. The table has since been amended anyway: PR-2 found its `addHeadersEndHandler` row still
  instructed writing `X-Basquin-Req` as a response header, which §4.4 had already refuted. Read this
  entry as a Phase-0 verdict, not as a claim the table stands unedited. **Scope: S3 ran in JVM
  mode only** (`s3-boundary/findings.md:5-6,162-163`; `REPORT.md:48-49`). The table is therefore
  *unamended*, not *verified under AOT* — and S1 is this branch's standing proof that a JVM-mode
  result does not transfer to native on this toolchain. §7.2's native cells carry the re-check.
- **§6.5** (latency's population) — already excludes `disconnected` samples from the latency
  distribution, which is exactly what S3's disconnect finding requires. S3 additionally observed that
  the end handler fires ~1 s after the client aborts, bounded by server-side close detection, so a
  disconnect's elapsed time is detection latency rather than client-observed latency — §6.5's existing
  exclusion already covers it and no text change was needed.
- **§6.2** (the JFR cross-check) — Phase 0 ran no JFR analysis; S2 only proved the `jfr,nmt` build flag
  is accepted. The section is **unverified, not confirmed**, and its §7.3 control still has to earn it.
  *(Round 2: §6.2's body said "verified" and has been corrected to match this bullet.)*
- **§6.3** (event-loop watchdog) — not exercised by any Phase-0 spike. Unchanged and untested.
- **§4.4** (result store and parking poll) — no spike touched the DD-040 channel transplant. Unchanged.
- **§8.1** (does Apicurio build native) — still open; Phase 0 did not address it. *(Resolved since,
  during PR-3: **NO** on the current line — see §8.1 for the finding, the substitute ranking, and the
  cross-version gate it exposed.)*

### Round 2 — final-review fixes (2026-07-24)

A whole-branch review (1 Critical / 6 Important / 4 Minor; its report was session-local scratch and is
not committed, so the findings are reproduced in the table below rather than cited) found that round 1
rewrote the sections the evidence contradicted **but not the sections that depend on them**. Every finding is an instance of this branch's defining defect class: *a reported zero that means
"never measured" rather than "checked and clean"*. Round 2 is those follow-throughs.

**Scope of Round 2.** It edited this spec, `bench-results/dd043-spikes-2026-07-24/REPORT.md`, and
`docs/ROADMAP.md`. `REPORT.md` is a derived report rather than evidence — several findings concerned
its prose drifting from the artifacts it cites. No raw spike artifact was edited.

#98 was squashed into `6aa16fc`, so per-round diffs are not recoverable from `main`: this paragraph
is a description of the round, not something a reader can reconstruct from history.

| # | Finding | Sections changed |
|---|---|---|
| **C1** | §7.3's coverage control still named the class-level instrument amendment 8 disproved, so it passed having measured nothing | §7.3 |
| **I1** | §6.2's body claimed native JFR streaming "verified" while the ledger called §6.2 unverified — the ledger disclaimed the section without correcting it | §6.2, §9 (PR-5 gate) |
| **I2** | §7.3's "closed set over §6" was false — `Thread leak` was in neither the control table nor the unpublished paragraph, and §6's cross-reference pointed at §6.2 instead of §6.3 | §6 table, §7.3 |
| **I3** | The taint rate and `UNMEASURED` were published figures whose only controls asserted the *negative*; a counter that could never fire would report 0% tainted forever | §6.1, §7.3 |
| **I4** | Amendment 8's replacement instrument had no §1.1-compatible home — a planted JAX-RS route means editing app source, and an extension-owned one is not in the app's coverage denominator | §7.1, §7.3 |
| **I5** | S3's JVM-only scope was in `REPORT.md` and the spike findings but nowhere in the spec, which is what PR-2…PR-5 are implemented from | ledger §4.3 bullet, §6, §7.2 |
| **I6** | "§5's mechanism holds" covered both injections; S4 exercised only the dependency half | ledger, §5, §5.1, §7.1 gate, §8.2 (new), §9 |
| **M1** | `REPORT.md` paraphrased the S2 startup gap as "near-contiguous", which concealed that `s2-memory/findings.md:29` states a **wrong figure** ("3 seconds") against the **9.454 s** the `[PROBE]` ids in `app.log` give. The wrong figure was never in `REPORT.md` — it is in the findings file, and `REPORT.md` merely declined to name it | `REPORT.md`, which now names and attributes the discrepancy. `s2-memory/findings.md` is committed spike evidence and was deliberately **not** rewritten; the drift is recorded instead |
| **M2** | "Maven 3.6.3" was asserted as the host toolchain with no artifact behind it | `REPORT.md` **and spec §3.1** — the claim appeared in both, so this row is *not* `REPORT.md`-only |
| **M3** | `REPORT.md` implied `banner-native.txt` is a one-line banner; it is a 9-line run log | `REPORT.md` |
| **M4** | §6.2's `com.sun.management`/SubstrateVM claim is uncited and underpins the 2×2 split | §6.2 |

**Judged not worth changing, and why:**

- **The Phase-0 plan (`plans/2026-07-24-dd043-phase0-spikes.md`) is left as executed.** Its Task-5 table
  still names `NeverCalled` as the signature-(ii) instrument (`:643`). That is the *hypothesis the spike
  tested and disproved*; editing it would rewrite the record of what was actually run and destroy the
  provenance of amendment 8. A plan is a record after execution, not a live instruction — the live
  instruction is §7.1.
- **`REPORT.md`'s amendment table is not renumbered.** Amendments 1–8 are what the *evidence* forced;
  round 2 is what a *review* forced. Merging them would make the report claim spike backing for edits no
  spike produced.

---

## 1. Context

Every target Basquin has run against — JPetStore, JSPWiki, Roller — shares two properties the tool
has quietly assumed are universal:

1. **Instrumentation attaches at runtime.** A `-javaagent` premain, a valve jar in Tomcat's `lib/`, a
   JVMTI `-agentpath`, and a JaCoCo tcpserver agent, all injected by the operator through
   `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`.
2. **The app is thread-per-request.** A request occupies one thread for its whole life, so
   "method enter → method exit" is the request lifetime, and a global `ITERATION_LOCK` held across the
   app call makes per-request heap and thread deltas attributable.

A GraalVM-native, reactive Quarkus application violates both. Neither assumption is recorded as a
constraint anywhere in the codebase, which is why they need naming before being designed around.

This spec covers **both axes at once**, deliberately, because they are independent and conflating them
is what would make a wrong number hard to localise:

| Axis | Today's three targets | DD-043 |
|---|---|---|
| Attachment | runtime (`-javaagent`, valve, JVMTI) | **build time** (Quarkus extension at augmentation) |
| Request model | thread-per-request (Tomcat) | **event loop** (Vert.x / Hibernate Reactive) |

### 1.1 Why this is a return to the thesis, not a retreat from it

`docs/LOCKFREE-LOAD-DESIGN.md:16` states closure's thesis:

> Closure's thesis is that the measurement boundary stays in play while throughput scales.

The operative property is that **the source application is never modified** — Basquin's tooling is
injected into it, at build time or at runtime, to assist fuzzing. The valve (DD-009/DD-011) achieved
that by attaching at runtime and avoiding WAR repacking. Runtime attachment was the *mechanism*, not
the thesis.

Build-time instrumentation serves the same thesis. Its honest advantage is narrower than "it cannot
fail": a compiled-in boundary **cannot detach at runtime**, and its presence is **verifiable exactly
once, at build time, via the `Installed features` banner**. The failure mode does not vanish — it
moves. A build where injection silently did not happen produces a binary with no boundary at all
(§5.1), which is the `BoundaryInstaller` silent no-op (`agent/BoundaryInstaller.java:39-48`) relocated
rather than eliminated. §5.2 is the guard.

**The non-negotiable constraint this spec inherits: no file in the application's source tree is
created or modified.** §5 meets it for the build-time path; §7.3 is where it nearly broke, twice —
first for the defect routes (resolved by shipping them in the extension) and then for the coverage
instrument, which cannot live in the extension because it must sit in the *app's* coverage
denominator. §7.1 resolves that second case with a withheld application route, planting nothing.

## 2. Goals and non-goals

**Goals**

- Instrument a GraalVM-native Quarkus application with the full signal set: request boundary,
  availability invariants, and coverage-guided exploration.
- Establish correct measurement semantics for an event-loop request model.
- Preserve the no-source-modification property on the build-time path.
- Produce a benchmark row for a native/reactive target that is comparable in **trustworthiness** —
  explicitly not in *shape* (§6.2, §6.5).

**Non-goals**

- The runtime-agnostic binary/OS channel (eBPF, `/proc`, `LD_PRELOAD`). Attractive — it would reach
  Go, Rust and Node targets, matching the runtime-agnostic ambition `docs/ARCHITECTURE.md` states for
  the operator — but it structurally cannot provide coverage-guided exploration, and it is a different
  product thread. Recorded so it is not rediscovered as novel.
- A GraalVM `Feature`/`@Substitute` front-end for Spring Native, Micronaut, Helidon. §4.1 leaves it
  reachable without a rewrite; not built now.
- Load mode against native targets. Explore first; DD-042 owns the load oracle.

## 3. Targets

| Order | Target | Stack | Purpose |
|---|---|---|---|
| 1 | `rest-villains`, JVM mode | RESTEasy Reactive, **blocking** endpoints; Hibernate ORM + Panache; PostgreSQL | Control. Isolates extension correctness. |
| 2 | `rest-villains`, native | as above, `-Dnative` | Isolates whether build-time attachment survives AOT. |
| 3 | `rest-heroes`, JVM mode | RESTEasy Reactive, **reactive**; Hibernate Reactive + Panache; PostgreSQL; port 8083 | Isolates reactive boundary semantics. |
| 4 | `rest-heroes`, native | as above, `-Dnative` | **The actual target.** |
| 5 | Substitute real product — §8.1's ranking: Debezium Server > Hono HTTP adapter > Apicurio 2.6.x | Quarkus 3.33.1.1 / 3.27.4.1 / 3.15.3 respectively | Real-product credibility row. **§8.1 resolved NO for current Apicurio** (its server has no native path on 3.x); the row now carries §8.1's cross-version augmentation gate instead. |

Both services come from [`quarkusio/quarkus-super-heroes`](https://github.com/quarkusio/quarkus-super-heroes).
Both are **contract-first**: the REST interface is generated at build time from
`src/main/resources/openapi/openapi.yml` via the Quarkiverse OpenAPI Generator server extension, which
gives the driver a precise route-and-parameter seed corpus rather than a hand-built one.

`rest-villains` is a throwaway control, not a maintained target. It costs one extra build and no new
code, and it is what makes a wrong number localisable — §7.2.

### 3.1 Toolchain — the entire Maven build runs in a container

**Evidence: `bench-results/dd043-target-pins-2026-07-24/`** — the upstream `pom.xml` files themselves,
with provenance. `rest-heroes` **and** `rest-villains` both set `maven.compiler.release=25`, pin Quarkus
**3.37.3**, and pin `jacoco.version=0.8.15`. That they pin *identically* matters: §7.2's 2×2 requires the
blocking control and the reactive target to differ only in request model, and a toolchain difference
between them would confound every cell. This host has **JDK 17** (`bench-results/dd043-spikes-2026-07-24/env/ENVIRONMENT.md:8,142`);
its Maven version is deliberately not quoted here, because nothing committed pins it and nothing
depends on it — the whole point of the decision below is that **no Maven step runs on the host at all.**

(An earlier draft asserted these as "Verified 2026-07-24" with nothing committed, while this same PR
carries a review recording the Java-release claim as unverifiable — `reviews/2026-07-24-dd043-fable-review.md`
NIT 19. The artifact supersedes that note and is the reason the claim now stands.)

`./mvnw` is a bootstrap script: it downloads a Maven *distribution* and runs it **on the host JVM**.
It supplies no JDK. With host JDK 17 and release 25, `javac` fails before Quarkus augmentation is
reached — and `quarkus.native.container-build=true` containerises only the `native-image` step, which
consumes already-compiled augmentation output. An earlier draft of this spec claimed "every build goes
through Docker"; that was false for exactly the half that needs the newer JDK.

**Decision: run the whole Maven build inside a JDK-25 container.**

```
docker run --rm -v "$PWD":/w -w /w \
  -v "$INJECTOR_DIR":/basquin \
  -e MAVEN_OPTS="-Dmaven.ext.class.path=/basquin/basquin-maven-injector.jar" \
  maven:3.9-eclipse-temurin-25 ./mvnw -B package [-Dnative]
```

Two consequences that must be carried into §5, because this is the seam where injection breaks first:

- the injector jar must be **mounted into** the container, and
- `-Dmaven.ext.class.path` must name a path valid **inside** the container.

Building `-Dnative` from inside this container additionally requires the Docker socket if
`container-build` is used, or a Mandrel-bearing builder image; S4 pins which.

## 4. Architecture

### 4.1 `basquin-core` — move the shared core, don't unify three copies

**Correcting an earlier premise:** there is exactly **one** copy of the boundary logic today.
`BasquinValve` imports and delegates to `agent.RequestBoundary`
(`tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java:3,49`), and `RequestBoundary` was
extracted precisely so the valve and agent run identical logic. The extraction is still needed — a
Quarkus module cannot depend on `agent/` as shaped — but it is a **move**, not a de-duplication.

**The split is the sharp edge, and it is not "extract what's duplicated". It is evaluation vs.
composition:**

| Goes into `basquin-core` | Stays behind, Tomcat-shaped |
|---|---|
| `Invariants` evaluation — pure thresholds over numbers | `Agent.begin/end` **composition** |
| `ResultStore` + the DD-040 salted id scheme | `Thread.sleep(25)` leak-snapshot grace (`agent/Agent.java:118`) |
| Invariant definitions and result types | the two thread enumerations |
| | optional `System.gc()` (DD-002 `basquin.heap.gcBeforeMeasure`, `agent/Agent.java:96,126`) |
| | anything `ThreadLocal`-backed |

Two reasons this boundary is drawn exactly here:

1. **`addEndHandler` runs on the event loop.** Transplanting `Agent.end()`'s composition would sleep
   25 ms on the loop every iteration — making the tool whose headline invariant is *"something blocked
   the event loop"* the most reliable loop-blocker in the process.
2. **`ThreadLocal` is meaningless on an event loop**, where requests interleave on one thread. State
   rides the `RoutingContext` (§4.4); nothing in the shared core may assume thread affinity.

The reactive equivalent of the leak-snapshot grace period is **decided, not inherited**: there is
none at the boundary. Any deferred re-check happens off-loop on a scheduled task, or not at all.

#### The package stays `agent` — a constraint on PR-2, not a decision PR-2 revisits

`Invariants` and `ResultStore` keep `package agent` after the move into `basquin-core`, producing a
split package across two artifacts. That is deliberate.

`runner/GenericRunner.java:218-221` decides which classes its reset ClassLoader loads
**parent-first**, by literal string prefix:

```java
private boolean parentFirst(String name) {
    return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
            || name.startsWith("agent.") || name.startsWith("runner.") || (!targetPrefix.isEmpty() && !name.startsWith(targetPrefix));
}
```

`targetPrefix` defaults to `""` (`runner/GenericRunner.java:195`), which makes the trailing clause
`(!targetPrefix.isEmpty() && !name.startsWith(targetPrefix))` false. A class renamed to
`com.basquin.core.*` therefore matches **no** clause: it loads child-first, and the reset loader
hands out a **fresh `ResultStore` per reset** — per-request results written to one instance and
polled from another. Silent data loss, DD-040's defect class, arriving through a refactor whose
contract is "no behaviour change".

The guard is `test/agent/ResetLoaderParentFirstTest.java`. Its second method,
`aRenamedCorePackageWouldNotBeParentFirst`, asserts the renamed form is *not* covered — a rename
becomes safe only once `parentFirst` is taught the new prefix, at which point that method flips
from passing to failing. That failure is the signal the rename is now safe, not a broken test.

**Constraint on PR-2:** PR-2 builds `basquin-quarkus` against this artifact, which makes it the
natural place to want to "tidy" the split package into something like `com.basquin.core`. Do not,
until `GenericRunner`'s parent-first predicate (or its replacement) is taught the new prefix in the
same change.

#### PR-2 entry requirement: `Invariants`' API surface is package-private

`Invariants` is `final class Invariants` with `static Invariants.Result evaluateAndMaybeFail(...)` —
package-private, and it already was before the move, so this is pre-existing, not something the
extraction introduced. It defeats the extraction's purpose as written: a Quarkus extension living in
`com.basquin.quarkus.*` cannot call a package-private class in `package agent`. `ResultStore` has no
such problem — `public final class ResultStore` with public members throughout.

**Widening `Invariants` alone is not sufficient** (whole-branch review, final-review-pr100.md, I5).
Making the class and `evaluateAndMaybeFail` public still leaves the call unusable from
`com.basquin.quarkus.*`: the return type, `Invariants.Result`
(`basquin-core/src/main/java/agent/Invariants.java:55`, `static final class Result`), is itself
package-private with package-private fields `violations`/`hardFailureMessage` (`:56-57`), and
`Violation`'s fields `name`/`detail` are package-private (`:44-45`) even though `Violation` itself is
already `public static class` (`:43`). A public method that returns an inaccessible type, or a public
type whose fields a cross-package caller can't read, is unusable either way. **PR-2 must widen all
four**: the `Invariants` class, `evaluateAndMaybeFail`, `Result` (plus accessors for `violations` and
`hardFailureMessage` — the fields can stay package-private if accessors are added instead), and
`Violation`'s `name`/`detail` (as public fields or accessors).

**Publishing — resolved, no longer an entry requirement.** All four DD-043 artifacts — `basquin-core`,
`basquin-quarkus` (runtime), `basquin-quarkus-deployment`, and `basquin-maven-injector` itself — now
apply `maven-publish` with the same two targets from one configuration. (An earlier revision of this
paragraph described only `basquin-core` publishing; spike S5 surfaced the gap — an injected build must
resolve the *whole chain*, the deployment artifact included, or Quarkus augmentation fails — and PR-3
wired the other three, plus the release workflow's publish-all-four and assert-all-four steps.)
`publishToMavenLocal` puts them in `~/.m2/repository` for developing the extension against an
unreleased core — and, after S5, that local-repo path is a documented **offline fallback**, no longer
the consumable mechanism: the injector injects the repository itself, so no pre-populate step exists
in the operator contract (§5). `publishAllPublicationsToPagesRepository` writes them to `docs/maven/`,
which GitHub Pages serves at `https://ianp94.github.io/basquin/maven/`; the release workflow's
existing `pages` job publishes and commits them exactly as it already does for `docs/charts/`. **That
URL serves nothing until the next `v*` tag** — v0.3.0 shipped before `basquin-core` existed and no
artifacts are committed here — and consequently **no acceptance run has exercised the real Pages
HTTPS repository**: every PR-3 acceptance published the chain to a scratch directory and served it
over localhost HTTP (`-Dbasquin.inject.repo.url=…`), which is also the interim path for anyone
running the injector before that tag. Consumers add that one `<repository>` and
need **no credentials**, unlike GitHub Packages, which requires a token even for public artifacts —
**an ordinary, pom-editing consumer, that is.** On PR-3's zero-edit path nobody adds it by hand: the
injector injects it, at both levels (§5), because editing the target's pom is exactly what that path
forbids. Do not read this sentence as PR-3's mechanism.
Maven has no native git-dependency form, so a static repo committed here and served over HTTPS is the
closest equivalent to depending on the source directly.

Verified rather than assumed, with the evidence committed: **`bench-results/dd043-publish-2026-07-25/`**
holds the consumer POM, the full `mvn dependency:resolve` log from a clean local repository, and the
serving HTTP request log. A correct layout is not the same as a resolvable one, so the resolution was
run — over **HTTP**, not `file://`, because Pages serves over HTTPS and `file://` exercises none of that
transport behaviour. The request log also settles the Gradle Module Metadata question raised in this PR's review empirically: Maven requested
only the `.pom`, `.jar` and their `.sha1` sidecars and **never asked for the Gradle Module Metadata
file**, so it cannot cause a variant mismatch for a Maven consumer.

What that does *not* establish is a real Pages deploy, which cannot be tested before the first `v*` tag
populates `docs/maven/`. The supporting argument there is precedent, not evidence: the same `pages` job
publishes `docs/charts/`, which serves over HTTPS today, and `docs/.nojekyll` disables Jekyll repo-wide.

**What remains of PR-2's entry requirement is visibility only:** `Invariants`,
`evaluateAndMaybeFail`, `Result` (and its accessors) and `Violation`'s fields are package-private and
must be widened together — widening `Invariants` alone leaves the call unusable.

**PR-2 cannot start its boundary filter until the visibility widening is done.** Recorded here as an entry
requirement (§9's PR-2 row) rather than left to be discovered mid-build, the way the packaging gap
below was.

#### One exception to "zero behaviour change": the invariant stack's top frame moved

Everything observable about `Invariants` is unchanged by the move — call counts per path,
side-effect ordering, the `basquin.forceExitOnLeak` → `System.exit(2)` path, short-circuit on the
first hard violation, and byte-identical exception messages — all verified against the pre-refactor
commit.

One thing did change, because the move required inverting a dependency: `Invariants` cannot call
back into `agent.Agent` (that would be the circular project dependency the package split exists to
avoid), so `evaluateAndMaybeFail` now takes `int iterationNumber` and returns an
`Invariants.Result(violations, hardFailureMessage)` instead of taking an `IterationContext` and
throwing directly. The caller, `Agent.end()`, records evidence and constructs the
`IllegalStateException` itself (`agent/Agent.java:162`) rather than `Invariants` constructing and
throwing it. `ctx.invariantStack` is built from the current call stack at the point evidence is
recorded (`agent/Agent.java:479`, called from `Agent.end()`), so it now loses the
`Invariants.evaluateAndMaybeFail` frame, and the thrown exception's top frame moves from `Invariants`
to `Agent.end()`.

That is not the whole delta, though: the capture is frame-windowed, capped by default at 15 frames
(`agent/Agent.java:513`, `Integer.getInteger("basquin.invariant.stack.maxFrames", 15)`). Losing a
frame near the top doesn't just drop it — it slides the entire window, so one additional deeper
caller frame now enters the snapshot and the trailing `"...N more"` count changes too. Cosmetic (the
suite asserts on none of this), but "loses one frame" understates the effect at the far end of the
window.

This surfaces via `getLastInvariantStack()` (`runner/CorpusRunner.java:86`,
`tomcat-war/src/main/java/com/basquin/examples/StatusServlet.java:19`). Nothing in the test suite
asserts on stack-frame contents, so nothing broke — but "zero behaviour change" should be read as
covering messages, ordering and exit codes, not stack-trace shape.

#### The packaging lesson: a green `check` does not prove the shipped jar is complete

Moving classes out of `sourceSets.main.output` silently emptied `runnerJar` — the jar
`deploy/runner-image/Dockerfile:16` ships as the campaign driver's `ENTRYPOINT` — while all 324
tests and every `check` task stayed green, because both run on `main.runtimeClasspath`, and
`runnerJar` is built from the `coverage` source set's own, separate runtime classpath. The shipped
driver crashed on iteration 1 with `NoClassDefFoundError: agent/Invariants`.

**No JUnit test can catch this class of defect** — it is a property of the built artifact, not of
the classpath tests run against. The fix is a permanent Gradle guard,
the `verifyShippedJarsContainCore` task in the root `build.gradle`. It opens every shipped jar (the
agent fat jar and `runnerJar`) via `java.util.jar.JarFile` and fails, naming the exact missing
entries, if either is short the core classes. Its expected entry set is **derived from
`basquin-core`'s own jar**, not hand-listed, so a class added to that module is guarded
automatically. It is attached with `finalizedBy` on the jar-producing tasks themselves rather than
to `check`, because the release path (`release.yml`, `deploy/*/build.sh`) invokes `jar`/`runnerJar`
directly and never runs `check` — so `./gradlew runnerJar` alone still runs the guard. It exists because the classes
will move again when `package agent` is eventually renamed, and that move will recreate exactly this
risk.

### 4.2 `basquin-quarkus` — the extension

Standard two-module Quarkus extension shape:

- **`runtime/`** — ships into the app, AOT-compiled into the native image: boundary filter, result
  store, invariant evaluation, status/result/coverage routes, negative-control defect routes (§7.3).
- **`deployment/`** — `BasquinProcessor` and its `@BuildStep` methods, augmentation-only, never in the
  image.

| Build step | Purpose |
|---|---|
| `FeatureBuildItem("basquin")` | Prints in the `Installed features` banner — the deploy signal, and §5.2's injection proof |
| **`FilterBuildItem`** | Installs the request boundary. *Not* `RouteBuildItem` — `FilterBuildItem` (handler + priority) is Quarkus's idiomatic router-wide filter; `RouteBuildItem` registers routes. **Ordering trap:** Quarkus installs a `FilterBuildItem` as `router.route().order(-priority)`, so a `RouteBuildItem` with a *more negative* order runs **before the boundary** and is silently uninstrumented. Found in PR-2: a control route at order `-10_000` bypassed the filter at order `-100` entirely, producing no measurement and no error. Any route added under `/__basquin/` must sit **after** the boundary |
| `RouteBuildItem` | The control surface at **`/__basquin/*`** — the prefix the driver actually calls (`LoadModeControl.PREFIX`), served as Vert.x routes so they exist in native without JAX-RS scanning. **Do NOT delegate to `LoadModeControl.handle`** — it calls `RequestBoundary.awaitQuiescence`, which is `ITERATION_LOCK.tryLock(...)`, and importing explore's serialization lock into the lock-free reactive path is a binding-invariant violation. The extension serves `result` and `violations` itself, sharing only `ResultStore`'s wire format; `mode` and `drift` are out of scope and answer `err:unknown`. See §4.4a |
| `@Recorder` | Wires runtime state at application startup. **None in PR-2** — the boundary filter and control handler hold no startup-time state, so no recorder was needed. Listed as the design target for when one is |

The boundary sees only router traffic. Anything bypassing the router — a separate management
interface port, gRPC, raw socket handlers — is invisible to it. Irrelevant for these two targets;
stated so it is not rediscovered.

### 4.3 Why a Vert.x-level filter and not a JAX-RS one

`@ServerRequestFilter`/`@ServerResponseFilter` sit inside JAX-RS, so they miss non-JAX-RS traffic, and
a response filter can run before the body is written. A `FilterBuildItem` handler sits below
everything, sees all router traffic, and gives access to `RoutingContext`'s end hooks.

Which end hook matters:

| Hook | Semantics | Use |
|---|---|---|
| `addHeadersEndHandler` | last moment **before** headers commit | **not used.** An earlier draft had the boundary write `X-Basquin-Req` here as a *response* header; §4.4 refuted that — the driver sends the id **inbound** and the boundary only reads it. The shipped extension has no `addHeadersEndHandler` at all. Only the S3 probe fixture ever wrote it, which is why S3's evidence mentions it |
| `addEndHandler` | response fully written; `AsyncResult` reports **success or failure** (incl. client disconnect) | record the measurement here |
| `addBodyEndHandler` | **may never fire** on connection reset — Vert.x docs say do not use for cleanup | not used |

### 4.4 The DD-040 channel: store and id scheme transplant; quiescence must be redesigned

An earlier draft claimed the DD-040 channel "transplants unchanged." **That was wrong**, and wrong in
the specific way DD-040's own history warns about. DD-040's record is explicit that the store alone
was insufficient — *"The poll waits on `ITERATION_LOCK`, and that is the critical detail"*. Without
the lock there is no `awaitQuiescence` (`agent/RequestBoundary.java:194`), so the race returns, and it
is **worse than before**: the driver's poll is triggered by response-end, and the store write happens
in `addEndHandler`, also at response-end, asynchronously on the loop. The two are near-simultaneous by
construction.

**What transplants:** the result store, the salted `<RUN_SALT>-<n>` id scheme, and DD-040's
first-class miss accounting.

**What is replaced:** lock-based quiescence becomes a **completion-parking poll**, which the extension
**writes itself** (`BasquinControlHandler.pollResult`). It cannot reuse the Tomcat path's:
`LoadModeControl.handle`'s `result` case is `awaitQuiescence` plus a single `take`, and
`awaitQuiescence` IS the lock-based wait this sentence replaces (§4.4a). The handler on a miss waits
for the store's `put` for that id,
bounded at **2 s** (mirroring DD-040's bound), with the driver's read timeout above it at **4 s**
(same reasoning). A timeout is a recorded miss, never a zero.

Per request:

1. Filter **reads** the driver's id from the inbound `X-Basquin-Req` **request** header, stamps start
   time, baseline heap and baseline thread count, and stashes them on the `RoutingContext`
   (`BasquinBoundaryFilter.handle`). **No in-flight counter exists here.** An earlier draft of this
   step said the filter increments one; the actual PR-2 filter has no counter field, so nothing in
   `basquin-quarkus` today implements §6.1's taint mechanism. That counter is **PR-5's** — see PR-5's
   roadmap entry: §6.1's in-flight taint needs a `ResultStore.Entry` field and does not exist until
   PR-5 lands it.
2. `addEndHandler` computes the measurement and puts the result into the store under that id — **but
   only for a completed response**. `ResultStore.Entry` carries no disposition field
   (`costCsv, invariantCount, detail, leakDetected`), so "records disposition" is not literally
   implementable against it. A disconnected request (`ar.succeeded()` false) is simply **not
   published**, which is what §6.5 actually requires: its elapsed time is time-to-abort, not
   latency, so it must not enter the distribution. Explicit disconnect *accounting* — as opposed to
   exclusion — would need a new `Entry` field and is deliberately not in PR-2.
3. The driver polls `/__basquin/result?id=<id>`.

**Corrected 2026-07-25 against the code; an earlier draft of this list was wrong in a way that would
have shipped a broken extension.** It said the filter *assigns* `<RUN_SALT>-<n>` and writes
`X-Basquin-Req` as a **response** header. The driver assigns it — `CoverageGuidedRun.java:1031`,
`RUN_SALT + "-" + REQ_SEQ.getAndIncrement()` — and sends it **inbound** (`:1056`); both existing
boundaries read it (`BasquinValve.java:62`, `TomcatBoundaryAdvice.java:30`). An extension that minted
its own ids would publish results under ids the driver never sent, so every poll would miss — DD-040's
exact failure mode, rebuilt from a spec sentence.

**Stamp only on the explore branch.** `BasquinValve.java:58-63` stamps the id *only* when
`decision.phase == EXPLORE_BEGAN`, so the load path reads no header at all. Stamping unconditionally
would leak explore's behaviour into the lock-free load path, which is a binding project invariant.

**A consequence worth stating plainly:** DD-040's opportunistic `X-Basquin-Cost` header fast path
**cannot exist on this path at all** — the measurement is only known after the last byte is written.
So *every* iteration polls, doubling requests per iteration. DD-040 rejected "piggyback request N−1's
result on request N" for complicating a path that mostly did not need it; here the poll is universal,
so that alternative deserves re-evaluation. Deferred to PR-2, flagged, not silently inherited.

### 4.4a The control surface: share the wire format, not the handler

**Corrected against the code the same day it was written.** An earlier version of this section said the
extension would "intercept `/__basquin/*`, call `LoadModeControl.handle(path, query)`, write the string
back", on the grounds that `LoadModeControl` has zero imports and is therefore framework-neutral. Zero
imports does not mean zero dependencies: same-package types need none. `handle` references `LoadMode`
(three times) and `RequestBoundary.awaitQuiescence` (once), both of which stay in `agent/`. Moving it
wholesale would recreate the circular dependency that broke the `Invariants` move — and
`awaitQuiescence` is `ITERATION_LOCK.tryLock(...)`, lock-based machinery that has no meaning on the
lock-free reactive path this whole spec exists to support.

**What is genuinely shared — and what that actually guarantees.** The drift risk was never the
routing — it is the **wire format**, and that already lives in `basquin-core` as
`ResultStore.format(...)` / `ResultStore.take(id)`, public and reused verbatim. Sharing that
formatter guarantees the Quarkus and Tomcat paths cannot disagree about the wire **shape** — four
`|`-separated fields, one line per hop. It does **not** guarantee they cannot disagree about
**content**: what goes INTO an `Entry` is decided per-boundary, at each boundary's own call site, and
the two had already diverged there. PR-2 briefly shipped `BasquinBoundaryFilter.publish` writing a
bare `detail` (just the violated invariant's own message) where the Tomcat path publishes
`name + ": " + detail` (`Agent.java:475`), silently losing *which* invariant fired, since
`ResultStore.Entry` stores the field opaquely. Fixed, and now pinned by
`BasquinBoundaryFilterTest#publishFormatsDetailAsNameColonDetailMatchingTomcat` — but the general
lesson stands: content alignment across boundaries is a per-boundary obligation that has to be
tested, not a property the shared formatter hands you for free. Also worth extracting into
`basquin-core`, being tiny and pure: the `PREFIX` constant and the query-parameter parser, so both
paths agree on `/__basquin/` and on how `?id=` is read.

**What the extension implements itself**, because it cannot be shared:

| `/__basquin/…` | PR-2 | Why |
|---|---|---|
| `result?id=` | yes — `ResultStore.format(ResultStore.take(id))` behind a **bounded wait of its own** | `awaitQuiescence` is lock-based; the reactive path has no lock. §4.4's bound (2 s) applies, and a timeout records a **miss**, never a zero |
| `violations` | yes — `ResultStore.totalViolations()` | no coupling |
| `mode`, `drift` | **no** | both are `LoadMode`, the DD-029 valve strategy flag. Load mode against native targets is a §2 non-goal and DD-042's business |

**`LoadModeControl` therefore does not move.** It stays in `agent/` as the Tomcat path's handler. Only
`PREFIX` and the parameter parser are extracted, and only if that extraction is behaviour-preserving —
otherwise the extension carries its own copies of two trivial pure functions, which is a smaller cost
than a bad refactor of a shipped control path.

## 5. Injection without source modification

The runtime path never touches the app: the operator appends to `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`
and patches only the pod template. The build-time path uses a Maven **core extension**: a
`basquin-maven-injector` jar containing an `AbstractMavenLifecycleParticipant`, activated by
`-Dmaven.ext.class.path`. Its `afterProjectsRead(MavenSession)` hook mutates each `MavenProject` —
adding **three** things: the `basquin-quarkus` dependency, the `<repository>` that makes it
resolvable, and the offline-JaCoCo plugin execution (§6.4) — before the per-project execution plan
is computed.

**The repository injection is a two-level mutation, and the one-level version fails silently.**
Injecting a `Repository` into the `Model` **alone is a no-op** at `afterProjectsRead`: the project's
effective repository lists are computed during project building, *before* any participant runs, so
the model entry changes nothing downstream. The participant must **also** rebuild the project's
effective list — construct an `ArtifactRepository`, append it, and call
`MavenProject.setRemoteArtifactRepositories(...)`, whose implementation (verified in Maven 3.9.16
bytecode) refreshes the Aether `remoteProjectRepositories` list that both Maven's dependency
resolution and quarkus-maven-plugin's `${project.remoteProjectRepositories}` parameter actually
consume. The two-level form is specified here, explicitly, because the model-only version is exactly
what an implementer would write from the one-line description above: it passes no unit test that
drives `inject()` directly, and against a populated local repository it builds **green while
resolving from the wrong source** — this section's own silent failure mode. Measured by spike S5:
with the local repository purged of `com.basquin` and Central not carrying the group, the injected
repository was the sole source for the full closure — including `basquin-quarkus-deployment`, which
the **Quarkus bootstrap resolver** fetched on its own during `generate-code`. Evidence:
`bench-results/dd043-s5-repo-injection-2026-07-26/` (`findings.md`, `build-s5-injected.log`,
`http-access.log`).

**Two of those three injections are measured; the plugin execution is not.** S4 injected a
`Dependency` and nothing else (`s4-injection/probe-participant/…/InjectProbe.java:26-43`); S5
measured the repository injection, both levels. No spike ever injected a plugin *execution*. It is not the same
operation — a dependency and a repository are consumed by resolution, while a plugin execution has
to survive into the per-project **execution plan**, which Maven computes at a different point in the
lifecycle. So "§5's mechanism holds" is true of the dependency and repository injections and
**unmeasured** for the plugin execution. §8.2 carries it as an open question and PR-4 cannot start on the
assumption that it is settled. **PR-3 shipped the first two injections only** — the offline-JaCoCo
plugin execution is PR-4's, behind §8.2's entry gate (§9).

**Gradle:** an init script (`-I basquin-init.gradle`, repository root) doing the same via
`allprojects { … }`, deliberately sharing the Maven injector's three system properties
(`basquin.inject.skip`, `basquin.inject.repo.url`, `basquin.inject.version`) so an operator
instrumenting a mixed estate learns one contract. **It is a stub in the delivery sense:** PR-3 never
exercised it against a Gradle-built Quarkus application — both acceptance targets are Maven-built —
so a Gradle target is unverified until someone runs §5.2's banner check against one (§9's PR-3 row).

**The injector must construct a fresh `Dependency` per `MavenProject`.** Maven's model objects are
mutable, so hoisting one `Dependency` allocation out of the `for (MavenProject p : session.getProjects())`
loop aliases a single instance across every module in the reactor — every project's dependency list
then holds a pointer to the same object, and any later in-place mutation or identity-dependent
handling on one module bleeds into all the others. **This passes every single-module test**, which is
what makes it worth specifying rather than leaving to implementation taste: S4's spike fixture is
single-module and its evidence was byte-for-byte unaffected by the bug, while the real targets are
multi-module (super-heroes is a multi-project repo; each of §8.1's row-5 candidates is a multi-module
reactor). Found and fixed in
the spike participant — `bench-results/dd043-spikes-2026-07-24/s4-injection/probe-participant/src/main/java/com/basquin/spike/InjectProbe.java:37`
carries the corrected shape and a comment saying why it must not be hoisted back out. **The same
fresh-instance-per-project discipline applies to the repository injection's objects** — the model
`Repository` and the effective-list `ArtifactRepository` are exactly as mutable and alias exactly the
same way, so every model object is allocated fresh inside the per-project loop, none hoisted.

**Native build arguments** ride the same channel. `quarkus.native.monitoring` is an enum list, and
`nmt` **is an accepted value in Quarkus 3.37.3** — verified by S2, not assumed:
`-Dquarkus.native.monitoring=jfr,nmt` was accepted at config parse *and* carried through a full native
compile to `BUILD SUCCESS`, with Quarkus translating it into a real `--enable-monitoring=jfr,nmt,heapdump,threaddump`
argument on the `native-image` invocation (`bench-results/dd043-spikes-2026-07-24/s2-memory/build-native-nmt.log:32,104,108,110`).
Use that form directly. The `-Dquarkus.native.additional-build-args=--enable-monitoring=nmt` fallback
an earlier draft named as "the safe form" is **not required** and is withdrawn.

No file in the application tree is created or changed. The symmetry:

| | Injection point | Mechanism |
|---|---|---|
| **Runtime** (Tomcat, JVM) | `CATALINA_OPTS` / `JAVA_TOOL_OPTIONS` | operator patches the pod template |
| **Build** (Quarkus, native) | `MAVEN_OPTS` / `-Dmaven.ext.class.path` | lifecycle participant mutates the project model |

This assumes the operator of Basquin controls the `mvn` invocation — has the app checked out and
builds it — while never editing its source. That is the intended deployment model.

**The operator contract needs no pre-populate step.** Because the injector supplies the repository as
well as the dependency, the one-command form is complete on its own: nothing has to be installed into
the target build's local repository first. S4's local-repo path (`publishToMavenLocal` /
`install:install-file`) is thereby demoted from *the* consumable mechanism to a documented **offline
fallback** — for build hosts that cannot reach the repository URL — and `docs/THIRD-PARTY-APPS.md`
documents it as exactly that (S5; §3's publishing paragraph carries the same demotion).

### 5.1 The real risk is that Quarkus may not read the model we mutated — measured, and it did not materialise

**Result first (S4, 2026-07-24): the hypothesis below is REFUTED.** Quarkus's bootstrap resolver
builds its `ApplicationModel` from the in-memory `MavenProject`/`Model`, not by re-reading `pom.xml`
from disk. A dependency injected purely in memory reached both `javac` and augmentation, in **JVM and
native** packaging, and the injected feature appeared in the `Installed features` banner of both
artifacts. Evidence: `bench-results/dd043-spikes-2026-07-24/s4-injection/` (`banner-baseline.txt` vs
`banner-jvm-injected.txt` / `banner-native.txt`). §5's **dependency injection** therefore stands as
designed and the degradation below is **not** the norm — it remains documented only as the contingency
it always was. S5 (2026-07-26) then extended the same result to the **repository** injection: the
bootstrap consumed the participant-refreshed `${project.remoteProjectRepositories}` and fetched the
deployment artifact from the injected repository on its own
(`bench-results/dd043-s5-repo-injection-2026-07-26/findings.md`). The **plugin-execution** injection
§5 also requires was part of neither experiment and
is not covered by either result (§8.2).
The reasoning is retained because it is why S4 existed, and because the same hazard would return for
any resolver that behaves differently.

The lifecycle-participant mechanism is sound. The hazard is **Quarkus-specific**: the
`quarkus-maven-plugin` builds its `ApplicationModel` through its own bootstrap resolver
(`quarkus-bootstrap-maven-resolver`), which in several modes resolves the workspace by **re-reading
`pom.xml` files from disk** rather than from the session's in-memory `MavenProject`. If the prod
`build` goal takes that path, the injected dependency exists for `javac` and vanishes for augmentation
— a build that **succeeds** and produces an uninstrumented binary. That is §1.1's relocated failure
mode, arriving through a mechanism worth naming.

**The Develocity precedent is narrower than an earlier draft claimed.** It proves the *loading* half
at scale — an extension you did not declare can participate in your build. It does **not** inject
dependencies or plugin executions into the project model; it observes and wraps the build through
event spies and its own APIs. "Exact analogue" was an overstatement and is withdrawn.

Other risks: a strict `dependencyManagement`/BOM may pin something the extension needs (the injector
must **fail loudly**, never silently — implemented in PR-3: a conflicting managed *or* declared
version both hard-fail the build, with the escape hatches named in the message); and builds that run
inside their own Dockerfile never see our
`MAVEN_OPTS`. Injecting a plugin *execution* is harder than injecting a dependency, and after S4 and
S5 it is the **only part of §5 with no evidence at all** — promoted out of this list to §8.2, because a risk
buried in a trailing sentence is how it stayed invisible to round 1's scope blocks.

### 5.2 Injection is proven by the banner, not assumed

**S4's acceptance criterion is not "the participant ran".** It is: the built artifact's startup banner
lists `basquin` under `Installed features`, for **(i)** the JVM-mode jar and **(ii)** the native image,
both built through §3.1's containerised Maven. Anything less cannot distinguish "instrumented" from
"silently uninstrumented", which is the whole failure this section exists to prevent.

If the disk-re-read path bites, the documented degradation is §5.1's pom edit — acceptable *for a
named app*, never as the design, and the benchmark row must record which mode was used.

## 6. Signals

DD-010 lists the four signals the valve captures. Three change meaning; one changes identity.

| Signal | Tomcat today | native + reactive |
|---|---|---|
| **Latency** | valve self-times the call | filter start → `addEndHandler`; **differently scoped**, see §6.5 |
| **5xx / crash** | response status | **gated on `ar.succeeded()`**, then the status code — see below. Reading `getStatusCode()` alone is wrong here |
| **Heap delta** | `Runtime` delta under `ITERATION_LOCK` | §6.1 — the lock is impossible; isolation weakens and must be *measured* |
| **Thread leak** | non-daemon thread diff | **§6.3** — structurally always zero on a fixed event-loop pool; replaced, and **explicitly unpublished** (§7.3) |
| **Coverage** | JaCoCo tcpserver `-javaagent` | §6.4 — offline JaCoCo, served by our own route |

**The 5xx/crash signal must be gated on `ar.succeeded()`, and disconnect is its own disposition.**
S3 measured a client disconnect reporting a clean `200`:

```
[PROBE] path=/slow status=200 succeeded=false cause=io.vertx.core.http.HttpClosedException: Connection was closed ms=1006
```

`200` is Vert.x's default on an `HttpServerResponse` that was never written to the wire — the client
received nothing at all. A crash signal that reads `getStatusCode()` at the end handler, as an earlier
draft of this table specified, would therefore have **counted a request that delivered nothing as a
clean success**. That is DD-040's defect shape (a number meaning "not measured" presented as "fine")
arriving by a new route, in the one signal whose whole job is to notice failure. So the disposition is
decided in this order, and only in this order:

| `ar.succeeded()` | Status | Disposition | Counts toward |
|---|---|---|---|
| `false` | *(meaningless — do not read)* | `disconnected` | neither success nor 5xx; its own counter, reported like the taint rate |
| `true` | 5xx | `failed` | the crash counter |
| `true` | anything else | `completed` | success |

`disconnected` is a **third** disposition, not a flavour of either other one: the request was neither
served cleanly nor demonstrably broken by the app, and collapsing it into either column manufactures a
number. §6.5 already excludes it from the latency distribution; this excludes it from the crash count
for the same reason. Evidence: `bench-results/dd043-spikes-2026-07-24/s3-boundary/probe.log`,
`curl.txt`.

**Scope of that evidence: S3 ran in JVM mode only** — `s3-boundary/findings.md:5-6` ("JVM mode only,
per Task 2's scope — no native build was run") and `:162-163` ("native-mode behavior of
`addEndHandler` is Task 3's question, not answered here"). The disposition table above is therefore
**measured on the JVM and assumed on native.** Do not read it as settled under AOT: S1 is this
branch's own proof that a JVM-mode result need not transfer — the identical reflective read worked in
JVM mode and was stripped by `native-image`. The specific things that could differ under SubstrateVM
are whether `addEndHandler` fires at all on native close detection, and whether `ar.cause()` is the
same `HttpClosedException` type. §7.2's two native cells must re-run S3's four dispositions before any
native row publishes a crash count.

**All invariants on this path are soft by structure.** `Invariants.evaluateAndMaybeFail` throwing at
the end handler can fail nothing — the response is fully written by definition of the hook. Tomcat
targets default to **hard** (`basquin.invariant.mode`, `basquin-core/src/main/java/agent/Invariants.java:116`). That is a real
semantic difference from the existing rows and belongs in the per-target notes on the benchmark page,
or §2's comparability claim quietly overstates.

### 6.1 Heap delta — driver serialization is politeness, not exclusivity; taint what it cannot exclude

Explore already runs one iteration at a time, so if the driver holds concurrency 1 and waits for each
response, *driver* traffic is serialized and the whole-heap start→end delta is nominally attributable
with no in-app lock.

**But driver-side serialization serializes only the driver**, and on Tomcat that was sufficient for a
reason this spec must not lose: a health probe **takes `ITERATION_LOCK` too**, so it *cannot* overlap
a driver iteration. Removing the lock removes that exclusion. What now lands inside a measurement
window:

- **Health probes.** DD-040 measured the kubelet readiness probe alone violating `heapDelta` ~12/min
  on JSPWiki at idle. The super-heroes compose files and any k8s deployment both ship health checks.
- **Post-response and periodic app work** — Hibernate Reactive / vertx-sql-client pool maintenance,
  Netty housekeeping, and any fire-and-forget `Uni` continuation started before responding.
  **Response-end does not imply work-end on a reactive stack; that is the defining property of one.**
- **Native-specific noise** — runtime-init class initialization on first touch, so first-request
  windows carry one-off spikes. (JIT noise is gone, which helps; Serial GC heap resizing still moves
  `totalMemory`.)

So "today's semantics survive" is withdrawn. Today's semantics are *lock-enforced exclusivity*; this
is *client-side politeness*. The fix is to make the weakening **observable and disqualifying**:

- The filter maintains an **in-flight counter**. Any window during which the counter exceeded 1, or
  during which a non-driver request started or ended, marks that iteration's sample
  **tainted → `UNMEASURED`** — DD-040 item 6's existing category — never a number.
- **The counter is process-global, and its decrement runs on every disposition.** Stated because both
  ways of getting it wrong produce a permanent, silent `0%`: a counter stashed on the `RoutingContext`
  (§4.4 step 1) is per-request and can never exceed 1, and a decrement placed anywhere other than
  `addEndHandler` misses the `disconnected` path (§6) and leaks the count upward instead. The
  `RoutingContext` carries the *id and start time*; the counter does not ride it.
- The **taint rate** is reported in the run summary exactly as `reportMisses` is; a majority-tainted
  run fails loudly, following `failOnMissMajority`.
- Local 2×2 runs disable compose healthchecks and say so in the bench manifest; cluster runs accept
  the taint rate as data.

This converts a silent attribution error into a measured limitation — **but only if the counter can
actually fire.** A taint rate of `0%` is the exact shape DD-040 exists to prevent: indistinguishable
from "checked and clean" while meaning "never detected". So taint is not a self-evidencing number, and
§7.3 carries a control that forces it positive; without that control passing, the taint rate is not
published and neither is the heap column that rests on it.

**A negative delta is a third failure the in-flight counter cannot see — measured on a real app.**
PR-2's `rest-villains` acceptance polled `47,-16456,0|0||`: a claimed heap delta of **−16,456 KB** for a
single Hibernate/Postgres GET (`bench-results/dd043-pr2-restvillains-2026-07-26/curl-transcript.txt`).
A request does not free 16 MB; that is a GC cycle inside the measurement window — the phenomenon behind
the standing `heapDriftKb` debt (+381 MB one run, −194 MB another, deliberately never published).

The taint rule does **not** catch it. The in-flight counter detects *overlapping requests*, and a GC is
not a request, so the window looks clean by that test while the number is meaningless. Left as is, PR-5
could implement taint and `UNMEASURED` in full and this figure would still be published.

**The sign is itself a disposition signal, and it is free.** A negative per-request heap delta is
*definitionally* unattributable: allocation cannot be negative, so the window contained a collection.
Record it `UNMEASURED`, never as a number — and unlike the overlap case it needs no counter and no GC
introspection to detect. Whatever PR-5 builds for `UNMEASURED` must cover **four** producers — overlap, sub-quantum, negative, and the
GC-contaminated positive case described immediately below, which subsumes the third.

**A fourth producer, and it subsumes the third.** Raised in PR-2's review: the sign argument only works
in one direction. A window where a GC reclaims 2 MB while the request allocates 3 MB nets to **+1 MB** —
above the quantum, positive, no overlapping request — so all three checks pass and the figure is
published as a clean measurement. It is exactly as GC-contaminated as the −16,456 KB case, and *nothing
currently names it*.

The general detector is not the sign but **whether a collection ran at all**: sample
`java.lang.management.GarbageCollectorMXBean.getCollectionCount()` at boundary entry and at
`addEndHandler`, and disposition `UNMEASURED` if it moved. That subsumes the negative rule (a negative
delta is just the case where the GC reclaimed more than the request allocated) and catches the
positive-but-contaminated case the sign rule cannot see.

Two caveats before PR-5 builds it. It is `java.lang.management`, **not** `com.sun.management` — so it
avoids the exclusion §6.2 records for `getThreadAllocatedBytes`, and the extension already uses
`java.lang.management.ThreadMXBean` — but **whether it works under SubstrateVM is unverified** and is a
PR-5 precondition, not an assumption. And a collection-count check makes the heap invariant strictly
*more* conservative: on a busy target many windows will contain a GC and go `UNMEASURED`, which is
honest but may leave few measurable samples. That trade — fewer numbers, all of them real — is the one
this project has consistently chosen.

**A fifth candidate, unconfirmed and design-shaping.** A response that never triggers `addEndHandler`
at all — a protocol upgrade, an indefinitely streaming response, a connection that never sees FIN/RST.
§6.1's counter model assumes the decrement runs on every disposition; a request class where it never
fires would leave the counter permanently elevated and taint **every subsequent measurement**. Not
reachable on `rest-villains`' plain REST routes, and PR-2 implements no counter, so it cannot manifest
yet — but PR-5 should test it deliberately (a WebSocket upgrade route) rather than meet it on a real
target.

Also observed on the same run: `6,477,4|0||` — a **thread delta of 4** on a reactive app, which is
Vert.x growing its worker pool rather than the request leaking threads. §6.3 already replaces thread
leak as an invariant for this target class, and this is a concrete reason why: the figure is real but
not attributable to the request.

#### The instrument is quantized, not continuous — state the floor

Everything above treats the heap reading as a continuous number that noise perturbs. It is not.
**`Runtime.freeMemory()` under SubstrateVM quantizes at 524,288 bytes (512 KiB).** Every `used` value
S2 observed across a whole run — 30 idle samples, the `/alloc` bracket, 5 quiescence samples, and both
`System.gc()` readings — is an exact multiple of that quantum, with no partial step anywhere
(`bench-results/dd043-spikes-2026-07-24/s2-memory/series.txt`). `totalMemory()` never moved
(`13,416,005,632` on every sample), so those deltas are pure allocation and collection with no resize
artifact confusing them.

The consequence is a hard floor on what this signal can say. A request allocating 100 KB reads as `0`
or as `524,288` — never as its actual cost.

- **Instrument resolution: 524,288 B (512 KiB).** No per-request heap threshold below this is
  meaningful, on any target, at any tolerance.
- **Practical minimum per-request delta: ~1,048,576 B (1 MiB) — two quanta.** One quantum is not
  enough, because idle drift can contribute a quantum step *inside* a measurement window; a threshold
  has to survive that coincidence, not just the instrument's resolution.
- **A per-request delta smaller than the practical minimum is `UNMEASURED`, never a number.** This is
  the same disposition tainted windows get, for the same reason — and, like taint, it gets a §7.3
  control that makes it *fire*, driven by a deliberately sub-quantum allocation. A disposition that has
  only ever been asserted in the negative is not known to exist.

What clears the floor comfortably does work: S2's `/alloc` produced a delta of `4,718,592` B — 9× the
largest single idle step and 3× the idle series' whole cumulative drift. The invariant is viable for
allocations well above the quantum and unvalidated below it; that is a scope, not a hedge.

Idle drift is real but must not be quoted as a rate. S2 observed **three** discrete 512 KiB steps over
a ~29 s idle window in a single run (`used` rising `4,194,304 → 5,767,168` = `1,572,864` B). The
`≈3.10 MiB/min` figure in that spike's findings is an arithmetic extrapolation from **n=3 events in
one run** of a step function — usable as an order of magnitude for sizing thresholds, not as a
measured slope, and not to be printed as one.

**`System.gc()` works under SubstrateVM, so use it.** S2 measured a real collection:
`10,485,760 → 3,670,016` B, a 65% reduction that landed *below* the pre-`/alloc` idle floor, meaning
it reclaimed accumulated idle drift as well as the deliberate allocation. DD-002's
`basquin.heap.gcBeforeMeasure` (`agent/Agent.java:96,126`) is therefore not merely portable to native
— it is the cheapest available mitigation and it attacks the floor directly, by clearing the drift
that forces the second quantum. **Recommendation: enable `gcBeforeMeasure` on native targets by
default**, and record in the bench manifest whether it was on, since it changes what the minimum
detectable delta means. Collecting *after* the window as well as before is a plausible further
reduction; untested, so not specified.

### 6.2 The JFR cross-check, redefined so it can actually fail

**Nothing in this section is verified on this toolchain, and it is the only section of which that is
true.** `jdk.ObjectAllocationSample` is *documented* as supported in native-image JFR (Serial GC), and
native JFR event streaming is what the §7.3 control below assumes as its transport. Phase 0 ran **no
JFR analysis**: S2 established only that `-Dquarkus.native.monitoring=jfr,nmt` is accepted at config
parse and survives a full native compile (§5) — that is evidence about a *build flag*, not about
whether a `RecordingStream` can be opened inside the image or whether `ObjectAllocationSample` is
emitted there. An earlier draft of this paragraph said "verified" with no artifact behind it, in the
same document whose ledger called §6.2 unverified; that is precisely the failure this PR's own
`bench-results/dd043-target-pins-2026-07-24/README.md:3-8` records the approver rejecting once already.

**What would verify it:** on the pinned Mandrel 25.0.3 image, open a `RecordingStream` in-process,
enable `jdk.ObjectAllocationSample`, drive `/__basquin/control/defect/alloc`, and show non-zero sampled
bytes attributed to that route — i.e. §7.3's JFR row, run and passing, with the recording committed as
an artifact. Until that exists §6.2 produces **no published figure**, and §7.3's row already prescribes
the fallback: demote the cross-check to diagnostic-only. **This is a PR-5 entry gate (§9), not a PR-5
assumption.**

The design point below stands independently of that, because it is a statement about what the two
quantities *mean* rather than about availability. The earlier draft compared `ObjectAllocationSample`
against net heap delta and called divergence a finding. Those two quantities **never agree**: `ObjectAllocationSample` is a *throttled
statistical sampler* estimating **gross** allocation (a request allocating tens of KB may emit zero
samples), while `totalMemory - freeMemory` is **net** — allocation minus collection plus resize
artifacts. Without a stated comparator that test is unfalsifiable: it either always "diverges" and is
ignored, or gets a tolerance wide enough never to fire. Both are DD-040 shapes.

DD-004 already ruled on this: *"JFR allocation sampling … is statistical; if adopted later it belongs
behind soft signals only."* That ruling stands and this spec now respects it.

**Redefined:** aggregate `ObjectAllocationSample` over the **whole run** (or per-route over many
iterations) and compare **per-route rankings**, not per-request magnitudes. Native JFR streaming
events carry no stack traces, so a divergence cannot be localised further than a route.

**The exact cross-check is expected to live in the JVM-mode cells.**
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on the event-loop thread is exact and
per-thread. The premise that it is *unavailable* on native — that SubstrateVM does not implement the
`com.sun.management` extensions — is **uncited and unspiked**, and it is what produces the whole
**JVM cells = exact cross-check, native cells = statistical** split. It is therefore a hypothesis in
the same state as everything else in §6.2, and it is cheap to settle: a single call to
`ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean` in the native image
resolves it, and it can ride the same PR-5 entry gate. If the extension *is* available on native, the
split collapses and native gets the exact cross-check too — a better outcome the spec should not
foreclose by asserting the negative.

### 6.3 Event-loop blocking: an extension-owned watchdog, not the log-only checker

Blocking the event loop *is* the signature reactive availability defect, so it is the headline
invariant. But Vert.x's `BlockedThreadChecker` **only emits a WARN log line** — no callback, no metric,
no event-bus message, no API to subscribe to. An earlier draft made it the flagship signal and never
said how the signal reaches the driver. This repo has rejected log scraping **twice** (DD-019: couples
the driver to log access and formatting; DD-040: rejected re-scoring from pod logs). An invariant whose
only transport is an undesigned log path renders as a clean zero column until someone notices nothing
could ever have arrived — the exact defect this spec warns about one paragraph earlier and then
committed.

**Mechanism: the extension owns a watchdog.** A sampler thread periodically schedules a no-op on each
event loop via `Context.runOnContext` and measures **scheduling delay**. Deterministic, threshold ours,
native-safe, attributable to the in-flight request window under concurrency-1, and consistent with
DD-004's preference for exact signals over inferred ones. Violations go to the result store like any
other.

Quarkus's `max-event-loop-execute-time` checker is retained as **corroboration in the logs**, never as
the mechanism.

Secondary reactive signals: **worker-pool saturation**, **Hibernate Reactive connection-pool pending
acquisitions**, and **file-descriptor count** from `/proc/self/fd` (cheap, native-safe).

**Consequence:** the reactive invariant set differs *in kind*, not merely in measurement. The benchmark
page must say so rather than print a thread-leak column of zeros.

### 6.4 Coverage

Native mode has no runtime coverage agent: Quarkus documents coverage as unsupported in native mode,
and `quarkus-jacoco` requires a jar artifact, explicitly not a native binary. The working path is
**offline instrumentation** between compile and `native-image`, so the JaCoCo runtime lives in the
image as ordinary code. Our extension serves the execution data on `/__basquin/coverage`;
`JacocoCoverageProvider` (DD-012/DD-023) changes **transport only** — tcpserver becomes HTTP — and
union-merge across replicas keeps working.

Two mechanics the injector must respect, because they are how this silently produces wrong numbers:

- The driver's `Analyzer` must run against the **original, pre-instrumentation classes** (offline
  instrumentation embeds the pre-instrumentation class id). The injector must therefore preserve them
  — JaCoCo's own backup directory, `target/generated-classes/jacoco`.
- `/__basquin/coverage` reads `RuntimeData` **directly**, not via the `jacoco-agent.properties`
  agent-boot path (shutdown hooks, file output), which may not survive native.

#### The read must be a direct, compile-time-typed call. Never reflection.

```java
IAgent agent = RT.getAgent();          // org.jacoco.agent.rt.RT — public API
byte[] data = agent.getExecutionData(false);
```

`RT` and `IAgent` are ordinary public compiled types in `org.jacoco.agent:runtime`, which the injector
already adds as a compile-scope dependency. Ordinary virtual dispatch is visible to native-image's
closed-world analysis like any other call, so it needs no `@RegisterForReflection` and no
`reflect-config.json`.

**A reflective read does not survive AOT.** An earlier draft of this section specified
`Class.forName(...).getMethod(...).invoke(...)`. S1 measured that form failing deterministically in
native — three requests, three HTTP 500s, the identical exception each time:

```
java.lang.NoSuchMethodException: org.jacoco.agent.rt.internal_bac9136.Agent.getExecutionData(boolean)
```

Native-image's default reflection policy strips it. The failure is specific to the reflective path and
not to JaCoCo under AOT: `javap` confirms the method exists in the pinned 0.8.15 jar, the class-init
report confirms the holding class is reachable and build-time-initialized in the image, and the
identical code works in JVM mode on the same toolchain. S1b then built the direct-call form and got
real execution data (`01 C0 C0 10` magic header) at all three dump points. Evidence:
`bench-results/dd043-spikes-2026-07-24/s1-coverage/` (`app.log:8,31,54`, `analysis.txt:5,19,33`,
`s1b-t{0,1,2}-*.exec`, `s1b-analysis.txt`).

**The direct call also decouples the design from JaCoCo's shaded package name.** The registration key
a reflective path would need — `org.jacoco.agent.rt.internal_bac9136.Agent` — carries a hash that
changes between JaCoCo versions. Registering it would pin this spec to one JaCoCo release and break
silently on upgrade, with the same `NoSuchMethodException` and no compile-time warning. The typed call
against the public `IAgent` interface has no such coupling: a version bump that moved the shaded
package would still compile and still link. This is a reason to prefer the direct call *independently*
of whether reflection could be made to work.

#### The native coverage denominator is not the JVM's, and the two percentages are not comparable

`jacoco-cli` analyzes against `target/generated-classes/jacoco` — the **pre-native preserved
classfiles**, produced before `native-image` runs and unaffected by what its reachability analysis
later discards. So code the closed-world analysis proves dead is **deleted from the image while
remaining in the denominator**: it counts against the percentage and is structurally incapable of ever
reading covered.

S1b measured this directly. `NeverCalled` was eliminated from the image entirely (zero occurrences in
the class-initialization report, zero in `strings` on the binary) yet still contributes `11` of the
`194` total instructions the report counts across the fixture's five classes. That is not a fixture
artifact — the same build reports `11,223 types ... found reachable` against a far larger compiled
universe (`s1-coverage/s1b-build-native.log:70`), and how much weight is eliminated varies per build.

Two consequences, both binding:

1. **Native's achievable coverage ceiling is capped below 100% for reasons unrelated to test
   thoroughness.** An 85% native reading is not evidence of "15% under-tested" the way an 85% JVM
   reading would be.
2. **A native coverage percentage is not like-for-like comparable with a JVM one from the same source
   tree.** Same formula, same denominator source, different achievable maximum.

Therefore: coverage-guided stopping rules and any published threshold must be **within one mode**
(native run N+1 covers more than native run N), never cross-mode. §7.4 carries the reporting-side
obligation.

### 6.5 Latency's population changed, not just its accuracy

`addEndHandler` fires when the response is fully written **to the wire**, so the reading now includes
client drain and TCP backpressure — a number the Tomcat rows never included (the valve exits when the
app returns; the connector flushes afterwards). Small for a same-host driver, but a definitional
change: "strictly better" is withdrawn in favour of *"differently scoped — includes write-out,
excludes nothing the valve measured."*

And on a **failed** `AsyncResult` (client disconnect), elapsed-until-abort is not a latency sample at
all. The boundary keeps `disconnected` samples out of the latency distribution by not publishing them,
or a flaky driver connection manufactures latency findings.

## 7. Validation

### 7.1 Phase 0 — spikes, on the Quarkus `todo` quickstart, before any real target

| | Question | Failure signatures |
|---|---|---|
| **S1** | Does offline JaCoCo produce *correct* coverage under AOT? | **(0) the read path itself fails under AOT** — the one that actually fired, see below; (i) frozen probes; (ii) **inflated baseline from build-time init**; (iii) augmentation/class-id mismatch |
| **S2** | Does `Runtime.totalMemory()/freeMemory()` behave under SubstrateVM's Serial GC; does `System.gc()`; does post-response work quiesce on Hibernate Reactive; does `quarkus.native.monitoring` accept `nmt`? | heap deltas that are GC noise, like the `heapDriftKb` debt already on the books |
| **S3** | Does `addEndHandler` fire on errors, 3xx, and client disconnects, and does `addHeadersEndHandler` survive response rewrites? | the requests we most care about are silently skipped |
| **S4** | Does Quarkus **augmentation** honour the injected dependency — i.e. does the banner list `basquin`, JVM **and** native, through the containerised build? | a successful build producing a silently uninstrumented binary |

**S1's failure signatures, rewritten from what the spike actually found.** Two successive drafts of
this paragraph named the wrong failure. The corrected set:

**(0) The read path fails before any coverage question is reachable — this is what fired.** A
reflective `RuntimeData` read is stripped by native-image's default reflection policy, so `/coverage`
returns HTTP 500 and the `.exec` files are error bodies that `jacoco-cli` rejects outright. None of
(i)–(iii) is testable when this happens: there is no number to be wrong, only no number. §6.4 now
specifies the direct typed call precisely so this signature cannot recur, and any future re-spike must
check it **first**, because it masks everything below it.

**(ii)'s instrument cannot be an unreferenced class.** Quarkus registers application classes for
build-time initialization by default, so during `native-image` the instrumented `<clinit>` runs,
`$jacocoInit` executes, and both the probe arrays and JaCoCo's `RuntimeData` are captured into the
image heap. Image-heap objects are **writable at runtime**, so the arrays do not freeze — the defect
this signature hunts is **pollution**: probes executed during image build reading as covered forever,
inflating the baseline. §7.3's "coverage must increase" control does **not** catch it — coverage
increases fine from an inflated floor, and the headline percentage is simply wrong.

But an earlier draft's instrument for this — a `NeverCalled` class referenced from nowhere — **cannot
work on native.** Closed-world reachability analysis deletes it from the image entirely: S1 found zero
occurrences in the class-initialization report and zero in `strings` on the binary, despite the class
being present in the source jar `native-image` consumed. Its `0 covered` reading is then a *structural
absence* — `jacoco-cli` finding no record and defaulting the whole class to missed — which is
indistinguishable from the pollution-free result the signature is trying to prove, and therefore
proves nothing.

**The working instrument is a registered-but-never-called JAX-RS route, read at method level.** JAX-RS
registration keeps it reachable, so it survives into the image and gets a real invoker class; never
invoking it means its probes must read zero. The discriminating evidence is that its zero persists
*against a live probe record* — S1b's `Probe.unused()` held at `2 missed, 0 covered` at t1 and t2 while
sibling methods in the same class, backed by the same execution-data record, flipped to covered as
their routes were hit. That is a live zero, and it is what refuted the pollution hypothesis. Evidence:
`bench-results/dd043-spikes-2026-07-24/s1-coverage/s1b-t{0,1,2}-after-*.xml`, `s1b-app.log`.

#### Where that instrument lives on an unmodified target — the §1.1-compatible form

In S1b the never-called route was `Probe.unused()`, **added to the fixture's own source**. On a real
target that is forbidden: §1.1 says no file in the application's source tree is created or modified,
and §7.3 exists precisely to avoid that trade. Nor can the route move into the extension: §6.4 has
`jacoco-cli` analyzing against the **application's** preserved classfiles
(`target/generated-classes/jacoco`), so a route living in the `basquin-quarkus` jar is not in the
denominator the coverage percentage is computed over and cannot police it. Left unresolved, PR-4 either
plants a route in `rest-heroes` and breaks the thesis, or stalls with no instrument. So the spec
decides it here.

**Decision: the instrument is a *withheld application route*, pre-registered before the run.** The
2×2's targets (§3 rows 1–4) are contract-first — the route set is enumerated at build time from
`src/main/resources/openapi/openapi.yml`, which is also the driver's seed corpus. (An earlier draft
said "every target in §3"; row 5's substitutes (§8.1) carry no such guarantee, so row 5's withheld
route must come from whatever enumerable route surface its target provides, named in the bench
manifest the same way — and if the target has none, that row's coverage control needs its own design
before the row publishes.) So the operator can
name, **in the bench manifest and before the control run starts**, one route the driver is forbidden to
send, chosen from a resource class whose *other* routes the driver will exercise. That method is then
exactly S1b's instrument, with none of S1b's source edit: it is application code, already in the
denominator by construction, kept live in the image by the app's own JAX-RS registration, and never
invoked.

Three obligations come with it, and each one is what stops the control degenerating into the
disproven class-level form:

1. **Pre-registration, not post-hoc selection.** The withheld route is fixed before the run. Scanning
   the report afterwards for a method that happens to read zero and declaring it the instrument is
   circular — it proves only that some zero exists.
2. **A reachability precondition, checked against committed build output.** The instrument's class must
   be present in the `-H:+PrintClassInitialization` report the native build already emits (S1b's is
   committed as `s1-coverage/s1b-class_initialization_report.csv`; the withheld method's Quarkus
   invoker appears there as `…$quarkusrestinvoker$<method>_<hash>`, which is how S1b showed `unused`
   survived while `NeverCalled` did not). **Absent from that report, the instrument is void** — its
   zero would be `NeverCalled`'s structural absence again — and the control fails rather than passing
   quietly on a class that no longer exists.
3. **The zero must be read against a live record.** The assertion is only meaningful at a dump point
   where a *sibling method of the same class* has already flipped to covered. Before that, the class
   has no execution-data record and every method in it reads zero for a reason that has nothing to do
   with pollution.

Withholding a route is in tension with coverage-guided exploration, whose whole job is to reach
everything, so **the control run is a distinct run from the published explore run** — as §7.3 already
requires for the defect routes, which are enabled by a system property only during Phase-2 control
runs. The coverage figure that gets published comes from the unrestricted run; the control run exists
to prove the number that run produces was measured rather than defaulted. If the two must be the same
run, the withheld route's instructions are excluded from the published denominator and the bench
manifest says which route and how many.

**A t0-baseline variant was considered and rejected as the primary instrument.** The proposal: skip the
instrument entirely, dump coverage at `t0` before any request, and require it to be confined to the
"genuine startup set" derived from the class-initialization report — pollution then shows up as covered
instructions in a class with no reason to run at startup. It plants nothing, which is genuinely
attractive, but the report cannot carry the weight: it records *initialization kind*, not *startup
execution*, and in S1b's own build **11,912 of 12,094 rows are `BUILD_TIME`** against 183 `RUN_TIME`.
Build-time initialization is the *precondition for* the pollution being hunted, not a discriminator
against it — a set containing 98.5% of the image cannot define "had no reason to execute at startup",
and that judgement would fall to a human rather than to an assertion. It also cannot see the dangerous
case: pollution inside a class that legitimately does run at startup is invisible to a whole-class
confinement check, whereas the method-level sibling-flip test catches it. **Retained as a diagnostic**
— an unexpectedly large t0 covered count is worth investigating — and the report's real contribution is
kept as obligation 2 above, where it does discriminate: it is what separates a live zero from a deleted
class.

S1 must also record which classes Quarkus shifted to runtime init (S1 found 183, none in the
application or JaCoCo packages), since that sets the expected floor.

S1–S4 share no state and are **driven by concurrent subagents — but they do not compile
concurrently.** S1, S2 and S4 each require a native build of the fixture, and §7.2's mutex applies
here in full: `native-image` wants ≥4 cores and several GB, this host has 8 cores / 15 GB, and three
concurrent builds will exhaust it. Preparation, driving and analysis overlap freely; `native-image`
invocations are serialized. (Stated inline rather than by reference, because §7.1 read on its own
would otherwise invite exactly the failure it warns about.)

**Phase 0 gates the rest of this spec.** If **S1** fails, §6.4 is void and coverage-guided exploration
on native needs redesigning — which reopens the full-parity goal in §2, since coverage is what forces
the compile-in step. If **S4** fails, §5's no-source-modification property cannot be met by the
described mechanism and §5.1's degradation becomes the norm. Either outcome returns here before any
implementation proceeds.

**Phase 0 ran on 2026-07-24 and the gate PASSED. Neither voiding outcome occurred.** S1 was REFUTED as
specified — the reflective read path does not survive AOT — but CONFIRMED via S1b once the read was
corrected to a direct typed call, so **§6.4 is amended, not void**, and §2's full-parity goal does not
reopen. S4 was CONFIRMED in JVM and native, so **§5's dependency injection stands** and §5.1's
degradation remains a contingency rather than the norm — but S4 injected only a `Dependency`, so §5's
**plugin-execution** injection passed no gate at all and is carried as §8.2 (its repository injection,
added to §5 after Phase 0, was later measured by spike S5). Full verdicts, evidence and the
amendment list: `bench-results/dd043-spikes-2026-07-24/REPORT.md`.

**Two Phase-0 scopes are carried forward as obligations rather than results**, because a spike that did
not ask a question is not a spike that answered it: S3 ran in **JVM mode only** (§6, §7.2), and S4
never injected a plugin execution (§8.2).

### 7.2 Phase 1 — the 2×2

|  | JVM mode | Native |
|---|---|---|
| **`rest-villains`** (blocking) | is the extension itself correct? | does build-time attachment survive AOT? |
| **`rest-heroes`** (reactive) | are the reactive boundary semantics right? | ← the actual target |

**Both native cells carry an S3 re-check as an entry condition.** S3 measured §4.3's hooks and §6's
disposition table in **JVM mode only**, and S1 is this branch's proof that a JVM-mode result need not
survive AOT. So before a native cell publishes any crash count or latency distribution, it must
reproduce S3's four dispositions under SubstrateVM — `/ok` 200, an app 500, a 3xx, and a mid-response
client disconnect — and confirm that `addEndHandler` fires on each, that `ar.succeeded()` is `false`
on the disconnect. **Do not gate on `addHeadersEndHandler`'s `X-Basquin-Req` reaching the client** — an
earlier draft did, and that criterion can never pass: the extension never writes that header (§4.3),
only S3's probe fixture did. Gating a native cell on it would block PR-4 on an impossible check. If `addEndHandler` does not fire on native close detection, the boundary records nothing for that
disposition and the crash counter silently reverts to the `getStatusCode()`-reads-200 behaviour
amendment 3 exists to prevent. The check is cheap — the extension's own control routes (§7.3) already
provide three of the four dispositions.

Each cell isolates one variable from its neighbours. The four builds are logically independent and
driven by concurrent subagents, but **`native-image` wants ≥4 cores and several GB each and this host
has 8 cores / 15 GB**: native builds are serialized on a mutex; only the two JVM-mode builds truly run
in parallel. Subagents may prepare, drive and analyse concurrently — not compile concurrently. No
benchmark campaign runs while any native build is in flight.

### 7.3 Phase 2 — negative controls that do not break the thesis

DD-040's rule: a reported zero means "checked and clean", never "never measured". Every invariant ships
with a negative control proving it can fire.

**Where the controls live matters, and the obvious placement is a thesis violation.** Planting a slow
route in `rest-heroes` modifies the app's source — breaking §1.1 and invalidating the row's
unmodified-app claim. Running controls only on the Phase-0 `todo` quickstart proves the invariants fire
in a *different app on a different stack cell* than the ones published.

**Resolution: negative-control defect routes ship in the extension's own runtime** —
`/__basquin/control/defect/{slow,alloc,error5xx,block-loop}`, with `alloc` taking a size parameter so it
can be driven both far above and deliberately below §6.1's quantum — disabled by default, enabled by a
system property only during Phase-2 control runs. The extension is injected tooling, not app source, so
the thesis holds; the controls run in the *same* process and stack cell as the published rows; and they
are reusable for every future Quarkus target.

**The one control that cannot ship in the extension is coverage** (§7.1), because §6.4 computes the
percentage over the *application's* preserved classfiles and an extension-owned route is not in that
denominator. Its instrument is a **withheld application route**, pre-registered in the bench manifest
before the run — app code, already in the denominator, kept alive by the app's own JAX-RS registration,
and never sent. §1.1 holds because nothing is planted; §7.1 states the three obligations that keep the
zero live rather than structural.

| Invariant | Control | Assertion |
|---|---|---|
| latency | `/__basquin/control/defect/slow` | violation **arrives in the driver-visible result** |
| **5xx / crash** | `/__basquin/control/defect/error5xx` returning a 500 | the crash is counted **and** attributed to the right iteration id — a boundary that skips error paths (§6.3, S3) would zero it silently |
| **5xx / crash, negative half** | a **client disconnect** mid-response (driver aborts before the body is written) | the crash counter does **not** increment, and the iteration is recorded as `disconnected` — S3 measured `getStatusCode()` returning `200` on exactly this disposition, so a control that only proves the counter *can* fire leaves the counter free to fire wrongly (§6) |
| event-loop blocking | `/__basquin/control/defect/block-loop`, sleeping **comfortably above the pinned threshold** | store entry → finding → rendered row |
| heap | `/__basquin/control/defect/alloc`, sized well above the quantum | delta recorded **and not tainted** (§6.1) |
| **heap, taint — the firing half** | a **deliberately overlapping request**: a second connection sent against any route while `/__basquin/control/defect/slow` is in flight on the driver's connection | the in-flight counter must exceed 1; the overlapped iteration must be **counted as tainted** and dispositioned `UNMEASURED`; and the run summary's **taint rate must come back strictly greater than zero**. A run that reports `0%` with the overlap injected **fails** — the counter is per-request, or its decrement is unreachable, and `0%` then means "never detected", not "clean" (§6.1) |
| **heap, `UNMEASURED` — the firing half** | `/__basquin/control/defect/alloc` sized **below one quantum** (≪ 524,288 B) | the sample must be recorded as `UNMEASURED` and **must not** appear as a number anywhere downstream of the boundary. A numeric delta here **fails** the control: the instrument cannot resolve that allocation, so any number it prints is manufactured (§6.1) |
| heap, **positive-noise** | idle window, no driver request | the sample must be recorded — as exactly `0` or as `UNMEASURED` — and **any reading at or above §6.1's practical minimum (1,048,576 B) with no request in flight fails the control.** Stated as a threshold rather than "~zero or `UNMEASURED`", which passed on either branch and so could not fail. This is the control that catches probe pollution and the `heapDriftKb` class of error |
| coverage | routes exercised progressively, with **one pre-registered application route withheld** from the driver (§7.1) | the total must increase **and** the withheld route's *method* must read `0 covered` **against a live execution-data record for its own class** — i.e. at a dump point where at least one sibling method of that class has already flipped to covered. Two ways to fail rather than pass vacuously: if no sibling has flipped, the class has no record and the zero measures nothing; if the withheld method's invoker class is absent from the build's `-H:+PrintClassInitialization` report, reachability analysis deleted it and the zero is a structural absence (§7.1). **A never-exercised *class* reading zero is not this control** — amendment 8 disproved that instrument, and using it would admit the coverage percentage on a measurement that never happened |
| **JFR cross-check** (§6.2) | `/__basquin/control/defect/alloc` driven across a run alongside ordinary routes | first, the transport must exist at all: a `RecordingStream` opens in the native image and `jdk.ObjectAllocationSample` events arrive (§6.2 — **unverified**, so this is a precondition, not an assumption). Then the alloc route must rank **first** by aggregated sample totals. If either half cannot be made to hold, the cross-check is demoted to **diagnostic-only, not published**, and §6.2 says so |

**This table is exhaustive over §6 by construction, and that property is load-bearing.** Three signals were
found silently exempt from it in successive reviews — the JFR cross-check, then 5xx/crash, then **thread
leak** — which is the same defect arriving three times by the same route: a signal named in §6 but never
enumerated here reads as a clean column forever, and nothing in the process catches it.

So the rule is stated as a closed set rather than a habit: **every signal named in §6 appears in the table
above, or is listed in the paragraph below as explicitly unpublished.** Adding a signal to §6 without doing
one or the other is a spec defect, not an oversight. The rule now covers **dispositions as well as
signals**: `disconnected`, `tainted` and `UNMEASURED` are each reported figures (§6, §6.1, §7.4), so each
needs a row that makes it *fire*, not only a row that asserts it stayed absent. An assertion that a counter
did not increment is compatible with a counter that cannot increment.

**Explicitly unpublished (no control, therefore no published number):**

- **Thread leak** (§6, §6.3). On an event-loop stack the non-daemon thread count is a fixed pool, so a
  per-request diff is **structurally always zero** — the column could never fire, and printing it would
  be the purest form of the defect this section exists to prevent. §6.3 replaces it *in kind* with the
  event-loop-blocking watchdog, which has its own control row above; §7.4 carries the rendering
  obligation (no thread-leak column on reactive targets). Listed here because §6 names it and the closed
  set admits no third option — it was previously in neither the table nor this paragraph, and §6's own
  cross-reference pointed at §6.2, which never mentions it.
- **§6.3's secondary reactive signals** — worker-pool saturation, Hibernate Reactive connection-pool
  pending acquisitions, and file-descriptor count. They are sketched, not designed; none has a defined
  threshold or a transport to the result store.

All of the above may be collected as diagnostics and **must not** appear as an invariant column on the
benchmark page until each earns a control row above. Promoting one is a spec change, not an implementation
detail.

**Control status as of PR-2 (2026-07-25) — two rows cannot be claimed, and one has already failed.**

- **`UNMEASURED` (sub-quantum heap): FAILED, measured.** Driving
  `/__basquin/control/defect/alloc?bytes=100` — far below §6.1's 524,288 B quantum — the polled result is
  `0,0,0|0||` on every trial (`bench-results/dd043-pr2-controls-2026-07-25/03-alloc-below-quantum.txt`).
  That is a plain numeric zero, not an `UNMEASURED` disposition. By this table's own rule a number here
  fails the control, because the instrument cannot resolve that allocation and any figure it prints is
  manufactured. **The heap invariant is therefore not publishable**, and this is not a bug in the control
  route — the 4 MiB case proves the route honours `bytes` faithfully. The gap is that neither the
  `UNMEASURED` disposition nor §6.1's in-flight taint exists below the boundary: `ResultStore.Entry` is
  `(costCsv, invariantCount, detail, leakDetected)` and carries no disposition field at all — the same
  structural gap that makes disconnect *accounting* impossible in §4.4. Both need an `Entry` field.
  **Assigned to PR-5**, which owns the reactive invariant set and the per-target reporting.
- **Event-loop blocking: not claimable yet.** `/__basquin/control/defect/block-loop` plants the defect
  correctly — the response and app log both show a genuine `vert.x-eventloop-thread-N` — but §6.3's
  watchdog, which would *detect* it, is PR-5. A planted defect with no detector is not a passing control.

Every control is verified **end-to-end at the reporting layer** (`render_page.py` input), not at the
log line — otherwise the control validates the logger, not the invariant. An invariant without a
passing control is **not published**, matching the discipline that keeps `heapDriftKb` off the page.

### 7.4 Reporting pipeline

- `deploy/bench/render_page.py` assumes the Tomcat invariant set. It must handle **per-target invariant
  sets** — no thread-leak column for reactive targets (§7.3 lists thread leak as explicitly unpublished
  and §6.3 says why), an event-loop-blocking column instead — plus per-target **invariant mode** (§6:
  soft-by-structure here, hard on Tomcat) and the **taint rate**.
- **The taint rate and the `UNMEASURED` count are only rendered if §7.3's two firing controls passed on
  that run.** They are the figures most exposed to the "zero means never measured" reading, so they
  inherit §7.3's closing rule literally: no passing control, no published number. A run whose control
  pass/fail state is unknown renders them as absent, never as `0%`.
- **Native and JVM coverage percentages must not share a column.** Per §6.4, `jacoco-cli` analyzes
  against the pre-native preserved classfiles, so code the reachability analysis eliminated still
  counts in the native denominator while being incapable of ever reading covered. Native's achievable
  ceiling is capped below 100% for reasons unrelated to test thoroughness, and by an amount that
  varies per build. Rendering the two side by side in one column invites exactly the wrong reading
  ("native is 12 points worse tested"). The page must render them as separate, labelled figures and
  state the reason inline — this is a per-target note the generator emits, not a footnote someone
  remembers to add.
- The **disconnect** disposition (§6) gets its own reported figure alongside the taint rate. It is
  neither a success nor a 5xx, and a target with a high disconnect rate is telling the reader
  something about the measurement, not about the app.
- The **minimum detectable heap delta** (§6.1) is a per-target note on native rows, together with
  whether `gcBeforeMeasure` was enabled. A heap column without it implies a precision the instrument
  does not have.
- Every figure in the native row is derived from the run artifact through that generator. No hand-typed
  numbers.

## 8. Open questions

### 8.1 Does the Apicurio Registry *server* build native? — RESOLVED: NO (2026-07-26)

**On the current (3.x) line there is no native build path for the server at all.** Investigation:
`.superpowers/sdd/dd043-apicurio-native.md` (documentary, against a sparse clone of
`Apicurio/apicurio-registry`; its §4 addendum spot-checked the load-bearing claims against primary
sources). The verified facts:

- Current `main` (`23159df`): `app/pom.xml` contains **zero** occurrences of the string `native`.
  The only native profiles anywhere in the repository are under `cli/`, `examples/` and
  `support-chat/` — none of them the registry server — and no `Dockerfile.native` exists outside
  `examples/`.
- `-DcliSkipNative` is defined in `cli/pom.xml` and governs the **CLI's** native image only — exactly
  the suspicion this section previously recorded, now verified.
- The secondary source was describing the **2.6.x** line, which *did* build the server native:
  `app/pom.xml:590` on that branch carries the `native` profile, `Dockerfile.native` exists there
  (HTTP 200 on `2.6.x`, 404 on `main`), and its CI native jobs ("Build and Test In Memory/SQL native
  images") concluded success on the latest 2.6.x run.

**Consequence: Target 5 as specced cannot be built native.** The credibility requirement (a real
application, not a reference demo) is what matters, not Apicurio specifically — so row 5 takes a
substitute. Ranked by **risk to what row 5 is for** (a real product we can actually instrument), not
by setup cost:

1. **Debezium Server** — Quarkus **3.33.1.1**, the closest to the harness's 3.37.3; native build
   verified in CI on every PR. Cost: a thin HTTP surface (a management API, not a full REST product
   API) — acceptable, because the fuzz/load axes are already carried by heroes/villains and row 5
   buys credibility that a real product can be instrumented natively.
2. **Eclipse Hono, HTTP adapter** — Quarkus **3.27.4.1**, genuinely reactive, and the strongest
   native-CI signal of the three (a dedicated native-image workflow on a 3×/day cron). Cost: the
   heaviest infrastructure — Kafka (or an AMQP network) plus a device registry.
3. **Apicurio Registry 2.6.x** — same product name, maintenance line, CI-green native. **Ranked last
   despite looking cheapest, deliberately:** `basquin-quarkus` is compiled against Quarkus **3.37.3**
   and consumes deployment-module APIs (`FilterBuildItem`, `RouteBuildItem`, `FeatureBuildItem`)
   that are not stable across 22 minor versions, while 2.6.x pins Quarkus **3.15.3**. Whether one
   `basquin-quarkus` build augments a 3.15.3 application is **unmeasured**, and the plausible failure
   is §1.1's relocated one — a build that succeeds and is silently uninstrumented. Without a
   cross-version compatibility spike first, this is not the low-cost option; it is the option whose
   cost is hidden.

**The new gate this exposes — row 5's entry gate, the same shape as §8.2 is for PR-4:** the Quarkus
version range over which one `basquin-quarkus` build augments successfully is **untested**. Heroes
and villains are both pinned at the toolchain's own 3.37.3 (§3.1), so nothing so far has exercised a
version mismatch between the extension and its target; whichever substitute row 5 takes will be the
first to. Settle it the way S4 and S5 settled theirs — a spike on the chosen target's Quarkus
version, checked against §5.2's banner — before row 5 publishes anything.

### 8.2 Does `afterProjectsRead` model mutation reach the *execution plan*, not just resolution?

Unresolved, and the only part of §5 with no evidence behind it. §5 requires the injector to add three
things to each `MavenProject`: the `basquin-quarkus` **dependency**, the **repository** that makes it
resolvable, and the offline-JaCoCo **plugin execution** (§6.4). S4 measured the first
(`InjectProbe.java:26-43` adds a `Dependency` and nothing else) and S5 measured the second, two-level
form (`bench-results/dd043-s5-repo-injection-2026-07-26/`); nothing has ever attempted the third — so
"§5's mechanism holds" is a claim about two thirds of a mechanism.

They are not obviously equivalent. A dependency and a repository are consumed by *resolution*, which
S4 and S5 showed reads the in-memory session state the participant mutated. A plugin execution has to
survive into the per-project **execution plan**, which
Maven computes at a different point in the lifecycle; `afterProjectsRead` fires before that
computation, so it *should* work, but "should" is what S4 existed to replace with a measurement.

**This is a PR-4 entry gate.** PR-4 needs `jacoco-maven-plugin:instrument` bound to `process-classes`
in the app's model, and it is the first PR that cannot proceed without it. Settle it the way S4 settled
the dependency half: inject a plugin execution with an observable side effect into the spike fixture,
build, and show the effect in the build log — an hour's work, and the alternative is discovering the
hole after PR-1 through PR-3 have landed on the strength of a claim that covered only the other half.
If it does not work, the fallback is §5.1's named-app pom edit, which the benchmark row must record —
and the coverage story for unmodified third-party apps changes materially, which is why this is a gate
and not a footnote.

## 9. Delivery — six PRs in dependency order

This is not one implementable unit. It spans a cross-cutting refactor of the measurement core, a new
Quarkus extension, a new Maven core-extension artifact, a driver transport change, a reporting change,
four spikes, and a five-target benchmark program. Under the project's own PR-granularity rule this is
six cohesive features, and a PR of that span cannot be adversarially reviewed at the depth DD-040's
history says this repo needs.

| PR | Contents | Gate |
|---|---|---|
| **PR-0** | Phase-0 spikes S1–S4 → `bench-results/dd043-spikes-2026-07-24/`, plus the spec amendments they forced. **No product code.** — **DONE, gate PASSED** | Gates everything below |
| **PR-1** | `basquin-core` extraction (§4.1) — pure refactor, zero behaviour change, existing tests green, no Quarkus code — **DONE** (324 → 326 tests, 0 failures; branch `dd043-pr1-basquin-core`, not yet merged) | PR-0 — cleared |
| **PR-2** | `basquin-quarkus` MVP — filter boundary, result store + a `/__basquin/{result,violations}` control surface sharing `ResultStore`'s wire format (§4.4a), control defect routes (§7.3); validated on `rest-villains` **JVM mode** | PR-1 · **entry requirement: §4.1** — `Invariants`, `evaluateAndMaybeFail`, `Result` (+ accessors) and `Violation`'s fields are all package-private and must be widened together before the boundary filter can call this artifact. The publishing half is resolved: `maven-publish` ships both a local and a Pages-served Maven repo |
| **PR-3** | `basquin-maven-injector` + Gradle init stub; acceptance is §5.2's banner, zero pom edits — **DONE.** Shipped: the core extension injecting the dependency **and** the repository at both levels (§5), 4 operator guards, each mutation-checked by `scripts/verify-dd043-pr3.sh` (skip; conflicting managed version; conflicting declared version; and an unusable declaration — wrong scope, `pom`/`test-jar` type, or a classifier. The last three fail loudly per §5.1. The scope and type/classifier shapes were each found by review AFTER the preceding guard landed, so the check is written as a whitelist of what is usable rather than a list of known-bad values), and the Pages repo widened to all four artifacts (§3). 20 module tests; 379 repo-wide, 0 failures. §5.2's JVM half **PASSED on `rest-villains`** with zero edits to its tree (`bench-results/dd043-pr3-restvillains-2026-07-26/`, banner lists `basquin`); §5.2's native half **PASSED on the Phase-0 fixture, not rest-villains** (`bench-results/dd043-pr3-native-2026-07-26/`, banner `[basquin, cdi, rest, smallrye-context-propagation, vertx]`) — rest-villains' native buildability stays **unmeasured** and belongs to PR-4's native 2×2 cells (§7.2). The Gradle init script is a **stub**: never exercised against a Gradle-built Quarkus target (§5). Both acceptances resolved over localhost HTTP; the real Pages HTTPS repository stays unexercised until the first `v*` tag (§3). The offline-JaCoCo plugin execution was **not** injected — §8.2 stands unmeasured, PR-4's entry gate | PR-2 — cleared |
| **PR-4** | Coverage — offline-JaCoCo execution injection, `/__basquin/coverage`, `JacocoCoverageProvider` HTTP transport; the native 2×2 cells | PR-3 · **entry gate: §8.2** (plugin-execution injection is unmeasured); the native cells additionally carry §7.2's S3 re-check |
| **PR-5** | Reactive invariant set (§6.3 watchdog), `render_page.py` per-target sets, benchmark rows, docs. **Entry condition:** §7.4's rule that the heap column, taint rate and `UNMEASURED` count render only when §7.3's firing controls passed is today **documented discipline, not enforced code** — nothing reads control-pass state, and it holds only because no renderer exists yet. PR-5 must make it a checkable gate in `render_page.py`, not a convention. **Also owns four gaps PR-2 measured:** the `UNMEASURED` disposition and §6.1's in-flight taint — both need a `ResultStore.Entry` field, and until they land §7.3's sub-quantum control **fails** and the heap invariant is **not publishable**; the watchdog, without which `block-loop`'s control cannot be claimed; **negative** heap deltas (measured `-16,456 KB` on `rest-villains`), which the in-flight counter structurally cannot detect because a GC is not a request; and **GC-contaminated positive** deltas, which the sign rule cannot see either — the general detector is a `GarbageCollectorMXBean.getCollectionCount()` delta across the window, whose SubstrateVM support is itself a PR-5 precondition | PR-4 · **entry gate: §6.2** — native JFR streaming and `com.sun.management`-on-SubstrateVM are both unverified; establish or demote before budgeting the cross-check |

Docs land with their PR: `THIRD-PARTY-APPS.md` gains a build-time-injection section, `ARCHITECTURE.md`
gains the build-vs-runtime injection symmetry.

## 10. Execution note

Independent work fans out to concurrent subagents by default: S1–S4 in Phase 0, the JVM-mode builds in
Phase 1, per-target benchmark batteries thereafter. Subagent reports go to files and return short.
Native compilation is the exception — serialized on a mutex (§7.2). One campaign at a time, and nothing
CPU-heavy during a run.
