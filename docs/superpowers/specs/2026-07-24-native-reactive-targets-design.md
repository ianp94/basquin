# DD-043 — Native and reactive targets

**Status:** designed, not yet planned (2026-07-24). Revised after adversarial review —
`reviews/2026-07-24-dd043-fable-review.md` (2 blockers, 8 majors; all addressed here).
**Depends on:** DD-040 (trustworthy measurement channel), DD-012/DD-023 (coverage over HTTP)
**Related:** DD-002 (`gcBeforeMeasure`), DD-004 (JFR sampling is soft-only), DD-009/DD-011 (why the
valve exists), DD-019/DD-040 (log scraping rejected, twice), DD-029 (closure's thesis stated),
DD-005/DD-010 (why the iteration lock exists)

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
created or modified.** §5 meets it for the build-time path; §7.3 is where it nearly broke.

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
| 5 | Apicurio Registry | Quarkus; PostgreSQL/KafkaSQL/in-memory | Real-product credibility row. **Gated on §8.1.** |

Both services come from [`quarkusio/quarkus-super-heroes`](https://github.com/quarkusio/quarkus-super-heroes).
Both are **contract-first**: the REST interface is generated at build time from
`src/main/resources/openapi/openapi.yml` via the Quarkiverse OpenAPI Generator server extension, which
gives the driver a precise route-and-parameter seed corpus rather than a hand-built one.

`rest-villains` is a throwaway control, not a maintained target. It costs one extra build and no new
code, and it is what makes a wrong number localisable — §7.2.

### 3.1 Toolchain — the entire Maven build runs in a container

**Verified 2026-07-24:** `rest-heroes/pom.xml` sets `maven.compiler.release=25` and pins Quarkus
**3.37.3** (and `jacoco.version=0.8.15`, already present). This host has JDK 17 and Maven 3.6.3.

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

### 4.2 `basquin-quarkus` — the extension

Standard two-module Quarkus extension shape:

- **`runtime/`** — ships into the app, AOT-compiled into the native image: boundary filter, result
  store, invariant evaluation, status/result/coverage routes, negative-control defect routes (§7.3).
- **`deployment/`** — `BasquinProcessor` and its `@BuildStep` methods, augmentation-only, never in the
  image.

| Build step | Purpose |
|---|---|
| `FeatureBuildItem("basquin")` | Prints in the `Installed features` banner — the deploy signal, and §5.2's injection proof |
| **`FilterBuildItem`** | Installs the request boundary. *Not* `RouteBuildItem` — `FilterBuildItem` (handler + priority) is Quarkus's idiomatic router-wide filter; `RouteBuildItem` registers routes |
| `RouteBuildItem` | `/basquin/status`, `/basquin/result/<id>`, `/basquin/coverage`, `/basquin/control/*` — Vert.x routes, so they exist in native without JAX-RS scanning |
| `@Recorder` | Wires runtime state at application startup |

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
| `addHeadersEndHandler` | last moment **before** headers commit | write `X-Basquin-Req` here |
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

**What is replaced:** lock-based quiescence becomes a **completion-parking poll**. The
`/basquin/result/<id>` handler, on a miss, returns a `Uni` completed by the store's `put` for that id,
bounded at **2 s** (mirroring DD-040's bound), with the driver's read timeout above it at **4 s**
(same reasoning). A timeout is a recorded miss, never a zero.

Per request:

1. Filter assigns `<RUN_SALT>-<n>`, stamps start time, stashes both on the `RoutingContext`, and
   increments the in-flight counter (§6.1).
2. `addHeadersEndHandler` writes `X-Basquin-Req: <id>` at the last moment before commit.
3. `addEndHandler` computes the measurement, records disposition (`completed|disconnected`), and puts
   the result into the store — completing any parked poll.
4. The driver polls `/basquin/result/<id>`.

**A consequence worth stating plainly:** DD-040's opportunistic `X-Basquin-Cost` header fast path
**cannot exist on this path at all** — the measurement is only known after the last byte is written.
So *every* iteration polls, doubling requests per iteration. DD-040 rejected "piggyback request N−1's
result on request N" for complicating a path that mostly did not need it; here the poll is universal,
so that alternative deserves re-evaluation. Deferred to PR-2, flagged, not silently inherited.

## 5. Injection without source modification

The runtime path never touches the app: the operator appends to `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`
and patches only the pod template. The build-time path uses a Maven **core extension**: a
`basquin-maven-injector` jar containing an `AbstractMavenLifecycleParticipant`, activated by
`-Dmaven.ext.class.path`. Its `afterProjectsRead(MavenSession)` hook mutates each `MavenProject`'s
`Model` — adding the `basquin-quarkus` dependency and the offline-JaCoCo plugin execution (§6.4) —
before the per-project execution plan is computed.

**Gradle:** an init script (`-I basquin-init.gradle`) doing the same via `allprojects { … }`.

**Native build arguments** ride the same channel. Note `quarkus.native.monitoring` is an enum list;
whether `nmt` is an accepted value in Quarkus 3.37.3 is unverified, so the fallback
`-Dquarkus.native.additional-build-args=--enable-monitoring=nmt` is the safe form. S2 settles it in one
line — a failed *build flag* would otherwise stall the native cells on a triviality.

No file in the application tree is created or changed. The symmetry:

| | Injection point | Mechanism |
|---|---|---|
| **Runtime** (Tomcat, JVM) | `CATALINA_OPTS` / `JAVA_TOOL_OPTIONS` | operator patches the pod template |
| **Build** (Quarkus, native) | `MAVEN_OPTS` / `-Dmaven.ext.class.path` | lifecycle participant mutates the project model |

This assumes the operator of Basquin controls the `mvn` invocation — has the app checked out and
builds it — while never editing its source. That is the intended deployment model.

### 5.1 The real risk is that Quarkus may not read the model we mutated

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
must **fail loudly**, never silently); builds that run inside their own Dockerfile never see our
`MAVEN_OPTS`; and injecting a plugin *execution* is harder than injecting a dependency.

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
| **5xx / crash** | response status | unchanged, from `RoutingContext.response().getStatusCode()` |
| **Heap delta** | `Runtime` delta under `ITERATION_LOCK` | §6.1 — the lock is impossible; isolation weakens and must be *measured* |
| **Thread leak** | non-daemon thread diff | §6.2 — structurally always zero; replaced |
| **Coverage** | JaCoCo tcpserver `-javaagent` | §6.4 — offline JaCoCo, served by our own route |

**All invariants on this path are soft by structure.** `Invariants.evaluateAndMaybeFail` throwing at
the end handler can fail nothing — the response is fully written by definition of the hook. Tomcat
targets default to **hard** (`basquin.invariant.mode`, `agent/Invariants.java:85`). That is a real
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
- The **taint rate** is reported in the run summary exactly as `reportMisses` is; a majority-tainted
  run fails loudly, following `failOnMissMajority`.
- Local 2×2 runs disable compose healthchecks and say so in the bench manifest; cluster runs accept
  the taint rate as data.

This converts a silent attribution error into a measured limitation.

### 6.2 The JFR cross-check, redefined so it can actually fail

`jdk.ObjectAllocationSample` **is** supported in native-image JFR (Serial GC), and event streaming
works in native — verified. But the earlier draft compared it against net heap delta and called
divergence a finding. Those two quantities **never agree**: `ObjectAllocationSample` is a *throttled
statistical sampler* estimating **gross** allocation (a request allocating tens of KB may emit zero
samples), while `totalMemory - freeMemory` is **net** — allocation minus collection plus resize
artifacts. Without a stated comparator that test is unfalsifiable: it either always "diverges" and is
ignored, or gets a tolerance wide enough never to fire. Both are DD-040 shapes.

DD-004 already ruled on this: *"JFR allocation sampling … is statistical; if adopted later it belongs
behind soft signals only."* That ruling stands and this spec now respects it.

**Redefined:** aggregate `ObjectAllocationSample` over the **whole run** (or per-route over many
iterations) and compare **per-route rankings**, not per-request magnitudes. Native JFR streaming
events carry no stack traces, so a divergence cannot be localised further than a route.

**The exact cross-check lives in the JVM-mode cells.** `ThreadMXBean.getThreadAllocatedBytes` on the
event-loop thread is exact and per-thread, but `com.sun.management` is JVM-mode only — SubstrateVM
does not implement it. So the 2×2 gives, for free: **JVM cells = exact cross-check, native cells =
statistical.** Use it that way.

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
image as ordinary code. Our extension serves the execution data on `/basquin/coverage`;
`JacocoCoverageProvider` (DD-012/DD-023) changes **transport only** — tcpserver becomes HTTP — and
union-merge across replicas keeps working.

Two mechanics the injector must respect, because they are how this silently produces wrong numbers:

- The driver's `Analyzer` must run against the **original, pre-instrumentation classes** (offline
  instrumentation embeds the pre-instrumentation class id). The injector must therefore preserve them
  — JaCoCo's own backup directory, `target/generated-classes/jacoco`.
- `/basquin/coverage` reads `RuntimeData` **directly**, not via the `jacoco-agent.properties`
  agent-boot path (shutdown hooks, file output), which may not survive native.

### 6.5 Latency's population changed, not just its accuracy

`addEndHandler` fires when the response is fully written **to the wire**, so the reading now includes
client drain and TCP backpressure — a number the Tomcat rows never included (the valve exits when the
app returns; the connector flushes afterwards). Small for a same-host driver, but a definitional
change: "strictly better" is withdrawn in favour of *"differently scoped — includes write-out,
excludes nothing the valve measured."*

And on a **failed** `AsyncResult` (client disconnect), elapsed-until-abort is not a latency sample at
all. The boundary records disposition and keeps `disconnected` samples out of the latency distribution,
or a flaky driver connection manufactures latency findings.

## 7. Validation

### 7.1 Phase 0 — spikes, on the Quarkus `todo` quickstart, before any real target

| | Question | Failure signatures |
|---|---|---|
| **S1** | Does offline JaCoCo produce *correct* coverage under AOT? | (i) frozen probes; (ii) **inflated baseline from build-time init** — see below; (iii) augmentation/class-id mismatch |
| **S2** | Does `Runtime.totalMemory()/freeMemory()` behave under SubstrateVM's Serial GC; does `System.gc()`; does post-response work quiesce on Hibernate Reactive; does `quarkus.native.monitoring` accept `nmt`? | heap deltas that are GC noise, like the `heapDriftKb` debt already on the books |
| **S3** | Does `addEndHandler` fire on errors, 3xx, and client disconnects, and does `addHeadersEndHandler` survive response rewrites? | the requests we most care about are silently skipped |
| **S4** | Does Quarkus **augmentation** honour the injected dependency — i.e. does the banner list `basquin`, JVM **and** native, through the containerised build? | a successful build producing a silently uninstrumented binary |

**S1's likeliest failure is not the one an earlier draft named.** Quarkus registers application classes
for **build-time initialization** by default, so during `native-image` the instrumented `<clinit>` runs,
`$jacocoInit` executes, and both the probe arrays and JaCoCo's `RuntimeData` are captured into the image
heap. Image-heap objects are **writable at runtime**, so the arrays do not freeze — the likelier defect
is **pollution**: probes executed during image build read as covered forever, inflating the baseline.
§7.3's "coverage must increase" control does **not** catch this — coverage increases fine from an
inflated floor, and the benchmark's headline percentage is simply wrong. S1 must therefore assert that
a **never-exercised class reads zero**, and record which classes Quarkus shifted to runtime init.

S1–S4 share no state and **run as concurrent subagents**.

**Phase 0 gates the rest of this spec.** If **S1** fails, §6.4 is void and coverage-guided exploration
on native needs redesigning — which reopens the full-parity goal in §2, since coverage is what forces
the compile-in step. If **S4** fails, §5's no-source-modification property cannot be met by the
described mechanism and §5.1's degradation becomes the norm. Either outcome returns here before any
implementation proceeds.

### 7.2 Phase 1 — the 2×2

|  | JVM mode | Native |
|---|---|---|
| **`rest-villains`** (blocking) | is the extension itself correct? | does build-time attachment survive AOT? |
| **`rest-heroes`** (reactive) | are the reactive boundary semantics right? | ← the actual target |

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
`/basquin/control/defect/{slow,alloc,block-loop}` — disabled by default, enabled by a system property
only during Phase-2 control runs. The extension is injected tooling, not app source, so the thesis
holds; the controls run in the *same* process and stack cell as the published rows; and they are
reusable for every future Quarkus target.

| Invariant | Control | Assertion |
|---|---|---|
| latency | `/basquin/control/defect/slow` | violation **arrives in the driver-visible result** |
| event-loop blocking | `/basquin/control/defect/block-loop`, sleeping **comfortably above the pinned threshold** | store entry → finding → rendered row |
| heap | `/basquin/control/defect/alloc` | delta recorded **and not tainted** (§6.1) |
| heap, **positive-noise** | idle window, no driver request | must read ~zero or `UNMEASURED` — this is the control that catches probe pollution and the `heapDriftKb` class of error |
| coverage | routes exercised progressively | must increase **and** a never-exercised class must read zero (§7.1 S1) |

Every control is verified **end-to-end at the reporting layer** (`render_page.py` input), not at the
log line — otherwise the control validates the logger, not the invariant. An invariant without a
passing control is **not published**, matching the discipline that keeps `heapDriftKb` off the page.

### 7.4 Reporting pipeline

- `deploy/bench/render_page.py` assumes the Tomcat invariant set. It must handle **per-target invariant
  sets** — no thread-leak column for reactive targets, an event-loop-blocking column instead — plus
  per-target **invariant mode** (§6: soft-by-structure here, hard on Tomcat) and the **taint rate**.
- Every figure in the native row is derived from the run artifact through that generator. No hand-typed
  numbers.

## 8. Open questions

### 8.1 Does the Apicurio Registry *server* build native?

Unresolved. A secondary source describes Apicurio's native support as an experimental `-Pnative` build,
but the repository's own `-DcliSkipNative` flag appears to govern the **CLI** native image rather than
the server. Target 5 is gated on verifying this directly. If the server does not build native, a
substitute real-product Quarkus target is needed — the credibility requirement (a real application, not
a reference demo) is what matters, not Apicurio specifically.

## 9. Delivery — six PRs in dependency order

This is not one implementable unit. It spans a cross-cutting refactor of the measurement core, a new
Quarkus extension, a new Maven core-extension artifact, a driver transport change, a reporting change,
four spikes, and a five-target benchmark program. Under the project's own PR-granularity rule this is
six cohesive features, and a PR of that span cannot be adversarially reviewed at the depth DD-040's
history says this repo needs.

| PR | Contents | Gate |
|---|---|---|
| **PR-0** | Phase-0 spikes S1–S4 → `bench-results/dd043-spikes-…/`, plus whatever spec amendments they force. **No product code.** | Gates everything below |
| **PR-1** | `basquin-core` extraction (§4.1) — pure refactor, zero behaviour change, existing tests green, no Quarkus code | PR-0 |
| **PR-2** | `basquin-quarkus` MVP — filter boundary, result store + parking poll (§4.4), `/basquin/status\|result`, control defect routes (§7.3); validated on `rest-villains` **JVM mode** | PR-1 |
| **PR-3** | `basquin-maven-injector` + Gradle init stub; acceptance is §5.2's banner, zero pom edits | PR-2 |
| **PR-4** | Coverage — offline-JaCoCo execution injection, `/basquin/coverage`, `JacocoCoverageProvider` HTTP transport; the native 2×2 cells | PR-3 |
| **PR-5** | Reactive invariant set (§6.3 watchdog), `render_page.py` per-target sets, benchmark rows, docs | PR-4 |

Docs land with their PR: `THIRD-PARTY-APPS.md` gains a build-time-injection section, `ARCHITECTURE.md`
gains the build-vs-runtime injection symmetry.

## 10. Execution note

Independent work fans out to concurrent subagents by default: S1–S4 in Phase 0, the JVM-mode builds in
Phase 1, per-target benchmark batteries thereafter. Subagent reports go to files and return short.
Native compilation is the exception — serialized on a mutex (§7.2). One campaign at a time, and nothing
CPU-heavy during a run.
