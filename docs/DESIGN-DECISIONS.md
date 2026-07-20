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

---

## DD-012: Coverage over HTTP via a JaCoCo agent in the app JVM, read by the client (2026-07-19)

**Context.** The exploration panel needs a real "% of code explored," and the north-star is
coverage-*guided* fuzzing of HTTP inputs. The app under test runs in its own JVM (Tomcat), so its
coverage cannot be seen by the client-side harness/JQF JVM. A percentage also needs a
covered/total denominator, which only instrumenting the app provides.

**Decision.** Put a **JaCoCo agent in the app JVM** (`-javaagent:jacocoagent.jar=output=tcpserver`)
alongside the ClosureJVM agent + valve. A client-side `JacocoCoverageProvider` connects to the
agent's TCP server, dumps execution data (accumulating, no reset), and analyzes it against the
app's class files with JaCoCo's `Analyzer` to compute covered/total instruction probes.
`CoverageDriver` polls this on a background thread and feeds it to
`StatusReporter.recordCoverage`, so the panel shows a real percentage. JaCoCo lives in a scoped
`coverage` source set so it never enters the main jar.

**Why in the app JVM, pulled by the client.** The coverage signal must come from the code under
test; measuring the harness JVM is meaningless for guiding HTTP inputs. JaCoCo's tcpserver is the
standard remote-collection path and needs no app cooperation beyond the `-javaagent` flag the
valve deployment already uses. Accumulating dumps give campaign-total coverage; the client owns
the analysis (and the class files), so nothing app-specific ships in the harness.

**Status.** The coverage *signal* works end to end (verified: ~4.4% of JPetStore's `org.mybatis
.jpetstore.*` from catalog-route traffic). Using coverage as a *guidance* signal — mutating HTTP
inputs toward new edges — is the next step; this establishes the measurement it needs.

**Rejected.** In-process JaCoCo/JQF coverage of the harness JVM (measures the driver, not the
app); a custom JVMTI coverage agent (JaCoCo is battle-tested and gives covered/total directly);
parsing JaCoCo `.exec` files on disk per sample (the tcpserver dump is live and needs no shared
volume); resetting coverage per request (campaign-total is what a "% explored" means; per-request
deltas are a later refinement for guidance).

---

## DD-013: Decouple the dashboard from the driver process — push to a standalone aggregator (2026-07-20)

**Context.** v0.10's first dashboard slice (`StatusServer`) was an HTTP server embedded in the
harness driver process itself (`GenericRunner`/`CoverageGuidedRun`). It never touched the
app-under-test's JVM — that part was already correctly isolated — but it still coupled the
dashboard's lifetime and blast radius to the process doing the actual measuring and driving, and
it could only ever show one campaign. That's the wrong shape for the stated next step: an
auto-injection operator that instruments many pods and needs one place to watch aggregate metrics
across all of them.

**Decision.** Split into two processes. `DashboardServer` is a standalone aggregator: an
in-memory store keyed by campaign id, fed by `POST /ingest/status?id=` and
`POST /ingest/findings?id=` with the raw JSON a driver already produces, served back verbatim via
`GET /api/campaign/{id}/status|findings` and summarized via `GET /api/campaigns`. `DashboardClient`
runs inside the driver and periodically pushes to it — the driver process never opens a listening
port. The dashboard's page gained a fleet view (campaign cards, alive/stale by last-seen) with
drill-down into one campaign's full metrics + findings.

**Why raw-JSON store-and-forward, not real parsing.** `DashboardServer` never parses the payload
structurally — it stores the harness's JSON text and replays it to the browser, which does the
real `JSON.parse`. The one exception is a narrow best-effort regex scrape of a few numeric fields
(iterations/crashes/coverage %) purely for the fleet-view summary cards; it degrades to "—" on a
miss and never throws. This keeps the aggregator schema-agnostic (no coupling to
`StatusReporter`'s JSON shape, no dependency on a JSON library) and correctly stateless about
what a campaign's payload means — exactly the shape that lets many different pods/producers push
to one dashboard without the dashboard needing to understand or version their internals.

**Why id defaults to `HOSTNAME`.** In Kubernetes, `HOSTNAME` is the pod's name by default — so a
driver running as (or alongside) a pod reports under a natural, unique identity with zero
configuration, which is the identity the future auto-injection operator would want per instrumented
pod.

**Verified (2026-07-20).** Two independent processes: `DashboardServer` on its own port, a
`CoverageGuidedRun` driver hitting the kind JPetStore pod and pushing to it. Fleet view showed the
live campaign (`alive:true`, real iteration/crash/coverage numbers); campaign detail and findings
endpoints round-tripped the driver's data correctly.

**Rejected.** Keeping the embedded per-driver dashboard (DD-012's `StatusServer`) — couples
lifetime, no cross-campaign view, and is the wrong foundation for the operator's "watch the whole
fleet" goal; a message bus between driver and dashboard (same reasoning as DD-006 — the
measurement cost is elsewhere, and a bus is overkill for a store-and-forward relay at this scale);
full server-side JSON parsing/validation of the payload (adds a dependency and a schema coupling
the aggregator doesn't need for v1).

---

## DD-014: Noise reduction by deterministic clustering on the read path, not by dropping findings (2026-07-20)

**Context.** Soft-mode invariants are deliberately generous — that's what makes them useful for
exploration. But it means a single systemic behavior (JPetStore allocating proportionally to page
size) produced 125 near-identical `heapDelta` findings on one route shape. Reviewing "genuine
issues" in a list like that is hopeless; the signal is real but the presentation buries it. The
temptation is to filter at save time (thresholds, rate limits, dedupe-before-write).

**Decision.** Never change what gets saved. Cluster at **read time**, in the dashboard, by a
fingerprint: `classification + invariant-kind + route-pattern` (parameter *values* stripped, shape
kept), or `crash + exception-class` for crashes. Each cluster reports count, distinct concrete
routes, magnitude range, and first/last seen. The corpus on disk stays whole.

**Why not filter at save time.** DD-006's "never drop a finding" exists because exploration needs
the full corpus — a finding that looks redundant to a human is still a distinct input that reached
distinct state, and the coverage-guided loop (DD-012) may care. Dropping at write time is
irreversible and couples the *oracle* to a *presentation* concern. Clustering is a pure function
of saved data: reversible, tunable later, and it can be recomputed differently without re-running
a campaign.

**Why deterministic, not the LLM.** agents.md's "enforcement > inference": anything the bug oracle
or triage relies on should be a hard check. A model deciding what counts as a duplicate finding
would make results non-reproducible run-to-run. The Claude layer (DD-015) sits strictly *on top*
of these clusters, explaining what a human is already looking at — it never decides what is or
isn't a finding.

**Verified (2026-07-20).** Against 937 real findings accumulated across this project's runs:
937 → 19 clusters. Testing at that scale caught two real bugs that unit-sized data would have
hidden: (1) a `(?:[^"\\]|\\.)*`-style regex for matching escaped JSON strings overflows the stack
(Java's `Pattern$Loop` recurses per character) on findings text of a few thousand chars — replaced
with an iterative scanner (`JsonScan`); (2) two different saved-finding formats exist in the wild
(HTTP-driven writes `detail=kind: …`, local/JQF writes the bare `kind: …` line), so keying only on
`detail=` left every local finding's kind unresolved as `?`.

**Rejected.** Save-time thresholds/dedupe (irreversible, couples oracle to presentation); stack-
hash fingerprinting for invariants (the sampled stack is nearly identical across these — it's the
route + kind that distinguishes them); LLM-based grouping (non-reproducible, see above).

---

## DD-015: Claude API analysis is opt-in, dashboard-side, and strictly advisory (2026-07-20)

**Context.** Clustered findings answer "what's distinct"; they don't answer "which of these is
actually a bug versus expected proportional behavior, and what would I check first."

**Decision.** An optional `POST /api/analyze/{campaign}` on the standalone dashboard builds a
prompt from the *already-clustered* summary plus campaign status, calls the Claude Messages API,
and returns prose. Triggered only by an explicit button click — never on the 1.5s auto-refresh.
The API key is read only by the `DashboardServer` process (`ANTHROPIC_API_KEY` or
`-Dclosurejvm.claude.apiKey`); it is never passed to a driver, never sent to the app under test,
and never rendered in the UI. Absent a key the endpoint returns a clear "not configured" message
and the rest of the dashboard is unaffected.

**Why advisory only.** The oracle stays deterministic (DD-014). Claude summarizes clusters a human
is already reviewing — it cannot create, suppress, or reclassify a finding. That keeps
reproducibility intact: two runs over the same corpus produce the same clusters regardless of
whether anyone clicked Analyze.

**Why the dashboard process.** It's the one component that already aggregates across campaigns and
is deliberately decoupled from both the driver and the app (DD-013) — the natural place for an
outbound network dependency and a secret, and the only place with the cross-campaign context that
makes the analysis worth asking for.

**Rejected.** Auto-analysis on refresh (unbounded cost, and would make the page's content
non-deterministic); running analysis in the driver (would put a secret and an outbound dependency
next to the measurement loop, violating DD-013); using the LLM to filter/dedupe findings
(see DD-014); an SDK dependency (one `HttpURLConnection` POST matches the project's
no-extra-dependency posture).

---

## DD-016: The exploration surface is a seed corpus, not compiled code (2026-07-20)

**Context.** `CoverageGuidedRun` originally hardcoded JPetStore's routes as Java string arrays and
synthesized new ones in a `randomRoute()` switch. Coverage plateaued at 8.6% and I attributed it
to "GET-only exploration can't reach checkout/order without sessions." That explanation was wrong,
and reviewing the app's actual endpoint surface proved it: JPetStore exposes **21 handler methods**
across 4 Stripes ActionBeans, and the hardcoded grammar reached **7 of them**. All of
`AccountActionBean` (7 handlers) and `OrderActionBean` (4) were unreachable — not because they
need sessions, but because no code path ever generated their URLs. Every other target in this repo
had `examples/corpus/<name>/`; JPetStore had none.

**Decision.** Endpoints come from a seed corpus (`examples/corpus/jpetstore/`, one route per file,
`-Dclosurejvm.corpusDir`), covering all 21 handlers. `CoverageGuidedRun` loads seeds, deterministically
exercises each one once before random exploration, and mutates only *parameter values* using
dictionaries. `randomRoute()` is deleted — the runner no longer invents endpoints. An empty corpus
warns loudly instead of silently exploring almost nothing.

**Why.** Baking the reachable surface into compiled code makes the fuzzer's ceiling invisible: the
coverage number looks like a property of the app when it's really a property of a hardcoded list.
As data, the surface is inspectable, diffable, extendable without a rebuild, and reusable across
targets — and a missing corpus becomes an obvious gap rather than a silent cap.

**Verified (2026-07-20).** Same app, same driver, same duration — coverage **8.6% → 17.3%**
(549 → 1103 of 6368 instructions), exactly doubling by reaching the previously-unreachable half of
the app. It also immediately surfaced **4 genuine crash clusters** where the run had previously
reported zero crashes:

```
java.lang.NullPointerException: Cannot invoke
  "AccountActionBean.getAccount()" because "accountBean" is null
    at OrderActionBean.listOrders(OrderActionBean.java:110)
```

`listOrders`, `viewOrder`, `newAccount`, and `editAccount` dereference the session account bean
with no null check, so an unauthenticated request yields an unhandled NPE and HTTP 500 instead of
a redirect to sign-on. These are real defects in the app under test, found only because the
exploration surface stopped being hardcoded.

**Rejected.** Keeping the hardcoded grammar and "explaining" the plateau (the explanation was
wrong and would have permanently hidden half the app); generating routes by crawling links (real,
but a bigger feature — a corpus is the honest primitive underneath it and is needed either way).

---

## DD-017: Grammar-driven request generation, and showing the input that caused a finding (2026-07-20)

**Context.** DD-016 moved *routes* out of code into a seed corpus, doubling coverage. But the
*parameter value space* was still hardcoded Java arrays (`CATS`, `PRODS`, `ITEMS`, `KW`), so the
fuzzer could only ever submit values someone had already thought of — it could reach every
endpoint but only with known-good inputs. Separately, the dashboard showed *that* a finding
happened but never *what input caused it*, even though `FuzzIO` had been saving each input as a
`.bin` beside every `.meta.txt` all along. Both gaps were reported by the user reviewing results.

**Decision — grammar.** A `RequestGrammar` file supplies both the routes and their value space:

```
$itemId  = EST-1 | EST-2 | <string> | <empty>
/actions/Cart.action?addItemToCart=&workingItemId=${itemId}
```

Rules mix literal dictionary values (to stay on happy paths and reach deep code) with
**generators** — `<int>` (boundary-biased), `<string>` (short, sometimes metacharacters),
`<long>`, `<empty>` — which produce values no hand-written list would. Mutation re-expands the
template behind an input, so mutants stay well-formed instead of becoming garbage URLs. Grammar
takes precedence over a plain seed corpus; both are data, neither is compiled in.

**Decision — input viewer.** `DashboardClient` now reads the sibling `.bin` for each finding
(text if ≥90% printable, else hex, capped at 2KB), the clusterer keeps a bounded sample of
concrete inputs per cluster, and cluster rows in the dashboard expand to show them — selectable,
so an HTTP finding can be copy-pasted straight into `curl` to reproduce. Crash findings don't
write a `route=` line, so their route now falls back to the saved input; previously every crash on
every endpoint collapsed into one cluster keyed only by exception class, which is what made the
dashboard read `route=(none)`.

**Verified (2026-07-20)** against the JPetStore pod, same driver and duration:
- corpus-only (DD-016): 17.3% coverage, crashes at 4 sites
- grammar: 17.7% coverage, crashes at **6 distinct sites in the app's own code**

The extra site is the point: `CartActionBean.addItemToCart:81 → CatalogService.isItemInStock:88`
NPEs on a `workingItemId` that isn't a real item — only reachable because `<string>` generated an
id no dictionary contained. Coverage barely moved (+23 edges) while *distinct defects found* went
up 50%, which is the honest way to read this: grammar generators buy bug-finding depth at a given
coverage level, not raw coverage breadth.

**Rejected.** A full BNF/EBNF grammar (this is a URL-shaped domain — one line per template with
`${}` placeholders covers it, and stays hand-editable); pure random byte mutation of URLs (breaks
the request shape immediately and never reaches deep handlers); shipping input bytes on every
status push regardless of size (capped and sampled instead — a campaign can save thousands).
