# Design Decisions

A running log of significant design choices, the alternatives considered, and why.
Newest entries at the bottom. Keep entries short; the goal is that six months from
now we can answer "why is it built this way?" without archaeology.

Format per entry: **Context → Decision → Why → Rejected alternatives.**

---

## DD-001: Latency is measured before the end-of-iteration grace sleep (2026-07-19)

**Context.** `Agent.endIteration()` sleeps 25ms as a grace period so short-lived
threads can wind down before the leak snapshot. Latency was computed *after* the
sleep, inflating every reading by ~25ms — any latency threshold under 25ms fired
unconditionally, and the metric had a hard floor unrelated to target work.

**Decision.** Capture `elapsedMs` at entry to `endIteration()`, before the sleep.
The grace period exists for the *leak snapshot*, not the latency oracle.

**Why.** Signal quality is The One Rule. A latency invariant that reports harness
overhead is a false oracle.

**Rejected.** Removing the sleep entirely — it still serves the leak check; making
it configurable was deferred until someone actually needs to tune it.

---

## DD-002: Heap-delta GC is opt-in, not default (2026-07-19)

**Context.** Heap delta is measured as `totalMemory - freeMemory` deltas.
Without a GC, that measures *allocation churn*, not *retention* — an
allocation-heavy but leak-free iteration trips the heap invariant.

**Decision.** `-Dclosurejvm.heap.gcBeforeMeasure=true` runs `System.gc()` before
the baseline and end-of-iteration readings (symmetrically, and before the latency
clock starts). Off by default.

**Why.** `System.gc()` is slow and would dominate iteration throughput if always
on. Opt-in gives leak-hunting runs a truthful retention signal while keeping
throughput-oriented runs fast. Opinionated defaults, but honest flags.

**Rejected.** Always-GC (kills throughput); GC-every-N-iterations (nondeterministic
signal — the same input would produce different heap deltas depending on position
in the run, violating "determinism is a feature").

---

## DD-003: Stack-free thread snapshots on the hot path; stacks captured lazily (2026-07-19)

**Context.** Every iteration called `Thread.getAllStackTraces()` (twice) to
enumerate threads — forcing a JVM safepoint that pauses all threads and walks
every stack — then discarded the stacks. Only the thread *set/count* was needed;
stacks matter only when something is flagged.

**Decision.** Use `ThreadMXBean.getThreadCount()` for counts and root-ThreadGroup
enumeration for the non-daemon set (both stack-free). Capture stacks only for
threads that actually leaked, or on invariant violation.

**Why.** The expensive work moved from every iteration to the rare failure path.
At 10k+ iterations and eventually ~200-thread Tomcat JVMs, per-iteration safepoint
stack walks are the dominant avoidable cost.

**Rejected.** Keeping `getAllStackTraces()` for implementation simplicity — the
cost scales with thread count, exactly wrong for the real-app direction.

---

## DD-004: Event-driven thread tracking via an in-process JVMTI agent — not an external watcher process (2026-07-19)

**Context.** We wanted continuous, low-overhead thread observation ("watch the JVM
at all times and feed information in") and considered a separate C program
observing the JVM from outside.

**Decision.** A JVMTI native agent (`native/closurejvmti.c`) loaded via
`-agentpath`, subscribing to `ThreadStart`/`ThreadEnd` events, seeded at `VMInit`
from `GetAllThreads`. It maintains live/non-daemon counts and a weak-global-ref
set of live non-daemon Thread objects. The Java harness reads via a JNI bridge
(`agent.NativeThreadTracker`) with a `ThreadMXBean`/enumeration fallback when the
agent is not loaded. Polling cost is zero because there is no poll — the VM tells
us on every thread start/end.

**Why in-process, not a separate process.** Reading another process's stacks or
heap *consistently* requires stopping it (safepoint / ptrace / Serviceability
Agent) — an external observer doesn't avoid the pause, it just asks for it with
more overhead and more fragility across JVM versions. The only data an external
process reads cheaply is coarse OS metrics (`/proc` thread counts, RSS), which
can't distinguish daemon/non-daemon or see JVM heap — too coarse for our oracles.
JVMTI events deliver *exact* lifecycle information with near-zero steady-state
cost, in-process, where the harness needs it.

**Why events for the leak set too.** Weak global refs to the actual Thread
objects (added at ThreadStart, removed at ThreadEnd) mean the leak oracle does no
enumeration at all; names/stacks resolve lazily from the tracked objects only on
a flagged leak. Weak refs guarantee tracking never keeps a dead thread's object
alive. Daemon status is final once a thread starts, so set membership can't flap.

**Determinism note.** JVMTI delivers every start/end event — deterministic, fit
for a hard oracle. JFR allocation sampling (considered for per-request heap
attribution) is statistical; if adopted later it belongs behind soft signals only.

**Rejected.** External watcher process (see above); JFR-only approach (sampling
is not an enforcement oracle); ByteBuddy bytecode instrumentation for thread
tracking (heavier, and JVMTI gives lifecycle events natively).

---

## DD-005: Concurrency — serialize iterations now (A), context API later (C), ThreadLocal rejected (B) (2026-07-19)

**Context.** All per-iteration baselines are static singletons in `Agent`. The
WAR's `IterationFilter` wraps each servlet request in begin/end, and Tomcat serves
requests concurrently — overlapping requests corrupt each other's baselines.

**Decision.** (A) Serialize: the filter takes a global lock so exactly one request
is inside an iteration at a time. Later (C): refactor to an explicit
`IterationContext` (`ctx = Agent.begin(); Agent.end(ctx)`) as the v0.6 shape.

**Why.** "One clean iteration at a time" *is* the project premise — the harness
measures iteration cleanliness, not throughput under load. Serializing is honest
and ~5 lines. The context API is the right long-term shape but should arrive with
the triage queue payload design (DD-006), not as an emergency fix.

**Rejected.** (B) ThreadLocal contexts: latency scopes cleanly to a thread, but
heap delta and thread delta are *process-global* — under concurrency another
request's allocations/threads land in your delta. B fixes the crash-y symptom
while making the invariants quietly lie, the worst outcome for signal quality.

---

## DD-010: IterationContext replaces static per-iteration Agent state (2026-07-19)

**Context.** Delivers the "C" half of DD-005. Per-iteration baselines (latency
start, heap, thread set, executor/timer identities, latency-sample task) were
static fields on `Agent` — a single global slot that overlapping begin/end pairs
overwrite.

**Decision.** An `IterationContext` object holds all per-iteration state.
`Agent.begin()` returns one; `Agent.end(ctx)` consumes it. The legacy
`beginIteration()`/`endIteration()` remain as wrappers that stash the context in a
`ThreadLocal`, so every existing caller (runners, filter, valve, JQF, tests) is
unchanged. Result fields (latency, deltas, violations, leak flag) live on the
context, ready to feed the triage payload (DD-006) without touching global state.

**Why.** Latency and the leak set now scope per-context — concurrent iterations no
longer corrupt them. It's the clean, testable foundation the context-carrying
triage payload needs, and it removes a whole class of cross-iteration bugs.

**What it does NOT change.** Heap and thread deltas are still process-global (they
measure the whole JVM), so they remain trustworthy only under serialized /
single-flight execution. The servlet integrations therefore keep the DD-005
serialization lock; the context makes the *code* concurrency-safe without claiming
the *heap/thread signals* are. A per-signal concurrency model is future work.

**Rejected.** Removing the serialization lock now that contexts exist — would make
heap/thread deltas lie under load (the DD-005(B) trap again). Changing the public
`beginIteration`/`endIteration` signatures — needless churn across all callers when
a ThreadLocal wrapper is transparent.

---

## DD-011: One namespace-free valve for both javax (Tomcat 9) and jakarta (Tomcat 10+) (2026-07-19)

**Context.** DD-009's valve was compiled against Tomcat 10 (jakarta). Third-party
targets are split: JPetStore and many real apps are still `javax.servlet` (Tomcat 9),
while newer apps are `jakarta.servlet` (Tomcat 10+). The obvious fix — a second
`javax`-compiled valve module — means duplicated source and two artifacts to ship.

**Decision.** Write the valve so its compiled bytecode references **no** servlet
class at all, and one jar loads on both. Three moves: (1) narrow the `invoke`
override to `throws IOException` only (a legal narrowing of ValveBase's
`throws IOException, ServletException`); (2) write headers through the Catalina
`org.apache.catalina.connector.Response` (its `setHeader`/`isCommitted` are concrete
methods, not the servlet interface); (3) re-raise the checked exception from
`getNext().invoke(...)` via a generic `sneakyThrow` helper without naming
`ServletException`. Verified with `javap`: the only external types referenced are
`java.io.IOException` and `org.apache.catalina.*` — zero `javax.servlet` / `jakarta.servlet`.

**Why.** The servlet namespace is the *only* thing that differs between the two
Tomcat lines for this valve; the Catalina connector API (`Request`, `Response`,
`ValveBase`, `getNext`) is stable across 9 and 10. A checked-exception throws clause
is compile-time only (not in the JVM method descriptor), so a T10-compiled override
is a valid override of T9's `ValveBase.invoke` at runtime. One jar, no duplication.

**Verified (2026-07-19).** Same jar: on Tomcat 10.1 (demo WAR) it wraps requests as
before; on Tomcat 9 it loads clean and captures real server-side invariants against
an unmodified JPetStore (see docs/THIRD-PARTY-APPS.md).

**Rejected.** Separate `javax` module (duplicate source, two artifacts, drift risk);
shared-source two-flavor build (needs two source sets and API deps — more machinery
than the namespace-free single artifact); catching `Exception` and wrapping in a
`RuntimeException` (changes the exception Tomcat sees — a real semantic difference).

---

## DD-006: Triage decoupling via an in-process bounded queue — not a message bus (2026-07-19)

**Context.** Proposal: publish metrics/findings to Kafka (or similar) so
processing happens out-of-band, hoping for higher throughput.

**Decision.** In-process bounded handoff (`ArrayBlockingQueue` + one consumer
thread): the iteration enqueues a captured triage payload and moves on; the
consumer does file I/O (bundles, stacks, saved inputs) off the hot path. When the
queue is full, fall back to a synchronous write — findings are never dropped
(fail fast, fail loud). Flush on shutdown.

**Why not a bus.** The per-iteration cost is *capture* (safepoints, snapshots),
which is paid in-process before any transport — a bus can't move it. The
concurrency bug (DD-005) is a measurement-locality problem a bus also can't fix;
it would ship already-corrupted numbers efficiently. And a broker injects async
ordering / at-least-once delivery into the layer that most needs reproducibility
("determinism is a feature"). Operationally it's a new service to run for a
single-JVM harness.

**Where a bus *would* belong.** Multi-node: many harness JVMs publishing findings
to a central triage store (a distributed fuzzing fleet). The queue abstraction
introduced here is deliberately the seam where a bus could be swapped in then.

**Rejected.** Kafka/message bus now (above); unbounded queue (hides backpressure,
OOMs the harness — the thing that hunts leaks must not leak).

---

## DD-007: JDK 21 support = Java 17 bytecode + CI matrix, not a toolchain bump (2026-07-19)

**Context.** We want the harness usable on modern LTS JVMs (17 and 21).

**Decision.** Compile to Java 17 bytecode (`sourceCompatibility` /
`targetCompatibility` 17); CI builds and tests on a JDK 17 + 21 matrix. The JVMTI
native agent needs no version handling — the JVMTI ABI is stable across both.

**Why.** One artifact that runs everywhere beats per-JDK builds. Nothing in the
harness needs 21-only APIs yet; when something does (e.g. virtual-thread-aware
tracking), that's its own decision entry.

**Rejected.** Building with 21 as baseline (drops 17 users for no feature gain);
multi-release JARs (complexity without a current need).

---

## DD-008: Real-app target sequence — JPetStore, then WebGoat, then JSPWiki (2026-07-19)

**Context.** The harness needs third-party Tomcat apps to prove itself outside
our own demo WAR, before being used on private/work codebases.

**Decision.** (1) MyBatis JPetStore: small WAR, reuses our docker-compose MySQL,
code small enough to hand-verify any finding. (2) WebGoat / OWASP Benchmark:
deliberately buggy, guarantees findings — calibrates triage output ("what does a
finding look like"), proves the pipeline, not novelty. (3) JSPWiki: real Apache
project, no DB needed, markup parsing is classic ground for input-dependent
latency pathologies — the differentiated oracle.

**Why this order.** Verifiability first (can we trust a finding?), then pipeline
calibration (guaranteed findings), then genuine hunting (real project). Large
apps (XWiki, OpenMRS) wait for pool/queue sampling — their signal needs it.

**Rejected.** Starting with a big app (findings unverifiable, infra drag);
starting with WebGoat (planted bugs prove plumbing, not value).

---

## DD-009: Third-party WAR integration via Tomcat valve, not a filter in the WAR (2026-07-19)

**Context.** Our demo WAR bundles `IterationFilter` (declared in its own
`web.xml`) to mark iteration boundaries and expose invariant headers. Third-party
WARs (JPetStore etc.) can't be modified — we need boundaries around *their*
requests without touching their code.

**Decision.** A Tomcat `Valve` packaged in its own JAR, dropped into Tomcat's
`lib/` and registered in `server.xml` (or context config) via docker-compose
mounts. The valve wraps every request in begin/end exactly like the filter, for
any WAR deployed on that Tomcat, unmodified.

**Why.** Valves sit in Tomcat's own pipeline — no `web.xml` edits, no WAR
repacking, works for any app. Container-level integration matches where the
agent already lives (`CATALINA_OPTS`).

**Rejected.** Repacking third-party WARs to inject the filter (fragile, modifies
the system under test — bad for reproducibility claims); servlet-container
initializers via extra classpath JARs (still WAR-scoped and more magic than a
valve); giving up on server-side boundaries and driving purely from the client
(loses in-JVM invariants, the whole point).

**Follow-up (verified 2026-07-19).** Valve confirmed loading and active in real
Tomcat 10.1 (agent premain ran; WAR deployed clean; routes served; server-side
`status` counters advanced). The valve and the in-WAR `IterationFilter` are
**mutually exclusive** — with both active, each request is wrapped twice (observed
6 `beginIteration` for 3 requests), nesting iteration boundaries meaninglessly.
Use the filter for our demo WAR, the valve for third-party WARs; never both.
Also: match the servlet namespace — a `jakarta` valve requires Tomcat 10+, a
`javax` app requires Tomcat 9 and a `javax`-compiled valve. See
docs/THIRD-PARTY-APPS.md.
