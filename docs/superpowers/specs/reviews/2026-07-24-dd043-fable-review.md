# Adversarial review — DD-043 Native and reactive targets

**Reviewer:** fable subagent, 2026-07-24
**Spec under review:** `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`
**Verdict:** The architecture is sound in outline (Vert.x-level boundary, DD-040-style result
store, build-time injection via a lifecycle participant), and two external claims I expected to be
wrong turned out to be right (contract-first super-heroes; `jdk.ObjectAllocationSample` in native
JFR). But the spec is **not executable as written**: the toolchain plan fails at the first build,
and the headline reactive invariant is built on a signal that has no programmatic surface. Several
of its "transplants unchanged" claims dissolve on contact with the code it cites.

Each finding states its **basis**: `[verified:code]` (read in this repo), `[verified:web]`
(checked against vendor docs today, sources at the end), `[training]` (my knowledge, not
re-verified), `[experiment]` (genuinely needs a spike).

---

## Blockers

### 1. [BLOCKER] §3.1 — `./mvnw` supplies Maven, not a JDK; the stated toolchain cannot compile the targets at all

**Claim:** "Both the Maven build and `native-image` therefore run in containers (`./mvnw` supplies
Maven; `quarkus.native.container-build=true` supplies the builder image), so nothing needs
installing locally. Every build goes through Docker."

**Why it is wrong.** The Maven wrapper is a bootstrap script that downloads a Maven distribution
and runs it **on the host JVM** (`JAVA_HOME`/`java` on the PATH). It supplies no JDK. With host
JDK 17 and a Java-25 source/target, `mvn compile` fails at javac before Quarkus augmentation is
even reached — and `quarkus.native.container-build=true` only containerizes the `native-image`
*step*, which runs on the already-compiled augmentation output. So the sentence "every build goes
through Docker" is false for exactly the half that needs the newer JDK, and **every phase of the
spec (all four spikes, all four 2×2 cells) is downstream of this build**. Basis: [training] — but
this is bedrock Maven behavior, not an obscure corner.

**Fix.** One of:
- Run the *entire* Maven build inside a JDK-25 container (`docker run -v $PWD:/w -w /w
  maven:3.9-eclipse-temurin-25 ./mvnw …`), which also has a knock-on effect on §5: the injector
  jar must be mounted into that container and `MAVEN_OPTS`/`-Dmaven.ext.class.path` forwarded
  through `docker run -e` — write that plumbing down, it is where the injection mechanism will
  actually break first (see finding 7).
- Or install JDK 25 host-side (sdkman/temurin tarball) and keep only `native-image` in Docker.

Either is fine; the spec must pick one and correct §3.1, because the current text will be
falsified in the first ten minutes of Phase 0.

### 2. [BLOCKER] §6.2 — the blocked-thread checker is log-only; the headline invariant has no designed path from signal to result store

**Claim:** "Event-loop blocking — Quarkus's built-in blocked-thread checker
(`quarkus.vertx.max-event-loop-execute-time`). … this is the **headline invariant** for this class
of app."

**Why it is risky.** Vert.x's `BlockedThreadChecker` **only emits a WARN log line** (logger
`io.vertx.core.impl.BlockedThreadChecker`, with an attached `VertxException: Thread blocked` stack
once the block exceeds the warning-exception threshold). There is no callback, no event-bus
message, no metric, no API to subscribe to it. Basis: [verified:web] — VertxOptions exposes only
thresholds/intervals; community answers and the Vert.x docs confirm log-only.

The spec designates this the flagship signal and then never says how the signal reaches the
driver. This repo has *twice* rejected log scraping as a mechanism (DD-019: "couples the driver to
log access and formatting"; DD-040 rejected "re-score from pod logs"). An invariant whose only
transport is an undesigned log path is precisely the DD-040 shape: it will render as a clean zero
column until someone notices nothing could ever have arrived. This spec's own §6.2 warns about
shipping a structurally-incapable invariant "knowingly" — and then does it.

**Fix.** Two honest options, either acceptable:
1. **In-process log interception** (not container-log scraping): the extension's runtime registers
   a JUL/JBoss-LogManager handler filtered to the `io.vertx.core.impl.BlockedThreadChecker`
   category inside the target JVM, parses thread + blocked-ms, and writes to the result store.
   Same-JVM, no log-transport coupling; but it inherits the checker's granularity (check interval
   default 1 s, threshold default 2 s) and its total lack of request attribution.
2. **Own watchdog (better, and more in the project's grain):** a sampler thread in the extension
   periodically schedules a no-op on each event loop (`Context.runOnContext`) and measures
   scheduling delay. Deterministic, threshold fully ours, native-safe, attributable to the request
   window in flight under concurrency-1, and consistent with DD-004's "enforcement > inference"
   preference for exact signals over inferred ones. The Quarkus checker then remains as
   corroboration, not the mechanism.

Whichever is chosen, §7.3's negative control must assert the violation **arrives in the
driver-visible result** (store entry / finding), not merely that the WARN line appears — see
finding 6.

---

## Majors

### 3. [MAJOR] §4.4 — "the DD-040 channel transplants unchanged" is false: its load-bearing element is the lock the same spec removes

**Claim:** §4.4: "The DD-040 channel transplants unchanged. … DD-040 already solved exactly this."
§6.1: "the lock is impossible."

**Why it is wrong.** DD-040's record (DESIGN-DECISIONS.md, item 2) is explicit that the store
alone was insufficient: *"The poll waits on `ITERATION_LOCK`, and that is the critical detail"* —
without it, the poll raced the post-response measurement "for the motivating class, every time,
while passing every mocked unit test." On the reactive path there is no lock, so there is no
`awaitQuiescence` (`agent/RequestBoundary.java:194`), so the race is back — and it is *worse*: the
driver's poll is triggered by response-end, and the store write happens in `addEndHandler`, which
also fires at response-end, on the event loop, asynchronously. The two are near-simultaneous by
construction. If the reactive path also acquires any analogue of the 25 ms grace period (finding
8), the poll losing the race stops being a tail risk and becomes deterministic. Basis:
[verified:code] for DD-040's mechanism; [verified:web] for `addEndHandler` firing semantics.

The failure is at least *honest* (DD-040 made misses first-class, so this surfaces as
`reportMisses`, not as zeros) — but a channel that misses on a systematic timing race is not a
transplant of DD-040, it is a regression to the pre-DD-040 shape with better bookkeeping.

Note also a smaller consequence the spec doesn't state: DD-040's opportunistic header fast path
(`X-Basquin-Cost` on the response) **cannot exist at all** on this path — the measurement happens
strictly after the last byte is written — so *every* iteration polls, doubling requests per
iteration. DD-040's rejected "piggyback request N−1's result on request N" alternative was
rejected for complicating a path that mostly didn't need it; on reactive, where the poll is
universal, it deserves re-evaluation.

**Fix.** Design the reactive quiescence analogue explicitly: the `/basquin/result/<id>` handler,
on a miss, parks (reactive-style — a `Uni`/promise completed by the store's `put` for that id, or
a bounded retry loop) up to a bound (2 s, mirroring DD-040), with the driver's read timeout above
it (4 s, same reasoning). Amend §4.4 from "transplants unchanged" to "transplants the store and id
scheme; replaces the lock-based quiescence with X" — the current wording is exactly the kind of
claim DD-040's residual 2 taught this project to distrust ("found by an approver reading the claim
against the code").

### 4. [MAJOR] §6.1 — "the app has one request in flight" is only true of *driver* traffic; nothing excludes probes and background work, and without the lock nothing even detects them

**Claim:** "if the driver holds concurrency 1 and waits for each response, the app has one request
in flight, and the whole-heap start→end delta is attributable with no in-app lock at all. Today's
semantics survive and the comparison to the Tomcat rows stays apples-to-apples."

**Why it is wrong.** Driver-side serialization serializes the driver. It cannot serialize:

- **Kubelet / compose health probes.** DD-040's own record measured "the kubelet readiness probe
  alone violates heapDelta ~12/min on JSPWiki at idle" [verified:code — DD-040 rejected
  alternatives]. On Tomcat this did not corrupt driver windows because a probe *takes the
  ITERATION_LOCK too* and therefore cannot overlap a driver iteration. On the lock-free reactive
  path a probe lands **inside** request N's window and its allocations are attributed to N.
  The very mechanism that made "concurrency 1 at the driver" sufficient on Tomcat is the lock the
  spec removes. The super-heroes compose files and any k8s deployment both ship health checks.
- **Post-response and periodic app work**: Hibernate Reactive / vertx-sql-client pool maintenance
  (idle reaper, keep-alive), Netty housekeeping, Quarkus metrics if present, and any
  fire-and-forget `Uni` continuation the app starts before responding. Response-end does not imply
  work-end on this stack; that is the *defining* property of a reactive app. [training]
- **Native-specific noise**: runtime-init class initialization on first touch of a code path —
  first-request windows will carry one-off allocation spikes. (JIT noise is gone, which helps;
  Serial GC heap resizing still moves `totalMemory`.) [training]

"Today's semantics survive" is therefore an overclaim: today's semantics are *lock-enforced
exclusivity*; the proposal is *client-side politeness plus hope*. The apples-to-apples claim needs
demoting to "same instrument, weaker isolation, and the weakening is detected."

**Fix.** Make overlap **observable and disqualifying** rather than assumed absent:
- The filter maintains an in-flight counter (trivially cheap). A window during which the counter
  ever exceeded 1, or during which any non-driver request started or ended, marks that iteration's
  sample **tainted → UNMEASURED** (DD-040 item 6's category, already first-class), never a number.
- Report the taint rate in the run summary the way `reportMisses` is reported; a majority-tainted
  run fails loudly (the `failOnMissMajority` pattern).
- For local 2×2 runs, disable compose healthchecks and say so in the bench manifest; for cluster
  runs, accept the taint rate as data.
This converts finding 4 from a silent attribution error into a measured limitation — the DD-040
discipline the spec claims to apply "before there is a problem."

### 5. [MAJOR] §6.1 — the JFR cross-check compares two quantities that differ by construction; "divergence means pollution" is not a valid inference

**Claim:** "JFR's `jdk.ObjectAllocationSample` gives concurrency-safe per-context allocation …
Run both. Divergence means the event loop is polluting the window, which is itself a finding."

**What is right:** `jdk.ObjectAllocationSample` **is** supported in native image JFR (with Serial
GC), event streaming works in native, and user-defined events work — I verified this against the
GraalVM reference docs today, so the availability claim stands. [verified:web]

**Why it is still wrong as designed.**
- `ObjectAllocationSample` is a **throttled statistical sampler** (default cap ~150 events/s):
  it estimates *gross allocation*, with sampling error that dominates at per-request scale
  (a request allocating tens of KB may emit zero samples). The primary channel measures *net
  used-heap delta* (`totalMemory - freeMemory`), i.e. allocation minus collection plus heap-resize
  artifacts. These two numbers **never agree**, clean window or dirty. Without a defined
  comparator (what magnitude of disagreement, over what aggregation window, means what), the
  cross-check is unfalsifiable — it will either "diverge" always (and be ignored) or be given a
  tolerance wide enough to never fire. Both are DD-040 shapes. [training]
- This repo already adjudicated this exact question: DD-004, "Determinism note": *"JFR allocation
  sampling … is statistical; if adopted later it belongs behind soft signals only."*
  [verified:code] The spec promotes it to "the cross-check matters more than the primary" without
  engaging that prior ruling.
- Native JFR streaming events carry **no stack traces** [verified:web], so a divergence, when it
  fires, cannot even be localized to a source.

**Fix.** Keep the cross-check but redefine it honestly: aggregate `ObjectAllocationSample` totals
over the *whole run* (or per-route over many iterations), compare per-route *rankings* rather than
per-request magnitudes, and state the comparator in the spec. For per-request cross-checking, the
right instrument on a JVM-mode cell is `ThreadMXBean.getThreadAllocatedBytes` on the event-loop
thread (exact, per-thread — but JVM-mode only; SubstrateVM does not implement
com.sun.management ThreadMXBean [training]) — which incidentally makes the JVM-mode cells the
place where the cross-check is *exact*, and native cells the place where it is statistical; the
2×2 gives you that for free if you use it.

### 6. [MAJOR] §7.3 — the negative controls require planted defects, and the spec never says where they live; every naive placement either breaks the thesis or tests the wrong thing

**Claim:** "every invariant ships with a negative control — a planted defect proving it can fire:
… a deliberately slow route … a `Thread.sleep()` on the loop … an allocation-heavy route."

**Why it is risky.** The targets are unmodified third-party apps (`rest-villains`, `rest-heroes`);
the constraint in §1.1 is bold-faced: *no file in the application's source tree is created or
modified*. A planted slow route in `rest-heroes` is a source modification — the spec's own thesis
violation, and it would also invalidate the benchmark row's "unmodified app" claim. Running the
controls only on the Phase-0 `todo` quickstart instead proves the invariants fire *in a different
app on a different stack cell* than the ones being published. The spec is silent on this choice,
and silence here is how a control quietly becomes a control-in-name-only. Basis: [verified:code]
for the constraint text; the gap is internal inconsistency.

Two sharper sub-points:
- The event-loop-blocking control ("a `Thread.sleep()` on the loop trips the checker") is
  under-specified: with the default `quarkus.vertx.max-event-loop-execute-time` of 2 s and a 1 s
  check interval, a sub-2 s sleep trips nothing. The control must pin the configured threshold and
  a sleep comfortably above it. [training]
- "Trips the checker" must mean **arrives as a driver-visible violation** (store entry → finding
  → rendered row), not "the WARN line exists" — otherwise the control validates the logger, not
  the invariant, and finding 2's gap ships anyway.

**Fix.** State the placement: negative-control defect routes ship **in the extension's own
runtime** (e.g. `/basquin/control/defect/slow|alloc|block-loop`), disabled by default, enabled by
a system property only in the Phase-2 control runs. That preserves the thesis (the extension is
injected tooling, not app source), runs in the *same* process/stack cell as the published rows,
and makes the controls reusable for every future Quarkus target. Add a line to §7.3 requiring each
control to be verified end-to-end at the reporting layer (`render_page.py` input), matching how
DD-021 verified tests by mutation.

### 7. [MAJOR] §5 — the injector's real risk is not "adding an execution is hard"; it is that Quarkus augmentation may not read the model you mutated. And the Develocity precedent is oversold.

**Claims:** (a) `afterProjectsRead()` "edits the in-memory project model before dependency
resolution … Quarkus discovers it at augmentation"; (b) "proven at scale — it is how Develocity
auto-injects its Maven extension into CI builds it does not own."

**Why it is risky.**
- (a) The lifecycle-participant mechanism itself is real: `-Dmaven.ext.class.path` loads a core
  extension, `afterProjectsRead(MavenSession)` may mutate each `MavenProject`'s `Model`
  (dependencies and `build/plugins` executions), and the per-project execution plan is computed
  after that hook, so injected executions do run. [training — solid]. The hazard is specific to
  **Quarkus**: the `quarkus-maven-plugin` builds its `ApplicationModel` through its own bootstrap
  resolver (`quarkus-bootstrap-maven-resolver`), which in several modes resolves the workspace by
  **re-reading pom.xml files from disk**, not from the session's in-memory `MavenProject`. If the
  prod `build` goal's resolution takes that path for the app's dependencies, the injected
  `basquin-quarkus` dependency exists for javac and vanishes for augmentation — a build that
  *succeeds* and produces an uninstrumented binary, i.e. §5.1's "silent no-op" realized through a
  mechanism the spec doesn't name. [training — uncertain in exactly which modes; this is the
  experiment]
- (b) Develocity proves the **loading** half at scale (core extension via `.mvn/extensions.xml` /
  `maven.ext.class.path`). It does **not** inject dependencies or plugin executions into the
  project model — it observes and wraps the build through its own APIs (event spies, cache
  interceptors). The precedent covers "an extension you didn't declare can participate in your
  build," not "an extension can add artifacts that a second build system layered on Maven
  (Quarkus bootstrap) will honor." The spec's word "exact analogue" should go. [training]
- Interaction with finding 1: once the Maven build moves inside a container, the injector jar and
  `MAVEN_OPTS` must cross the container boundary; `-Dmaven.ext.class.path=/path/...` must be a
  path valid *inside* the container. Two independently-fine mechanisms, one seam.

**Fix.** Tighten S4's acceptance criterion from "can the participant inject both" to: **the built
artifact's startup banner shows `basquin` in Installed features** (the spec's own deploy signal,
§4.2) for (i) JVM-mode jar, (ii) native image, both built through the containerized Maven of
finding 1. If the disk-re-read path bites, the documented fallback is stronger than a pom edit:
`-Dquarkus.platform...`? No — the honest fallbacks are (α) injecting via
`quarkus.bootstrap.workspace-discovery` knobs if they exist for this, or (β) the §5.1(4)
documented-degradation pom edit. Keep (β) but the spike must prove whether it is needed.

### 8. [MAJOR] §4.1/§6 — the transplanted measurement path is not event-loop-safe as it exists today, and hard mode is structurally void at the end handler; the spec addresses neither

**Claim:** §4.1 extracts "invariant definitions and evaluation, the DD-040 result store" for the
extension to reuse; §6's table implies the same measurement semantics with a new boundary.

**Why it is risky.**
- `Agent.end()` (`agent/Agent.java:111-121`) begins with `Thread.sleep(25)` — the leak-snapshot
  grace period — followed by two thread enumerations, and optionally `System.gc()` (DD-002).
  `addEndHandler` runs **on the event loop**. Transplant that composition and the harness blocks
  the loop on every iteration — the tool whose headline invariant is "blocking the loop" would be
  the process's most reliable loop-blocker. The extraction boundary must therefore split
  *evaluation* (pure: thresholds over numbers — safe to share) from *measurement composition*
  (sleep, enumerations, GC — Tomcat-shaped, must not cross). The spec's "extract only what is
  already duplicated" gestures at this but never names the sleep, and it is the sharpest edge in
  the extraction. [verified:code]
- Hard mode is impossible here: `Invariants.evaluateAndMaybeFail` throwing at the end handler can
  fail nothing — the response is fully written by definition of the hook. Every invariant on this
  path is soft-by-structure. That is fine (DD-040 already softened the leak throw for servlet
  targets) but it is a semantic difference from the Tomcat rows (`basquin.invariant.mode` defaults
  to hard there) and belongs in §6's table and the benchmark page's per-target notes, or the
  comparability claim in §2 quietly overstates. [verified:code for the default]
- Minor but same family: `RequestBoundary`'s phase/id state is `ThreadLocal` — meaningless on an
  event loop where requests interleave on one thread. §4.4 correctly says state rides the
  `RoutingContext`; the extraction just must ensure none of the shared core secretly assumes
  thread affinity.

**Fix.** Name the split in §4.1: `basquin-core` = `Invariants` evaluation + `ResultStore` + id
scheme, explicitly excluding `Agent.begin/end` composition and anything `ThreadLocal`-backed. Add
a sentence to §6: "reactive invariants are soft-by-structure; the per-target invariant table on
the benchmark page records mode." Decide where the reactive equivalent of the leak-snapshot grace
period goes (a scheduled re-check off-loop, or nothing — but decide, don't inherit).

### 9. [MAJOR] §6.4/S1 — the spike's stated failure signature ("probe arrays freeze") is the least likely failure mode; the likely ones are build-time-init pollution and augmentation ordering, and S1 as designed might pass while coverage is still wrong

**Claim:** S1: "Does offline JaCoCo survive AOT, or do `$jacocoData` probe arrays freeze at build
time? Failure signature: coverage reads a constant."

**What I actually know.** [training + experiment]
- Offline-instrumented classes call `Offline.getProbes(classId, name, count)` on first touch; the
  probe `boolean[]` lives in a static field and registers with a `RuntimeData`.
- Quarkus registers application classes for **build-time initialization** by default. So during
  `native-image`, `<clinit>` of instrumented classes runs, `$jacocoInit` executes, and both the
  probe arrays *and the JaCoCo `RuntimeData` singleton* are captured into the image heap.
  Image-heap objects are **writable at runtime** — so the arrays do not freeze; runtime execution
  keeps setting probes on top of the captured state. The likelier defect is therefore
  **pollution**: probes executed during image build read as covered forever, inflating the
  baseline. §7.3's coverage control ("must increase") does *not* catch pollution — coverage
  increases fine from an inflated floor. A run could pass S1 and Phase 2 while the absolute
  percentage (the benchmark's headline coverage figure) is wrong.
- Second likely failure: **ordering against Quarkus's own bytecode transformation**. Augmentation
  rewrites classes (Panache entity enhancement, Hibernate Reactive enhancement, Jandex-driven
  transforms) *after* target/classes is produced. Instrument-then-augment means Quarkus transforms
  instrumented bytecode (probably tolerated, worth confirming); augment-then-instrument isn't
  expressible with a standard `jacoco:instrument` execution. And JaCoCo's analyzer matches exec
  data to class files by class id — the driver must analyze against the **original compiled
  classes** (offline instrumentation embeds the pre-instrumentation id, so this works, but only if
  the driver is pointed at `target/classes`-before-instrumentation, which the injector must
  therefore preserve, e.g. JaCoCo's own backup dir `target/generated-classes/jacoco`).
- Third: the offline runtime normally boots from `jacoco-agent.properties` (it starts a real agent
  with an output mode). In native, that init path (shutdown hooks, file output) may need config or
  substitution; `/basquin/coverage` should read `RuntimeData` directly and S1 should confirm that
  is reachable without the agent-boot path.

**Fix.** Rewrite S1's failure signatures to three: (i) frozen probes (spec's current one), (ii)
**inflated constant baseline from build-time init** — detected by asserting coverage at first
request ≈ the build-time-executed set, and that a *never-exercised* class reads zero, (iii)
augmentation/class-id mismatch — detected by round-tripping one exec dump through the driver's
`Analyzer` against the preserved original classes. S1 must also record which classes Quarkus
shifted to runtime init, since that changes (ii)'s expected floor.

### 10. [MAJOR] §9/§10 — this is not one implementable unit; five of the deliverables have independent failure modes and reviewers

**Why.** The deliverables list spans: a cross-cutting refactor of the measurement core
(`basquin-core`, touches agent + valve + every existing test), a new two-module Quarkus extension,
a new Maven core-extension artifact, a driver/coverage transport change, a reporting-pipeline
change (`render_page.py` per-target invariant sets), four spikes, and a five-target benchmark
program. Under the project's own PR-granularity rule ("one cohesive feature = one PR") this is
four to six cohesive features. A single PR of this span cannot be adversarially reviewed at the
depth DD-040's history says this repo needs.

**Fix — the split, in dependency order:**
1. **PR-0 (evidence only):** Phase-0 spikes S1–S4 → `bench-results/dd043-spikes-…/`, plus the
   spec amendments they force. No product code. Gates everything (spec already says so; make it a
   PR so the evidence is reviewed, not just produced).
2. **PR-1:** `basquin-core` extraction — pure refactor, zero behavior change, existing tests
   green. Riskiest-to-review PR; keep it free of any Quarkus code.
3. **PR-2:** `basquin-quarkus` extension MVP — boundary filter + result store + `/basquin/status|result`
   routes + negative-control defect routes (finding 6), validated on `rest-villains` **JVM mode**
   (2×2 cell 1). No coverage, no native.
4. **PR-3:** `basquin-maven-injector` (+ Gradle init script stub), validated by cell 1 building
   with zero pom edits (finding 7's acceptance).
5. **PR-4:** coverage — offline-JaCoCo execution injection + `/basquin/coverage` +
   `JacocoCoverageProvider` HTTP transport; native cells of the 2×2.
6. **PR-5:** reactive invariant set (finding 2's mechanism) + `render_page.py` per-target sets +
   the benchmark rows + docs.

---

## Minors

### 11. [MINOR] §4.2 — "RouteBuildItem (filter)" names the wrong build item

Quarkus's dedicated mechanism for a router-wide filter is **`FilterBuildItem`** (handler +
priority, runs before/around all routes); `RouteBuildItem` registers routes. A catch-all
route-with-order that calls `next()` also works, but the spec should name the idiomatic item —
implementers grep for the exact type. [training] Also note the boundary will *not* see traffic
that bypasses the router (separate management-interface port if enabled, gRPC, raw-socket
handlers) — irrelevant for these targets, worth one sentence so it isn't rediscovered.

### 12. [MINOR] §4.1 — "copying it would create a third divergent copy alongside the agent and valve" misstates the current state

There is exactly **one** copy today: `BasquinValve` imports and delegates to `agent.RequestBoundary`
("this valve is only Catalina glue over it" — its own javadoc), and `RequestBoundary`'s javadoc
says it was extracted precisely so valve and agent "run identical logic." [verified:code] The
extraction motivation is still correct (a Quarkus module can't depend on `agent/` as shaped), but
the premise as written implies a duplication debt that doesn't exist, and whoever executes §4.1
should know they are *moving* one shared core, not unifying three.

### 13. [MINOR] §6 — "Latency … strictly better, now includes body write" changes the metric's population, not just its accuracy

`addEndHandler` fires when the response is fully written **to the wire**, so the reading now
includes client drain/TCP backpressure — a slow-reading client inflates "latency" into a number
Tomcat rows never included (the valve exits when the app returns; the connector flushes after).
For a same-host driver this is small; it is still a definitional change, and "strictly better"
should be "differently scoped (includes write-out; excludes nothing the valve measured)".
Separately: on the **failed** `AsyncResult` (client disconnect — verified semantics
[verified:web]), elapsed-until-abort is not a latency sample at all; the boundary must record
disposition (`completed|disconnected`) and keep disconnected samples out of the latency
distribution, or a flaky driver connection manufactures latency findings.

### 14. [MINOR] §4.4 — write the id header in `addHeadersEndHandler`, not at request entry

`putHeader` at filter time works until something resets or rewrites the response
(`response.reset()`, error handlers that rebuild the response). `addHeadersEndHandler` is the hook
Vert.x provides for exactly "add a header at the last moment before commit" [verified:web] and
costs one line. The spec's "always safe" is *nearly* true; this makes it actually true.

### 15. [MINOR] §5 — `-Dquarkus.native.monitoring=jfr,nmt` may not parse

`quarkus.native.monitoring` is an enum list; `jfr`, `heapdump`, `jvmstat`, `jmxserver`,
`jmxclient`, `threaddump` are long-standing values — whether `nmt` has been added to that enum in
the Quarkus version super-heroes pins is unverified [training]. If not, the same effect is
`quarkus.native.additional-build-args=--enable-monitoring=nmt`. One-line check during S2; cheap,
but a failed *build flag* would otherwise stall the native cells on a triviality.

### 16. [MINOR] §7.3/§6.2 — the negative-control table's "heap" row inherits finding 4, and the blocked-loop row inherits finding 6's threshold caveat

An "allocation-heavy route registers a delta" control passes trivially even when attribution is
broken (any big number looks like success). Pair it with a **positive-noise control**: an idle
window with *no* driver request must produce ~zero / UNMEASURED — that is the control that would
have caught the probe-pollution class. (The Tomcat-era analogue is exactly the `heapDriftKb`
lesson: +381 MB one run, −194 MB another.)

---

## Nits

### 17. [NIT] §1.1 — "a boundary compiled into the binary … cannot fail to attach" overclaims

Its failure mode moved, it didn't vanish: a build where injection silently didn't happen produces
a binary with no boundary (§5.1 admits this). The honest sentence is "cannot *detach at runtime*;
its attachment is verifiable once at build via the features banner." The current sentence invites
the same complacency the `BoundaryInstaller` no-op exploited (`agent/BoundaryInstaller.java:39-48`).

### 18. [NIT] §7.1/S2 — add `System.gc()` behavior to the spike

DD-002's `basquin.heap.gcBeforeMeasure` is the existing tool for separating retention from churn;
SubstrateVM's Serial GC honors `System.gc()` [training]. S2 should record whether it does on the
pinned GraalVM version, because if it does, gc-before-measure under driver-side serialization is
the cheapest answer to a chunk of finding 4's noise (at explore's already-low throughput).

### 19. [NIT] §3 — "port 8083" and contract-first checked out; pin the Java version claim with a citation

The contract-first claim I expected to be wrong is **right**: the super-heroes README documents
the REST interface as generated at build time from `src/main/resources/openapi/openapi.yml` via
the Quarkiverse OpenAPI Generator server extension [verified:web]. The "base JVM is Java 25" claim
I could not verify from the repo page fetched; since finding 1 turns entirely on it, S-zero of
Phase 0 is: read the pinned `pom.xml`'s `maven.compiler.release`. If it is lower than 25, finding
1 relaxes accordingly (the fix stays, the urgency drops).

---

## Epistemic summary

**Verified against this repo's code/docs:** DD-040's poll-quiescence mechanism and miss
semantics; `ITERATION_LOCK` exclusivity incl. probe serialization; `Agent.end()`'s 25 ms sleep,
thread enumerations, `System.gc()` option; hard-mode default; valve→`RequestBoundary` single-copy
structure; DD-004's ruling on JFR sampling; DD-019/DD-040's rejections of log scraping; the
kubelet-probe heapDelta noise figure.

**Verified against vendor docs today (2026-07-24):** `jdk.ObjectAllocationSample` and
`ObjectAllocationInNewTLAB` supported in native image JFR (Serial GC); JFR event streaming in
native (no stack traces on streamed events); `--enable-monitoring=jfr` as the flag;
`RoutingContext.addEndHandler` (fires on success *and* failure/disconnect, `AsyncResult`),
`addBodyEndHandler` (may never fire on connection reset — docs say do not use for cleanup),
`addHeadersEndHandler` (pre-commit header hook); Vert.x `BlockedThreadChecker` exposes only
thresholds — log-only, no programmatic hook; quarkus-super-heroes `rest-villains` is
contract-first from `src/main/resources/openapi/openapi.yml`.
Sources: [GraalVM JFR docs](https://www.graalvm.org/latest/reference-manual/native-image/debugging-and-diagnostics/JFR/),
[Vert.x RoutingContext API](https://vertx.io/docs/apidocs/io/vertx/ext/web/RoutingContext.html),
[Vert.x VertxOptions API](https://vertx.io/docs/apidocs/io/vertx/core/VertxOptions.html),
[quarkus-super-heroes rest-villains](https://github.com/quarkusio/quarkus-super-heroes/tree/main/rest-villains).

**Training-only (confident, not re-verified):** Maven wrapper supplies no JDK;
lifecycle-participant model mutation semantics; `FilterBuildItem` vs `RouteBuildItem`; JaCoCo
offline probe/`Offline.getProbes`/class-id mechanics; Quarkus build-time-init default;
image-heap writability; `ObjectAllocationSample` throttling; SubstrateVM lacking
`getThreadAllocatedBytes`.

**Genuinely needs an experiment (beyond the spec's S1–S4):** whether Quarkus's bootstrap resolver
honors the in-memory model for the prod `build` goal (fold into S4 with the features-banner
acceptance); build-time-init coverage pollution and instrument/augment ordering (fold into S1 as
rewritten in finding 9); post-response allocation quiescence on Hibernate Reactive (fold into S2);
whether `quarkus.native.monitoring` accepts `nmt` (S2, one line); the super-heroes pinned Java
release (pre-Phase-0, one file read).
