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
   - [ ] Optionally include invariant summary line in output with structured key=val pairs
   - [x] Add heap/thread invariant tests similar to latency test

2) Reset strategy: Hard reset fallback
   - [x] Implement child-first ClassLoader for target package; keep Agent/Runner in parent
   - [x] Load `runner.api.IterationTarget` via child loader; re-instantiate on reset
   - [x] Flags:
       - `-Dclosurejvm.reset=classloader` (enable)
       - `-Dclosurejvm.reset.onFailure=true` (reset after hard invariant/leak)
       - `-Dclosurejvm.reset.maxResets=3` (cap)
   - [ ] Minimal smoke test that induces a failure, triggers reset, and runs another iteration

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
   - [ ] Optional: unify coverage-interesting inputs saved by JQF guidance with harness triage format (currently saved in `-Djqf.ei.DIRECTORY`)

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

Open items for Phase 2 hardening
- [ ] Add Compose troubleshooting (v1 vs v2) to README (install and `COMPOSE_API_VERSION` fallback).
- [ ] Smoke test for Docker path (blip a latency invariant; assert headers and status JSON fields).

### Phase 3 — Enrichment
- [ ] Pool/queue sampling (servlet thread pool size, executor queues) and preset invariants.
- [ ] Triage bundles: input + route + classification + stack/thread dump + metrics.
- [ ] Minimization flow documented for Tomcat inputs.
- [ ] Optionally pull server-side stack snippet into Docker fuzz triage metadata.

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
- [ ] Option A: serialize iterations in the WAR IterationFilter (global lock) + doc note that the harness measures iteration cleanliness, not throughput under load
- [ ] Option C (later): explicit IterationContext API (`ctx = Agent.begin(); Agent.end(ctx)`) as the v0.6 refactor
- Rejected: ThreadLocal-only contexts (per-request heap/thread deltas would silently lie under concurrency); external message bus for metrics (capture cost is in-process; bus is for a future multi-node fleet, not single-harness throughput)

### Triage decoupling
- [ ] In-process bounded handoff queue (ArrayBlockingQueue + one consumer thread) so triage I/O (bundles, stacks, saved inputs) never stalls the iteration loop; design the enqueue payload alongside Option C's context object

### Real-app targets (after the above)
- [x] Injection mechanism decided + built: Tomcat valve (DD-009), verified loading in real
      Tomcat 10.1; valve and in-WAR filter are mutually exclusive. Scaffolding:
      `tomcat-valve/`, `deploy/valve/context.xml`, `docker-compose.valve.yml`, docs/THIRD-PARTY-APPS.md
- [x] JPetStore (MyBatis) live run DONE (2026-07-19): javax.servlet, deployed on Tomcat 9 with
      the namespace-free valve; server-side latency (up to 531ms cold) + heap (up to 44MB/req)
      invariants captured in the unmodified app. See docs/THIRD-PARTY-APPS.md.
- [ ] HTTP driver target + seed corpus to explore JPetStore routes automatically (vs manual curls)
- [ ] WebGoat / OWASP Benchmark for guaranteed-findings calibration of triage output
- [ ] JSPWiki as the "real Apache project" target (markup parsing = latency-pathology hunting ground)
- [ ] Stretch: XWiki or OpenMRS once pool/queue sampling (Phase 3) lands

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
- [ ] Triage handoff payload (DD-006) carries the context snapshot (result fields now exposed
  on IterationContext; wiring the payload is the remaining step)
- [ ] Optional: IterationFilter/valve move to explicit begin()/end(ctx) (still serialized;
  the ThreadLocal wrapper already makes them per-thread correct, so this is cleanup only)

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
- [ ] Optional: periodic machine-readable status line (JSON) for tooling

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

- [ ] **Coverage % in the exploration panel**: the UI already has the row + `StatusReporter
  .recordCoverage(covered, total)` plumbing (v0.9); v0.10 supplies the source. A true "% of code
  explored" needs a covered/total denominator, which requires instrumenting the code under test —
  hence it belongs with the coverage agent below, not the client-only JQF path.
- [x] **Coverage signal over HTTP** (DD-012): JaCoCo agent (tcpserver) in the app JVM;
  client `JacocoCoverageProvider` dumps + analyzes against the app classes; `CoverageDriver`
  polls it into the panel. Verified: real coverage % of JPetStore (`org.mybatis.jpetstore.*`)
  in the live panel. `runHttpDriveCoverage` task + `docker-compose.coverage.yml`.
- [x] **Coverage-*guided*** exploration (`CoverageGuidedRun`, task `runCoverageGuided`): mutates
  HTTP inputs (route + params grammar), samples coverage after each request, and keeps the inputs
  that reach new code. Verified against the kind JPetStore pod: coverage climbed 4.4% -> 8.6%
  (281 -> 549 edges) vs the flat round-robin driver, then plateaued (GET-only reach).
  - [ ] Deeper reach: sessions/POST (cart checkout, account, order) to get past the GET plateau.
- [ ] Optional in-process coverage %: a JaCoCo provider for the local JQF targets, so the panel
  shows a real percentage without the server round-trip.
- [x] **Kubernetes deploy**: `kind` demo environment (`deploy/k8s/`): JPetStore as a pod with
  valve + JaCoCo + ClosureJVM agent baked into a self-contained image, NodePort Service,
  one-command `up.sh`. Verified in-cluster: valve invariant headers, 96 server-side invariant
  finds, and live coverage % (281/6368 edges) all working against the pod. Demo `docs/demo-k8s.svg`.
- [ ] **Auto-injection agent**: a mutating admission webhook (operator) that injects the
  `-javaagent` + valve into annotated pods automatically — likely its own repo.
- [x] **Web dashboard, decoupled (DD-013)**: `DashboardServer` is a standalone aggregator process
  (own port, no driving logic) that many drivers push to via `DashboardClient`
  (`-Dclosurejvm.dashboard.push=host:port`), keyed by campaign id (defaults to `HOSTNAME` — a pod's
  name in k8s). Fleet view (campaign cards, alive/stale) + drill-down into one campaign's metric
  cards, coverage bar, and findings table (route/detail/classification/time). Verified live: two
  independent processes, dashboard showed the driver's real numbers via push. Task: `runDashboard`.
  This IS the aggregation point the auto-injection operator (below) would point every pod at.
  - [ ] Persistence/eviction beyond in-memory (fine for the demo scale; a real deployment would
    want a TTL or a small store).
  - [ ] Full crash stack / sampled stack drill-down in the findings view (currently a text excerpt).
  - [x] **Noise reduction / clustering (DD-014)**: findings are grouped at read time by
    fingerprint (classification + invariant kind + route shape, or crash + exception class) with
    count / distinct-routes / magnitude-range / last-seen. Nothing is dropped at save time — the
    corpus stays whole for exploration. Verified against 937 real findings: 937 → 19 clusters.
  - [x] **Optional Claude-API analysis (DD-015)**: `POST /api/analyze/{campaign}` +
    "Analyze with Claude" button; prompts from the *clustered* summary, key server-side only
    (`ANTHROPIC_API_KEY`), explicit-click only, never on the auto-refresh. Strictly advisory —
    it explains clusters, it never decides what counts as a finding.
    - [ ] Not yet exercised against a live key (verified only that the request is well-formed and
      the API rejects a bad key cleanly). Needs one real end-to-end call to confirm.

---

## Post-v1.0: simplify the Kubernetes deploy

Explicitly deferred past v1.0 (2026-07-20) — the current `deploy/k8s/` path works and is verified,
but it's more manual than it should be for a real user. Pain points noticed while building it:

- `up.sh` hand-bakes a per-app Dockerfile (`Dockerfile.jpetstore`) with the WAR + all three agent
  jars `COPY`'d in — every new target app means a new bespoke image build, not a reusable pattern.
- Coverage requires manually extracting `WEB-INF/classes` from the WAR on the host and pointing
  `-Dclosurejvm.coverage.classes` at it by hand.
- Reaching the pod means either a NodePort + manual `docker inspect` for the node IP, or a
  `kubectl port-forward` the user has to manage themselves.
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
