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

**Decision.** `-Dbasquin.heap.gcBeforeMeasure=true` runs `System.gc()` before
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

**Decision.** A JVMTI native agent (`native/basquinjvmti.c`) loaded via
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
alongside the Basquin agent + valve. A client-side `JacocoCoverageProvider` connects to the
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
`-Dbasquin.claude.apiKey`); it is never passed to a driver, never sent to the app under test,
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
`-Dbasquin.corpusDir`), covering all 21 handlers. `CoverageGuidedRun` loads seeds, deterministically
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

---

## DD-018: Corpus supplies values, grammar supplies structure — and sessions are the real coverage ceiling (2026-07-20)

**Context.** Two limits surfaced while reviewing results. (1) The grammar file conflated two
different things: concrete values (`FISH`, `EST-1`) and value *structure*. (2) Coverage stalled at
17.7% even with all 21 handlers reachable — because every request was a stateless GET, so
JPetStore's authenticated handlers found a null account bean in the session and 500'd instead of
running their real logic. No amount of grammar tuning fixes that; it's state, not surface.

**Decision — split values from structure.** The **corpus** supplies values
(`$itemId = @../corpus/jpetstore/values/itemId.txt`, one per line, versioned alongside the app's
other seeds). The **grammar** supplies structure (`~EST-[0-9]{1,4}`, `~[A-Z]{2}-[A-Z]{2}-[0-9]{2}`)
— a mini generator over literals, `[a-z]`-style classes and `{n,m}` repetition. A rule mixes both,
plus the existing `<int>/<string>/<long>/<empty>` generators.

**Why the split matters.** Real values reach happy paths and deep code. *Structurally valid but
nonexistent* values (`EST-847`) get past parsing and format validation and into the lookup and
dereference code — where the interesting failures are. Purely random junk usually gets rejected at
the first validation and never gets that far. Keeping values as corpus data also means adding a new
product id is editing a text file, not a grammar.

**Decision — sessions.** The driver maintains a `JSESSIONID` cookie and alternates *session
epochs*: sign on for `-Dbasquin.session.epoch` iterations (default 40), then go anonymous, and
repeat — so both authenticated and unauthenticated paths keep getting probed rather than the run
committing to one. Disable with `-Dbasquin.session=false`.

**Verified (2026-07-20)** on the JPetStore pod. Coverage **17.7% → 22.1%** (1126 → 1409 of 6368),
still climbing when the run ended. The session mechanism was confirmed directly rather than
inferred, with a control:

```
listOrders WITH session:  200
listOrders WITHOUT:       500      # the NPE from DD-016
```

Distinct app-code crash sites went 6 → 7; the new one, `CatalogActionBean.viewItem:181`, is
reachable only via structurally-generated ids (`EST-####` that parse but don't exist) — exactly
the case the corpus/structure split was meant to buy.

**Rejected.** One cookie jar for the whole run (would explore only the authenticated half);
logging in before every request (hides all unauthenticated-path bugs, which is where DD-016's
findings came from); a full cookie-policy/redirect stack (`JSESSIONID` plus follow-redirects is
all this needs, and it stays dependency-free).

---

## DD-019: A crash finding must carry the APP's stack, not the harness's (2026-07-20)

**Context.** Reviewing crash findings in the dashboard, every single one showed the same stack:

```
java.lang.RuntimeException: HTTP 500 for /actions/Order.action?listOrders=
  at runner.coverage.CoverageGuidedRun.request(CoverageGuidedRun.java:228)
  at runner.coverage.CoverageGuidedRun.main(CoverageGuidedRun.java:147)
```

That is the driver's own stack. It records *that* a 500 happened and nothing about *why* — the
actual `NullPointerException at OrderActionBean.listOrders:110` existed only in the container log,
which had to be read by hand. A crash finding you can't triage from is barely a finding.

**Decision.** On a 5xx the driver captures the response body (capped, only for errors) instead of
draining it, parses the container's error page for the server-side exception and frames, and
installs them on the thrown exception via `setStackTrace`. `FuzzIO` then persists the app's
exception type, message, and stack into the finding, so the dashboard shows a directly triageable
record. When no stack can be parsed, the message says so explicitly rather than pretending.

**Why the response body.** The container already renders the failure there, so it needs no
cooperation from the app, no agent change, and works for any servlet app. The alternative — having
the valve capture the throwable — doesn't work here: Stripes catches the exception in its own
handler and returns 500, so nothing propagates out to the valve to catch.

**Verified (2026-07-20)**, and testing against the live app found three bugs a code review of the
parser would not have:
1. Tomcat emits **two** `<pre>` blocks — the framework wrapper first
   (`StripesServletException: Unhandled exception in exception handler`), then a **Root Cause**
   block holding the app's real exception. The first implementation took the first block, i.e. the
   useless one. Now it prefers the root cause.
2. Tomcat's error page renders frames **without** the `at ` prefix that a logged stack has. The
   parser required it and would have extracted **zero frames** on every finding.
3. `/` is HTML-escaped as `&#47;`, so class paths came back mangled; numeric entities are now
   decoded.

A finding now reads, e.g., `DataIntegrityViolationException` with the MyBatis/Spring frames — which
also settled an open triage question, confirming the `newAccount` 500s are duplicate-key violations
from fuzzed usernames (a validation smell) rather than the null-dereference class of defect.

**Rejected.** Capturing the throwable in the valve (the framework swallows it first); scraping the
container log (couples the driver to log access and formatting, and breaks entirely across a
network/pod boundary); reporting only the HTTP status (the status quo — untriageable).

---

## DD-020: Multi-step transactions, run configuration in the dashboard, and what breaks with many target instances (2026-07-20)

**Context.** Three things, all from reviewing real runs.

**1. Multi-step transactions.** Order-placement code needs a *populated cart*, not just a login, so
no amount of single-request fuzzing reaches it. The grammar gained `@sequence` blocks: indented
steps run in order against one session. The critical detail is that placeholders **bind once per
sequence execution**, so `${itemId}` is the same item in `viewItem`, `addItemToCart` and
`removeItemFromCart`. Re-randomising per step would produce an incoherent transaction that adds
one item and removes a different one, never reaching the code a real checkout does. Coverage went
**22.1% → 23.1%** (1409 → 1469 of 6368).

**2. Run configuration is part of the result.** "23% coverage, 7 crash sites" is uninterpretable
without knowing which grammar, endpoints and thresholds produced it. The driver now pushes its
configuration once per run — the `basquin.*`/`examples.*` parameters plus the grammar source —
and the dashboard shows it per campaign. Values whose names look like credentials are redacted:
system properties aren't a deliberate secret store, but this payload is rendered verbatim in a
browser that may be shared.

**3. Findings cluster across target instances.** Fingerprints are instance-independent by
construction (invariant kind + route shape, or exception class), so the same defect found on
several targets merges into one row via `/api/clusters`, with `campaigns` recording where it was
seen — rather than the same bug appearing as N unrelated clusters in N campaigns.

**Also fixed: the invariants card read 0 while findings were full of heapDelta.** Invariants arrive
from two different places — the harness measures latency/heap/thread in ITS OWN JVM, while the
app's agent reports its own through response headers, which land as `Invariant-Remote` findings.
The card only counted the former. It now shows both, separately labelled ("app" vs "harness"),
because conflating them is exactly what made the display look broken.

**Known limits with many target instances.** Cross-campaign clustering solves the
*one-driver-per-pod* topology. The *one-driver-behind-a-Service* topology (N replicas, one driver)
has sharper problems that clustering does not address, and they are not yet fixed:

- **Coverage under-reports.** The JaCoCo tcpserver connection lands on ONE pod while requests
  load-balance across all N, so the coverage figure reflects roughly 1/N of what was actually
  exercised. Fixing it means polling every replica and merging `ExecutionDataStore`s (JaCoCo
  supports merging) rather than talking to a single address.
- **Sessions break.** `JSESSIONID` is pinned to the pod that created it, but a round-robin Service
  will route the next step elsewhere, so multi-step sequences silently lose their session. Needs
  `sessionAffinity: ClientIP` on the Service (or per-pod addressing).
- **No per-instance attribution.** A finding records the route and the app's stack but not *which*
  replica served it, so "one sick pod" is indistinguishable from "systemic". The cheap fix is for
  the valve to stamp its pod identity (`HOSTNAME`) into a response header the driver already reads.

**Rejected.** Merging findings across campaigns by mutating what each driver saves (clustering is
a read-path concern — DD-014); making a sequence abort on a failing step (later steps may still
reach code, and stopping early hides it); pushing configuration every interval (it is immutable
for a run).

---

## DD-021: The harness's own logic gets unit tests (2026-07-20)

**Context.** v0.10 added ~1,800 lines of pure logic — grammar parsing and generation, findings
clustering, JSON scanning, server-error parsing — with **zero** tests. CI was green only because
nothing exercised any of it. Four real bugs were found in that code during development, every one
by hand: a regex that stack-overflowed on realistic input, an error-page parser reading the wrong
`<pre>` block, a frame parser requiring an `at ` prefix Tomcat doesn't emit, and a kind extractor
that only understood one of the two saved-finding formats. None of those were caught by anything
repeatable, and `agents.md` already says a feature is done when it has a minimal test. A tool whose
job is finding other people's bugs cannot credibly ship untested parsing logic.

**Decision.** Unit-test the pure logic that decides what the tool can find and how it reports it:
`JsonScan`, `FindingsClusterer`, `RequestGrammar`. Prefer regression tests tied to bugs that
actually happened over breadth-for-coverage's sake, and state in each test *why* it exists so the
next person doesn't delete it as redundant. Integration behaviour (agent, invariants, leak
detection, reset) keeps its existing forked-process tests.

**Notable cases pinned.** The `JsonScan` stack-overflow guard uses a 20k-frame value, because the
original failure needed only length, not exotic input. The clustering tests encode both saved
formats and the crash route-fallback. The grammar tests pin the sequence invariant — a placeholder
binds **once per sequence execution** — since re-randomising per step yields a transaction that
adds one cart item and removes a different one, which still *looks* fine in a dashboard while
never reaching checkout code.

**Verified by mutation, not just by passing.** Sequence binding was deliberately broken
(`computeIfAbsent` → bind per step); `placeholdersBindOncePerSequenceExecution` failed and nothing
else did. A test that passes but wouldn't fail on the bug it names is worse than no test, so the
guard was checked rather than assumed. 41 tests total, 31 new.

**Rejected.** Chasing a coverage percentage on the harness itself (the goal is regression safety on
logic that has already broken, not a number); unit-testing the HTTP/dashboard plumbing (its value
is in real integration, which the live runs against JPetStore already exercise); deleting the
now-redundant manual verification steps from the docs (they document how the bugs were found).

---

## DD-022: The dashboard is loopback-only, guarded, and never CORS-open (2026-07-20)

**Context.** PR review found the dashboard bound `new InetSocketAddress(port)` — all interfaces —
with `Access-Control-Allow-Origin: *` on every response and no authentication, while exposing
`POST /api/analyze/{id}`, which spends the operator's Claude credit. A cross-origin POST with no
custom headers isn't preflighted, so any page the operator had open could trigger billed calls in
a loop, and anyone on the same network could do so directly.

**Decision.** Three layers, cheapest first:
1. **Bind `127.0.0.1` by default** (`basquin.dashboard.bind` to override). This is a local
   operator tool; network exposure should be opt-in, not the default.
2. **No CORS headers at all.** The UI is same-origin, so the wildcard bought nothing and only
   enabled cross-origin reads.
3. **Require an `X-Basquin-Dashboard` header** on every state-changing or billed endpoint
   (`/ingest/*`, `/api/analyze/*`). A cross-origin "simple" request cannot set a custom header
   without a preflight, and with no CORS headers the preflight fails — so this closes the drive-by
   CSRF path without any token distribution. An optional `basquin.dashboard.token` adds a shared
   secret; when the bind is non-loopback and no token is set, `/api/analyze` refuses outright and
   the server says so at startup.

**Why a header rather than only a token.** A token has to be handed to both the driver and the
browser, which invites embedding it in the page. The header alone defeats the CSRF vector because
of how the browser's preflight rules work; the token is then optional hardening for the deliberate
non-loopback case rather than load-bearing for the default one.

**Verified (2026-07-20).** `ss` confirms the listener is `127.0.0.1:7070` only; `/ingest/status`
and `/api/analyze` both return `missing X-Basquin-Dashboard header` without it and succeed with
it; no `Access-Control-*` header appears on any response.

**Also fixed from the same review.** The kind deploy targeted whatever kubectl context happened to
be current (`kind create cluster` sets it, and is skipped on re-run) — every `kubectl` call is now
pinned to `--context kind-$CLUSTER`, since the failure mode was deploying into a shared cluster.
The JaCoCo tcpserver was published as a NodePort even though its protocol is unauthenticated and
permits *resetting* execution data; the Service is now `ClusterIP`, matching the docs that already
said to use `port-forward`. A fixed `:latest` tag made re-runs a silent no-op that reported success
while the pod kept the old image, so the tag is now unique per build and the deploy does an
explicit `set image`. And `COVERAGE_INCLUDES` defaulted to `*`, directly contradicting the comment
above it and instrumenting Tomcat and MyBatis — inflating the coverage denominator and adding
enough overhead to fake latency violations.

**Rejected.** Leaving the bind wide with a warning (the default should be safe, not documented as
unsafe); putting a token in the served HTML (turns a shared secret into a page-readable one);
disabling `/api/analyze` entirely (the feature is wanted, it just needed a trust boundary).

## DD-023: Coverage merges across replicas, not per-connection (2026-07-20)

**Context.** One HTTP driver drives a target Service that fronts N replicas. Requests load-balance
across all N pods, so no single pod sees the whole campaign — but the coverage reader opened one
JaCoCo tcpserver connection, which lands on exactly one pod. The number it reported was that one
pod's slice: with 3 replicas evenly balanced, coverage read ~1/3 of what the fleet had actually
executed. This is worse than a missing feature — it is a *wrong number* that gets worse the more
you scale, and it silently under-reports exactly when a run matters most.

**Decision.** Read every replica and merge. JaCoCo keys execution data by a CRC64 of the class
bytes, so identical replicas (same image → same class ids) produce the same keys, and dumping all
of them into one `ExecutionDataStore` OR-merges their probe arrays into true union coverage — no
custom merge logic, the store already does it. `JacocoCoverageProvider` now takes a list of
`host:port` endpoints, and each host is resolved with `InetAddress.getAllByName`, so a **headless
Service** name transparently expands to every pod IP behind it — the driver never has to enumerate
pods itself. The `-Dbasquin.coverage.jacoco` flag accordingly accepts a comma-separated list.

**Partial responses are reported, not hidden.** A restarting or unreachable replica is skipped
(one dead pod must not zero the campaign's accumulated coverage), but the sample carries
`sourcesResponded`/`sourcesTotal`, and the status panel shows `[N/M pods]` whenever a replica is
missing or more than one is expected. Under-reporting because a pod is down looks identical to
genuinely low coverage on the number alone; surfacing the fraction is what distinguishes them. If
*nothing* responds, `sample()` throws rather than reporting a confident 0%.

**Why union over the fleet is the right semantic.** The question a campaign answers is "what code
did our inputs reach," not "what did pod 2 reach." Two pods covering disjoint halves of a branch
should read as full coverage of that branch, because the corpus did exercise both sides. OR-merge
gives exactly that; averaging or per-pod reporting would answer a question nobody asked.

**Rejected.** Session affinity to pin the reader and the traffic to one pod (throws away the
parallelism that having replicas buys, and still misses whatever the other pods' warm caches and
state reach); scraping per-pod files and merging offline (a live campaign needs the number now, and
the tcpserver dump already streams it); averaging pod percentages (a covered line on any pod is
covered — union, not mean, is the fleet's real reach).

## DD-024: The operator instruments by appending, is idempotent, and reverts via a finalizer (2026-07-20)

**Context.** Operator P2 (docs/OPERATOR-DESIGN.md §4) makes the `BasquinTarget` controller patch
the referenced Deployment to carry the agents in an *unmodified* app image. Several sub-decisions
shape whether that patch is safe and reversible.

**Decision.**
1. **Copy agents in via an initContainer + shared `emptyDir`.** The app image doesn't contain our
   agents, so an initContainer running a versioned `basquin/agents` image copies them into an
   `emptyDir` mounted into the app container at `/basquin`. Agent upgrades are an image-tag bump,
   never a target rebuild. The image is operator-configurable (`--agents-image`).
2. **Append to `jvmOptsVar`, never replace.** The app's own `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`
   (heap sizing, GC flags) must survive. The original value is stashed in a
   `basquin.dev/original-jvmopts` annotation and the injected value is always recomputed as
   `<original> + " " + <agent flags>`, so re-reconciling never double-appends.
3. **Idempotency by spec hash.** A `basquin.dev/injected: <hash>` annotation records the spec the
   Deployment was last patched for; a steady target is a no-op on every resync, and a spec change
   re-derives from the stashed original rather than layering onto an already-injected value.
4. **Revert via a finalizer, not owner references.** The target holds a `basquin.dev/revert`
   finalizer; deleting it removes the initContainer/volume/mount/port and restores the original
   `jvmOptsVar` — the Deployment returns to exactly its pre-injection shape. Owner references were
   **rejected**: the operator patches a Deployment it does not own, and an owner ref would make
   Kubernetes *garbage-collect that Deployment* when the target is deleted, when the intent is to
   revert it. Drift and late-appearing Deployments are handled by a mapping watch instead (list
   targets in the namespace whose `deploymentRef.name` matches the Deployment).
5. **The valve is deferred.** The Tomcat valve is not a JVM flag — it needs a Catalina
   `context.xml` Valve entry — so `agents.valve` injects nothing in P2; the JVMTI agent, Java agent
   (+ bootclasspath), JaCoCo coverage agent, and `-Dbasquin.*` flags (all pure JVM opts) are the
   P2 surface. Valve mounting is backlog.

**Verified.** envtest (real apiserver) asserts: the patch shape (initContainer/volume/mount/env/port),
that the original `jvmOptsVar` is preserved at the front, idempotency across repeated reconciles, the
`Injected` phase once replicas roll over, a `ContainerNotResolved` error on an ambiguous container,
and — the safety property — that deleting the target reverts the Deployment byte-for-byte.

**Not yet.** End-to-end against a real pod needs the `basquin/agents` image built and published
(backlog); P2 proves the reconcile/patch/revert logic, which is the risky part.

**Revert exactness — refinements (2026-07-20, PR #11 review).** Three correctness gaps in the naïve
revert were closed: (a) the env-var restore is scoped to the **exact injected container** (stashed in
`basquin.dev/jvmopts-container`), so a sidecar that independently sets the same generic var
(`JAVA_TOOL_OPTIONS`/`CATALINA_OPTS`) is never clobbered; (b) a `valueFrom`-sourced `jvmOptsVar` is
**refused at inject time** (`InjectionRejected`) rather than silently flattened to a literal and then
deleted on revert — that would be permanent loss of a Secret/ConfigMap-sourced value; (c) an
originally-*absent* var is distinguished from an explicit-empty one (annotation-key presence), so
revert removes the former and restores the latter. Additionally, `injectionApplied` now checks the
initContainer is actually present (not just the hash annotation), so **out-of-band content drift**
(a `kubectl edit` stripping the fields) self-heals; and a spec change re-derives by reverting the
prior injection first, so retargeting `spec.Container` un-instruments the old container.

**Validated in-cluster (2026-07-20).** `deploy/e2e/e2e.sh` runs the operator as a real pod (its
namespaced ServiceAccount/Role/RoleBinding, built image) and instruments a *raw* JPetStore: agents
loaded on the live JVM, `CATALINA_OPTS` appended (original kept), app serving. This caught two bugs
the envtest/local-run path masked (both fixed): the operator Role was missing `update;patch` on
`basquintargets` — so the P2 finalizer could not be added and the operator could not run
in-cluster at all; and the injected initContainer needed `imagePullPolicy: IfNotPresent` (a `:latest`
agents image otherwise `ImagePullBackOff`s on a kind/air-gapped node). Lesson: envtest runs as admin
and never exercises the operator's own RBAC — the namespaced-trust-boundary design must be verified
against the real ServiceAccount.

## DD-027: Multi-arch images — compile the native agent per-arch inside the Dockerfile (2026-07-20)

**Context.** The four published images (`ghcr.io/ianp94/basquin-{operator,agents,runner,dashboard}`)
were built single-arch (`linux/amd64`) on the release runner. arm64 clusters (AWS Graviton, Apple-Silicon
`kind`) can't pull them, and the injected agents initContainer would `exec format error` on an arm64 node.
Of the four, only one artifact is actually arch-specific: the native JVMTI agent `libbasquinjvmti.so`
(DD-004). Everything else is arch-independent — the operator is a `CGO_ENABLED=0` Go binary on a multi-arch
distroless base, the runner/dashboard are JVM jars on `eclipse-temurin:17-jre` (multi-arch), and the agents
image's other payloads (`basquin-agent.jar`, `jacocoagent.jar`, `basquin-valve.jar`) are jars on a
`busybox` base (multi-arch).

**Decision.**
1. **Build all four with `docker buildx --platform linux/amd64,linux/arm64 --push`** in `release.yml`,
   with QEMU (`docker/setup-qemu-action`) for cross-platform emulation. The operator/runner/dashboard
   Dockerfiles need **no change** — their bases are already multi-arch and their artifacts arch-independent;
   buildx just fans the same build across platforms and pushes a manifest list.
2. **Compile `libbasquinjvmti.so` inside the agents Dockerfile, per-arch.** Rather than cross-compile the
   `.so` on the host (managing an `aarch64-linux-gnu` toolchain + arch-matched JDK headers) or shipping two
   pre-built `.so`s selected by `TARGETARCH`, add a `eclipse-temurin:17-jdk` builder stage that runs the
   existing `native/Makefile` (`cc -shared basquinjvmti.c`). Under buildx, that stage runs once **per target
   platform** (natively on amd64, under QEMU for arm64), so each leg produces its own correct `.so` from the
   same portable C source (standard JVMTI, no arch-specific asm) with zero cross-toolchain plumbing. The
   busybox final stage `COPY --from` grabs the per-arch `.so`.
   - Trade-off: the agents image now pulls a JDK builder layer at build time (cached), and an arm64 `.so`
     compiled under emulation is not *functionally* exercised in CI (the amd64 e2e runner can build it but
     can't run an arm64 JVM to load it). We accept build-time validation (both legs compile + the manifest
     publishes) and defer functional arm64 validation to a real arm64 runner (follow-up). The C source is
     small and portable, so the emulation-compiled `.so` is low-risk.
3. **`STAGE_ONLY=1` on the build.sh scripts** stages the gradle/native artifacts into the build context
   *without* running `docker build`, so `release.yml` can stage once and hand the context to `buildx`. The
   default (local/`kind`) path is unchanged: a plain `docker build` still produces a single host-arch image
   for `deploy/e2e/e2e.sh` (buildx `--load` can't load a multi-platform image anyway).

**Consequence.** A release tag publishes arm64 + amd64 manifest lists for all four images; the local/e2e
build path is untouched. Follow-up: a native arm64 runner (`ubuntu-24.04-arm`) to *run* the arm64 agents
image and prove the emulation-compiled `.so` loads, and multi-arch for the raw-app e2e fixtures if we ever
test on arm64 CI.

## DD-025: BasquinCampaign CRD — the operator owns the whole test run (2026-07-20)

Full design in [CAMPAIGN-DESIGN.md](CAMPAIGN-DESIGN.md). A second CRD (`BasquinCampaign`) that,
gated on an Injected `BasquinTarget`, launches the coverage-guided driver Job and a per-campaign
dashboard, and aggregates coverage/findings/load into `status`. Shipped as operator phase **P5**
(P5a driver Job; P5b per-campaign dashboard). Implemented.

## DD-026: Load / soak mode — replay the interesting corpus under sustained load (2026-07-20)

Full design in [LOAD-MODE-DESIGN.md](LOAD-MODE-DESIGN.md). `spec.mode: explore|load` on the same
`BasquinCampaign` (fuzz and load differ in objective, not infra); the explore corpus is persisted to
a ConfigMap and `runner.coverage.LoadRun` replays it at a fixed concurrency for a duration, reporting
throughput / latency percentiles / heap+thread drift into `status.load`. Shipped as two PRs
(producer, then consumer). Implemented.

## DD-028: Dashboard read-path authentication — token-to-cookie handoff (2026-07-21)

Full design in [READ-AUTH-DESIGN.md](READ-AUTH-DESIGN.md). Closes the read half of the #21/#43
finding: dashboard reads are token-gated when a token is configured — `X-Basquin-Token` for
scripts/drivers, or a one-time `?token=` handoff to a per-campaign `HttpOnly` cookie for browsers.
`/healthz` stays unauthenticated for the readiness probe; comparisons are constant-time. Implemented
in #55. NetworkPolicy kept as defense-in-depth documentation only (kind's CNI does not enforce it).

## DD-029: Lock-free load-mode instrumentation profile (2026-07-21)

Full design in [LOCKFREE-LOAD-DESIGN.md](LOCKFREE-LOAD-DESIGN.md). Load mode drives an Injected
target whose valve serializes every request (DD-005's ITERATION_LOCK), capping concurrency at 1 —
the 2026-07-21 benchmark proved it (k6 10-VU = 256ms of pure queueing, not throughput). Split
instrumentation by what concurrency allows: in load mode the valve runs **lock-free/passthrough**
(no per-request heap/thread deltas), latency + 5xx are measured client-side by the driver, and the
app's heap/thread **drift** is sampled process-globally (absolute Runtime reads) by a cheap agent
thread on a pollable endpoint the driver reads. The driver toggles the target into load mode over
the existing HTTP channel (target-wide for the campaign; the lock is all-or-nothing), not by an
operator JVM-flag restart. Explore mode is untouched. Foundational for cost-guided (pheromone) load
and clustered runners. Extends DD-026 (load mode); grounded in DD-005/DD-010 (why the lock exists). **Implemented 2026-07-21:** control+drift served as valve-intercepted /__basquin/* requests on the app port (resolving the open port question — no separate agent HTTP server, no operator change).

---

## DD-030: Operator server-side boundary via agent bytecode (2026-07-21)

**Context.** The operator injects `-javaagent:basquin-agent.jar` but `Agent.premain` was a no-op
stub — it installed no instrumentation and called no iteration boundaries. The Tomcat valve is never
mounted by the operator (mounting it requires `context.xml` changes and `/lib/` surgery that the
operator deliberately defers). Consequence: the operator path had **no server-side oracle** — no
heap/thread/latency findings from the app under test, only client-side measurements from the driver
JVM. The 2026-07-21 benchmark (docker-compose with a hand-mounted valve) proved server-side findings
exist; Kubernetes deployments with the operator got nothing.

**Decision.** `Agent.premain` installs a `ClassFileTransformer` that instruments
`org.apache.catalina.core.StandardHostValve.invoke(Request, Response)` using ByteBuddy (`1.14.12`,
already a dependency), wrapping requests in `Agent.beginIteration()/endIteration()` when
`-Dbasquin.boundary=agent` is set. The transformed bytecode uses only `org.apache.catalina.*` types
(no `javax.servlet` / `jakarta.servlet`), so the one agent jar serves Tomcat 9 and 10+ unchanged
(namespace-free, DD-011 preserved). Shared `RequestBoundary` logic (installed by both the agent
advice and the hand-mounted valve) bridges the two paths.

**Why.** The operator already injects `-javaagent` on every target — a flag that turns on bytecode
instrumentation is simpler and more reliable than teaching the operator to mount a valve (which
would need image-specific Tomcat `lib/` paths and `context.xml` plumbing). ByteBuddy is a
single-file, no-dependency transform that runs at JVM startup before any app code, so it intercepts
every request on any Tomcat version. Namespace-free bytecode lets one artifact satisfy both worlds.
The agent path and the valve path now converge on the same `RequestBoundary` — shared logic,
identical behavior, proof both implementations agree.

**Consequence.** Operator-instrumented Tomcat targets now capture **server-side heap/thread/latency
findings** (the availability oracle) for the first time. Load mode (DD-029) arrives too, via the
agent's instrumentation of the same `/__basquin/*` control surface already implemented in the valve.

**Verified.** End-to-end: agent premain runs and installs the transformer; `StandardHostValve.invoke`
is wrapped; iteration boundaries fire; server-side invariant violations are captured and reported
in response headers harvested by the driver. `CLASSPATH` coverage still works (injected separately).
No operator code changes needed — only the JVM flag, `-Dbasquin.boundary=agent`, a five-character
toggle.

**Rejected.** Having the operator mount a `<Valve>` directly (requires image knowledge and
`context.xml` surgery); replicating valve logic in the premain as separate code (asking for drift
between two implementations, solved by the shared `RequestBoundary` library); deferring the boundary
to a Phase P3 (it fits into P2's agent-injection footprint and relies on no new operator machinery).

See [`docs/superpowers/specs/2026-07-21-operator-server-side-boundary-design.md`](docs/superpowers/specs/2026-07-21-operator-server-side-boundary-design.md).

---

## DD-031: Cost-ranked replay corpus — cost-guided exploration, phase 1 (2026-07-21)

**Context.** Exploration keeps an input only if it increased coverage, and mutates parents by uniform
random draw; the replay corpus that load mode later consumes is emitted in *insertion order*, truncated
by a byte budget. Which states get load-tested was therefore an accident of discovery order, not of how
expensive they are — and no per-input cost (latency / heap growth / thread leak) was computed or stored
anywhere. With DD-029 (lock-free load) and DD-030 (the operator now measures the server) both merged,
the missing piece was a per-input cost signal and a way to steer load with it.

**Decision.** Steer **load**, not exploration — exploration stays coverage-driven (its job is diverse
discovery; cost-biasing selection risks trading away coverage). The shared `RequestBoundary` (DD-030)
adds a per-request cost channel, `X-Basquin-Cost: <latencyMs>,<heapDeltaKb>,<threadDelta>`, read on the
`EXPLORE_BEGAN` path before `ITERATION_LOCK` releases (same attributability point as the invariant
header, DD-010). `runner.coverage.CostModel` scores each fired input from that header (coefficients
`-D`-overridable); `CoverageGuidedRun` retains an input if it increased coverage **or** its cost clears
an EMA-tracked baseline (cold-start gated so the bar means something), evicts the cheapest non-coverage
entry once the corpus is over its cap (coverage-find entries are never evicted), and emits the replay
ConfigMap sorted by cost descending instead of insertion order — so the most expensive discovered states
survive byte-budget truncation and get replayed under load first. The replay ConfigMap format itself is
unchanged (plain route lines); no operator or `LoadRun` change was needed.

**Why not "true pheromone" (evaporation + reinforcement) now.** In the current loop each corpus entry is
fired exactly once (seeds in the first pass; a coverage/cost find when added) — entries are never
re-fired, only their *mutated children* are, as new entries. So an entry carries a single measured cost;
there is nothing to evaporate or reinforce. Real pheromone dynamics need **credit assignment** (a
child's cost flowing back along the mutation lineage to bias parent *selection*) — a genuine future
feature, valuable only once selection is cost-biased. This phase deliberately stops at cost-ranked
replay; the richer pheromone stays a documented roadmap extension.

**Config (`-D`, all defaulted):** `basquin.cost.latencyWeight` (1.0), `.heapWeight` (0.0625, ≈per-16KB),
`.threadWeight` (500), `.invariantWeight` (1000) tune `CostModel.score`; `basquin.cost.retainFactor`
(3.0) and `basquin.cost.minSamples` (20, cold-start guard) gate cost-based retention;
`basquin.cost.emaAlpha` (0.1) smooths the "typical recent cost" baseline; `basquin.corpus.max` (1000)
caps corpus size. **`basquin.cost.enabled=false` is the kill-switch, and it lives driver-side, not on
the boundary:** the boundary always emits `X-Basquin-Cost` unconditionally (a target property — one
target can serve many campaigns, so it can't know a given campaign's cost intent; the header is cheap
and inert if unread). With `enabled=false` the *driver* skips reading the header, skips cost
computation, retention, and eviction, and emits the replay corpus in today's insertion order — a clean
A/B baseline against this feature, restoring pre-DD-031 behavior exactly.

**Two things that look like bugs but are not:**
- **`topCost` observability is a driver stdout log line, not a summary JSON field.** The termination
  summary shares the kubelet's ~4 KB termination-message budget with the replay corpus itself (see
  `REPLAY_CORPUS_MAX_BYTES` in `CoverageGuidedRun`); adding a `topCost` array to that JSON would compete
  with corpus entries for the same tight budget. Cost visibility is instead printed to the driver's
  stdout log — `[Basquin] replay cost-ranked (top N): <route>=<cost>, ...` — and asserted via `kubectl
  logs` in the e2e (DD-031 assertion (b)), not via `status`/summary JSON. This is deliberate, not an
  oversight.
- **The `X-Basquin-Cost` header's `latencyMs` field is emitted but the driver does not read it.** The
  driver computes cost from its own round-trip latency — the always-available floor per the spec, since
  the header is best-effort and can be dropped by apps that flush early (DD-029 noted this). The header
  still carries `latencyMs` for completeness / other consumers (e.g. a future dashboard reading the raw
  channel), but the driver deliberately ignores it in favor of its own measurement. Not dead code.

**Verified.** In-cluster e2e (`deploy/e2e/e2e.sh`): (a) the target serves `X-Basquin-Cost` as a
`latency,heap,thread` CSV on a request through the explore boundary; (b) the explore campaign's driver
Job logs the `replay cost-ranked (top N): ...` line, proving the corpus was actually cost-ordered, not
just that the header exists.

**Rejected.** True pheromone (cost-biased selection + credit assignment) — deferred, see above; weighted
replay inside `LoadRun` (proportional firing by cost) — needs a ConfigMap format change, a follow-up;
combined coverage+cost fitness — blocked on JaCoCo's per-edge attribution, which doesn't exist today.

See [`docs/superpowers/specs/2026-07-21-cost-guided-replay-design.md`](docs/superpowers/specs/2026-07-21-cost-guided-replay-design.md).

---

## DD-032: Cost-biased parent selection with credit assignment — opt-in "true pheromone" (2026-07-21)

**Context.** DD-031 cost-ranked the *replay* corpus but left exploration parent selection uniform —
deliberately, because cost-biasing selection risks fixating on one expensive region and trading away
coverage, which is exploration's whole job. This DD adds the deferred piece: a real pheromone loop
that biases *selection* toward parents whose mutated children turn out expensive. It is opt-in and
bench-validated, not CI-validated, because its value (does it out-discover uniform?) is genuinely
uncertain and must be proven, not assumed.

**Decision.** Enabled only under `-Dbasquin.pheromone=on` (default **off** = today's exact behavior:
uniform selection, no reinforcement, no evaporation, DD-031 cost-based eviction unchanged).

1. **ε-greedy selection.** With probability ε, `CostCorpus.selectParent` draws a parent uniformly
   (pure exploration); otherwise it draws by roulette weighted on `pheromone`. `ε=1.0` ≡ uniform — ε
   is literally the kill-switch.
2. **Immediate-parent credit assignment.** After a mutated child is fired and its cost measured, that
   cost is deposited onto the *one* parent it was mutated from (`reinforce`) — before the child's own
   `consider` call, so a freshly-reinforced parent can't be evicted by the very child that earned it.
   Multi-hop lineage (crediting grandparents with decay) is deferred — see below.
3. **Evaporation.** Every `evaporateEvery` iterations (default 50, fixed cadence, checked at the top
   of the loop regardless of which branch a given iteration took), every entry's `pheromone *= decay`.

**ε is the coverage guardrail, not the initial pheromone.** Real costs run in the hundreds-to-thousands
(one invariant hit = 1000, a leaked thread = 500); inside the roulette branch a cheap coverage-find
cannot compete with an 800+-pheromone entry — it is *selectable* but not *competitive*. The thing that
actually keeps cheap/coverage-finding parents in rotation is the ε-uniform share of iterations, not the
starting pheromone value. **New entries start at `pheromone = emaCost + cost`** — EMA-relative rather
than a constant, so the starting value is scale-relative and cross-app meaningful (a constant `1.0`
would be swamped on a ~5000-cost app and meaningless on a ~10-cost one) — but this is a *tie-breaker
among cheap entries*, not a coverage protector. Cold start (EMA not warm, or the corpus's total
pheromone weight is 0) falls back to uniform.

**Sticky-spike defaults: `decay=0.7`, deposit cap `10×EMA`.** Deposits are raw child costs, so without
bounds a single 5000-cost spike (one invariant hit) could dominate the roulette for an entire bench run
— indistinguishable from "pheromone got stuck." Two guards: `deposit = min(childCost, depositCap ×
emaCost)` caps how large one spike's deposit can be, and `decay=0.7` (chosen over the more obvious 0.9)
means a capped 10×EMA spike decays to ~1×EMA in `0.7^k ≈ 0.1 → k≈6.5` evaporation cycles — **≈325
iterations** at the default `evaporateEvery=50`, recoverable within a normal bench run rather than
spanning it. These are the defaults the A/B in this record runs with.

**Eviction becomes pheromone-aware when pheromone is on.** DD-031's `evictIfOverCap` evicts the
cheapest-by-own-cost non-coverage entry; under credit assignment that fights reinforcement, since an
entry with low own-cost but high pheromone is a *proven productive parent* — the one to keep. So with
`pheromone=on`, eviction targets the min-`pheromone` non-coverage entry instead; with it off, DD-031's
cost-based eviction is unchanged. Coverage-find entries are never evicted either way.

**`pheromone=on` forces cost measurement on.** Reinforcement needs a measured cost, so `pheromone=on`
overrides `-Dbasquin.cost.enabled=false` and logs that it did (`[Basquin] pheromone=on forces cost
measurement on`) — no silent half-configured run that would poison an A/B comparison.

**Two A/B-readout caveats — record verbatim so results aren't misread:**

- **The uniform ("off") arm is not a pure-uniform strawman.** The loop already spends ~30% of
  iterations on fresh/random expansion before ε-selection even applies, so roulette's real share of
  iterations is ≈ `0.7 × (1 − ε)` — with a typical ε, ≈49% of iterations, not 100%. The "off" arm is
  the *same* loop structure minus the roulette branch, not a differently-shaped baseline. Attribute
  any effect size to that ~49%, not to the whole run.
- **Winner-take-all inside the roulette is intended, not a bug.** A parent that is consistently
  selected and consistently spawns expensive children has its pheromone converge toward
  `deposit-per-cycle / (1 − decay)` — with the defaults above, tens of EMA multiples — bounded only by
  ε's uniform share. Seeing one parent dominate a readout's cost-ranked top is the design working as
  intended, not evidence of a stuck loop.

**Performance stance — no new data structure.** Pheromone selection runs inside the driver's
exploration loop, which is serialized and HTTP-round-trip-bound (one request at a time, milliseconds
each); in-memory work over ≤`corpus.max` (1000) entries is microseconds against that. So the loop keeps
a **running total pheromone weight, maintained incrementally** (O(1) on add/reinforce/evict) so
`selectParent` never re-sums the corpus, and locates the drawn entry with a single **O(n) cumulative
scan** — no per-draw recomputation, no per-iteration allocation. Evaporation is O(n) but only every
`evaporateEvery` (50) iterations, scaling the running total in the same pass. A Fenwick/BIT-backed
selection (O(log n) draw + point update) with lazy-scale evaporation (represent deposits relative to a
growing global scale so evaporation is O(1), periodically renormalized) is **documented as the upgrade
path, not built** — profiling the serialized, request-bound loop would not justify it today. The
target-side `RequestBoundary` (the genuinely latency-critical, per-request path) is untouched by any
of this.

**Scope — bench-first; operator/CRD deferred.** v1 is driver-only, `-Dbasquin.pheromone=on` measured
in the docker-compose bench path (protocol: [`docs/BENCH-AB.md`](BENCH-AB.md)). No CRD field, no
operator change — a CRD field is a compat commitment that's awkward to walk back if the A/B says "kill
it," and the driver-flag passthrough costs the same to add later, after the numbers earn it. Multi-hop
lineage propagation (crediting grandparents with per-hop decay) is likewise deferred — immediate-parent
credit is the correct v1; a genuine ant-trail needs parent links that don't exist yet.

**Verified.** Bench-validated only (`docs/BENCH-AB.md`), not CI-validated: the in-cluster e2e only
smoke-tests the default-off path (plumbing, not the pheromone-vs-uniform question), and unit tests
cover `selectParent` (ε=0 dominated by the high-pheromone entry, ε=1 uniform, cold-start/zero-total
uniform), `reinforce` (the deposit cap is unit-tested — `depositIsCappedAtDepositCapTimesEma` — while
the ~325-iteration sticky-spike recovery is a derived-from-the-defaults property, decay 0.7 / cap
10×EMA, not a separately tested one), `evaporate` (decay applied, running total stays consistent),
pheromone-aware eviction, and the kill-switch (`pheromone=off` ≡ DD-031/today byte-for-byte).
Reinforcing an already-evicted parent is **not** a harmless no-op: because `CostCorpus` maintains a
running `totalPheromone`, a post-eviction `reinforce` would silently drift that total above the sum
actually held by live entries, corrupting every subsequent
`selectParent` roulette draw — which is exactly why `reinforce()` must run before `consider()` (the
only evictor); the ordering keeps the parent live and the total exact. The negative test
`PheromoneLoopTest.reinforceAfterEvictionWouldCorruptTheRunningTotal` pins this failure mode, so it's
a tested invariant, not a latent bug.

**Rejected.** Multi-hop lineage propagation now (needs parent links exploration doesn't track yet —
immediate-parent first); operator/CRD enablement now (a CRD field is a much bigger commitment than a
driver flag, and premature before the A/B has a verdict); a constant initial pheromone (meaningless
across apps whose typical costs differ by orders of magnitude); a Fenwick/lazy-scale structure now
(unjustified for a serialized, HTTP-bound loop).

See [`docs/superpowers/specs/2026-07-21-pheromone-selection-design.md`](docs/superpowers/specs/2026-07-21-pheromone-selection-design.md)
and the bench protocol, [`docs/BENCH-AB.md`](BENCH-AB.md).

---

## DD-033: Load as a first-class citizen — mode-aware dashboard + CLI, OTel-typed metrics (2026-07-21)

**Context.** Load campaigns produce throughput / latency-percentiles / heap+thread-drift / 5xx
(`LoadRun`, DD-026/DD-029), but both surfaces that display a campaign are hard-coded to explore:
`LoadRun` never called `StatusReporter`/`DashboardClient`, so a load campaign's dashboard showed
`iterations=0`/`crashes=0`/empty explore scaffolding, and the CLI (`operator/cmd/basquin/status.go`)
printed `<none>` coverage / `0` findings for load. The real numbers only reached the pod termination
message and `status.load` (via `kubectl`) — `docs/LOAD-MODE-DESIGN.md:127`'s promised dashboard push
was never built.

**Decision.** Make the one existing push path mode-aware instead of building a second one.
`StatusReporter.snapshotJson()` emits `"mode":"explore"|"load"` and, in load, a `"load":{...}` block
(`throughputRps`, `latencyMs.{p50,p90,p99,max}`, `heapDriftKb`, `threadDrift`, `serverErrors`,
`requests}`) via new `setMode(String)`/`recordLoad(...)` API (mode defaults to `explore`, so explore
is byte-for-byte unchanged). `LoadRun` calls `setMode("load")` at start and runs a lightweight
snapshotter thread (every `basquin.dashboard.pushIntervalMs`, default 2000ms) that computes
throughput-so-far, live percentiles, current drift, and 5xx, feeding `recordLoad(...)` — carried by
the **existing** `DashboardClient` loop `CoverageGuidedRun.main` already starts. No second pusher, no
new endpoint. `resources/dashboard.html` reads `st.mode` and renders a load card (throughput, p50/
p90/p99, heap/thread drift, 5xx) in place of the explore cards when `mode=load`; the `/api/campaigns`
list scrape additionally greps `mode` so the fleet view distinguishes explore vs load at a glance
(server stays schema-agnostic — a light regex, not a schema parse). The CLI (`status.go`) gains a
**MODE** column and a mode-aware metrics column: explore shows `cov% · N finds`, load shows
`rps · p99ms` from `cp.Status.Load`.

**Live-snapshotter semantics (three requirements, load-bearing).**
- **Torn reads are acceptable; the terminal numbers are authoritative.** Each `AtomicLongArray`
  bucket read is atomic, but scanning it while workers concurrently increment is not a consistent
  snapshot — live percentiles are approximate *by design* for a 2s display. `status.load` and the
  end-of-run metrics remain the authoritative record. No lock is added to the hot path to reconcile a
  transient live/terminal discrepancy — that would defeat DD-029's lock-free load. (The dashboard's
  last shown value is the last *live* snapshot, which has converged to terminal; the JVM may exit
  before the daemon push loop ships an explicit final push, so `status.load` — not the dashboard — is
  the authoritative terminal record.)
- **Live throughput uses the same post-warmup window as the terminal number**, anchored at the same
  post-warmup mark the baseline drift snapshot already uses — so the live value *converges to* the
  terminal one instead of diverging (a live/terminal disagreement would otherwise read as a bug).
- **One drift poller, one source of truth.** The snapshotter owns drift polling; the terminal drift
  reuses its last poll rather than keeping the previous independent post-warmup-baseline + terminal-
  poll pair as two separate paths. `/__basquin/drift` is control-handled in the boundary (no lock,
  never reaches the app), so the added 2s poll is negligible load on the soak.

**OTel-typed metrics (typed now; export deferred).** Every signal gets an OTel-native type + stable
name + attributes, so a future optional OTLP exporter is a thin adapter over the same registry — no
OpenTelemetry dependency is added by this DD:

| Signal | OTel type | Name (intended) | Unit (UCUM) | Notes |
|---|---|---|---|---|
| Request latency | **histogram** | `basquin.load.request.duration` | `ms` | `*.duration` per OTel convention (cf. `http.server.request.duration`), not "latency"; boundaries below |
| Throughput | counter (+rate) | `basquin.load.requests` | `{request}` | rate = counter / window |
| Heap drift | **gauge** | `basquin.load.heap_drift` | `KiB` | absolute over-time reading (DD-029) |
| Thread drift | **gauge** | `basquin.load.thread_drift` | `{thread}` | |
| 5xx | counter | `basquin.load.server_errors` | `{error}` | |
| Iterations | counter | `basquin.explore.iterations` | `{iteration}` | |
| Coverage | gauge (ratio) | `basquin.explore.coverage` | `1` | ratio 0..1 |
| Findings / crashes | counter | `basquin.explore.findings` / `.crashes` | `{finding}` / `{crash}` | |

Three contract commitments a future exporter inherits: **unit is the UCUM `unit` field**, never baked
into the name (`ms`, not `..._ms`); **the histogram's bucket boundaries are part of the contract** —
the `AtomicLongArray`'s layout (1 ms buckets from 0..30000 ms (`MAX_MS`), plus one overflow bucket for
anything slower) becomes the explicit-bucket-histogram boundaries the exporter must emit verbatim
(re-bucketing later is a breaking change to downstream dashboards);
and **`campaign.id` is a resource attribute, `mode` is a metric attribute** (identity vs. dimension —
what makes the future exporter thin, not a re-model).

**Verified.** Unit: `StatusReporter.snapshotJson()` defaults to `mode=explore`, emits a well-formed
`load` block only after `setMode("load")`+`recordLoad(...)`, explore fields unaffected; `LoadRun`'s
live-histogram percentile snapshot matches the end-of-run computation for the same histogram state;
Go CLI table renders `MODE` + mode-aware metrics column, alignment holds for both explore and load
rows. In-cluster: `deploy/e2e/e2e.sh` **asserts** the load campaign's own per-campaign dashboard,
queried at `/api/campaign/{id}/status` right after the campaign reaches `Completed` (before its GC'd
delete), received `"mode":"load"` and a non-empty `"load":{"throughputRps"...}` block — closing the
loop in-cluster, not just at the unit level; **CI executes this e2e in a kind cluster on every
change.**

**Rejected.** A second push mechanism for load (violates "one push path"; the existing
`DashboardClient` loop already carries whatever `StatusReporter` emits); a hot-path lock to make live
percentiles exactly consistent with the terminal read (defeats DD-029's lock-free load for a 2s
display concern); a full unified explore+load metrics model / `StatusReporter` rewrite (this DD is
additive/mode-tagged); building the OTLP exporter now (typed here so it's a thin adapter later, but
export is its own DD — off by default, alongside not replacing the bespoke dashboard, and tied to
clustered runners' honest cross-runner percentile merge).

See [`docs/superpowers/specs/2026-07-21-load-first-class-design.md`](docs/superpowers/specs/2026-07-21-load-first-class-design.md).

## DD-034: Running time-series graph on the dashboard — client-side history, mode-aware sparklines (2026-07-21)

**Context.** `resources/dashboard.html` only ever rendered *current-value* cards (`card(k,v,sub)`):
each 1.5s poll of `/api/campaign/{id}/status` replaced the numbers in place, so there was no way to
see a trend — is throughput climbing or falling, is coverage still finding new edges, is heap drift
accelerating — without watching the numbers scroll by and doing the differencing in your head.
`DashboardServer` stores only the latest snapshot per campaign (no history, no TTL/store per
DD-033's known limitations); it does not batch or replay past polls.

**Decision.** Keep the server exactly as-is and accumulate history **client-side**, from the polls
`tick()` already makes — no new endpoint, no server-side storage, no schema change. A single
in-memory buffer (`history`, keyed by metric name, capped at 120 samples per metric — roughly 3
minutes at the 1.5s poll interval) is pushed to on every `tick()`, and reset whenever the viewed
campaign id (`current`) changes (opening a different campaign, or leaving to the fleet view and
back), so two campaigns' numbers never splice into one chart. A small inline-SVG polyline
(`sparkline(values,w,h,color)`, ~200×40px) renders each buffer, scaled to the buffer's own min/max;
`trendCard(label,values,color,fmt)` wraps it with a label and the latest formatted value, styled to
match the existing `.card` look. Mode-aware, same split `tick()` already makes on `st.mode`: **load**
charts `throughputRps`, `latencyMs.p99`, `heapDriftKb`; **explore** charts `iterations`, coverage
`pct`, and `finds` (crashes + every invariant hit, harness- and app-reported). They render in a new
"Trend" section between the existing cards and the coverage card — the cards themselves are
unchanged.

**Robustness.** Missing/undefined metric values coerce to `0` before entering the buffer (consistent
with the existing `||0` convention throughout `tick()`), so a metric absent for one poll doesn't break
the polyline. `min===max` (a flat series, or the deliberately-tested single-sample buffer) is handled
by widening the range by ±1 before scaling, avoiding a divide-by-zero that would otherwise produce
`NaN` coordinates; a genuine single-sample buffer draws a flat horizontal line at that value's height
rather than a degenerate zero-length point. Labels go through the existing `esc()` before interpolation
even though they're currently static strings, matching the file's rule that anything reaching
`innerHTML` is escaped.

**Verified.** Unit: the bundled `<script>` block parses (`new Function(...)` over the extracted JS)
after the change. Build: `./gradlew jar` still builds (dashboard.html is bundled as a jar resource).
In-cluster: `deploy/dashboard-image/build.sh 0.2.0 basquin` rebuilt `basquin/dashboard:0.2.0` with the
updated HTML and `kind load`ed it into the standing `basquin` cluster so the next dashboard pod picks
it up.

**Rejected.** Server-side history (a ring buffer in `DashboardServer` keyed by campaign+metric) —
correct long-term home for this once the dashboard needs to survive a page reload or serve multiple
viewers a consistent trend, but out of scope here per the task's explicit "do not change the server,"
and the client-side buffer already satisfies "graph what this browser tab has observed this session."
An external charting library — one more dependency for a ~40-line polyline the existing inline-SVG/
no-dependency style of this file already handles.

## DD-035: Honest load: method/session/sequence-aware replay + drift honesty (2026-07-22)

**Context.** DD-026 PR 2's `LoadRun` replayed every corpus line as a bare `GET <line>` — no HTTP
method, no body, no session. Against jpetstore, a meaningful share of the corpus an `explore` run
emits is `POST` (logins, cart mutations, checkout); replaying those as `GET` mostly produced
404/405/redirect noise instead of exercising the endpoint the explore run actually found interesting.
Under `concurrency: 20+` that noise was enough to **saturate the target** — 5xx responses and
timeouts piling up — which in turn **starved the `/__basquin/drift` poller**: it shares the target's
request-handling capacity with the load traffic, so a saturated target means the poll itself times
out or fails. `driftDelta()` silently degrades a missing baseline-or-terminal sample to a zero delta,
indistinguishable from a real flat heap — so the run's summary reported `heapDriftKb:0`, a **fabricated
"no drift"** for a heap that was never actually sampled. That's the "honest load" bug: not that load
mode lied about a number, but that its plumbing could produce a plausible-looking number with nothing
real behind it.

**Decision.** Two independent fixes, both required — fixing only the replay leaves drift honesty
unaddressed for the *next* way a poll can fail; fixing only drift honesty ships a load tester that
still stress-tests the wrong requests.

1. **Corpus format v2 — method/session/sequence-aware replay**, backward-compatible with every v1
   corpus on disk today. A step is `METHOD? path( SP body )?` (`RequestLine.parse` — a bare `/foo`
   with no method token is still `GET /foo`, so v1 lines parse unchanged). A line is either one step
   or a **TAB-separated sequence** of steps (`RequestLine.parseSequence`), replayed **in order** as one
   logical flow (e.g. `POST /actions/Account.action?signon= u=j2ee` then the authenticated GETs it
   unlocks) — TAB was chosen specifically because it cannot appear in a URL or a form-urlencoded body,
   so it never collides with real request content. Corpus-file filtering (`readCorpus`) keeps a line
   iff its **first step's** path starts with `/` (`RequestLine.firstPath`), which is what lets a
   `POST /...`-leading v2 line still be recognized as a route line rather than mistaken for a grammar
   value file (whose lines are bare tokens, e.g. `values/keyword.txt`). Each load **worker** now owns
   one session cookie jar for its whole run, capturing `Set-Cookie` from one step and replaying it as
   `Cookie` on every later step so an authenticated sequence actually replays authenticated.
2. **`driftUnavailable` as a first-class signal**, not a fabricated zero. `status.load` (and the
   terminal `[Basquin] load done: {...}` log JSON) now carries either a real `heapDriftKb`/
   `threadDrift` pair, **or** `"driftUnavailable":true` with those two fields omitted entirely — never
   a `0` standing in for "couldn't measure it" (a real flat/shrinking heap legitimately prints `0` or a
   negative delta; that must stay visually indistinguishable from a genuine reading, which is exactly
   why the omit-vs-print split, not a sentinel value, is the mechanism). **The correct definition**
   (`LoadRun.driftUnavailable`, review-fixed from an earlier narrower version): `driftUnavailable` is
   `true` when **any** of — the baseline drift poll never landed (no worker ever reached its first
   post-warmup sample, or the poll came back null when it was attempted), **or** the current/terminal
   drift sample is missing (a later poll failure, independent of whether the baseline succeeded),
   **or** the target never confirmed load mode at all (`setTargetMode` didn't get back exactly
   `"ok:load"` — a target that's still serializing requests, or unreachable, cannot be trusted for a
   concurrent-load drift reading even if a drift poll happens to succeed). The narrower, buggy version
   checked only the baseline: a baseline that landed fine followed by a later poll failure still
   reported `driftUnavailable=false` with a fabricated `heapDriftKb:0` — the exact false-negative this
   decision exists to close.

**Verified.** Unit tests exercise `RequestLine.parse`/`parseSequence`/`firstPath` (v1 backward-compat,
method/body parsing, TAB sequences, value-file exclusion via `firstPath`), `LoadRun.driftUnavailable`
(all four inputs independently, including the review-fixed terminal-sample case), and
`LoadRun.summaryJson` (the omit-vs-print split). In-cluster e2e (`deploy/e2e/e2e.sh`): the emitted
replay corpus's route-count grep now recognizes v1/method-prefixed/sequence lines alike; after a load
campaign, the driver Job log is asserted to carry neither a failed mode-toggle line nor a
`"driftUnavailable":true` terminal summary, and `/__basquin/drift` is curled over the app's Service
DNS **while the campaign is still `Running`** and asserted to return HTTP 200 with a parseable body —
sampled-ness, deliberately not `drift > 0` (a GC pause can legitimately shrink heap between samples).

**Rejected alternatives.**
- **JSON-lines corpus** (one JSON object per line: `{"method":...,"path":...,"body":...,"seq":...}`) —
  self-describing and trivially extensible, but roughly doubles the byte cost of every route (field
  names repeated per line) against a corpus that's already budget-constrained by the ~4 KiB
  termination-message transport (DD-026 §3), and forces every existing corpus file, every operator
  hand-authoring a corpus, and every doc example to change format. Rejected: the byte budget is the
  binding constraint, and TAB-separated steps cost zero bytes over today's format for the common
  single-step case.
- **Blank-line-separated sequence blocks** (a sequence is a run of consecutive non-blank lines; a blank
  line ends it) — reads naturally for a hand-authored corpus, but collides with the existing
  `readCorpus` line-at-a-time contract and creates real backward-compat ambiguity: nothing today
  distinguishes "blank line as a deliberate sequence separator" from "blank line the file happened to
  have" (trailing newline artifacts, hand-edited corpora), so an existing corpus could silently splice
  unrelated lines into one bogus "sequence" the moment this shipped. Rejected: TAB-in-one-line makes a
  sequence's boundary syntactically unambiguous — a sequence either is or isn't the whole line, no
  cross-line state to accidentally get wrong.
- **A dedicated drift port** (serve `/__basquin/drift` on its own listener/thread pool, insulated from
  the app's request-handling capacity, so load traffic can never starve it) — the instinctive fix once
  "drift polling was starved" is diagnosed, but a benchmark against a **healthy** target (not saturated
  by method-unaware 404 noise) showed the shared-port poll succeeds reliably; the real root cause
  wasn't port contention, it was the undersized **512 MB** heap (`-Xmx512m`, DD-023's baseline) getting
  driven into GC pressure by traffic that was mostly failing anyway. Rejected for this cut: it adds a
  second listener/thread pool to every instrumented target for a problem the replay fix (1) already
  resolves at the source; `driftUnavailable` (2) remains the honest fallback for whatever narrower
  starvation case survives on a target that's merely under-provisioned, not broken.

## DD-036: Response correlation — capture-and-substitute for CSRF-protected replay (2026-07-22)

**Context.** DD-035's honest load replay captures and replays the *session cookie*, but every other
value in a corpus line is still fixed at emit time — it can capture nothing from a response body and
inject it into a later request. That makes any write path guarded by a per-session token unreachable:
JSPWiki's page-save POST carries a hidden **`X-XSRF-TOKEN`** minted fresh on the edit form's GET, and
JPetStore's Stripes form handlers want a `_sourcePage` hidden field emitted the same way. A replayed
static POST carries a dead token and is rejected (4xx) — so a fuzz/load corpus against either app can
only ever exercise cached reads, never the write paths that would actually differentiate the tool from
a read-only mix (every route on a cache-friendly app like JSPWiki warms to roughly the same latency).
This is what Gatling calls `.check(...).saveAs(...)` + `${...}` and JMeter calls extractors + variables:
capture a value from response N, substitute it into request N+k. The DD-035 ordered-sequence model
already guarantees the happens-before between the capturing step and the referencing step; this
decision adds the capture and the substitution on top of it.

**Decision.** Corpus format **v3**, backward-compatible with v1/v2 — a line with no `<<` capture suffix
and no `${{}}` reference parses and fires byte-for-byte as today.

1. A step gains an optional trailing `<<name=kind:arg` **capture suffix** (`RequestLine` now carries a
   `Capture`), where `kind` is `header:<HeaderName>` (an `HttpURLConnection` header lookup) or
   `input:<inputName>` (the `value` of the `<input>` tag whose `name` matches) — the `input` extractor
   is a targeted regex over `<input>` tags, deliberately **not** a full HTML/CSS parser, since the
   runner's hot path is dependency-free by design and a hidden token field is machine-generated markup,
   not adversarial HTML.
2. A request body may contain one or more `${{name}}` **references**, substituted (URL-encoded) from
   values captured **earlier** in the same sequence execution. The two `${…}` forms coexist and differ
   by binding time: `${param}` binds once per sequence *expansion* (grammar time), `${{name}}` binds
   once per sequence *execution* (fire time, from a live response).
3. The corpus stores only the capture **recipe**, never a token — tokens live only in a per-sequence-
   execution `bindings` map (fresh per execution, per worker) and never persist to disk or across
   executions. A capture-once/cache-across-executions optimization was considered and rejected: a token
   is minted per session, so caching it would replay a stale or foreign token — the exact bug
   correlation exists to fix.
4. Two honesty counters land in the terminal summary — `captureMisses` (a referenced value that was
   never bound; warmup-gated, single-counted per occurrence) and `clientErrors` (4xx responses) —
   because an unresolved reference or a rejected CSRF token would otherwise silently no-op the step
   instead of surfacing a broken correlation.
5. The grammar's `expand` step preserves `${{}}` verbatim (it previously mistook `${{csrf}}` for the
   placeholder `{csrf`, found no rule, and silently expanded it to `""`, destroying the reference), and
   correlated write sequences ship in the example grammars: `edit_save` (JSPWiki) and
   `order_with_sourcepage` (JPetStore).

**Verified.** Unit tests: `Capture` parse/format/extract (both `header`/`input` kinds, both quote
styles, attribute order, wrong-tag/name-substring safety); `RequestLine` v3 round-trip
(`format(parse(line))==line`, the recipe-preservation guard, v1/v2 backward compatibility); grammar
`expand` preserves `${{}}` (adjacency, unterminated, out-of-bounds cases); `LoadRun`/`CoverageGuidedRun`
capture-then-substitute end-to-end over a JDK `HttpServer` (the server observes the substituted body);
single-counted, warmup-gated `captureMisses`; `readCorpus`'s correlated-flag computation; no-NPE on the
uncorrelated/null-bindings fast path. Full suite green (188 tests). Post-merge proof plan: re-onboard
JSPWiki with the `edit_save` sequence, arm B (reads + correlated writes) against arm A (reads only) —
success is arm B's save POSTs returning 2xx/3xx with `captureMisses`≈0, plus a write-path cost (p99,
heap drift from store mutation/reindex) that arm A's cached reads structurally cannot show.

**Rejected alternatives.**
- **A full HTML/CSS-selector parser** (jsoup or similar) — would correctly handle adversarial or
  deeply nested markup, but the runner's hot path is deliberately dependency-free, and a hidden CSRF
  field is machine-generated markup, not adversarial HTML. Rejected: a regex extractor on a known
  input name is exactly the JMeter/Gatling CSRF recipe, at zero added dependency weight.
- **JSON-lines corpus** — self-describing and easy to extend with a capture/reference field, but
  doubles the per-route byte cost against a corpus already budget-constrained by the ~4 KiB termination
  transport (DD-026 §3), and forces every existing corpus and hand-authored file to change format.
  Rejected for the same reason DD-035 rejected it for v2.
- **Recording live token values into the corpus** — the simplest thing that could work, but a captured
  token is bound to one session; replaying it later just fails the same way a static token does, and it
  additionally leaks a session secret onto disk (a ConfigMap). Rejected: it solves nothing and adds a
  credential-handling liability.
- **Automatic CSRF-token forwarding / transparent framework detection** — appealing because it needs no
  corpus syntax at all, but it's magic (silently guesses which header/field is the token), app-specific
  (every framework names its token differently), and silently wrong when it guesses incorrectly.
  Rejected: an explicit recipe the operator writes is diagnosable; an autodetector that misfires is not.
- **Capture-once value caching across executions** — would save a redundant GET per sequence execution,
  but a token is minted per session; caching it across executions would replay a stale or foreign token
  on a later execution — the exact failure mode correlation exists to eliminate. Rejected: correctness
  beats one saved request.

## DD-037: Dynamic-name field correlation — inputpair capture + multi-capture (2026-07-22)

**Context.** DD-036 captures the value of a *statically*-named input, but Apache JSPWiki's
`SpamFilter.checkHash` (called unconditionally from `Edit.jsp`, before every save — no config can
bypass it, confirmed by bytecode + a live A/B in `.superpowers/sdd/jspwiki-save-rootcause.md`) requires
a hidden field whose **name** is itself dynamic: 6 random lowercase letters, session-pinned and rotated
daily (e.g. `ztbams`), holding a value of `lastModified ^ hash(clientIP)` that changes after every save.
The name is un-nameable at grammar-authoring time — DD-036's `<<x=input:FIELD` needs a literal field
name — so the whole `name=value` pair has to be selected by regex and captured together. The edit form
also needs *two* captures from one GET (the existing CSRF token plus this new spam-hash pair). A missing
hash redirects `302 → SessionExpired`; a wrong or missing CSRF token redirects `302 → Forbidden`.

**Decision.**
1. New `Capture.Kind.INPUTPAIR`, wire form `<<name=inputpair:<nameRegex>=<valueRegex>` — the arg splits
   on its **first** literal `=` (so a value regex may itself contain `=`, but a name regex may not).
   Both halves match **anchored** (`Matcher.matches()`, the whole attribute value — never `find()`,
   which would let `[a-z]{6}` match a substring of `_editedtext` or `-?[0-9]+` match digits inside the
   CSRF UUID and silently pick the wrong field). It binds `urlEncode(name) + "=" + urlEncode(value)` —
   the joining `=` is a literal, each half independently encoded.
2. Encoding model A: encoding moves from `substitute` into `Capture.extract`, so `substitute` becomes a
   verbatim splice. This is what lets the pair's structural `=` survive the substitution (encoding the
   whole pair as one string would mangle it to `%3D`); existing `input:`/`header:` captures are
   byte-identical, since the encoding step just moves one layer, not away.
3. `RequestLine.capture` becomes `List<Capture> captures`; `parse` peels *all* trailing `<<…` tokens in
   a loop (source order preserved), `format` re-emits every one, and captures stay trailing — this is
   what lets JSPWiki's `edit_save` carry both the CSRF and spam-hash captures off one GET.

**Verified.** Unit tests: `Capture` parse/format/extract for `inputpair`, including the anchoring cases
(the `action`/`_editedtext` decoys and the `data-name=` guard); `RequestLine` multi-capture round-trip
(`format(parse(line))==line` for 0–3 captures) plus v1/v2 back-compat; `substitute` verbatim (adjacent
references, unbound → `null`). Grammar `expand` of `edit_save` preserves both `${{csrf}}` and `${{spam}}`
unexpanded. Full suite green. The live root-cause A/B (test E in the root-cause doc) is the proof that
matters: replaying the captured spam-hash pair turns a `302 SessionExpired` rejection into a persisted
save.

**Rejected alternatives.**
- **Typed binding map** (`Map<String,Bound>` carrying the kind, rendered per-kind in `substitute`) —
  cleaner separation of concerns, but changes the bindings type across both engines and the
  `fire`/`request` signatures for no functional gain over encoding-at-capture. Rejected: larger
  footprint, same outcome.
- **Sentinel-delimiter binding** (`extract` returns a joined string, `substitute` splits and encodes
  each half) — smallest diff, but hides structure inside a plain `String` — implicit typing a reviewer
  would rightly flag. Rejected in favor of the explicit encode-at-capture model.
- **Config-disabling the SpamFilter** — impossible; `SpamFilter.checkHash` is hard-wired into `Edit.jsp`
  independent of filter registration, so there is no config surface to turn it off.
- **Two separate captures for name and value** — impossible in principle: the dynamic name and its
  value must come from the *same* matched `<input>`, and the grammar has no way to name the field to
  capture a value from it separately.

## DD-038: `<nonce>` generator (unique per-fire payload) + Location-classified 3xx counters (2026-07-22)

**Context.** Two findings from validating DD-037's correlated JSPWiki writes end-to-end. (1) A load
corpus replays a **fixed** request body, and change-detecting apps no-op an identical write: JSPWiki's
`DefaultPageManager.saveText` returns *before writing* when `oldText.equals(proposedText)`, yet still
issues the same `302 → success` redirect — so after the first replay a page never changes again, and
the write-path cost the benchmark wants to measure disappears. This is a general CMS pattern, not
JSPWiki-specific. (2) `fire` auto-followed redirects, so a *rejected* write (`302 → SessionExpired` /
`PageModified`) was counted as a normal request — not a 4xx, not a 5xx, and capture succeeded so not a
`captureMiss`. Rejections were silently invisible.

**Decision.**
1. `<nonce>` is a **grammar generator primitive**, in the same `<…>` namespace as `<int>`/`<string>` —
   the author names their own rule (`$rev = <nonce>`), so it reserves **nothing** in the `${{}}`
   correlation namespace, which stays purely author-controlled for response captures. It emits the
   fire-time deferral marker `${{@nonce}}` (the `@` is not in `Capture.NAME_PATTERN`, so `@nonce` can
   never be a valid author-named capture → collision-proof), which `LoadRun.substitute` fills per-fire
   with `RUN_SALT-counter` (`RUN_SALT` = `currentTimeMillis()` folded with the pod's `HOSTNAME` and the
   process pid). The pod identity is load-bearing, not belt-and-braces: the driver runs as a Kubernetes
   Job, and inside a container's own PID namespace every driver's main process is renumbered from a
   small integer — commonly `1` — so a millis+pid salt collides outright for two pods started in the
   same millisecond (a parallel Job, or two campaigns off one controller tick), and both then emit an
   identical token stream. `HOSTNAME` is the pod name in Kubernetes (DD-013) and separates them; pid
   remains the fallback for a local run where `HOSTNAME` is unset. A bare
   `AtomicLong` *seeded* with millis is not enough: the seed advances in wall-clock ms, but the counter
   advances by number-of-saves (millions per run under load), so a later run's millis seed can land
   inside a prior run's emitted counter range and re-emit values still saved on pages — reintroducing
   the exact no-op this feature exists to kill.
2. Substitution scope widens to the **full request line — path and body, null-safe** — because
   generators are usually used in URL queries (`?rev=<nonce>`), and body-only substitution would bake
   the literal marker into a fired URL. The path is never null and is substituted unconditionally; the
   body can be null and is guarded, so `GET /Wiki.jsp?rev=${{@nonce}}` with no body does not NPE.
3. Load mode stops following redirects (`setInstanceFollowRedirects(false)`) — explore mode keeps
   following them, since it optimizes for coverage, not redirect metrics; a distinct method
   `fireR` returns `record FireResult(int code, String location)` (the existing `int`-returning `fire`
   delegates, so `LoadFireTest` and every other caller are untouched — Java can't overload on return
   type alone). A pure-static `normalizeLocation` classifies the `Location` into `redirects`/
   `redirectTargets` counters. **Self-redirects fold to one reserved `"self"` key**: a *successful*
   JSPWiki save also 302s to `/Wiki.jsp?page=<savedPage>`, so without folding, the 66 real page names
   would fill the bounded map first-come and evict the actual rejects (`SessionExpired`/
   `PageModified`) — the exact invisibility this feature kills. `"self"` and `"other"` (the overflow
  bucket) are therefore *reserved* key names: a target app with a route literally named `self`/`other`
  would merge into the reserved bucket. Accepted — these are operator-facing counters, not an oracle.
  `redirectTargets` keys are restricted to
   a safe charset and the map is hard-bounded (`CAP=12`), because the operator drops the **whole**
   termination-message summary on invalid or oversized JSON, so one fuzzed/reflected `Location` must
   never be able to void it.

**Load-baseline discontinuity (read before comparing runs).** Because load mode no longer pays the
follow hop, any corpus whose steps redirect (JSPWiki `edit_save`, the jpetstore Stripes POSTs) reports
`p50`/`p90`/`p99` and `throughputRps` that are **not comparable to pre-DD-038 numbers** — the
post-DD-038 figures measure the server's direct response only, so re-baseline any benchmark campaign
that spans this change rather than reading the shift as a performance regression or win.

**Verified.** Unit tests: nonce uniqueness across calls and never masking an unbound `${{ref}}`; a
path-only marker substitutes without NPE on a null body; `lintCorrelationOrdering` exempts `@nonce` and
scans both path and body; `paramValue`'s `(^|[?&])page=` rule on all three shapes (body-leading `^`,
`?page=`, `&page=`) and its rejection of `frompage=`; `normalizeLocation` self-fold composed from a
body-leading `page=` (i.e. the `paramValue` → `normalizeLocation` pair the worker actually performs),
absolute-URL handling, and 64-char truncation; `admitKey`'s cap — a NEW key on a full map becomes
`"other"`, a key already present is still admitted (never fragmented across two buckets); `fireR`
returns the `Location` on a `302`, `null` on a `200`, and a `302` carrying `Set-Cookie` still populates
the jar; `summaryJson` stays valid JSON with a populated `redirectTargets`. Grammar validation confirms
the marker survives expansion verbatim and fills uniquely per fire. Full suite green (215 tests).
*Residual gap:* the worker's own recording block (extract-page → classify → admit → increment) is
covered only by its parts, not end-to-end through a live redirect under load.

**Boundary of the rule.** The classifier's only signal is the `Location` string, so it cannot
distinguish a rejection from a success when both echo the *same* route. The three rejection causes
observed live each answer on a distinct route, which is what makes the path-segment rule work — but
that is an empirical property of this app, not a guarantee. A future cause that renders its refusal
as a flash message on the view page (an approval hold, an ACL denial) would land on `Wiki.jsp` and
fold into the success bucket, reproducing this exact defect for a new cause. The rule assumes "one
route, one meaning"; when that stops holding, the signal has to come from the response body, not the
header. Likewise `samePage` encodes JSPWiki/MediaWiki first-letter canonicalization specifically; a
target that canonicalizes differently (full case-fold, whitespace to underscore) reintroduces the
under-folding bug for that target, and the fold would need to become pluggable.

**Rejected alternatives.**
- **A reserved `${{nonce}}` correlation name** — would squat the author-controlled `${{}}` namespace
  DD-036 established for response captures. Rejected in favor of a `<…>` generator, the namespace
  `<int>`/`<string>` already occupy for value primitives.
- **A bare `AtomicLong` seeded with millis, no separate salt** — the seed and the counter advance in
  different units (wall-clock ms vs. number-of-saves), so under load a later run's seed lands inside a
  prior run's emitted range and re-emits still-saved values, reintroducing the no-op (see Decision 1).
- **Packing salt and counter into one integer's high/low bits** — caps a run's save count (bits spent
  on the salt) and collides when two runs start in the same millisecond. The
  `<millis>x<host>x<pid>-<counter>` string has neither limit and stays URL-safe.
- **Random/UUID nonce** — works, but is bigger on the wire for no uniqueness benefit over a per-process
  salt plus a monotonic counter.
- **Keep auto-follow, infer the redirect target from `getURL()`** — `HttpURLConnection.getURL()`
  returns the *original* URL after following, not the final one, so the actual redirect target isn't
  recoverable without disabling follow. Rejected.

**Note — no-follow is a session-carry FIX, not a regression.** With follow ON, a `302` from an
authenticated write was re-issued as a *cookieless* GET whose *anonymous* `Set-Cookie` overwrote the
jar's valid `JSESSIONID` on the way to the redirect target, silently dropping the session mid-sequence.
`captureSessionCookie` runs on the direct `3xx` response *before* the (now-absent) follow, so the real
session cookie is kept and the anonymous overwrite never happens — session carry strictly improves.

**Amended 2026-07-23 — classification rule revised after the first live c8 run.** Two defects, both
verified against the running JSPWiki 2.12.4 (probes, not code reading):
1. **First-letter canonicalization broke the fold.** JSPWiki resolves an unknown page name through
   `MarkupParser.cleanLink` → `TextUtil.cleanString`, which uppercases the *first* kept character and
   preserves the rest (live: saving `page=ndws` answers `Location: /Wiki.jsp?page=Ndws`; `nDWS` →
   `NDWS`). The byte-equality fold missed every lowercase-page save (`"Ndws": 1051` in the first run),
   refilling the bounded map with per-page success keys — the crowd-out the fold existed to prevent.
   `samePage` now also equates names differing *only* in first-character case (deliberately not full
   `equalsIgnoreCase`: past the first char, case still distinguishes genuinely different targets, e.g.
   request `sessionexpired` vs. reject `SessionExpired` must NOT merge).
2. **The `"self"` fold over-folded a real reject.** A genuine concurrent-edit conflict answers
   `302 /PageModified.jsp?page=<theVeryPageBeingSaved>` (live-verified: two sessions, interleaved
   saves) — the `page=` matches the firing request's own page, so "page= wins, same page → `self`"
   filed a *rejected* write into the success bucket. Over-folding hides rejects — strictly worse than
   the crowd-out. Revised rule: a **cross-page** redirect keys by the page name (unchanged); a
   **same-page** redirect keys by the Location's last *path segment* — every success shares the one
   view route's key (`"Wiki.jsp"`), while a same-page reject route keeps its own (`"PageModified.jsp"`).
   `"self"` remains reserved only for the degenerate same-page echo with an empty path segment.
   Consequence for reading summaries: pre-fix runs cannot distinguish accepted saves from
   `PageModified` conflicts inside `"self"` — treat pre-fix `"self"` as "save attempts", not
   "accepted saves".
---

## DD-040: Trustworthy measurement — a reported `0` means "checked and clean" (2026-07-23)

**Context.** Four defects shared one shape: the tool printed `0` for something it had never
measured. That is the exact failure this project exists to eliminate, and it was sitting in the
project's own output.

*The measured loss, and which way it is biased.* The boundary evaluates every invariant server-side
and logs each violation, then attaches `X-Basquin-Invariant-Count` to the response — but only
`if (!r.headers.isEmpty() && !response.isCommitted())`, and the driver learned of violations
**solely** from that header. A synchronized in-pod replay of each app's own corpus measured what
survives:

| app | responses that could not report | violations evaluated → reported |
|---|---|---|
| roller | 36/37 (**97.3%**) | 52 → 0 |
| jspwiki | 24/32 (**75%**) | 23 → 0 |
| jpetstore | 0/43 (**0%**) | 5 → 5 |

One Roller explore window evaluated **1,906 violations and reported 0** (`invariants {0,0,0}`,
`findInvariant 0`, over 1,786 iterations). JSPWiki's surviving 25% are redirects and small 400s,
which never violate, so its effective loss is total too. **JPetStore is the outlier that made the
tool look functional** — its pages are 1–6 KB, so nothing commits before the boundary exits. The
bias runs the wrong way: loss correlates with response size and with flushing, i.e. with **the
expensive requests the tool exists to find**. Second-order, and worse: `CostSample`'s
heap/thread/invariant inputs came from the same headers, so where they were lost the cost model
received zeros and exploration degenerated to latency-only ranking — which is why Roller's retained
corpus collapsed to 2 entries.

*The three commit triggers, and that they depend on which boundary is installed.* Kubernetes injects
`-Dbasquin.boundary=agent`, so the cluster runs `TomcatBoundaryAdvice` woven into
`StandardHostValve.invoke`, whose exit runs **after** Tomcat's error-page dispatch. The header is
lost iff the response is committed at boundary exit:

1. **More than 8192 bytes** through the `OutputBuffer` (Roller's 8,799 B search loses it; JPetStore's
   6,116 B keeps it).
2. **Any explicit flush, at any size** — Struts/Tiles JSPs, and Roller's opensearch servlet at
   **552 bytes**. A buffer-size story alone cannot explain the data.
3. **An error status with a *custom* `web.xml` error-page**: the `RequestDispatcher.forward` commits
   and closes inside `StandardHostValve`. Roller declares error pages, so its 1,215 B 404 loses the
   header; JSPWiki and JPetStore declare none, so their 404s render in `ErrorReportValve` *above* the
   boundary and survive.

It survives on redirects, on sub-8 KB non-flushing responses, and — the detail that proves the rule
— on static files **above** the 48 KB sendfile threshold: a 35,891 B `mootools.js` **loses** the
header while a 51,058 B `jspwiki-common.js` **keeps** it, because sendfile bypasses the
`OutputBuffer` entirely. Only this rule set explains that non-monotonicity.

*Three more structural zeros.* Load mode is passthrough (DD-029) and evaluates nothing, and the load
driver JVM was never given `-Dbasquin.invariant.latency.maxMs` — Roller reported
`violations.latency: 0` at a p50 of 503 ms against an intended 250 ms budget; `summaryJson`
additionally hardcoded `"heap":0,"thread":0`. The explore summary's `invariants` block measured the
**driver's** JVM (no thresholds configured), presented as target data. And `Agent`'s leak detector
threw unconditionally: a Roller publish wrote its row and the client received an empty `500` — the
harness destroying the app's correct response, and one step from recording its own exception as an
app crash.

**Decision.** A per-request-id result store inside the target JVM, written by the boundary and read
by the driver over the `/__basquin/*` control surface it already talks to. No new listener, port, or
operator wiring; nothing added to the load path.

1. **The channel.** The driver stamps each explore request `X-Basquin-Req: <RUN_SALT>-<n>`; the glue
   calls `RequestBoundary.stampRequestId` **only** on the `EXPLORE_BEGAN` branch (so a load request
   never even reads the header) — and `onExit` likewise touches the id thread-local **only** after
   the phase check. The first version cleared it before the check, so every load passthrough paid a
   `ThreadLocal.get()` (whose `setInitialValue` *inserts* a null-valued `ThreadLocalMap.Entry` — an
   allocation) plus a `remove()`, while three separate places in this repo said the load path gained
   zero instructions. The clear is safe to skip because only an explore request can ever *write* an
   id, and the explore branch always removes it, so nothing can go stale on a pooled connector
   thread; `RequestBoundaryLoadPathCostTest` now asserts the absence of the map entry directly
   rather than restating the claim. `onExit` writes `id → Entry(costCsv, invariantCount, detail, leak)`
   into a 256-entry LRU; the driver `GET`s `/__basquin/result?id=…`, which is `CONTROL_HANDLED` and
   never reaches the app. Entries are removed on read; retention is bounded to ≈ 256 × ~0.5 KB
   ≤ **~150 KB**, stated as a byte bound because it is a baseline shift inside the very JVM whose
   heap deltas this tool reports. The response header stays as an opportunistic fast path,
   discriminated on `X-Basquin-Cost` (emitted on *every* explore exit) rather than
   `X-Basquin-Invariant-Count` (absent on every clean request, which would make the driver poll
   almost always). The two channels are alternatives, never a sum: remove-on-read plus counting the
   header would file the same violation twice.

2. **The poll waits on `ITERATION_LOCK`, and that is the critical detail.** `Agent.end()` sleeps
   25 ms **before** measuring, and the store write happens after that. On a committed response — a
   `Content-Length` body, or the error-page class that commits and closes inside `StandardHostValve`
   — the client reaches EOF roughly 25 ms *before the entry exists*. A poll issued ~1 ms later finds
   nothing and records a miss, **for the motivating class, every time**, while passing every mocked
   unit test. So `/__basquin/result` acquires the fair `ITERATION_LOCK` (bounded wait, 2 s) before
   reading the store: the poll simply queues behind the in-flight iteration, which costs nothing the
   run was not already going to spend, since the driver's *next* explore request would queue on the
   same lock anyway. The wait is a **timeout**, not an indefinite block — a target wedged inside the
   app must produce a miss, not hang the driver. The driver's own read timeout (4 s) deliberately
   exceeds the handler's 2 s bound, or a poll queued behind the very iteration about to write its
   entry would score as a miss every time.

3. **The store is internally synchronized, separately from the lock.** `ITERATION_LOCK` makes the
   *write* provably belong to that request; it does not make the *reader* safe, because the poll is
   handled at `onEnter` on a different connector thread that never touches it. Both are required.

4. **The entry is built from `IterationContext`, before `endIteration()`, and published in a
   `finally`.** `Agent.endIteration()` **throws** on a hard-mode invariant violation (global mode
   defaults to **hard**) and on a leak, and its own `finally` does `CURRENT.remove()`. Building the
   entry from its return value — or capturing the context after the call — therefore skips the store
   write for **exactly the violating iterations**, making the new "reliable" channel weaker than the
   header it replaces, and failing *green*. Building from the per-iteration context rather than
   `Agent`'s process-global `last*` volatiles also makes the channel concurrency-*proof* instead of
   concurrency-*conditioned*: those statics belong to the right request only because explore is
   serialized today, and misattributing another request's numbers to this id is the worst failure
   class this work exists to prevent.

5. **Ids are salted** with DD-038's per-pod `RUN_SALT` (`<RUN_SALT>-<n>`). A bare monotonic counter
   collides in two real cases — two drivers against one target (parallel campaigns, or a human `curl`
   mid-run) both count from 1, and a long-lived target across sequential campaigns still holds
   campaign 1's unread entries when campaign 2 asks for id 173. Remove-on-read then hands back
   **stale data as fresh**, which is strictly worse than a miss. A foreign or stale id now misses
   honestly.

6. **A miss is *unmeasured*, never zero.** `CostSample.EMPTY` is gone — deliberately renamed
   `UNMEASURED`, so a zero-filled sample cannot be reintroduced by autocomplete. A miss skips
   `CostModel.score` entirely, increments `reportMisses`, and marks the run's finding count as a
   **lower bound** as first-class data (`findingsLowerBound`), not prose — *and that marker is wired
   all the way through*, which it was not when this PR was first handed over. Emitting it into the
   driver summary and reading it nowhere would have been the DD-035 `driftUnavailable` defect (item
   7) reintroduced by the change that fixes it: the operator would still print "run complete: …, 12
   findings" for a run that was blind to a third of its traffic. So both markers are parsed into
   `status.findingsLowerBound` / `status.reportMisses` (a **pointer**, so "the driver never said" is
   not rendered as "zero misses"), the Ready condition says "at least N findings (lower bound: M
   request(s) reported no measurement)", and the dashboard prefixes the count with `≥` and names the
   unmeasured requests. Both CRD copies were regenerated — a field absent from the *served* schema is
   pruned by Kubernetes, which would silently defeat the whole chain, and a test now pins its
   presence in both. A run where misses are the
   *majority* prints a FATAL line and exits 3 (`-Dbasquin.report.failOnMissMajority=false`
   downgrades it) — against an uninstrumented target `reportMisses` ≈ iteration count, a situation
   that used to read as a clean zero. The poll lives in a `finally`, because a `serverError` would
   otherwise leave a 500's measurements rotting in the map until eviction, and 500s are precisely the
   interesting requests; nothing thrown by the poll may replace the in-flight error.

7. **Omitting a field at the driver would have been theater, so all four layers changed.**
   `LoadViolations.{Latency,Heap,Thread}` were non-pointer `int32` with `omitempty`, so an omitted
   field unmarshals straight back to `0`; the Ready condition — the message operators actually read
   via `kubectl get basquincampaign` — printed `"%d latency violations"` unconditionally; and the
   dashboard rendered `(ld.heapDriftKb||0)+' KiB'`. Note that DD-035's `driftUnavailable` marker had
   been **emitted since DD-035 and parsed nowhere**; it is now actually wired. So: pointer fields
   (`nil` = not checked, non-nil `0` = checked and clean) plus explicit `notEvaluated` /
   `driftUnavailable` markers; a condition message that does not print a count it does not have; no
   `||0` defaults for measured values, and a *skipped* sparkline point rather than a fabricated `0`
   (a fake zero does not merely misplot — it drags the line to the floor and reads as "the heap
   recovered"); an empty CSV cell, which every plotting tool renders as a gap; and `summaryJson` no
   longer emitting `heap`/`thread` counts load mode never evaluates. The Helm chart's CRD copy was
   synced in the same change — a stale copy makes Kubernetes **prune** the new honesty markers out of
   stored status.

8. **The `latencyMaxMs` trap.** `buildDriverJob` already propagated
   `-Dbasquin.invariant.latency.maxMs` from `campaign.spec.driver.invariants`; the bench runs had
   none because the threshold lives on the **BasquinTarget**, which only reaches the *target* JVM.
   An implementer could have satisfied "propagate latencyMaxMs" by pointing at the existing field and
   closing the ticket with the trap intact. The rule: **the load driver inherits the target's
   `invariants.latencyMaxMs`**, and `spec.driver.invariants` overrides it. **Explore deliberately
   does not inherit** — the target's agent evaluates and reports over this new channel, so a
   driver-side copy of the same budget would count a second, differently-scoped violation (client
   round trip, network, driver GC) for the same request. `driverSpecHash` excludes the target's
   `latencyMaxMs`, consistently with the app image: editing the target mid-campaign does not restart
   an in-flight driver.

9. **Soft mode never alters the response, and the leak is actually recorded.** The leak throw is
   gated on `Invariants.isHard("basquin.invariant.leak.mode")` — a per-invariant override falling
   back to global mode, which defaults to **hard**, so unconfigured runs and CI are unchanged.
   `basquin.forceExitOnLeak` stays deliberately *outside* the gate: a flag whose entire meaning is
   "exit on leak" must not silently do nothing. The recording half mattered as much as the safety
   half: "in soft mode a leak is recorded" was **false** before this change — leak evidence was not
   in `lastInvariantViolations`, therefore not in any header, and `StatusReporter.recordIteration` is
   a no-op in a target JVM without `-Dbasquin.status`. It was stderr only. The leak flag now rides
   the per-id entry and surfaces as a `Leak-Remote` finding, so soft mode trades a false 500 for a
   *kept* defect rather than a lost one.

10. **§A.6 correction — the pod-identity header cannot route the poll.** The spec originally proposed
    that the boundary stamp its pod identity and the driver follow it. That can never work: the pod
    header rides **the same response headers the poll exists to compensate for**, and the driver
    polls *iff* no cost header arrived. The header is therefore present exactly when there is no
    poll, and absent exactly when there is one — mutually exclusive by construction. On Roller, 97.3%
    of responses had committed, so on 97.3% of requests it cannot exist. Any design that routes a
    poll using information carried on the response it is compensating for has this defect. What
    ships instead is **DNS fan-out**: the driver resolves the target's pod addresses with
    `InetAddress.getAllByName` (DD-023's exact mechanism and loopback collapse, reused) from
    `-Dbasquin.report.podHost`, else the host of `-Dbasquin.coverage.jacoco` — the headless coverage
    Service the operator already passes on every explore campaign, so **no operator change was
    required** — else the baseURL host, and tries each until one returns the entry. The fan-out
    rests on §A.4 and §A.7: ids are salted and entries are removed on read, so **for a request
    answered without a redirect** at most one pod can hold a given id, and a fan-out can be wrong
    only by finding nothing — a counted miss, never another pod's measurement. **That property does
    not hold across a followed redirect on a multi-replica target, and this record originally
    claimed it did**; see residual 2 below. Single-replica behaviour
    is byte-identical (a derived source resolving to fewer than two addresses returns the base URL
    untouched). `X-Basquin-Pod` is still emitted, demoted to what it can actually do: attribution
    (`pod=` on a finding), which is what a two-replica reconciliation against pod logs needs.

**Verified.** 278 Java unit tests (275 before the approver round that added the load-path-cost
probe) plus the operator's Go unit + envtest suites, and an acceptance run against Apache Roller on 2026-07-23
that **did not pass**, recorded here as measured rather than as success.

**Evidence: [`bench-results/dd040-acceptance-2026-07-23/`](../bench-results/dd040-acceptance-2026-07-23/).**
Every figure below re-derives from committed artifacts — the redacted driver log and summary, the
target pod's `[Basquin][Invariant]` lines sliced to the driver's exact window, and the run's own
`Invariant-Remote` findings — via `reconcile.py`, which exits nonzero if any of them drifts from
what this record claims. They were prose only when this PR was first handed over, which is invariant
3's failure mode and, uncomfortably, this record's own defect class one level up. What is *not*
recoverable (the campaign object and the driver's `summary.json`, deleted with the campaign; the
live redirect probe and the 200-poll perturbation check, which produced no files) is named as such
in that directory's README rather than reconstructed.

The run recovered a great deal: **1,413 violations reported** where the same campaign shape
previously reported **0**, with `reportMisses: 0` and the retained corpus back to **99 entries**
from the degenerate **2** that the zero-fed cost model had produced. Several independent
reconciliations hold exactly — the per-id findings sum equals `targetViolations`, all 1,235 driver
`detail=` strings appear verbatim in the pod log, per-iteration violation shapes match one-for-one,
and 200 `/__basquin/result` polls advanced the target's iteration counter by 0 (the control path
genuinely never begins an iteration).

**But the pod logged 1,602 in the same window: a residual gap of 189, or 11.8%.** The acceptance
criterion was that these two numbers agree, and they do not.

**Residual 1 — root cause, reproduced on demand.** A followed redirect that *changes method* (POST → 302 → GET)
loses the `X-Basquin-Req` header, because the JDK does not carry a `setRequestProperty` value onto a
method-rewritten hop. Hop 2 therefore runs unstamped: it evaluates, it logs, it publishes nothing,
and it is never counted. Measured live — `POST /roller-ui/authoring/entryAdd.rol` → `login.rol` moved
`/__basquin/violations` by **0** while the pod logged `heapDelta: 4900KB > 512KB`. Same-method hops
*are* stamped and counted, which is why the "final-hop-wins" reasoning looked right and is wrong for
precisely the shape Roller's grammar generates most.

**This residual is worse than a miss, and it is this record's own defect class in a narrower form.**
The poll *succeeds* — hop 1 answers it — so the driver records `measured=true, count=0`: a clean
zero for a request whose real work violated. `reportMisses` cannot see it, because nothing missed.

**It was predicted and we did not connect it.** DD-039's spec documents this exact JDK behaviour for
the `Cookie` header ("on a 301/302/303 the JDK issues the follow hop with no `Cookie` header at all
— it is specifically the method-rewriting path that strips it"). The same mechanism drops
`X-Basquin-Req`. DD-040's Task 3 was explicitly instructed to keep final-hop-wins and leave per-hop
semantics to DD-039; that instruction produced this gap.

**Closing it is DD-039's acceptance criterion.** DD-039 replaces the JDK's auto-follow with an
explicit hop loop that sets headers on every hop, which stamps each one by construction. Until then
this channel is a large improvement with a measured, bounded, documented hole — not a fix that can
be described as complete.

Probe noise is not the explanation: measured idle rate is 3 lines/120s, and the kubelet probe's
5-second grid accounts for at most ~22 lines on the most generous attribution, against a gap of 189.

**Residual 2 — the §A.6 fan-out can return the wrong hop's measurement on a multi-replica target,
and this record originally claimed it could not.** §A.6 above asserted, as the reason pod fan-out is
safe, that "at most one pod can ever hold a given id". That is false for a **same-method redirect
hop across replicas**. Explore deliberately re-sends the same `X-Basquin-Req` on a followed hop
(final-hop-wins), and the JDK *does* preserve the header on a same-method hop — so hop 1 (pod A) and
hop 2 (pod B) can both `put` under that id, and the fan-out returns whichever pod answers first in
DNS order. That may be hop 1's clean, cheap 302 for a request whose real work violated on hop 2:
`measured=true` with the wrong hop's numbers, invisible to `reportMisses` because nothing missed.

It is the *same class* as residual 1 — a clean zero for work that violated — reached by a different
route, and it is worth saying plainly that residual 1 was found by measurement while this one was
found by an approver reading the claim against the code. Scope: single-replica targets cannot hit it
(the run above was single-replica, and multi-replica — spec verification item 14 — was not
exercised); multi-replica targets are a supported configuration (DD-020), so this is a real hole,
not a hypothetical one. **No fix is attempted here:** DD-039 replaces auto-follow with an explicit
per-hop loop that gives every hop its own id, which dissolves residual 1 and residual 2 together,
and a partial fix in this PR would be a second mechanism to retire a week later. The claim sites are
corrected instead — spec §A.6, this §A.6, `PodPollTargets`'s class javadoc and `pollResult`'s — so
nothing in the tree still asserts a guarantee the code does not provide.

**Rejected alternatives.**
- **Set the headers before the app runs.** The values do not exist yet.
- **Piggyback request *N−1*'s result on request *N*'s response** (headers set at `onEnter` survive
  commit, and the "values do not exist yet" objection does not apply to the *previous* request's
  values). Genuinely elegant — it removes the extra request, the perturbation and the grace-sleep
  race in one move. Rejected as the primary mechanism because it makes every finding lag one request,
  complicating the skip, abort and end-of-run paths (the last request still needs a flush) and
  coupling iterations that are otherwise independent. Worth revisiting as an optimization once the
  poll is correct, at which point the poll becomes a tail-flush only.
- **A response wrapper that defers commit.** Buffers unbounded bodies inside the JVM whose heap this
  tool reports, defeats sendfile, breaks streaming, and is deeply invasive under agent advice. A
  larger `setBufferSize` would still lose the flush and error-page classes.
- **The target pushes to the dashboard.** The target never talks to the dashboard — the *driver* does
  (DD-013 deliberately keeps the measured JVM free of that plumbing).
- **Cumulative counters only.** `/__basquin/violations` loses per-input attribution — which is what
  the cost model needs — and requires probe-noise filtering (the kubelet readiness probe alone
  violates heapDelta ~12/min on JSPWiki at idle). Adopted as a supplement, rejected as the mechanism;
  the id-keyed path sidesteps probe noise entirely, since the driver reads only ids it issued.
- **Re-score from pod logs instead of fixing the channel.** Cannot feed the cost model during a run,
  cannot attribute per input, and the logs rotate. Adopted once as corroboration; see "Disposition".
- **The boundary reports its pod IP for the driver to build a directory.** Strictly weaker than DNS:
  the driver learns only pods that *returned headers*, knows none at run start, never learns a pod
  that always commits — and would still have to fan out over that incomplete set.
- **Pod-name → address via headless DNS.** `<pod>.<svc>` only resolves with `spec.hostname`/
  `subdomain`, a StatefulSet convention; Basquin instruments Deployments.
