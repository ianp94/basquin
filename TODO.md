# ClosureJVM v0.1 Tasks

## Milestone: "Make leaks obvious"

### Core Requirements
- [x] Create repo structure (`agent/`, `runner/`, `examples/`)
- [x] Add README + this file
- [x] Add a `TODO.md` with v0.1 tasks
- [x] Add a "hello runner" that executes an iteration loop over a corpus directory
- [x] Add thread leak check that fails a demo target

### Implementation Tasks
- [x] Implement iteration boundary API: `beginIteration()` / `endIteration()`
- [x] Implement thread leak detection (new non-daemon threads after iteration)
- [x] Implement basic executor/timer leak detection (best-effort)
- [x] Create minimal runner (CLI or JUnit extension)
- [x] Create one example target + README quickstart
- [x] Add forked leak integration test (expects non-zero exit and leak text)
- [x] Add Gradle wrapper for consistent CI/dev usage

### Testing
- [x] Test with a small servlet/controller example (Javalin leak demo task)
- [x] Test 10,000 iterations locally and record metrics (2026-07-19: 10k/10k clean, no leaks; latency p50=1ms p99=2ms max=15ms after grace-sleep fix; threads steady at 7)
- [x] Demonstrate reliable detection of deliberate thread leak (local + test)
- [x] Add CI workflow: build, test, proper demo; leak demo expected to fail

### Operational Notes
- Leak demo can hang the JVM due to non-daemon threads; use `-Dclosurejvm.forceExitOnLeak=true` in demos/CI to force fast termination on leak detection.

### Next Up (v0.1 hardening)
- [x] Add simple metrics snapshot (thread count, heap delta) at iteration boundaries (print-only)
- [x] Tighten executor/timer tracking (weak refs; ScheduledThreadPoolExecutor details)
- [x] Document quick flags in README (done) and add CI badge once wrapper lands

---

## Milestone: v0.2 — "Reset discipline"

Goal: Turn findings into enforceable guarantees.

### Deliverables
- [x] Configurable invariants (latency, heap delta, thread delta) via `-Dclosurejvm.invariant.*`
- [x] Hard failure vs soft signal modes (global and per-invariant)
- [x] First reset strategy: hard-reset fallback (ClassLoader swap)

### Tasks
1) Invariant polish
   - [x] Hook invariant checks at iteration end (after metrics snapshot)
   - [x] Add heap/thread invariant tests similar to latency test

2) Reset strategy: Hard reset fallback
   - [x] Implement child-first ClassLoader for target package; keep Agent/Runner in parent
   - [x] Load `runner.api.IterationTarget` via child loader; re-instantiate on reset
   - [x] Flags:
       - `-Dclosurejvm.reset=classloader` (enable)
       - `-Dclosurejvm.reset.onFailure=true` (reset after hard invariant/leak)
       - `-Dclosurejvm.reset.maxResets=3` (cap)

3) Docs & DX
   - [x] README: document invariant flags and reset flags/behavior
   - [x] Example snippet showing how to enable reset with GenericRunner
   - [x] CI job includes Gradle 'check' (verification tasks)

---

## Milestone: v0.3 — "Exploration"

Goal: Add coverage-guided exploration without destabilizing the harness.

### Deliverables
- [x] Integration with a fuzzer: JQF (separate source set; opt-in Gradle tasks)
- [x] Save interesting inputs + triage metadata (Crash + Invariant; timestamp; stacks via configurable capture/sampling)
- [x] Minimization (basic ddmin via `runner.MinimizeRunner`)

### Tasks
1) Pick exploration engine
   - [x] Choose JQF based on pure-Java embedding and clean iteration integration
   - [x] Minimal glue to feed inputs to IterationTarget (`examples.fuzz.JQFIterationHarness`)

2) Input management
   - [x] Store inputs that trigger crash (exceptions) and invariants (soft mode) in per-target results dirs
   - [x] Record triage metadata: classification, timestamp, violation details, stacks (current/all; latency sampled execution stack)
   - [x] Provide simple corpus layout with example seeds (calculator/http/json/latency/heap/tomcat)

3) Minimization
   - [x] Implement ddmin-like reducer (`runner.MinimizeRunner`)
   - [x] Ensures boundaries via IterationTarget + begin/end in each trial

4) Docs & DX
   - [x] README quickstart for exploration runs, seeding, per-target results, minimization
   - [x] Multiple example targets wired (calculator/http/json/latency/heap; optional tomcat)
   - [x] Gradle tasks for fuzz and corpus replay per target; opt-in JQF via `-DenableJQF=true`

---

## Milestone: v0.4 — "Real App Demo (Tomcat)"

Goal: Deliver a realistic web app slice that surfaces both crashes and availability invariants in one place for stakeholder buy‑in.

### Phase 1 — Embedded Tomcat (in‑JVM)
- [x] Add embedded Tomcat bootstrap under `examples.server.*` with three routes:
  - [x] `/crash?type=...` (throws)
  - [x] `/latency?ms=...` (sleeps)
  - [x] `/heap?kb=...` (allocates)
- [x] Add `examples.targets.TomcatFuzzTarget` (IterationTarget + InputReceiver) that:
  - [x] Starts/stops embedded Tomcat in initialize()/close().
  - [x] Maps `byte[]` → one HTTP request/iteration; treats 5xx as crash.
- [x] Seeds + tasks:
  - [x] Starter corpus for each route (`examples/corpus/tomcat/...`).
  - [x] `runFuzzTomcatJQF` (opt‑in): seeds + coverage‑guided; results to `fuzz-results/tomcat`.
  - [x] `runTomcatCorpus`: deterministic replay + invariant capture.
- [x] Docs:
  - [x] README section: purpose, commands, and what artifacts to expect.
  - [x] AGENTS guardrails reaffirmed for scope.

### Phase 2 — WAR + External Tomcat (Docker)
- [x] Package routes as a minimal WAR.
- [x] `docker-compose.yml` with Tomcat + MySQL (Compose v2 recommended).
- [x] Inject agent via `CATALINA_OPTS=-javaagent:...` to capture server‑side invariants.
- [x] HTTP driver target issues one request/iteration; thresholds in soft mode to collect invariants.
- [x] Minimal `/db` route with knob to induce latency (`SELECT SLEEP()` via tiny pool).
- [x] Status servlet and simple UI page with recent invariant details and short stack snippet.

> Phase 2 hardening + Phase 3 enrichment open items are consolidated in the **Backlog** section
> below (Docs & DX, Signal & triage quality).

### Non-goals (keep scope tight)
- No coverage integration yet (v0.3)
- No full static rollback; prefer classloader swap fallback

---

## Milestone: v0.5 — "Observability core"

Goal: Make the measurement layer trustworthy and cheap enough to point at real apps.
(Decisions recorded 2026-07-19; rationale in agents.md Status Snapshot.)

### Measurement quality (done)
- [x] Latency measured before the end-of-iteration grace sleep (was inflating all readings ~25ms)
- [x] Opt-in `-Dclosurejvm.heap.gcBeforeMeasure` so heap delta measures retention, not allocation noise
- [x] Hot path: `ThreadMXBean` + stack-free enumeration instead of `getAllStackTraces()`; stacks captured lazily on violation only
- [x] Soak validated: 10k/10k clean (latency p50=1ms p99=2ms) — retires v0.1 Definition of Success

### Native agent (JVMTI)
- [x] Event-driven thread *counts* via ThreadStart/ThreadEnd (`native/closurejvmti.c`), `ThreadMXBean` fallback when not loaded
- [x] Event-driven leak *set*: weak global refs to non-daemon Thread objects tracked at ThreadStart/ThreadEnd; leak oracle needs no enumeration (verified: leak demo still names leaked threads; proper mode clean)
- [x] JDK 17 + 21 CI matrix; bytecode targets 17

### Concurrency decision (A now, C later — decided)
- [x] Option A: serialize iterations in the WAR IterationFilter — DONE and **still the live mechanism by design**. DD-010 deliberately KEPT the DD-005 serialization lock (`IterationFilter`'s `ReentrantLock ITERATION_LOCK`, "iterations are SERIALIZED"), because heap/thread deltas are process-global and would lie under concurrent requests. Option C did not remove it.
- [x] Option C: explicit IterationContext API (`ctx = Agent.begin(); Agent.end(ctx)`) — done in v0.6 (DD-010); made the code concurrency-safe *without* dropping Option A's lock
- Rejected: ThreadLocal-only contexts (per-request heap/thread deltas would silently lie under concurrency); external message bus for metrics (capture cost is in-process; bus is for a future multi-node fleet, not single-harness throughput)

### Real-app targets (after the above)
- [x] Injection mechanism decided + built: Tomcat valve (DD-009), verified loading in real
      Tomcat 10.1; valve and in-WAR filter are mutually exclusive. Scaffolding:
      `tomcat-valve/`, `deploy/valve/context.xml`, `docker-compose.valve.yml`, docs/THIRD-PARTY-APPS.md
- [x] JPetStore (MyBatis) live run DONE (2026-07-19): javax.servlet, deployed on Tomcat 9 with
      the namespace-free valve; server-side latency (up to 531ms cold) + heap (up to 44MB/req)
      invariants captured in the unmodified app. See docs/THIRD-PARTY-APPS.md.
- [x] HTTP driver + seed corpus to explore JPetStore routes automatically — done in v0.10
      (grammar/corpus-driven coverage-guided exploration).

> More calibration targets (WebGoat/OWASP Benchmark, JSPWiki, XWiki/OpenMRS) are in the **Backlog**.

---

## Milestone: v0.6 — "Iteration context"

Goal: Replace the static per-iteration Agent state with an explicit context object so
concurrency correctness stops depending on a serialization lock.

- [x] `IterationContext` API: `ctx = Agent.begin(); ... Agent.end(ctx)` — per-iteration
  baselines (latency, heap, thread set, sampler) moved onto the context; legacy
  beginIteration/endIteration kept as ThreadLocal-backed wrappers (all callers unchanged).
  Contract test in IterationContextTest; full suite + native leak-set verified green. (DD-010)
- [x] Documented the boundary: latency/leak-set scope cleanly per context; heap/thread deltas
  remain process-global and are only trustworthy under serialized or single-flight runs

> Remaining v0.6 threads (triage handoff payload carrying the context snapshot; valve/filter moving
> to explicit begin/end(ctx)) are in the **Backlog** under Signal & triage quality.

---

## Milestone: v0.7 — "Operator DX" — DONE

Goal: Make a running harness legible at a glance, AFL-style.

- [x] Live in-place CLI status screen (`-Dclosurejvm.status`): elapsed, iterations, iters/sec,
  crashes, invariants by kind (latency/heap/thread), leaks, latency last/mean/max, heap delta,
  threads, resets. `StatusReporter` fed once per iteration from `Agent.end`.
- [x] TTY-aware: in-place box on a terminal, one-line summaries when piped/CI;
  `-Dclosurejvm.status.forceTty` to force the box; `renderFinal()` guarantees a final tally.
- [x] Suppresses per-iteration metrics spam when active.
- [x] HTTP driver target (`examples.targets.HttpRouteDriveTarget`, task `runHttpDrive`):
  client-side latency + 5xx-as-crash, harvests server-side `X-ClosureJVM-Invariant-*` headers.
- [x] Animated demo (`docs/demo.svg`) from real frames, embedded in the rewritten README.

---

## Milestone: v0.8 — "Cross-namespace" — DONE

Goal: Run against `javax.servlet` apps (Tomcat 9), not just `jakarta.servlet` (Tomcat 10+).

- [x] Decision (DD-011): NOT a second module — made the valve bytecode namespace-free so ONE
  jar runs on both. Narrowed `invoke` to `throws IOException`, headers via Catalina Response,
  `sneakyThrow` for the checked exception. `javap`-verified: zero servlet-namespace references.
- [x] `docker-compose.valve9.yml` for the Tomcat 9 path (WAR_PATH-driven).
- [x] Dropped the servlet-api imports from the valve entirely (not just HttpServletResponse).
- [x] Verified against a real javax app: JPetStore-6 on Tomcat 9, server-side invariants
  captured (see docs/THIRD-PARTY-APPS.md); same jar re-verified on Tomcat 10 (no regression).
- [x] Docs: THIRD-PARTY-APPS namespace-selection table + JPetStore findings.

---

## Milestone: v0.9 — "Exploration + UI"

Goal: See coverage-guided exploration running, with exploration progress in the live UI.

- [x] Exploration metrics in `StatusReporter`: execs/sec, corpus size, finds by classification
  (crash / invariant), time-since-last-find — fed from the triage layer (FuzzIO.recordSaved) so
  they cover both the JQF path and corpus replay. Panel appears when finds arrive.
- [x] Forward `-Dclosurejvm.status*` through the JQF fuzz task so the panel shows during a run;
  StatusReporter render started in the JQF harness + a shutdown-hook final frame for all run types.
- [x] Live demo of a JQF campaign with the exploration panel; captured as `docs/demo-explore.svg`,
  embedded in the README Exploration section.
- [x] Honest scoping: in-process JQF coverage guidance stays local; the app-under-test coverage
  signal (over HTTP) is v0.10 (below).

## Milestone: v0.10.0 — "Deploy & distributed exploration"

Goal: Run ClosureJVM against apps in a cluster, and make exploration coverage-guided by the
app's own execution over HTTP. Some of this may live in a **separate repo** (the operator/agent
and the dashboard) — decide once the boundaries are clear.

- [x] **Coverage % in the exploration panel**: done — the JaCoCo coverage source (below, DD-012)
  supplies the covered/total denominator and the panel shows a real "% of code explored", extended
  to the fleet by DD-023.
- [x] **Coverage signal over HTTP** (DD-012): JaCoCo agent (tcpserver) in the app JVM;
  client `JacocoCoverageProvider` dumps + analyzes against the app classes; `CoverageDriver`
  polls it into the panel. Verified: real coverage % of JPetStore (`org.mybatis.jpetstore.*`)
  in the live panel. `runHttpDriveCoverage` task + `docker-compose.coverage.yml`.
- [x] **Coverage-*guided*** exploration (`CoverageGuidedRun`, task `runCoverageGuided`): mutates
  HTTP inputs (route + params grammar), samples coverage after each request, and keeps the inputs
  that reach new code. Verified against the kind JPetStore pod: coverage climbed 4.4% -> 8.6%
  (281 -> 549 edges) vs the flat round-robin driver, then plateaued.
  - [x] The plateau was NOT "GET-only reach" as first assumed — it was a hardcoded route list
    reaching 7 of JPetStore's 21 handlers (DD-016). Fixed by making the surface data:
    seed corpus `examples/corpus/jpetstore/` (all 21 handlers) -> **17.3%**, then a request
    grammar `examples/grammar/jpetstore.grammar` (`-Dclosurejvm.grammar=`) that also supplies the
    parameter value space with fuzzing generators (`<int> <string> <long> <empty>`) -> **17.7%**,
    and distinct crash sites found in the app's own code went 4 -> 6 (DD-017).
  - [x] **Input viewer**: dashboard cluster rows expand to show the concrete inputs behind a
    finding (read from the saved `.bin`), selectable for copy-paste replay.
  - [x] **Rich input viewer**: cluster rows expand to show, per sample, the input (copyable, with
    a "curl" button), the exception + message, the invariant detail, the stack trace, and the full
    raw record on demand. Dashboard UI moved to `resources/dashboard.html` (it outgrew being a
    Java string literal).
  - [x] **Corpus/structure split + sessions (DD-018)**: corpus supplies VALUES
    (`@values/itemId.txt`), grammar supplies STRUCTURE (`~EST-[0-9]{1,4}`); driver maintains a
    JSESSIONID and alternates authenticated/anonymous session epochs. Coverage 17.7% → **22.1%**;
    verified with a control (listOrders: 200 with session, 500 without). Crash sites 6 → 7.
  - [x] **Multi-step sequences (DD-020)**: `@sequence` blocks in the grammar; steps run in order on
    one session and placeholders bind ONCE per execution so a transaction is coherent. Coverage
    22.1% → **23.1%**.
  - [x] **Run config in the dashboard (DD-020)**: driver pushes its parameters + grammar source
    once per run; shown per campaign (credential-looking values redacted).
  - [x] **Cross-target clustering (DD-020)**: `/api/clusters` merges the same defect found on
    several targets into one row with campaign attribution.
  - [x] Fixed: the invariants card read 0 while findings were full of heapDelta — app-reported and
    harness-measured invariants are now counted and labelled separately.

> Open exploration threads (POST/form-body support) are in the **Backlog** (Exploration & coverage).

### Multi-instance targets (one driver, N replicas behind a Service) — DD-020 known limits
- [x] **Coverage merge across replicas.** JaCoCo's tcpserver connection lands on one pod while
  requests load-balance across all of them, so coverage reflects ~1/N of what ran. Poll every
  replica and merge `ExecutionDataStore`s. *(DD-023: `-Dclosurejvm.coverage.jacoco` takes a
  comma-separated endpoint list; a headless Service name expands to all pod IPs via
  `getAllByName`; every responder OR-merges into one store for true union coverage; the panel
  shows `[N/M pods]` when a replica is missing.)*

> Remaining multi-instance limits (session affinity for sequences; per-instance finding attribution)
> are in the **Backlog** (Exploration & coverage).

### Dashboard as a control plane (needs a decision before building)
Currently the dashboard is strictly read-only: drivers push, it displays (DD-013). Making it
*launch runs* or *reject corpus entries* reverses that and is a real architectural fork, not just
a feature — it would need a control channel back to drivers, and a dashboard that can start
processes is effectively remote code execution on whatever it runs on. **Update (2026-07-20):** the
"launch runs" half is being taken up by the **operator-orchestrated test** direction — the operator
(not the read-only dashboard) owns scheduling. See the Backlog's *Operator orchestration* group.

> Control-plane threads (launch/stop campaigns → now operator orchestration; dashboard-local
> dismiss/mute of a cluster; optional in-process JQF coverage %) are in the **Backlog**.

- [x] **Kubernetes deploy**: `kind` demo environment (`deploy/k8s/`): JPetStore as a pod with
  valve + JaCoCo + ClosureJVM agent baked into a self-contained image, ClusterIP Service,
  one-command `up.sh`. Verified in-cluster: valve invariant headers, 96 server-side invariant
  finds, and live coverage % (281/6368 edges) all working against the pod. Demo `docs/demo-k8s.svg`.
- [ ] **Auto-injection operator** — design **approved** and merged
  ([`docs/OPERATOR-DESIGN.md`](OPERATOR-DESIGN.md), PR #6). An **explicit patch controller**: a
  namespaced `ClosureJVMTarget` CR that instruments only the Deployments you name via an
  initContainer + shared volume, revertible by deleting the CR — deliberately *not* a mutating
  admission webhook, for a bounded, auditable trust boundary. Built in **Go / kubebuilder** as its
  own `operator/` module (the control plane is runtime-agnostic; keeps the door open to non-JVM
  runtime profiles). Becomes **DD-024** when P2 lands the actual injection. Phased delivery, each its
  own PR:
  - [x] **P1 — scaffold + CRD + no-op controller.** `kubebuilder init` under `operator/` (Go module,
    controller-runtime 0.17), `ClosureJVMTarget` CRD (group `closurejvm.dev/v1alpha1`, spec/status
    per the design, `includes` required-when-coverage-enabled), a reconciler that only *observes* the
    named Deployment and writes `status` (never patches a workload; `instrumentedReplicas` always 0),
    and **namespaced** RBAC — one `ClusterRole` bound via `RoleBinding` (no `ClusterRoleBinding`,
    no webhook), manager cache scoped to `WATCH_NAMESPACE` and refuses to run cluster-wide. Zero
    mutation risk. Renders clean (`kubectl kustomize`), `go build`/`go vet` green.
  - [x] **P2 — injection + revert (DD-024).** Deployment patch (initContainer + `emptyDir` + appended
    `jvmOptsVar` + coverage port), spec-hash idempotency, finalizer-based exact revert, Deployment
    mapping-watch (no owner refs — they'd GC the Deployment). envtest verifies patch shape / append /
    idempotency / exact revert / Injected phase (7/7). *Remaining:* e2e against a real pod needs the
    `closurejvm/agents` image (Backlog); valve mounting deferred (needs Tomcat `context.xml`).
  - [x] **P3 — coverage Service + status.** Operator creates a headless Service (`clusterIP: None`)
    selecting the target's pods on the coverage port, owner-ref'd (GC'd on delete), toggled by
    `spec.coverageService`; publishes `status.coverageEndpoint` (`<svc>.<ns>.svc.cluster.local:6300`)
    for the DD-023 union-coverage flag. `core/services` RBAC added. envtest (create/endpoint/toggle-off)
    + in-cluster e2e (headless, has-endpoint, endpoint-published) all green.
  - [x] **P4 — docs + demo.** USAGE "Kubernetes: instrument any app with the operator" section
    (install → apply a `ClosureJVMTarget` → read `status.coverageEndpoint` → revert), ARCHITECTURE
    operator/control-plane section, and the `deploy/k8s` README points at the operator as preferred
    (baked image kept as the no-install demo). **The operator injection track P1–P4 is complete; only
    P5 (`ClosureJVMCampaign` orchestration, DD-025) remains.**
- [x] **Web dashboard, decoupled (DD-013)**: `DashboardServer` is a standalone aggregator process
  (own port, no driving logic) that many drivers push to via `DashboardClient`
  (`-Dclosurejvm.dashboard.push=host:port`), keyed by campaign id (defaults to `HOSTNAME` — a pod's
  name in k8s). Fleet view (campaign cards, alive/stale) + drill-down into one campaign's metric
  cards, coverage bar, and findings table (route/detail/classification/time). Verified live: two
  independent processes, dashboard showed the driver's real numbers via push. Task: `runDashboard`.
  This IS the aggregation point the auto-injection operator (below) would point every pod at.
  - [x] **Noise reduction / clustering (DD-014)**: findings are grouped at read time by
    fingerprint (classification + invariant kind + route shape, or crash + exception class) with
    count / distinct-routes / magnitude-range / last-seen. Nothing is dropped at save time — the
    corpus stays whole for exploration. Verified against 937 real findings: 937 → 19 clusters.
  - [x] **Optional Claude-API analysis (DD-015)**: `POST /api/analyze/{campaign}` +
    "Analyze with Claude" button; prompts from the *clustered* summary, key server-side only
    (`ANTHROPIC_API_KEY`), explicit-click only, never on the auto-refresh. Strictly advisory —
    it explains clusters, it never decides what counts as a finding.
    - [x] `verifyClaude` smoke check merged (PR #5) — audited the request/parse against the live API
      shape. One real `[claude-check] OK` run against a key is the last step (**Backlog**, Dashboard).

> Remaining dashboard threads (persistence/TTL; full crash/sampled-stack drill-down; the live-key
> Claude run) are in the **Backlog** (Dashboard).

---

## Backlog — future work (bubbled forward; nothing here is lost)

Open threads consolidated out of the completed milestones above, plus components surfaced while
building the operator. Grouped by theme, not ordered by priority. The active, sequenced work is the
operator **P1–P4** checklist (in the v0.10 operator entry) and the **Post-v1.0** section below.

### Signal & triage quality
- [ ] Structured invariant summary line (key=val pairs) in output *(v0.2)*
- [ ] Reset smoke test: induce a failure, trigger a classloader reset, run another clean iteration *(v0.2)*
- [ ] Unify JQF coverage-interesting inputs (`-Djqf.ei.DIRECTORY`) with the harness triage format *(v0.3)*
- [ ] Triage handoff payload carries the IterationContext snapshot (DD-006) — the bounded handoff **queue itself is already built** (`runner/util/TriageSink.java`: `ArrayBlockingQueue` + daemon consumer, `-Dclosurejvm.triage.queueCapacity`, synchronous fallback, shutdown flush; wired into FuzzIO/GenericRunner/CorpusRunner/CoverageGuidedRun/JQFIterationHarness). Result fields are exposed on IterationContext; wiring the context *snapshot* into the enqueued payload is the remaining step *(v0.6)*
- [ ] IterationFilter/valve move to explicit `begin()/end(ctx)` — cleanup only; still serialized (Option A's lock stays), and the ThreadLocal wrapper already makes them per-thread correct *(v0.6)*
- [ ] Triage bundles: input + route + classification + stack/thread-dump + metrics in one artifact *(v0.4 P3)*
- [ ] Pool/queue sampling (servlet thread pool size, executor queues) + preset invariants *(v0.4 P3)*
- [ ] Minimization flow documented for Tomcat/HTTP inputs *(v0.4 P3)*
- [ ] Pull the server-side stack snippet into Docker/valve fuzz triage metadata *(v0.4 P3)*

### Exploration & coverage
- [ ] POST support with form bodies — several JPetStore handlers are POST-only in real usage *(v0.10)*
- [ ] Session affinity for multi-pod sequences (`sessionAffinity: ClientIP` or per-pod addressing) — a round-robin Service breaks `@sequence` transactions *(v0.10, DD-020 limit)*
- [ ] Per-instance finding attribution — valve stamps `HOSTNAME` into a response header the driver already parses, so "one sick pod" ≠ "systemic" *(v0.10, DD-020 limit)*
- [ ] Optional in-process JaCoCo coverage % for the local JQF targets (no HTTP round-trip) *(v0.10)*

### Load / soak mode — replay the interesting corpus under load *(post-v0.11 idea, user 2026-07-20)*
**Managing the fuzz-vs-load split (planned decomposition, user 2026-07-20):** design-note first, then two PRs along the producer/consumer seam.
- [ ] **PR 0 — design note (DD-0xx).** Decide the crux: is load a **`mode: explore|load` field on `ClosureJVMCampaign`** (reuses the target-gating + driver-Job + dashboard + status machinery; one CRD, two objectives — *recommended first cut*, fuzz and load differ in goal, not infrastructure) OR a **separate `ClosureJVMLoadTest` CRD** (cleaner status separation, but duplicates the reconciler)? Also fix the corpus **artifact format** and the load **metrics** shape. Do this first — design-first caught real bugs on DD-025.
- [ ] **PR 1 — persist the corpus (producer).** A fuzz run already accumulates the inputs that reached new coverage in-memory; write them out at end-of-run (a corpus ConfigMap/PVC the campaign emits), closing the "corpus vanishes on teardown" gap. Independently useful (reproducibility, the dashboard **corpus view** above) — ship it on its own.
- [ ] **PR 2 — load/soak mode (consumer).** Consumes a saved corpus and replays it at high concurrency/throughput for a duration — reusing the HTTP driver + invariant oracles (latency budget, heap-delta, thread/leak) but tuned for soak, not discovery. **Fuzz to discover the interesting states, then hammer those states under load** and watch the same invariants hold. Depends on PR 1's artifact format + PR 0's mode/CRD decision.
- [ ] Report load-mode-specific stats (throughput/RPS, latency percentiles under sustained load, heap/thread drift over the soak) distinct from exploration stats (coverage %, corpus growth).

### Dashboard
- [ ] Dismiss/mute a cluster (dashboard-local hide; no control channel, safe to build now — distinct from removing it from a driver's corpus, which conflicts with DD-006/DD-014 "never drop a finding") *(v0.10)*
- [ ] Persistence / eviction beyond in-memory (TTL or small store) for real deployments *(v0.10)*
- [ ] Full crash / sampled-stack drill-down in the findings view (currently a text excerpt) *(v0.10)*
- [ ] Exercise the Claude analysis path against a live key — the `verifyClaude` smoke check is merged (PR #5); one real `[claude-check] OK` run closes it *(v0.10, DD-015)*
- [ ] Periodic machine-readable status line (JSON) on stdout for external tooling (distinct from the dashboard push) *(v0.7)*
- [ ] **Corpus view in the dashboard** — surface the accumulated coverage-guided corpus (the interesting inputs that reached new coverage), not just status/coverage/findings. The driver already holds the corpus in-memory; push a sample (or all) to the dashboard and render a browsable/searchable list. Pairs with the load/soak-mode idea (persist + replay the interesting corpus) above. *(user 2026-07-20)*

### Operator orchestration — P5 (confirmed 2026-07-20: two CRDs, after P2–P4)
The operator owns the whole test, not just injection. Two-CRD shape confirmed; built as **P5** after
the P1–P4 injection work (a campaign needs a working instrumented target). Design in full first
(likely DD-025). See §10 of [`docs/OPERATOR-DESIGN.md`](OPERATOR-DESIGN.md).
- [ ] **`ClosureJVMCampaign` (test) CRD** — a second CRD that fires off a complete test run (DD-025, [`docs/CAMPAIGN-DESIGN.md`](CAMPAIGN-DESIGN.md)). Phased P5a–P5d.
  - [x] **P5a — runner flags** (`-Dclosurejvm.run.duration`, `-Dclosurejvm.summary.out`) — merged (#18).
  - [x] **P5a — CRD + driver-Job reconciler** — gate on Injected target, launch driver Job (coverage-classes initContainer from the target's image, coverage endpoint, summary via terminationMessage), phase machine + TargetGone. envtest 22/22.
  - [x] **P5a — runner image + in-cluster campaign e2e** — built `closurejvm/runner` (`runnerJar` task + `deploy/runner-image/`), extended `deploy/e2e/e2e.sh` to apply a `ClosureJVMCampaign` and assert a **non-zero coverage %** end to end.
  - [ ] Campaign driver-Job **spec-hash idempotency** — a spec edit mid-`Running` is currently a silent no-op (Job is create-if-missing); hash the spec so an edit deletes+recreates the Job (a new run), per DD-025 §7c. *(surfaced in the #19 review)*
  - [ ] **Driver coverage-classes: handle war-only target images** — the driver initContainer copies `WEB-INF/classes` **out of the target image** (§7b), but a war-packaged app ships `ROOT.war` with the exploded dir only existing at runtime, so the copy finds nothing and coverage is 0. The e2e works around this by exploding the war into `ROOT/` at image-build time. Operator options: extract classes from the war/lib jars, or copy from the *running* target pod instead of the image. As part of this, make the empty-classes case **fail loud** (initContainer errors, or a distinct campaign status condition) instead of silently reporting `coveragePct=0`, which is indistinguishable from a genuinely low run. *(surfaced by the campaign e2e + #20 review)*
  - [x] **P5b — per-campaign dashboard** — `closurejvm/dashboard` image + operator brings up a per-campaign dashboard Deployment+Service (owner-ref'd/GC'd), sets `status.dashboardURL`, and wires the driver's `-Dclosurejvm.dashboard.push/.id` at it (or an `externalPush`, or nothing when disabled). envtest 25/25 + in-cluster e2e.
  - [ ] **Per-campaign dashboard auth for multi-tenant** — the P5b dashboard is ClusterIP-only with no token wired, and `guarded()` only checks a static CSRF header, not access control. Any pod with network reach to the Service can read another campaign's findings or POST spoofed status. Acceptable for a single-tenant first cut (not internet-facing); before a shared/multi-tenant cluster, wire the already-supported `-Dclosurejvm.dashboard.token` from a per-campaign Secret (dashboard env + driver push header) and/or add a NetworkPolicy. *(surfaced in the #21 review)*
  - [ ] **Wire `corpusConfigMap` → the driver (seed values)** — the `corpusConfigMap` spec field is declared but **inert**: `buildDriverJob` never mounts it and never sets `-Dclosurejvm.corpusDir`, so campaigns run with grammar *structure* only — the grammar's `@../corpus/...` value-file refs resolve to a non-existent dir (`readValues` warns, returns empty) and each `@file` alternative degrades to `~pattern`/`<string>` generators. Only structural values reach the app; real seed values never do (this is why the e2e sits ~22%). Fixing it means projecting the corpus *tree* from a ConfigMap at a path consistent with the grammar's relative refs (or rewriting the refs / setting `corpusDir`) — fiddlier than the single grammar file because corpus is a directory of many `@file`s (DD-018). *(surfaced answering "how does grammar/corpus propagate")*
- [ ] Operator launches the **driver** as a Job (the coverage-guided runner) pointed at the target Service + JaCoCo endpoints + dashboard push
- [ ] Operator launches/ensures the **dashboard** (aggregator Deployment + Service) and wires the driver's push at it
- [ ] Campaign lifecycle: owner refs + finalizer tear the driver/dashboard down on delete; status aggregates run state, finds, and coverage onto the Campaign
- [ ] Package the **runner** and **dashboard** images the operator launches (alongside the `closurejvm/agents` image below)

### Operator (post-P1 platform work — beyond the P2–P4 checklist)
- [x] Build the versioned `closurejvm/agents:<tag>` image the operator's initContainer copies from — `deploy/agents-image/` (Dockerfile + `build.sh` staging the agent/valve/jacoco jars + native `.so` onto busybox). Built `closurejvm/agents:0.2.0` + `:latest`, loaded into the `closurejvm` kind cluster, verified contents + the initContainer `cp` + ELF arch.
  - [x] **e2e-instrument the stock JPetStore image** — `deploy/e2e/e2e.sh` builds+loads all images, deploys the operator in-cluster with its namespaced RBAC, deploys a RAW JPetStore, applies a `ClosureJVMTarget`, and asserts injection (initContainer added, `CATALINA_OPTS` appended, agents on the live JVM, app serves 200, **no RBAC forbidden errors**). Passing against the `closurejvm` kind cluster. **This in-cluster test caught two real bugs the local/envtest runs masked:** (a) the operator Role was missing `update;patch` on `closurejvmtargets`, so the finalizer couldn't be added — the operator literally could not run in-cluster; (b) the injected initContainer needed `imagePullPolicy: IfNotPresent` or a `:latest` agents image `ImagePullBackOff`s. Both fixed.
  - [ ] Multi-arch agents image (the native `.so` is linux/amd64-only; arm64 clusters need an arm64 build) — *(surfaced building the image)*
  - [ ] `docker push` / registry publish flow for the agents image (build.sh loads into kind only) — *(surfaced building the image)*
- [ ] **Helm chart to deploy the operator** — package the CRDs + namespaced RBAC + controller Deployment (currently `kustomize config/default` + manual image/flag patching, as `deploy/e2e/e2e.sh` does) into an installable chart parameterized by image tags and the `--agents-image`/`--runner-image`/`--dashboard-image`/`WATCH_NAMESPACE` values. *(user 2026-07-20)*
- [ ] **CLI to launch tests** — a thin `closurejvm` CLI that scaffolds/applies `ClosureJVMTarget` + `ClosureJVMCampaign` (and the grammar/corpus ConfigMaps) and tails status/dashboardURL, so launching a run isn't hand-written YAML + `kubectl`. Pairs with the Helm chart. *(user 2026-07-20)*
- [ ] **Keep the operator usage docs current** — refresh `docs/` (CRD reference: target + campaign fields, dashboard, grammar/corpus ConfigMaps, spec-edit rerun) as the operator surface grows; a recurring pass, not one-and-done. *(user 2026-07-20)*
- [ ] Operator CI: `go build` / `go vet` / `gofmt` (and envtest when assets are cached) in the pipeline — today only the Java build is in CI
- [ ] Field indexer on `spec.deploymentRef.name` so the Deployment mapping-watch does a targeted lookup instead of a namespace-wide list+filter (PR #11 review; fine at current scale, optimization for high target-count/churn namespaces)
- [x] **Deeper content-drift detection** — `injectionApplied` now verifies the agent flags in the target container's `jvmOptsVar` AND the shared volume mount, not just the annotation + initContainer, so a stripped/edited env (agents silently stop loading) is re-healed rather than read as steady-state Injected. envtest covers the env-strip drift case. *(surfaced by the in-cluster e2e)*
- [ ] Operator e2e in CI (`deploy/e2e/e2e.sh` against an ephemeral kind cluster) so the in-cluster path (RBAC, real images, agent loading) is guarded, not just envtest *(surfaced building the e2e)*
- [ ] Validating enforcement for `container` required-when-ambiguous (a CRD schema can't express "required only when the pod has >1 container") — pairs with P2
- [ ] Namespaced metrics security: re-enable a metrics-protection approach that needs no cluster-scoped binding (the kube-rbac-proxy sidecar was dropped in P1 because it requires a `system:auth-delegator` ClusterRoleBinding)
- [ ] **Multi-runtime profiles** (`runtimeProfile: jvm | node | …`) — the forward reason for Go: the CR/reconcile/inject/revert control plane is runtime-agnostic; a new profile supplies different agents/flags without touching the machinery

### Calibration & real-app targets
- [ ] WebGoat / OWASP Benchmark for guaranteed-findings calibration of triage output *(v0.5)*
- [ ] JSPWiki as the "real Apache project" target (markup parsing = latency-pathology hunting ground) *(v0.5)*
- [ ] Stretch: XWiki or OpenMRS once pool/queue sampling lands *(v0.5)*

### Docs & DX
- [ ] Compose v1-vs-v2 troubleshooting in the README (install + `COMPOSE_API_VERSION` fallback) *(v0.4)*
- [ ] Smoke test for the Docker path (blip a latency invariant; assert headers + status JSON fields) *(v0.4)*

---

## Post-v1.0: simplify the Kubernetes deploy

Explicitly deferred past v1.0 (2026-07-20) — the current `deploy/k8s/` path works and is verified,
but it's more manual than it should be for a real user. Pain points noticed while building it:

- `up.sh` hand-bakes a per-app Dockerfile (`Dockerfile.jpetstore`) with the WAR + all three agent
  jars `COPY`'d in — every new target app means a new bespoke image build, not a reusable pattern.
- Coverage requires manually extracting `WEB-INF/classes` from the WAR on the host and pointing
  `-Dclosurejvm.coverage.classes` at it by hand.
- Reaching the pod means a `kubectl port-forward` the user has to manage themselves. (The Service
  is deliberately ClusterIP: JaCoCo's remote-control port is unauthenticated and must not be
  published — see DD-022.)
- No Helm chart / single manifest set; `jpetstore.yaml` is demo-specific, not a template for "any
  WAR."

Ideas for later (not decided; revisit once the auto-injection operator direction is clearer, since
that will reshape a lot of this anyway):
- A sidecar/init-container pattern instead of baking a custom image per app, so any existing app
  image can be instrumented by adding a container/volume, not rebuilding its Dockerfile.
- A Helm chart (or `kustomize` base) parameterized by image + WAR path instead of a hand-written
  Dockerfile per target.
- Have the coverage class extraction happen automatically (from the mounted WAR/image at pod
  start) instead of a manual host-side unzip step.
- A single CLI entry point that replaces the current mix of `up.sh` + manual `kubectl`/`docker`
  commands from the README/deploy docs.
