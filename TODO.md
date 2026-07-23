# Basquin v0.1 Tasks

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
- Leak demo can hang the JVM due to non-daemon threads; use `-Dbasquin.forceExitOnLeak=true` in demos/CI to force fast termination on leak detection.

### Next Up (v0.1 hardening)
- [x] Add simple metrics snapshot (thread count, heap delta) at iteration boundaries (print-only)
- [x] Tighten executor/timer tracking (weak refs; ScheduledThreadPoolExecutor details)
- [x] Document quick flags in README (done) and add CI badge once wrapper lands

---

## Milestone: v0.2 — "Reset discipline"

Goal: Turn findings into enforceable guarantees.

### Deliverables
- [x] Configurable invariants (latency, heap delta, thread delta) via `-Dbasquin.invariant.*`
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
       - `-Dbasquin.reset=classloader` (enable)
       - `-Dbasquin.reset.onFailure=true` (reset after hard invariant/leak)
       - `-Dbasquin.reset.maxResets=3` (cap)

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
- [x] Opt-in `-Dbasquin.heap.gcBeforeMeasure` so heap delta measures retention, not allocation noise
- [x] Hot path: `ThreadMXBean` + stack-free enumeration instead of `getAllStackTraces()`; stacks captured lazily on violation only
- [x] Soak validated: 10k/10k clean (latency p50=1ms p99=2ms) — retires v0.1 Definition of Success

### Native agent (JVMTI)
- [x] Event-driven thread *counts* via ThreadStart/ThreadEnd (`native/basquinjvmti.c`), `ThreadMXBean` fallback when not loaded
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

- [x] Live in-place CLI status screen (`-Dbasquin.status`): elapsed, iterations, iters/sec,
  crashes, invariants by kind (latency/heap/thread), leaks, latency last/mean/max, heap delta,
  threads, resets. `StatusReporter` fed once per iteration from `Agent.end`.
- [x] TTY-aware: in-place box on a terminal, one-line summaries when piped/CI;
  `-Dbasquin.status.forceTty` to force the box; `renderFinal()` guarantees a final tally.
- [x] Suppresses per-iteration metrics spam when active.
- [x] HTTP driver target (`examples.targets.HttpRouteDriveTarget`, task `runHttpDrive`):
  client-side latency + 5xx-as-crash, harvests server-side `X-Basquin-Invariant-*` headers.
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
- [x] Forward `-Dbasquin.status*` through the JQF fuzz task so the panel shows during a run;
  StatusReporter render started in the JQF harness + a shutdown-hook final frame for all run types.
- [x] Live demo of a JQF campaign with the exploration panel; captured as `docs/demo-explore.svg`,
  embedded in the README Exploration section.
- [x] Honest scoping: in-process JQF coverage guidance stays local; the app-under-test coverage
  signal (over HTTP) is v0.10 (below).

## Milestone: v0.10.0 — "Deploy & distributed exploration"

Goal: Run Basquin against apps in a cluster, and make exploration coverage-guided by the
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
    grammar `examples/grammar/jpetstore.grammar` (`-Dbasquin.grammar=`) that also supplies the
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
  replica and merge `ExecutionDataStore`s. *(DD-023: `-Dbasquin.coverage.jacoco` takes a
  comma-separated endpoint list; a headless Service name expands to all pod IPs via
  `getAllByName`; every responder OR-merges into one store for true union coverage; the panel
  shows `[N/M pods]` when a replica is missing.)*

> Remaining multi-instance limits (session affinity for sequences; per-instance finding attribution)
> are in the **Backlog** (Exploration & coverage).

### Clustered runners (N drivers, one campaign) — distributed load + exploration *(roadmap, user 2026-07-21)*
The complementary distributed axis to "multi-instance **targets**" above: that scales the app under
test (one driver → N app pods); this scales the **driver/runner side** (one campaign → N coordinated
runner pods). Today the operator launches a **single** driver Job per campaign (`buildDriverJob`),
so a campaign's load and exploration are bounded by one pod. Clustered runners lift both ceilings.
Design-note first (likely its own DD); phased.

- [ ] **Distributed load generation (load mode).** `driver.replicas`/`parallelism` on `BasquinCampaign`
  → the operator runs N runner pods (Job `parallelism`, or a Deployment) each driving a share of the
  target concurrency, so aggregate RPS scales past one pod — the standard distributed-load-tool shape
  (Locust/k6 workers). Extends DD-026 (load mode). Aggregation is *mostly already there*: many drivers
  already push to one dashboard keyed by campaign id (DD-013) — the new work is summing throughput/
  latency-percentiles across runners honestly (percentiles don't average — merge histograms or t-digests).
- [ ] **Distributed exploration (explore mode) with corpus sync.** N runners explore different slices
  of the input space and **cross-pollinate**: an interesting input (new coverage, or high cost) found
  by one runner seeds the others — distributed fuzzing (AFL `-M`/`-S`, ClusterFuzz). Needs a shared
  corpus channel: a shared volume/ConfigMap, or the dashboard as the shared brain (it already receives
  every runner's finds). Open: dedup + coverage **union across runners** (DD-023 unions coverage across
  *target* replicas; this unions across *driver* replicas — different merge point), and finding
  attribution per runner.
- [ ] **Work partitioning.** How N runners divide the job without overlap or gaps: shard by route
  subset, seed subset, grammar `@sequence`, or session-epoch; vs. a shared work-queue (pull model).
  Session-affinity interplay with the DD-020 `@sequence` limit (a sharded runner owning whole sessions
  sidesteps the round-robin-Service breakage).
- [ ] **Ties into the pheromone idea** (Exploration & coverage backlog): a **shared cost-pheromone
  corpus** lets the whole cluster converge load onto the expensive states — distributed *cost-guided*
  load, the strongest version of "concentrate the fleet where it hurts."
- Open decisions for the note: coordination mechanism (leaderless + shared corpus store, or a
  coordinator); Job `parallelism` vs a Deployment of runners; how the dashboard distinguishes
  per-runner vs aggregate views; back-pressure so N runners don't overwhelm a small target unintentionally.

### Mode-aware dashboard (load campaigns are blank today) — ✅ delivered by DD-033 *(roadmap, user 2026-07-21)*
The per-campaign dashboard is hard-coded to **explore** metrics — it is not mode-aware. `LoadRun`
(load mode) never calls `StatusReporter`/`DashboardClient`, and `CoverageGuidedRun.main` starts the 2s
push loop *before* branching into the load path — so a load campaign's dashboard shows `iterations=0`,
`crashes=0`, "no coverage source", and empty findings (empty explore-shaped scaffolding), while the
actual load metrics (throughput req/s, p50/p90/p99 latency, heap/thread drift, 5xx count) reach only
the pod termination message and `status.load` (visible via `kubectl get basquincampaign`) — never the
dashboard UI. `docs/LOAD-MODE-DESIGN.md:127` documents load metrics as "pushed to the dashboard," but
that half was never implemented — design/implementation drift, not merely an oversight. This matters
more now: DD-026 load + DD-029 lock-free + DD-031 cost-ranked + DD-032 pheromone all make **load** the
mode where the interesting availability signals land, and it's the one the dashboard can't show.

**Done: DD-033** (`docs/DESIGN-DECISIONS.md`) — `StatusReporter`/`LoadRun` push mode-aware load
status through the existing one push path, the dashboard renders a load card, the campaign list
scrapes `mode`, and the CLI status table is mode-aware. e2e-asserted in-cluster
(`deploy/e2e/e2e.sh`).

- [x] **`LoadRun` pushes to the dashboard.** Start `DashboardClient` in the load path and push the load
  JSON (throughput/percentiles/drift/5xx) to `/ingest/status`. The server is schema-agnostic (opaque
  blob + a light scrape, DD-013), so this is a driver-side change — no server change.
- [x] **A load view in the UI.** `resources/dashboard.html` renders explore fields only (iterations,
  crashes, coverage%, corpus, invariant finds). Add a load card (throughput, p50/p90/p99, heap/thread
  drift, 5xx), selected off a `mode` field the driver includes in its status payload.
- [x] **`mode` in the data model.** Thread the campaign `mode` through the status payload so the
  dashboard — and the `/api/campaigns` list scrape (which today greps `iterations`/`crashes`/`pct`) —
  distinguishes explore vs load at a glance.
- Ties in: **clustered runners** (aggregating load across N runners — merge histograms/t-digests, don't
  average percentiles) and the **pheromone/cost** work (load is where cost-concentration shows up).

### Snap packaging for the `basquin` CLI *(roadmap, user 2026-07-21)*
The CLI is pure Go (`operator/cmd/basquin`, `CGO_ENABLED=0`) → the easiest case to snap: a static
binary, no runtime deps. The one real decision is **confinement**, driven by the CLI reading
`~/.kube/config` (`dashboard.go`/`kube.go`/`instrument.go`): strict confinement's `home` interface
excludes hidden dot-dirs, so a strict snap can't read kubeconfig. Options: (1) **classic** confinement
— what kubectl/helm/k9s do; needs a `store-requests` forum approval (routinely granted for kubectl-like
tools, ~days–weeks); (2) strict + a `personal-files` plug scoped to `$HOME/.kube` (privileged; also
needs approval + auto-connect); (3) strict `home`+`network` only — publishable immediately, but users
must pass `--kubeconfig` at a non-hidden path (poor UX). **Go with classic** (ecosystem convention).

- [ ] Register the name early (`snapcraft register basquin` — globally unique, first-come) + accounts.
- [ ] Write `snap/snapcraft.yaml`: base `core24`, `plugin: go`, `source: operator/`, `override-build`
  = `CGO_ENABLED=0 go build -o $CRAFT_PART_INSTALL/bin/basquin ./cmd/basquin`, `confinement: classic`,
  `adopt-info`/version.
- [ ] **File the classic-confinement `store-requests` forum post FIRST** — it's the long pole (~days–weeks).
- [ ] Build/test: `snapcraft` via LXD, or `snapcraft remote-build` (Launchpad, amd64+arm64 in one shot —
  likely needed under WSL2); `sudo snap install ./basquin_*.snap --dangerous --classic`; then
  `snapcraft upload --release=stable`.
- [ ] Automate in `release.yml`: `snapcraft export-login` → `SNAPCRAFT_STORE_CREDENTIALS` secret →
  `snapcore/action-build` + `action-publish` on each tag (edge/candidate from `main` for pre-release).
- Install UX: classic snaps install with `sudo snap install basquin --classic` (plain `snap install` is
  strict-only). Ties to the CLI-install-methods docs (`go install` + curl one-liner shipped in the README).

### Optional OTLP metrics export — off by default, alongside the dashboard *(roadmap, user 2026-07-21)*
DD-033 types every load/explore signal in OTel-native terms (histogram/counter/gauge, stable names,
units, attributes — `docs/DESIGN-DECISIONS.md`) but ships no exporter. The next step: an **optional**
OTLP metrics exporter, off by default, emitting exactly that set — `basquin.load.request.duration`
(histogram, unit `ms`, the 1ms × 30000-bucket layout as a hard contract), `basquin.load.requests` /
`.server_errors` (counters), `basquin.load.heap_drift` / `.thread_drift` (gauges),
`basquin.explore.iterations` / `.findings` / `.crashes` (counters), `basquin.explore.coverage`
(gauge) — `campaign.id` as a resource attribute, `mode` as a metric attribute — so adopters' existing
Prometheus/Grafana/Datadog stacks consume Basquin's numbers directly, with zero re-modelling (the
exporter is a thin adapter over the registry DD-033 already typed).

- [ ] **Alongside, never replacing, the bespoke dashboard.** Grafana can't express the fuzzing-domain
  UX (input viewer, finding clusters, triage) — the dashboard stays the primary surface; OTLP is an
  additional, opt-in output for teams that already run observability stacks.
- [ ] **Ties directly into clustered runners** (above): once N runners drive one campaign, summing
  throughput is trivial, but **percentiles don't average** — OTel histogram aggregation (explicit-
  bucket merge across runners) is what makes a cross-runner p99 honest instead of an
  average-of-averages lie.
- [ ] Its own DD before building (per DD-033's deferral): exporter config (endpoint, protocol), which
  attributes travel as OTel resource vs. metric attributes in practice, and whether it runs
  in-process or as a sidecar/agent.

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
  valve + JaCoCo + Basquin agent baked into a self-contained image, ClusterIP Service,
  one-command `up.sh`. Verified in-cluster: valve invariant headers, 96 server-side invariant
  finds, and live coverage % (281/6368 edges) all working against the pod. Demo `docs/demo-k8s.svg`.
- [ ] **Auto-injection operator** — design **approved** and merged
  ([`docs/OPERATOR-DESIGN.md`](docs/OPERATOR-DESIGN.md), PR #6). An **explicit patch controller**: a
  namespaced `BasquinTarget` CR that instruments only the Deployments you name via an
  initContainer + shared volume, revertible by deleting the CR — deliberately *not* a mutating
  admission webhook, for a bounded, auditable trust boundary. Built in **Go / kubebuilder** as its
  own `operator/` module (the control plane is runtime-agnostic; keeps the door open to non-JVM
  runtime profiles). Becomes **DD-024** when P2 lands the actual injection. Phased delivery, each its
  own PR:
  - [x] **P1 — scaffold + CRD + no-op controller.** `kubebuilder init` under `operator/` (Go module,
    controller-runtime 0.17), `BasquinTarget` CRD (group `basquin.dev/v1alpha1`, spec/status
    per the design, `includes` required-when-coverage-enabled), a reconciler that only *observes* the
    named Deployment and writes `status` (never patches a workload; `instrumentedReplicas` always 0),
    and **namespaced** RBAC — one `ClusterRole` bound via `RoleBinding` (no `ClusterRoleBinding`,
    no webhook), manager cache scoped to `WATCH_NAMESPACE` and refuses to run cluster-wide. Zero
    mutation risk. Renders clean (`kubectl kustomize`), `go build`/`go vet` green.
  - [x] **P2 — injection + revert (DD-024).** Deployment patch (initContainer + `emptyDir` + appended
    `jvmOptsVar` + coverage port), spec-hash idempotency, finalizer-based exact revert, Deployment
    mapping-watch (no owner refs — they'd GC the Deployment). envtest verifies patch shape / append /
    idempotency / exact revert / Injected phase (7/7). *Remaining:* e2e against a real pod needs the
    `basquin/agents` image (Backlog); valve mounting deferred (needs Tomcat `context.xml`).
  - [x] **P3 — coverage Service + status.** Operator creates a headless Service (`clusterIP: None`)
    selecting the target's pods on the coverage port, owner-ref'd (GC'd on delete), toggled by
    `spec.coverageService`; publishes `status.coverageEndpoint` (`<svc>.<ns>.svc.cluster.local:6300`)
    for the DD-023 union-coverage flag. `core/services` RBAC added. envtest (create/endpoint/toggle-off)
    + in-cluster e2e (headless, has-endpoint, endpoint-published) all green.
  - [x] **P4 — docs + demo.** USAGE "Kubernetes: instrument any app with the operator" section
    (install → apply a `BasquinTarget` → read `status.coverageEndpoint` → revert), ARCHITECTURE
    operator/control-plane section, and the `deploy/k8s` README points at the operator as preferred
    (baked image kept as the no-install demo). **The operator injection track P1–P4 is complete; only
    P5 (`BasquinCampaign` orchestration, DD-025) remains.**
- [x] **Web dashboard, decoupled (DD-013)**: `DashboardServer` is a standalone aggregator process
  (own port, no driving logic) that many drivers push to via `DashboardClient`
  (`-Dbasquin.dashboard.push=host:port`), keyed by campaign id (defaults to `HOSTNAME` — a pod's
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
- [x] Reset smoke test: induce a failure, trigger a classloader reset, run another clean iteration — done in `test/ResetClassLoaderTest.java` (subprocess fails iteration 1, resets, succeeds iteration 2; asserts "Performed classloader reset" + "Iteration 2"). *(v0.2; 2026-07-21 reconciliation — was a stale leftover)*
- [ ] Unify JQF coverage-interesting inputs (`-Djqf.ei.DIRECTORY`) with the harness triage format *(v0.3)*
- [ ] Triage handoff payload carries the IterationContext snapshot (DD-006) — the bounded handoff **queue itself is already built** (`runner/util/TriageSink.java`: `ArrayBlockingQueue` + daemon consumer, `-Dbasquin.triage.queueCapacity`, synchronous fallback, shutdown flush; wired into FuzzIO/GenericRunner/CorpusRunner/CoverageGuidedRun/JQFIterationHarness). Result fields are exposed on IterationContext; wiring the context *snapshot* into the enqueued payload is the remaining step *(v0.6)*
- [ ] IterationFilter/valve move to explicit `begin()/end(ctx)` — cleanup only; still serialized (Option A's lock stays), and the ThreadLocal wrapper already makes them per-thread correct *(v0.6)*
- [ ] Triage bundles: input + route + classification + stack/thread-dump + metrics in one artifact *(v0.4 P3)*
- [ ] Pool/queue sampling (servlet thread pool size, executor queues) + preset invariants *(v0.4 P3)*
- [ ] Minimization flow documented for Tomcat/HTTP inputs *(v0.4 P3)*
- [ ] Pull the server-side stack snippet into Docker/valve fuzz triage metadata *(v0.4 P3)*

### Exploration & coverage
- [ ] **Cost-guided ("pheromone") exploration — a second objective alongside coverage** *(user idea, 2026-07-21; design-note first)*.
  Today the coverage-guided loop keeps an input iff it reached **new code**. Add a parallel signal that keeps/reinforces
  inputs by the **invariant cost they induced** — latency ms, heap-delta KB, thread/executor growth — so the search
  climbs a *pathology* gradient, not just a coverage one. Framed as **ant pheromones**: each input carries a cost score;
  expensive inputs get reinforced (higher selection/mutation weight), cheap ones evaporate over time (decay so the search
  doesn't ossify on one hot spot). Design questions to settle in the note: (a) cost function + normalization across
  latency/heap/threads (per-invariant weights, or Pareto-keep on any axis); (b) how it composes with coverage — union
  ("interesting = new code OR new-high cost"), weighted blend, or a switchable objective; (c) evaporation/decay schedule
  to keep exploring; (d) not just rediscovering the globally-most-expensive route — reward *marginal* cost increase like
  coverage rewards *marginal* edges. **Why it matters most for load mode:** a dynamic load test wants to concentrate load
  where it hurts — replay weighted by cost-pheromone so the soak hammers the states that grow heap / stall latency /
  leak, surfacing degradation faster than uniform replay. Directly extends DD-026 (load) and the coverage-guided
  `CoverageGuidedRun`; the harness already measures every input's latency/heap/thread cost (the invariant layer), so the
  signal is *already computed* — this is about feeding it back into selection. Likely its own DD.
- [ ] POST support with form bodies — several JPetStore handlers are POST-only in real usage *(v0.10)*
- [ ] Session affinity for multi-pod sequences (`sessionAffinity: ClientIP` or per-pod addressing) — a round-robin Service breaks `@sequence` transactions *(v0.10, DD-020 limit)*
- [ ] Per-instance finding attribution — valve stamps `HOSTNAME` into a response header the driver already parses, so "one sick pod" ≠ "systemic" *(v0.10, DD-020 limit)*
- [ ] Optional in-process JaCoCo coverage % for the local JQF targets (no HTTP round-trip) *(v0.10)*

### Load / soak mode — replay the interesting corpus under load *(post-v0.11 idea, user 2026-07-20)*
**Managing the fuzz-vs-load split (planned decomposition, user 2026-07-20):** design-note first, then two PRs along the producer/consumer seam.
- [x] **PR 0 — design note ([DD-026](docs/LOAD-MODE-DESIGN.md)).** Decided (user 2026-07-20): **`mode: explore|load` on `BasquinCampaign`** (not a separate CRD — fuzz/load differ in objective, not infra) + **ConfigMap-emitted corpus** (`status.corpusConfigMap`, consumed via the existing corpus path). Load metrics = throughput/latency-percentiles/heap-thread-drift. Producer→consumer PR split.
- [x] **PR 1 — persist the corpus (producer).** Merged (#33). The driver splices a byte-capped `replayCorpus` into the summary it already writes to the termination message (no sidecar / driver credentials); the operator materializes a campaign-owned `<campaign>-corpus-out` ConfigMap + `status.corpusConfigMap`. Validated in-cluster (24-route corpus emitted). Closes "corpus vanishes on teardown"; unblocks the dashboard **corpus view** + load replay.
- [x] **PR 2 — load/soak mode (consumer).** `spec.mode: load` + `driver.concurrency`/`warmup`; `runner.coverage.LoadRun` replays the corpus at a fixed concurrency for a duration (keep-alive, warmup-excluded), reporting throughput + latency percentiles + heap/thread drift → `status.load`. Coverage-free driver Job in load mode; CLI `--mode load`. Validated envtest + in-cluster (replayed the emitted corpus → Completed with load metrics). Follow-ups: periodic drift sampling; target-side heap/thread; concurrency ramp.
- [ ] Surface `status.load` in the CLI `status` / `run --watch` output — the backend is DONE (`LoadRun` computes throughput/RPS, p50/p90/p99/max latency, heap+thread drift → `status.load` via the reconciler, and the `Ready` condition message carries a human summary); the only gap is `operator/cmd/basquin/status.go`'s table showing just COVERAGE/FINDINGS, so a load-mode row is blank in those columns. *(narrowed 2026-07-21 — was "report load stats", mostly built)*
- [ ] **Lock-free load-mode instrumentation profile (keep instrumentation in play, scale throughput)** *(user idea, 2026-07-21; design-note first, likely its own DD).* **The problem, proven empirically by the 2026-07-21 benchmark:** load mode drives an *Injected* target whose valve **serializes** requests (`ITERATION_LOCK`, DD-005/DD-010) so per-request heap/thread deltas are attributable — but that caps concurrency at 1, so a "load" test can't actually load the app. (Benchmark: k6 at 10 VUs showed ~256ms = 10×26ms *queueing behind the lock*, not app throughput.) Naively uninstrumenting to go fast throws away the whole availability oracle → load mode degrades to plain k6. **The fix keeps closure's thesis — measurement boundary stays in play while throughput scales — by splitting instrumentation by what concurrency allows:**
  - **Drop the serialization lock in load mode.** Real concurrency, real aggregate RPS.
  - **Keep the lock-free per-request latency** (each request times itself — context-scoped, no lock, safe under concurrency per DD-010) **and ADD 5xx/crash detection** — new work, not a "keep": `LoadRun.fire()` currently swallows exceptions and never checks the HTTP status (`runner/coverage/LoadRun.java`, "a load run measures behavior, not crashes"). *(#63 review)*
  - **Replace per-request heap/thread *deltas* (which need the lock) with periodic process-global *absolute* sampling for DRIFT over the soak** — what load mode cares about (does heap climb / do threads leak over minutes). ⚠️ *Not* the #59 CSV sampler's heap columns: those are fed from the per-iteration `ctx.heapDeltaBytes` begin/end delta — the exact lock-dependent value, just resampled coarsely, so still racy under concurrency (#63 review). The lock-free primitive that already exists is `LoadRun`'s own direct `Runtime.totalMemory()-freeMemory()` read (process-global, Agent-independent); the greenfield work is making it **periodic + target-side** (the "periodic drift sampling / target-side heap/thread" follow-ups already listed under PR 2 above). The #59 sampler contributes its *timer/CSV cadence*, not its heap value. Per-request heap *attribution* is given up — but that's explore mode's job, not load mode's.
  - **Net:** load mode gets realistic concurrent throughput + latency/5xx findings + heap/thread drift, losing only the one signal it didn't need. A `-Dbasquin.mode=load` / valve profile flag selects lock-free behavior; explore mode keeps the serialized per-request attribution.
  - **Composes with** clustered runners (lock-free concurrent runners) and the cost-pheromone corpus (the fleet concentrates *concurrent* load onto expensive states) — the three together are a real distributed cost-guided load engine. Cross-ref DD-005/DD-010 (why the lock exists), DD-026 (load mode), the sampler (#59).

### Dashboard
- [ ] Dismiss/mute a cluster (dashboard-local hide; no control channel, safe to build now — distinct from removing it from a driver's corpus, which conflicts with DD-006/DD-014 "never drop a finding") *(v0.10)*
- [ ] Persistence / eviction beyond in-memory (TTL or small store) for real deployments *(v0.10)*
<!-- removed 2026-07-21: duplicate of the checked "Rich input viewer" v0.10 item — resources/dashboard.html
     already parses exception/message/detail/stack sections and renders the full stack + a raw-record toggle. -->

- [ ] Exercise the Claude analysis path against a live key — the `verifyClaude` smoke check is merged (PR #5); one real `[claude-check] OK` run closes it *(v0.10, DD-015)*
- [ ] Periodic machine-readable status line (JSON) on stdout for external tooling (distinct from the dashboard push) *(v0.7)*
- [ ] **Corpus view in the dashboard** — surface the accumulated coverage-guided corpus (the interesting inputs that reached new coverage), not just status/coverage/findings. The driver already holds the corpus in-memory; push a sample (or all) to the dashboard and render a browsable/searchable list. Pairs with the load/soak-mode idea (persist + replay the interesting corpus) above. *(user 2026-07-20)*

### Operator orchestration — P5 (confirmed 2026-07-20: two CRDs, after P2–P4)
The operator owns the whole test, not just injection. Two-CRD shape confirmed; built as **P5** after
the P1–P4 injection work (a campaign needs a working instrumented target). Design in full first
(likely DD-025). See §10 of [`docs/OPERATOR-DESIGN.md`](docs/OPERATOR-DESIGN.md).
- [ ] **`BasquinCampaign` (test) CRD** — a second CRD that fires off a complete test run (DD-025, [`docs/CAMPAIGN-DESIGN.md`](docs/CAMPAIGN-DESIGN.md)). Phased P5a–P5d.
  - [x] **P5a — runner flags** (`-Dbasquin.run.duration`, `-Dbasquin.summary.out`) — merged (#18).
  - [x] **P5a — CRD + driver-Job reconciler** — gate on Injected target, launch driver Job (coverage-classes initContainer from the target's image, coverage endpoint, summary via terminationMessage), phase machine + TargetGone. envtest 22/22.
  - [x] **P5a — runner image + in-cluster campaign e2e** — built `basquin/runner` (`runnerJar` task + `deploy/runner-image/`), extended `deploy/e2e/e2e.sh` to apply a `BasquinCampaign` and assert a **non-zero coverage %** end to end.
  - [x] Campaign driver-Job **spec-hash idempotency** — done (reconciler stamps `basquin.dev/spec-hash` on the Job and a mismatch deletes+recreates it as a new run; envtest covers edit-mid-Running rerun and the no-diff resync case). *(2026-07-21 TODO reconciliation: was already merged, item was stale)*
  - [x] **Empty coverage-classes fails loud** — done (`verify-classes` initContainer errors on an empty extract and the reason is surfaced into campaign status instead of a silent `coveragePct=0`). *(2026-07-21 reconciliation: was already merged)*
  - [ ] **Driver coverage-classes: handle war-only target images** — the driver initContainer copies `WEB-INF/classes` **out of the target image** (§7b), but a war-packaged app ships `ROOT.war` with the exploded dir only existing at runtime, so the copy finds nothing (now a loud failure, see above). The e2e works around this by exploding the war into `ROOT/` at image-build time. Operator options: extract classes from the war/lib jars, or copy from the *running* target pod instead of the image. *(surfaced by the campaign e2e + #20 review)*
  - [x] **P5b — per-campaign dashboard** — `basquin/dashboard` image + operator brings up a per-campaign dashboard Deployment+Service (owner-ref'd/GC'd), sets `status.dashboardURL`, and wires the driver's `-Dbasquin.dashboard.push/.id` at it (or an `externalPush`, or nothing when disabled). envtest 25/25 + in-cluster e2e.
  - [x] **Per-campaign dashboard auth — WRITE path (done 2026-07-21).** The operator mints a random 256-bit token per campaign into a `<campaign>-dashboard-token` Secret (owner-referenced, so GC'd with the campaign), mounts it into both the dashboard and its driver via `SecretKeyRef` + Kubernetes `$(VAR)` expansion inside `JAVA_TOOL_OPTIONS` (so the literal never appears in a pod spec), and `DashboardClient` authenticates every push with `X-Basquin-Token`. Spoofed status/findings pushes are now rejected. Needed a new `secrets: get;list;watch;create` rule in both the generated Role and the chart's hand-maintained `rbac.yaml`.
    - [x] **READ path closed (DD-028, #52 design + #55 implementation, 2026-07-21).** Reads are
          token-gated when a token is configured: `X-Basquin-Token` for scripts/drivers, or a
          one-time `?token=` handoff → per-campaign `HttpOnly` cookie + redirect (browsers).
          `/healthz` is the one unauthenticated endpoint (probes). Constant-time comparisons
          throughout; the session cookie also fixed the latent #43 Analyze-button 401. e2e asserts
          the 401/200 pair in-cluster; unit tests cover the parsing helpers. NetworkPolicy demoted
          to defense-in-depth docs (kind's CNI doesn't enforce it).
    - [ ] Token-auth RBAC-scope nit from the #43 review: the operator Role's `secrets: get;list;watch` is namespace-wide, not scoped to `*-dashboard-token` (consistent with the existing trust model, and `resourceNames` can't express a prefix — revisit if the operator ever shares a namespace with sensitive app Secrets). *(The `String.equals` timing-oracle half is DONE — #55 made all token comparisons `MessageDigest.isEqual` on both the read and write guards.)*
  - [x] **Wire `corpusConfigMap` → the driver (seed values)** — done (was already merged; this item was stale). `buildDriverJob` mounts the corpus ConfigMap flat at `/basquin-corpus` and sets `-Dbasquin.corpusDir`; `RequestGrammar.readValues` falls back to `<corpusDir>/<basename>` when a grammar-relative `@`-ref misses (basenames must stay globally unique across the corpus tree, enforced by the CLI's dup check — DD-018). Hardened by the #22 review (empty primary value file must not mask the fallback). The e2e creates the corpus ConfigMap incl. `values/` and asserts a `resolved values … via corpusDir` line in the driver log. *(2026-07-21 reconciliation)*
- [x] Operator launches the **driver** as a Job — done (P5a): `buildDriverJob` launches it pointed at the target/coverage endpoints + dashboard push.
- [x] Operator launches/ensures the **dashboard** — done (P5b): `ensureDashboard` creates the Deployment/Service, mints the token Secret, wires the driver's push, sets `status.dashboardURL`.
- [x] Campaign lifecycle — done: owner refs on the driver Job, dashboard Deployment/Service, and corpus ConfigMap drive k8s GC on campaign delete (no finalizer needed — the campaign holds no external state to revert, unlike BasquinTarget); status aggregates `Phase`/`CoveragePct`/`Findings`/`Load`/`Conditions`.
- [x] Package the **runner** and **dashboard** images — done: `deploy/runner-image/` + `deploy/dashboard-image/`, built + pushed to ghcr by `release.yml`.

### Operator (post-P1 platform work — beyond the P2–P4 checklist)
- [x] Build the versioned `basquin/agents:<tag>` image the operator's initContainer copies from — `deploy/agents-image/` (Dockerfile + `build.sh` staging the agent/valve/jacoco jars + native `.so` onto busybox). Built `basquin/agents:0.2.0` + `:latest`, loaded into the `basquin` kind cluster, verified contents + the initContainer `cp` + ELF arch.
  - [x] **e2e-instrument the stock JPetStore image** — `deploy/e2e/e2e.sh` builds+loads all images, deploys the operator in-cluster with its namespaced RBAC, deploys a RAW JPetStore, applies a `BasquinTarget`, and asserts injection (initContainer added, `CATALINA_OPTS` appended, agents on the live JVM, app serves 200, **no RBAC forbidden errors**). Passing against the `basquin` kind cluster. **This in-cluster test caught two real bugs the local/envtest runs masked:** (a) the operator Role was missing `update;patch` on `basquintargets`, so the finalizer couldn't be added — the operator literally could not run in-cluster; (b) the injected initContainer needed `imagePullPolicy: IfNotPresent` or a `:latest` agents image `ImagePullBackOff`s. Both fixed.
  - [x] **Multi-arch images (DD-027, done 2026-07-20)** — all four images publish `linux/amd64` + `linux/arm64` manifest lists. The only arch-specific artifact is the native `.so`; rather than cross-compile on the host, the agents Dockerfile gained a `eclipse-temurin:17-jdk` builder stage that runs `native/Makefile`, so `docker buildx --platform` compiles it **per-arch** (native on amd64, QEMU on arm64) from the same portable C source. `release.yml` sets up QEMU+buildx and pushes manifest lists; `STAGE_ONLY=1` on the build.sh scripts stages a context without the local single-arch build. Verified locally: both legs build and produce correct ELFs (`x86-64` / `ARM aarch64`).
    - [x] **arm64 functional validation — done (#56, 2026-07-21).** `arm64-smoke.yml` on
          `ubuntu-24.04-arm`: builds the `.so` natively (asserted `ARM aarch64` ELF), loads it via
          `-agentpath` in a real arm64 JVM, asserts the JVMTI hooks engaged (`isActive()`, not the
          `ThreadMXBean` fallback), and runs the full leak-oracle iteration loop. Runs on every
          `native/**`/`agent/**` change. Living-doc caveats retired; the v0.2.0 images themselves
          predate the check (QEMU-cross-compiled from the same source) — the first post-#56 release
          ships images backed by it.
  - [x] `docker push` / registry publish flow for the agents image — done: `release.yml` buildx-builds + `--push`es all four images (incl. agents) to `ghcr.io/ianp94/basquin-*` on every `v*` tag. (`build.sh` remains the local dev path that loads into kind only.) *(2026-07-21 reconciliation)*
- [x] **Helm chart to deploy the operator** — `deploy/helm/basquin-operator/`: CRDs + namespaced RBAC (Roles, not ClusterRoles) + controller Deployment, with the three images wired from values onto the controller args (no manual patching). `INSTALL=helm deploy/e2e/e2e.sh` exercises it end to end (injection + campaign + dashboard, 0 RBAC forbidden). **Publishing + tag-driven release (done 2026-07-20):** a **release is just a `v*` git tag** — `.github/workflows/release.yml` builds + pushes the four images to **ghcr.io** (`ghcr.io/ianp94/basquin-*`, chart defaults point there), cross-builds the **CLI** + attaches it + the packaged chart to the GitHub Release, and its `pages` job repackages the chart into `docs/charts/` + reindexes + commits to main so the **GitHub Pages Helm repo** self-updates (`helm repo add basquin https://ianp94.github.io/basquin/charts`). **Single-source version:** one chart value `imageTag` (default appVersion) drives all four image tags; `helm package --app-version <tag>` makes the tag the only version input. `deploy/helm/publish.sh` remains for a manual/bootstrap Pages refresh. Remaining follow-ups: cut the first real tag to verify the CI publish end-to-end; a CI check that the chart RBAC hasn't drifted from `make manifests`; multi-arch images (agents `.so` is amd64-only); OCI-registry chart push as an alternative.
- [~] **CLI to launch tests** — a thin Go `basquin` CLI (reuses the operator's api/v1alpha1 types + controller-runtime client → applies real typed CRs). `make -C operator cli`.
  - [x] `instrument` — apply a `BasquinTarget` from flags (--deployment, --container, --jvm-opts-var, --coverage-includes, --coverage-port, --coverage-service, --invariant-*, --thread-tracker), `--wait` for Injected. Unit-tested + validated against the kind cluster.
  - [x] `run` — create grammar/corpus ConfigMaps from local files (owner-ref'd to the campaign → GC'd with it) + apply a `BasquinCampaign` (--target, --base-url, --iterations|--duration, --grammar, --corpus, --no-dashboard/--external-push), `--watch` tails to Completed and prints coverage/findings/dashboard. Unit-tested + validated against kind (full run to Completed, ConfigMap GC).
  - [x] `status` — renders targets + campaigns (phase, coverage %, findings, dashboardURL) as aligned tables, `--watch` re-renders. Validated against kind.
  - [x] `dashboard` — self-contained client-go port-forward to the campaign's dashboard pod, prints the local URL. Validated against kind (forwarded + served live `/api/campaigns`).
  - [x] Distribution: `.github/workflows/release.yml` cross-builds the CLI (**linux/darwin/windows × amd64/arm64** — six binaries, `.exe` on Windows) → GitHub Release assets on a version tag, with the tag stamped via `-X main.version` and surfaced by `basquin version`. Follow-ups: a `kubectl basquin` plugin alias; a `version --short` (bare `0.2.0`, no platform/parens) so install scripts asserting a minimum version don't have to parse the human-readable form *(#39 review)*.
- [ ] **Keep the operator usage docs current** — refresh `docs/` (CRD reference: target + campaign fields, dashboard, grammar/corpus ConfigMaps, spec-edit rerun) as the operator surface grows; a recurring pass, not one-and-done. *(user 2026-07-20)*
- [x] Operator CI: `go build` / `go vet` / `gofmt` / envtest in the pipeline — done (#51): `.github/workflows/operator-ci.yml` runs `go build` then `make test` (manifests + generate + fmt + vet + cached-envtest suite) plus a generated-artifact drift check. *(2026-07-21)*
- [ ] Field indexer on `spec.deploymentRef.name` so the Deployment mapping-watch does a targeted lookup instead of a namespace-wide list+filter (PR #11 review; fine at current scale, optimization for high target-count/churn namespaces)
- [x] **Deeper content-drift detection** — `injectionApplied` now verifies the agent flags in the target container's `jvmOptsVar` AND the shared volume mount, not just the annotation + initContainer, so a stripped/edited env (agents silently stop loading) is re-healed rather than read as steady-state Injected. envtest covers the env-strip drift case. *(surfaced by the in-cluster e2e)*
- [x] Operator e2e in CI — `.github/workflows/operator-e2e.yml` builds the JPetStore WAR (mvn) + every image, Helm-installs the operator into an ephemeral kind cluster, and runs the full `deploy/e2e/e2e.sh` (injection + campaign + load + dashboard) on operator/deploy/runner/agent changes. Guards the in-cluster path (RBAC, real images, agent loading) beyond envtest.
- [ ] **E2e pipeline speed** — the kind e2e is ~8 min/run and PR-blocking; keep it (it's the test
      that catches what envtest can't), but reclaim the ~3 min that is build plumbing, not testing
      (analysis from step timings of run 29791523687, 2026-07-21: ~1 min setup incl. 45s WAR build +
      409s e2e.sh). No test-semantics changes:
  - [x] Parallelize the four image builds + kind cluster creation in `e2e.sh` — done: one up-front
        gradle invocation stages every jar (so the build.sh gradle calls are up-to-date no-ops and
        don't contend), then the four docker builds + cluster creation run concurrently, then the
        four `kind load`s. Per-build logs land in `/tmp/e2e-build-*.log` and are dumped on failure;
        the build.sh CRLF gradlew shim got a PID-unique name so parallel runs can't race on it.
  - [x] Docker layer caching for the operator image in CI — done: `operator-e2e.yml` pre-builds it
        via `docker/build-push-action` with `cache-from/to type=gha` + `--load`, and `e2e.sh`
        (`PREBUILT_OPERATOR=1`) skips its own uncached rebuild when the image is already in the
        daemon (local runs without the env keep building as before). Warm runs skip the whole
        `go mod download` + compile (~1 min).
  - [x] Cache the built JPetStore WAR keyed on `JPETSTORE_SHA` — done: the WAR artifact itself is
        cached (`~/jpetstore-war`); clone+mvn (and the Maven install + m2 cache steps) are skipped
        entirely on a hit.
  - [x] `concurrency: cancel-in-progress` on `operator-e2e.yml` — done (group `operator-e2e-<ref>`).
  - Optional, mild signal trade-off (only if the above isn't enough): explore `iterations: 200`→100
    (assertion is only non-zero coverage) *(~30–60s)*; load `duration: 45s`→30s *(~15s)*
- [ ] Validating enforcement for `container` required-when-ambiguous (a CRD schema can't express "required only when the pod has >1 container") — pairs with P2
- [ ] Namespaced metrics security: re-enable a metrics-protection approach that needs no cluster-scoped binding (the kube-rbac-proxy sidecar was dropped in P1 because it requires a `system:auth-delegator` ClusterRoleBinding)
- [ ] **Multi-runtime profiles** (`runtimeProfile: jvm | node | …`) — the forward reason for Go: the CR/reconcile/inject/revert control plane is runtime-agnostic; a new profile supplies different agents/flags without touching the machinery

### Project workflow / repo infra
- [ ] **Bot profile as a GitHub App** (user, 2026-07-21 — tackle 2026-07-22): PRs are currently
      authored by the owner's token, so the owner can't approve them and every merge needs an admin
      bypass. Decision: a **full GitHub App** (not a machine-user account) — cleaner `app[bot]`
      identity, no second account. Owner's part: create the app (contents + pull-requests write),
      install it on the repo, hand over the app ID + private key. Claude's part: installation-token
      minting plumbing (tokens expire hourly), wire git/gh to use it for branches/PRs/commits, and
      the bot's commit identity — after which the protect-main required-approval flow works as
      designed: bot authors, owner approves, auto-merge lands it.
      **Must also fix the release pages job**: its direct chart-commit push to main is blocked by
      protect-main, and the GitHub Actions app **cannot** be a ruleset bypass actor on a personal
      repo (API: "must be part of the ruleset source or owner organization" — verified 2026-07-21;
      the v0.2.0 pages job failed twice on this, chart was published manually via #47). Fix: the
      pages job pushes with the bot app's installation token, and the bot app goes on the ruleset
      bypass list (user-owned apps are accepted there). Also revisit the two app integrations
      currently on the bypass list — added while debugging this; keep only what's intended.

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
  `-Dbasquin.coverage.classes` at it by hand.
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

## Follow-ups from the 2026-07-23 benchmark campaign

Each of these was established against a live app during the multi-app benchmark pass, not
theorized. Recorded here so the evidence isn't lost with the session that found it.

### Tool

- [ ] **GC-bracketed drift sampling.** `heapDriftKb` is a raw `totalMemory − freeMemory` difference
      with no collection on either side, so it reports GC phase as much as retention: the same
      metric came back **+381 MB** on one run of JSPWiki and **−194 MB** on another, and 600 probe
      reads alone swung raw used-heap by +684 MB, all of it collectable. Bracketing with a forced
      collection (or taking a min-envelope over many polls) showed no unbounded growth at all.
      Until this lands, no drift number is publishable — the benchmark page deliberately omits one.
- [ ] **`violations.heap` is hardcoded 0 in load mode.** `summaryJson` reports a heap violation
      count that no code ever increments — load mode has no heap gate. "0 violations" therefore is
      not a check that passed, which is exactly the kind of reassuring-but-empty signal this project
      exists to eliminate. Either gate it or stop reporting it.
- [ ] **`readCorpus` counts each ConfigMap corpus file twice.** `Files.walk` sees both the
      `corpus.txt` symlink and the same file inside the volume's `..<timestamp>` real directory, so
      the driver logs "54 sequence(s)" for a 27-line corpus. Uniformly 2×, so no selection skew —
      but the logged corpus size is wrong.
- [ ] **Attribute the "failed request" count.** Explore runs report a large count of iterations
      where the driver's own HTTP call threw (1307 of 7813 on JPetStore). Not yet split between the
      app, the driver, and the single-node cluster, so it currently can't be reported as a defect
      count. Saving the exception class alongside the input would settle it.
- [x] **DD-039 — session carry across redirects in explore mode.** Spring Security rotates the
      `JSESSIONID` **on the 302**, and `HttpURLConnection` only exposes the final hop's headers when
      it follows redirects itself, so the rotated cookie is unreachable and every later step runs
      anonymous. Blocks every form-login app, not just Roller. Spec:
      `docs/superpowers/specs/2026-07-23-redirect-session-carry-design.md`. **Done** — all tasks
      landed, Task-7 acceptance **PASSED** (84 `login_publish` DB rows; gap 189→48, 2.8%,
      `bench-results/dd039-acceptance-2026-07-23/`); PR [#96](https://github.com/ianp94/basquin/pull/96).
- [ ] **A request-header directive** (`>>Header: value`, the mirror of `<<name=header:`). Roller's
      AtomPub surface (`/roller-services/app/*`) is a complete authenticated write API guarded by
      HTTP Basic — no session, no CSRF, ideal for a corpus — and unreachable today because a route
      can express method, path, and body but not a header.

- [ ] **Load mode has no counter for a transport failure.** `fireR` returns `code == -1` when the
      request throws (connection refused, reset, read timeout, protocol error). `-1` is neither
      `>= 500` nor in `[400,500)`, so it increments **neither** `serverErrors` nor `clientErrors` --
      it is counted as an ordinary successful request with its measured latency. A run against an
      app that is refusing connections therefore reports high throughput, a low p50, and zero
      errors: the exact "broken run looks clean" failure this project exists to eliminate. Add a
      `transportFailures` counter and surface it in the summary.
- [ ] **JPetStore: `/actions/Order.action?listOrders=` 500s for an unauthenticated caller** --
      `NullPointerException`, and then a *second* NPE inside Stripes'
      `DefaultExceptionHandler` ("Unhandled exception in exception handler"). Reproducible with a
      plain curl, no instrumentation involved. This is the source of the ~5.5% 5xx rate in the
      2026-07-23 load runs; the 2026-07-21 baseline's corpus never fired that route, so it is a
      newly-reached app defect and NOT a regression. Worth reporting upstream.

- [ ] **CRITICAL — the invariant reporting channel drops most violations.** The valve evaluates
      every invariant server-side and logs it, then attaches `X-Basquin-Invariant-Count` to the
      response *only* `if (!response.isCommitted())` (`BasquinValve.java:66`,
      `TomcatBoundaryAdvice.java:43`). The driver learns about violations **solely** by reading that
      header, so a committed response silently discards the finding. Evaluation is intact; only
      reporting is lost.

      Measured on each app's real replay corpus, with synchronized server-log windows:

      | app | responses that cannot report | violations lost |
      |---|---|---|
      | roller | 36/37 (97.3%) | 52/52 (100%) |
      | jspwiki | 24/32 (75%) | 23/23 (100%) |
      | jpetstore | 0/43 (0%) | 0/5 (0%) |

      One Roller explore window evaluated **1,906 violations and reported 0**. The 25% of jspwiki
      responses that *can* report are redirects and 400s, which never violate — so its effective
      loss is also total. JPetStore is the only app whose published numbers were ever real, which is
      exactly why it looked healthy and the other two looked clean.

      Note *which* boundary: the operator injects `-Dbasquin.boundary=agent`
      (`operator/internal/controller/injection.go:115`), so k8s runs `TomcatBoundaryAdvice` woven
      into `StandardHostValve.invoke`, whose exit runs **after** Tomcat's error-page dispatch. Under
      the compose-path Context valve the exit runs *before* it, so trigger (c) would not apply —
      which matters for reproducing this outside k8s.

      The commit rule: lost iff
      committed at boundary exit — >8192 bytes through the output buffer, any explicit flush
      (Struts/Tiles JSPs, Roller's opensearch servlet, which kills even a 552-byte response), or any
      error status with a **custom** `web.xml` error-page (forward commits and closes at any size —
      that is Roller's 1215-byte 404; jspwiki and jpetstore have no custom error pages so their 404s
      survive). Survives on redirects, sub-8KB non-flushing responses, and static files >48KB via
      sendfile (a 36KB js loses it, a 51KB js keeps it — only sendfile explains that
      non-monotonicity).

      **Fix:** a per-request-id side channel over the existing `/__basquin/*` valve surface. The
      driver sends `X-Basquin-Req: <id>`; `onExit` stores `id -> (cost, violations)` in a small
      bounded map under the already-held lock; the driver GETs `/__basquin/result?id=` after each
      explore request. The poll is `CONTROL_HANDLED` at `onEnter` so it never touches the app and
      never begins an iteration. Cost is negligible beside the existing 25ms grace sleep per explore
      iteration, and it adds zero instructions to the load path. Note the target never talks to the
      dashboard (the *driver* does), so a target-side dashboard push would be new infrastructure.
      Cumulative counters alone are not enough — per-input attribution is what the corpus cost model
      needs, and losing it is why Roller's corpus collapsed to 2 search entries.
- [ ] **Load mode never evaluates invariants at all** (passthrough by design, DD-029) *and* the
      load driver JVM is never given `-Dbasquin.invariant.latency.maxMs` (`LoadRun.java:52` defaults
      to 0 = disabled). Roller's load `violations.latency: 0` at p50 503 ms against an intended
      250 ms budget is structural, not empirical. The fix needs the operator to propagate
      `latencyMaxMs` into the load driver's JVM opts.
- [ ] **The explore summary's `invariants` block is the driver measuring itself** with no thresholds
      configured, so it is structurally 0 too. Two different reported zeros, neither meaning what a
      reader would assume.
- [ ] **The leak detector throws unconditionally at `agent/Agent.java:249`, ignoring
      `invariant.mode=soft`**, turning a response the app had already written
      into an empty 500. Observed while publishing a Roller entry manually: the row was written and
      the client got a 500. Soft mode must record and continue, never alter the response.
- [ ] **Roller's `login_publish` sequence has never published a single row.** Explore's `request()`
      still follows redirects, so the login 302's `Set-Cookie` is eaten, the salt capture misses, and
      the step is silently skipped — the exact bug class DD-038 fixed in `LoadRun.fireR` but not in
      explore. This is live confirmation that DD-039 is worth doing.
- [ ] **The jspwiki readiness probe violates heapDelta ~12/min at idle** — a noise source that any
      server-side counting fix will pick up.

- [ ] **The lost violations are recoverable without re-running anything.** Evaluation is intact and
      every violation is in the target pod's log with its iteration number, so the explore windows
      already captured under `bench-results/` can be re-scored from logs offline. Worth a small
      script — it turns discarded campaigns back into real data.

### Follow-ups from PR #93 and #94 (both approved with these outstanding)

Recorded rather than fixed in-branch: each was raised on a PR that was already approved, and
touching the tip would have put it outside what was reviewed.

**The one that matters — do this first.**

- [ ] **Wire `deploy/bench/check_claims.py` and `deploy/bench/test_redact.py` into CI.** Both exist
      because reviewers were doing a script's job: four consecutive review rounds on #93 blocked on a
      hand-written number, and one misquote survived all four. A guard that fires only when someone
      remembers to run it will drift, and then the reason for adding it is gone.

**From #93 (round-5 approval)**

- [ ] Add the `X-Basquin-Token` case to `test_redact.py`'s `MUST_REDACT` set — the shape was restored
      after a narrowing dropped it and is currently unpinned, so the next narrowing drops it silently.
- [ ] `deploy/bench/roller/README.md:177` — "corroborated in `evidence.txt`" overreaches; that file
      backs the write path, not the count of 16 or the nonce distinctness.
- [ ] The benchmark page's "Two observations … reported, not explained" lead half-overstates now that
      the JPetStore 5xx observation carries an explanation.

**From #94 (round-2 approval)**

- [ ] **`TestHonestyMarkersAreInTheServedCRDSchema` cannot fail for the reason it exists.** It uses
      `strings.Contains` over the whole CRD YAML, so it passes even with the `reportMisses` property
      pruned — precisely the failure it was written to catch, since Kubernetes prunes fields absent
      from the served schema. Assert on the parsed schema's property set instead. Same shape as the
      DD-040 Task 1 concurrency test that passed 85–95% of the time with synchronization removed
      entirely: green, and proving nothing.
- [ ] `FindingsLowerBound` is a plain `bool` while `ReportMisses` is a `*int32`. Latent with a
      matched-version driver, but the asymmetry means an omitted `findingsLowerBound` reads as
      `false` ("not a lower bound") rather than as absent — the defect class DD-040 exists to remove.

### Future: DD-042 — a load-mode oracle (deferred, not scoped)

The intended division of labour is sound: **explore** finds serialized, per-request defects
(invariant breaches, expensive inputs, cold cliffs) because it runs one clean iteration at a time;
**load** is the only mode that produces real interleaving, so it is the only mode that can expose
concurrency defects. But exposure is not detection, and today load mode has **no failure oracle at
all** — it counts and reports, and the campaign completes as long as the driver Job exits 0.

The evidence is embarrassing and worth keeping: two load campaigns ran against a JSPWiki instance
that had two workers spinning at 100% of a core and a dead NIO Poller. They reported 2.1 rps and a
p50 of 5003 ms and were marked **Completed**. The outage was caught by a human noticing `0/1
Running`; the app's own WatchDog logged it for five hours; Basquin did not. See
`bench-results/jspwiki/incident-2026-07-23-login-hang/ANALYSIS.md`.

What load mode would need for the claim to hold:

- **A latency budget that actually fires.** In scope for DD-040 item B (the threshold lives on the
  BasquinTarget and never reaches the load driver).
- **Transport-failure counting.** Already recorded above: `code == -1` currently increments neither
  error counter and lands in the histogram as a fast success.
- **A degradation oracle**, which does not exist in any form: throughput collapse, a latency cliff
  mid-run, or the target becoming unreachable should FAIL a campaign, not complete it. Availability
  is supposedly the bug oracle; a run that ends with the target dark must not be a pass.
#### Proposed shape: an out-of-band census, with the oracle in the driver

The constraint that makes this hard is also what makes it easy: load mode is lock-free passthrough
and must stay that way, because that lock-freedom is the *only* reason real interleaving happens.
So the oracle must add **zero per-request work** — which means it cannot be instrumentation at all.
It has to be sampling.

The channel already exists. The driver polls `/__basquin/drift` periodically during a load run
(`LoadMode.driftSnapshotCsv()`), out of band. Add a sibling endpoint returning a compact thread
census computed on demand, and keep the *analysis* in the driver so the target JVM stays dumb:

```
GET /__basquin/threads  ->  deadlocked=<ids>
                            <id>,<state>,<cpuNs>,<stackFingerprint>
                            ...
```

- `stackFingerprint` is a hash of the top ~8 frames, so the payload stays tiny; the full stack is
  fetched only for a confirmed suspect.
- Uses `ThreadMXBean`, already held as `Agent.THREAD_MX`. Guard on
  `isThreadCpuTimeSupported()`/`isThreadCpuTimeEnabled()` and degrade to state-only if absent.
- Excludes Basquin's own threads and the sampler.

Four findings fall out of comparing consecutive samples, all in the driver:

1. **Spin** — `cpuNs` delta ≈ wall-clock delta (say ≥90%) **and** an unchanged stack fingerprint
   across ≥2 samples. That is precisely the JSPWiki `WeakHashMap` signature: pinned at 100% of a
   core, `RUNNABLE`, same frame, forever. Would have caught it in seconds instead of five hours.
2. **Deadlock** — `ThreadMXBean.findDeadlockedThreads()` is a JDK-native call that returns `null`
   in the normal case. Essentially free, and definitive when it fires.
3. **Contention** — ≥K threads `BLOCKED` on the same lock across ≥2 samples; report the lock and the
   owner's stack. Catches the hot-lock case that shows up as a latency cliff with idle CPU.
4. **Stall** — `RUNNABLE` with an unchanged fingerprint and *no* CPU growth: blocked in a syscall or
   lost, distinct from (1) and worth distinguishing.

Cost: ~50 threads × (one native CPU call + an 8-frame `getThreadInfo`) at a 5 s interval —
sub-millisecond, off the request path, and nothing added to the request path at all. Compare with
the per-request cost of explore mode's boundary (a 25 ms grace sleep plus two full thread
enumerations), which is three orders of magnitude more work per request.

Two properties worth preserving in the design: the target only *reports*, it never decides (so the
finding is attributable to the campaign and lands in the run summary), and a spin is confirmed only
across multiple samples (a thread transiently inside a hot method is normal; the same thread still
there 20 s later is not).

- **A wedged-thread detector.** The `WeakHashMap` spin's signature was CPU pinned at ~100% of a core
  with the thread count *stable* — so `threadDrift`, which counts threads, is blind to it. Sampling
  per-thread CPU (`ThreadMXBean.getThreadCpuTime`) and flagging a thread whose CPU climbs at wall-clock
  rate while its stack is unchanged would have caught it in seconds, and is a genuinely new class of
  finding for this tool.

Ordering note: this is independent of DD-040 and DD-041 and could be built at any point, but the
latency-budget half is already inside DD-040, so start there and let the rest follow.

### Next after DD-040: DD-041 — clustered exploration across replicas (wanted)

Explore mode is synchronous and globally serialized: `ITERATION_LOCK` (a *fair* `ReentrantLock`,
`RequestBoundary.java:39`) is held across the whole app call, and `Agent.end()` adds a synchronous
25 ms grace sleep plus two thread enumerations before releasing it. Throughput is therefore capped
near `1/(25ms + appTime)` — under ~40 rps regardless of driver concurrency (observed: roller 6.1/s,
jspwiki ~11/s). Only triage I/O is off the hot path (DD-006). This is DD-005's deliberate choice:
per-request heap delta is only meaningful with one request in flight.

The tension to resolve, stated plainly so it is not rediscovered later:

- **Scale-out across target replicas** multiplies throughput linearly *without weakening
  measurement* — each pod still runs one clean iteration at a time. It needs a coordinator for the
  shared corpus and coverage map (both are global state today), and per-pod result attribution,
  which DD-040's salted `<RUN_SALT>-<n>` id already provides.
- **Concurrency within a pod** is the only thing that makes explore able to find *concurrency*
  defects at all — today it structurally cannot, which is why the JSPWiki `WeakHashMap` spin was
  findable only in load mode (see `bench-results/jspwiki/incident-2026-07-23-login-hang/ANALYSIS.md`
  §7.2). But it breaks whole-heap per-request attribution: it would need per-thread allocation
  counting (`ThreadMXBean.getThreadAllocatedBytes` / JFR) instead of a heap delta, and retention
  still could not be attributed per request.

**Why this is wanted, concretely:** real apps run behind a Service with several replicas, and that
is the deployment shape the tool has to be credible against. Today a campaign points at a Service
VIP and the requests round-robin, which is not "N clean serialized streams" — it is one stream
scattered across N JVMs, and several things quietly break:

- **Sequences lose their session.** `@sequence` steps carry a cookie; step 2 landing on a different
  replica than step 1 is an anonymous request. This is the same class of silent skip DD-039 exists
  to fix, arriving from a different direction.
- **DD-040's result poll goes to the wrong pod.** The result store is per-JVM, so a poll through the
  Service VIP reaches a pod that never saw the request. Addressed pre-emptively in DD-040 §A.6 —
  the driver must address the serving pod directly — but it is called out here because it is the
  general shape of the problem: *anything* per-JVM must be addressed per-pod.
- **Coverage is already multi-source** (`JacocoCoverageProvider` aggregates
  `sourcesResponded`/`sourcesTotal`, and the target status already tracks `InstrumentedReplicas`),
  so that part of the groundwork exists and should be reused rather than reinvented.

The design that preserves the premise: the driver resolves **pod addresses** from the headless
Service and pins each worker to one pod, so every replica runs one clean serialized iteration at a
time. N replicas then give N× throughput with per-request allocation fidelity fully intact — the
serialization is per-JVM, and that is exactly where it needs to be. What has to become shared is the
*coordination* (corpus, coverage map, cost ranking), not the measurement.

So the honest shape is probably: shard across replicas for throughput, keep each replica serialized
for allocation fidelity, and treat concurrency-defect hunting as load mode's job rather than
retrofitting it into explore. That should be a spec of its own, after DD-040 lands — DD-040's result
store is a prerequisite either way, since a distributed driver cannot rely on response headers it
may not be the one to receive.

### Bench targets

- [ ] **The seeded JSPWiki pages are not being served.** `jspwiki-custom.properties` sets
      `jspwiki.fileSystemPath`, but the provider reads `jspwiki.fileSystemProvider.pageDir`. JSPWiki
      silently falls back to `/root/jspwiki-files`, so the seeded 70-page corpus in
      `/var/jspwiki/pages` is ignored and many corpus "views" hit pages that don't exist. Fix the
      key and re-seed.
- [ ] Roller's default comment CAPTCHA ("what is 3 + 5?") requires computing an answer, which the
      grammar cannot express — hence the swap to `DefaultCommentAuthenticator` in the bench deploy.
      Worth noting as a general limit: captured values can be replayed, not computed.

### JSPWiki behaviours worth knowing (verified live on 2.12.4)

- An empty `page=` redirects to `/Login.jsp?redirect=` **with or without** a valid session cookie —
  it is not a session-loss symptom.
- An unresolved page name is canonicalized by uppercasing the first kept character only
  (`ndws` → `Ndws`, `nDWS` → `NDWS`, `FOO` → `FOO`).
- A concurrent-edit conflict answers `302 /PageModified.jsp?page=<page>`; a missing anti-spam pair
  gives `302 /Wiki.jsp?page=SessionExpired`; a missing CSRF token gives `302 /error/Forbidden.html`.
- Tomcat rejects a request line containing a raw `"` or `<` with a 400 before JSPWiki sees it.
