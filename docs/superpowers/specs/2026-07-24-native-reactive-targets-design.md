# DD-043 — Native and reactive targets

**Status:** designed, not yet planned (2026-07-24)
**Depends on:** DD-040 (trustworthy measurement channel), DD-012/DD-023 (coverage over HTTP)
**Related:** DD-009/DD-011 (why the valve exists), DD-029 (closure's thesis stated), DD-005/DD-010
(why the iteration lock exists)

---

## 1. Context

Every target Basquin has run against — JPetStore, JSPWiki, Roller — shares two properties that the
tool has quietly assumed are universal:

1. **Instrumentation attaches at runtime.** A `-javaagent` premain, a valve jar in Tomcat's `lib/`,
   a JVMTI `-agentpath`, and a JaCoCo tcpserver agent, all injected by the operator through
   `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`.
2. **The app is thread-per-request.** A request occupies one thread for its whole life, so
   "method enter → method exit" is the request lifetime, and a global `ITERATION_LOCK` held across
   the app call makes per-request heap and thread deltas attributable.

A GraalVM-native, reactive Quarkus application violates both. Neither assumption is written down as
a constraint anywhere in the codebase, which is precisely why they need naming before they are
designed around.

This spec covers **both axes at once**, deliberately, because they are independent and conflating
them is what would make a wrong number hard to localise:

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

Build-time instrumentation serves the same thesis at least as well, and arguably better:

- A `-javaagent` boundary can be detached, disabled, or silently fail to attach. It has: the
  ByteBuddy transform of `StandardHostValve` silently no-opped on Tomcat images shipping a newer JDK
  until `net.bytebuddy.experimental` was set (`agent/BoundaryInstaller.java:39-48`).
- A boundary compiled into the binary cannot be removed, cannot fail to attach, and cannot be
  omitted from a deployment.

**The non-negotiable constraint this spec inherits: no file in the application's source tree is
created or modified.** §5 is how that is met for a build-time path.

## 2. Goals and non-goals

**Goals**

- Instrument a GraalVM-native Quarkus application with the full signal set: request boundary,
  availability invariants, and coverage-guided exploration.
- Establish correct measurement semantics for an event-loop request model.
- Preserve the no-source-modification property on the build-time path.
- Produce a benchmark row for a native/reactive target that is comparable in trustworthiness — not
  necessarily in *shape* — to the existing three.

**Non-goals**

- The runtime-agnostic binary/OS instrumentation channel (eBPF, `/proc`, `LD_PRELOAD`). It is
  genuinely attractive — it would reach Go, Rust and Node targets, matching the runtime-agnostic
  ambition `docs/ARCHITECTURE.md` already states for the operator — but it structurally cannot
  provide coverage-guided exploration, and it is a different product thread. Recorded here so the
  option is not rediscovered as novel later.
- A GraalVM `Feature`/`@Substitute` front-end covering Spring Native, Micronaut and Helidon. The
  shared core in §4.1 leaves this reachable without a rewrite; it is not built now.
- Load mode against native targets. Explore first; DD-042 owns the load oracle.

## 3. Targets

| Order | Target | Stack | Purpose |
|---|---|---|---|
| 1 | `rest-villains`, JVM mode | RESTEasy Reactive, **blocking** endpoints; Hibernate ORM + Panache; PostgreSQL | Control. Isolates extension correctness. |
| 2 | `rest-villains`, native | as above, `-Dnative` | Isolates whether build-time attachment survives AOT. |
| 3 | `rest-heroes`, JVM mode | RESTEasy Reactive, **reactive**; Hibernate Reactive + Panache; PostgreSQL; port 8083 | Isolates reactive boundary semantics. |
| 4 | `rest-heroes`, native | as above, `-Dnative` | **The actual target.** |
| 5 | Apicurio Registry | Quarkus; PostgreSQL/KafkaSQL/in-memory storage | Real-product credibility row. **Gated on §8.1.** |

Both services come from [`quarkusio/quarkus-super-heroes`](https://github.com/quarkusio/quarkus-super-heroes).
Both are **contract-first**: the REST interface is generated at build time from
`src/main/resources/openapi/openapi.yml`, which gives the driver a precise route-and-parameter seed
corpus rather than a hand-built one.

`rest-villains` is a throwaway control, not a maintained target. It costs one extra build and no new
code, and it is what makes a wrong number localisable — see §7.2.

### 3.1 Toolchain

Super Heroes' base JVM is **Java 25**; this host has JDK 17 and Maven 3.6.3. Both the Maven build and
`native-image` therefore run in containers (`./mvnw` supplies Maven;
`quarkus.native.container-build=true` supplies the builder image), so nothing needs installing
locally. Every build goes through Docker, which is slower — acceptable, but it is why build count is
a design consideration and why the full 7-service Super Heroes stack was rejected as a first target.

## 4. Architecture

### 4.1 `basquin-core` — extracted shared module

Invariant evaluation and the result store currently live in `agent/`, coupled to the java-agent path.
The extension needs the same logic; copying it would create a third divergent copy alongside the
agent and valve.

Extract the framework-neutral parts — invariant definitions and evaluation, the DD-040 result store
and its id scheme — into `basquin-core`, depended on by the agent, the valve, and the new extension.
**Extract only what is already duplicated.** No speculative abstraction for frameworks that do not
yet have a target.

### 4.2 `basquin-quarkus` — the extension

The standard two-module Quarkus extension shape:

- **`runtime/`** — ships into the app and is AOT-compiled into the native image: boundary filter,
  result store, invariant evaluation, status/result/coverage routes.
- **`deployment/`** — `BasquinProcessor` and its `@BuildStep` methods, which run only at augmentation
  and never enter the image.

Build steps:

| Build step | Purpose |
|---|---|
| `FeatureBuildItem("basquin")` | Prints in the startup banner — the deploy signal, matching `Basquin Agent initialized` |
| `RouteBuildItem` (filter) | Installs the request boundary (§4.3) |
| `RouteBuildItem` (endpoints) | `/basquin/status`, `/basquin/result/<id>`, `/basquin/coverage` — registered as Vert.x routes so they exist in native without JAX-RS scanning |
| `@Recorder` | Wires runtime state at application startup |

### 4.3 The boundary is a Vert.x route filter

**This is the load-bearing decision.** `@ServerRequestFilter`/`@ServerResponseFilter` are the obvious
choice and the wrong one: they sit inside JAX-RS, so they miss non-JAX-RS traffic, and a response
filter can run before the response body is actually written.

A Vert.x filter registered via `RouteBuildItem` sits below everything, sees all HTTP, and exposes
`RoutingContext.addEndHandler()`, which fires when the response is **fully written**. On an event
loop that is the only honest definition of request lifetime.

### 4.4 The DD-040 channel transplants unchanged

Per request:

1. Filter assigns `<RUN_SALT>-<n>`, stamps start time, stashes both on the `RoutingContext`.
2. Filter writes `X-Basquin-Req: <id>` **before the response commits**. The id is known at request
   start, so this is always safe.
3. `addEndHandler()` computes the measurement and records it in the per-JVM result store.
4. The driver polls `/basquin/result/<id>`.

That the measurement is not available when headers must be sent is *more* acute on an event loop, not
less. DD-040 already solved exactly this, and it is the reason this target is reachable at all.

## 5. Injection without source modification

The runtime path never touches the app: the operator appends to `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`
and patches only the pod template. The build-time path has an exact analogue, proven at scale — it is
how Develocity auto-injects its Maven extension into CI builds it does not own.

**Maven.** A `basquin-maven-injector` jar containing an `AbstractMavenLifecycleParticipant`, activated
by `-Dmaven.ext.class.path=/path/basquin-injector.jar` on the command line or via `MAVEN_OPTS`. Its
`afterProjectsRead()` hook edits the **in-memory** project model before dependency resolution:

- adds `com.basquin:basquin-quarkus` as a dependency (Quarkus discovers it at augmentation via
  `META-INF/quarkus-extension.properties` on the classpath);
- injects the offline-JaCoCo instrumentation execution between compile and package (§6.4).

**Gradle.** An init script (`-I basquin-init.gradle`) doing the same via `allprojects { … }`.

**Native build arguments** ride the same channel: `-Dquarkus.native.monitoring=jfr,nmt`,
`-Dquarkus.native.container-build=true`. All command-line, all zero-modification.

No file in the application tree is created or changed. The symmetry:

| | Injection point | Mechanism |
|---|---|---|
| **Runtime** (Tomcat, JVM) | `CATALINA_OPTS` / `JAVA_TOOL_OPTIONS` | operator patches the pod template |
| **Build** (Quarkus, native) | `MAVEN_OPTS` / `-Dmaven.ext.class.path` | lifecycle participant edits the project model |

This assumes the operator of Basquin controls the `mvn` invocation — i.e. has the app checked out and
builds it themselves — while never editing its source. That is the intended deployment model.

### 5.1 Injection risks

1. **Version conflicts.** A strict `dependencyManagement` or BOM may pin something the extension
   needs. The injector must **fail loudly** rather than silently produce an uninstrumented binary — a
   silent no-op here is the `BoundaryInstaller` failure mode repeating.
2. **Builds that run inside their own Dockerfile** never see our `MAVEN_OPTS`. Super Heroes builds via
   `./mvnw`, so this is controllable here, but it is a real limit and belongs in the docs.
3. **Injecting a plugin *execution*** is harder than injecting a dependency and is the part most
   likely to fight a given app's build. Spike S4.
4. **Degradation is explicit.** If injection proves impossible for some app, a pom edit is acceptable
   *as a documented degradation for that app*, never as the design — and the benchmark row must
   record which mode was used.

## 6. Signals

DD-010 lists the four signals the valve captures. Three change meaning here; one changes identity.

| Signal | Tomcat today | native + reactive |
|---|---|---|
| **Latency** | valve self-times the call | filter start → `addEndHandler`; strictly better, now includes body write |
| **5xx / crash** | response status | unchanged, from `RoutingContext.response().getStatusCode()` |
| **Heap delta** | `Runtime` delta under `ITERATION_LOCK` | §6.1 — the lock is impossible |
| **Thread leak** | non-daemon thread diff | §6.2 — structurally always zero; replaced |
| **Coverage** | JaCoCo tcpserver `-javaagent` | §6.4 — offline JaCoCo, served by our own route |

### 6.1 Heap delta — move serialization from the app to the driver

`ITERATION_LOCK` cannot be held across an event-loop call without blocking the loop. But explore mode
already runs one iteration at a time: if the driver holds concurrency 1 and waits for each response,
the app has one request in flight, and the whole-heap start→end delta is attributable **with no
in-app lock at all**. Today's semantics survive and the comparison to the Tomcat rows stays
apples-to-apples.

**Cross-check, and the cross-check matters more than the primary.** JFR's
`jdk.ObjectAllocationSample` gives concurrency-safe per-context allocation — which `TODO.md:920`
already anticipated ("per-thread allocation counting (`ThreadMXBean.getThreadAllocatedBytes` / JFR)").
Run both. Divergence means the event loop is polluting the window, which is itself a finding. This
applies the DD-040 discipline *before* there is a problem rather than after.

JFR is available in native via `--enable-monitoring=jfr`, including user-defined `jdk.jfr.Event`
subclasses; `nmt` additionally provides `jdk.NativeMemoryUsage`/`jdk.NativeMemoryUsagePeak`, which are
the more meaningful memory numbers for a native binary.

### 6.2 Thread leak is replaced, not re-measured

On a fixed event-loop pool the non-daemon thread diff is always zero. The invariant would report
"clean" forever while being **structurally incapable** of reporting anything else — precisely the
DD-040 failure mode, and worse this time because it would ship knowingly.

Reactive substitutes:

- **Event-loop blocking** — Quarkus's built-in blocked-thread checker
  (`quarkus.vertx.max-event-loop-execute-time`). Blocking the loop *is* the signature reactive
  availability defect, so this is the **headline invariant** for this class of app, not a consolation
  prize.
- **Worker-pool saturation** and **Hibernate Reactive connection-pool pending acquisitions** — the
  reactive analogue of executor-queue retention.
- **File-descriptor count** from `/proc/self/fd` — cheap and native-safe.

**Consequence:** the reactive invariant set differs *in kind*, not merely in measurement. The
benchmark page must say so rather than print a thread-leak column of zeros.

### 6.3 What the request boundary must not miss

`addEndHandler` must fire on error responses, 3xx, and client disconnects. A boundary that silently
skips error paths would hide exactly the requests that matter most. Spike S3.

### 6.4 Coverage

Native mode has no runtime coverage agent: Quarkus documents that coverage is unsupported in native
mode and that `quarkus-jacoco` requires a jar artifact, explicitly not a native binary. The working
path is **offline instrumentation**: instrument classes between compile and `native-image`, so the
JaCoCo runtime lives in the image as ordinary code.

Our extension then serves the execution data on `/basquin/coverage`. `JacocoCoverageProvider`
(DD-012/DD-023) changes **transport only** — tcpserver becomes HTTP — and union-merge across replicas
keeps working untouched.

## 7. Validation

### 7.1 Phase 0 — spikes, on the Quarkus `todo` quickstart, before any real target

Four independent experiments. None of their conclusions may be assumed in the plan.

| | Question | Failure signature |
|---|---|---|
| **S1** | Does offline JaCoCo survive AOT, or do `$jacocoData` probe arrays freeze at build time? | coverage reads a constant — a zero meaning "broken" |
| **S2** | Does `Runtime.totalMemory()/freeMemory()` behave under SubstrateVM's GC, and is driver-side serialization sufficient on an event loop? | heap deltas that are GC noise, like the `heapDriftKb` debt already on the books |
| **S3** | Does `addEndHandler` fire on errors, 3xx, and client disconnects? | the requests we most care about are silently skipped |
| **S4** | Can the lifecycle participant inject both a dependency **and** a plugin execution? | falls back to a pom edit, breaking the thesis |

S1–S4 share no state and **run as concurrent subagents**.

**Phase 0 gates the rest of this spec.** These are not confirmations of a settled design; two of them
can invalidate parts of it. If **S1** fails, §6.4's coverage strategy is void and coverage-guided
exploration on native targets needs re-designing from scratch — which would mean revisiting the
full-parity goal in §2, since coverage is the signal that forces the compile-in step in the first
place. If **S4** fails, §5's no-source-modification property cannot be met by the described
mechanism and §5.1's degradation clause becomes the norm rather than the exception. Either outcome
returns to this spec before any implementation plan proceeds.

### 7.2 Phase 1 — the 2×2

|  | JVM mode | Native |
|---|---|---|
| **`rest-villains`** (blocking) | is the extension itself correct? | does build-time attachment survive AOT? |
| **`rest-heroes`** (reactive) | are the reactive boundary semantics right? | ← the actual target |

Each cell isolates exactly one variable from its neighbours, so any wrong number is localisable. The
four builds are logically independent and are **driven by concurrent subagents**, but with one
hardware caveat that overrides the fan-out: `native-image` wants ≥4 cores and several GB each, and
this host has 8 cores / 15 GB. **Native builds are serialized on a mutex; only the two JVM-mode builds
genuinely run in parallel.** Subagents may prepare, drive and analyse concurrently — they may not
compile native images concurrently. No benchmark campaign runs while any native build is in flight.

### 7.3 Phase 2 — the publishability gate

DD-040's rule: a reported zero means "checked and clean", never "never measured". Applied here, **every
invariant ships with a negative control** — a planted defect proving it can fire:

| Invariant | Negative control |
|---|---|
| latency | a deliberately slow route returns a violation |
| event-loop blocking | a `Thread.sleep()` on the loop trips the checker |
| heap | an allocation-heavy route registers a delta |
| coverage | must **increase** as new routes are hit, not merely be non-zero — a frozen probe array reads non-zero but constant, which is S1's failure mode hiding in plain sight |

**An invariant without a passing negative control is not published**, matching the discipline that
keeps `heapDriftKb` off the benchmark page today.

### 7.4 Reporting pipeline

- `deploy/bench/render_page.py` assumes the Tomcat invariant set. It must handle **per-target invariant
  sets** — no thread-leak column for reactive targets, an event-loop-blocking column instead.
- Every figure in the native row is derived from the run artifact through that generator. No
  hand-typed numbers.

## 8. Open questions

### 8.1 Does the Apicurio Registry *server* build native?

Unresolved. A secondary source describes Apicurio's native support as an experimental `-Pnative`
build, but the repository's own `-DcliSkipNative` flag appears to govern the **CLI** native image
rather than the server. Target 5 is gated on verifying this directly. If the server does not build
native, a substitute real-product Quarkus target is needed — the credibility requirement (a real
application, not a reference demo, matching the standard JPetStore/JSPWiki/Roller set) is what
matters, not Apicurio specifically.

## 9. Deliverables

- `basquin-core` — extracted shared invariants and result store (§4.1)
- `basquin-quarkus` — `runtime/` + `deployment/` (§4.2)
- `basquin-maven-injector` — Maven lifecycle participant, plus the Gradle init script (§5)
- `render_page.py` support for per-target invariant sets (§7.4)
- Spike evidence: `bench-results/dd043-spikes-2026-07-24/`
- The 2×2 with negative controls: `bench-results/dd043-acceptance-<date>/`
- Docs: `THIRD-PARTY-APPS.md` gains a build-time-injection section; `ARCHITECTURE.md` gains the
  build-vs-runtime injection symmetry

## 10. Execution note

Independent work fans out to concurrent subagents by default: S1–S4 in Phase 0, the four builds in
Phase 1, and per-target benchmark batteries thereafter. Subagent reports go to files and return short.
The single-node cluster rule still binds: one campaign at a time, and nothing CPU-heavy — a native
image build most of all — during a run.
