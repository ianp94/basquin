# DD-040 — Trustworthy measurement: make a reported zero mean "checked and clean"

**Status:** revised after spec review
**Date:** 2026-07-23
**Motivating evidence:** `bench-results/header-loss-2026-07-23/evidence.txt`,
`bench-results/violation-logs-2026-07-23/` (9,023 recovered violations), and `TODO.md`.

**Ordering:** this lands **before** DD-039. See "Relationship to DD-039".

## Problem

Four defects share one shape: the tool reports `0` for something it never measured. That is the
failure this project exists to eliminate, currently sitting in the project's own output.

### 1. Most invariant violations are evaluated, logged, then discarded

The boundary evaluates every invariant server-side and logs each violation, then attaches
`X-Basquin-Invariant-Count` to the response — but only `if (!r.headers.isEmpty() &&
!response.isCommitted())` (`agent/TomcatBoundaryAdvice.java:43`,
`tomcat-valve/.../BasquinValve.java:66`). The driver learns of violations **solely** from that header
(`CoverageGuidedRun.java:629`). A response committed before the boundary exits loses the finding.

| app | responses that cannot report | violations evaluated → reported |
|---|---|---|
| roller | 36/37 (97.3%) | 52 → 0 |
| jspwiki | 24/32 (75%) | 23 → 0 |
| jpetstore | 0/43 (0%) | 5 → 5 |

One Roller explore window evaluated **1,906 violations and reported 0**. JSPWiki's surviving 25% are
redirects and small 400s, which never violate, so its effective loss is total too. JPetStore loses
nothing only because its pages are 1–6 KB; it is the outlier that made the tool look functional.

Which triggers apply depends on **which boundary is installed**: the operator injects
`-Dbasquin.boundary=agent` (`operator/internal/controller/injection.go:115`), so Kubernetes runs
`TomcatBoundaryAdvice` woven into `StandardHostValve.invoke`, whose exit runs **after** Tomcat's
error-page dispatch. Lost iff committed at boundary exit:

- **>8192 bytes** through the `OutputBuffer` (Roller's 8,799 B search loses it; JPetStore's 6,116 B
  keeps it).
- **Any explicit flush**, at any size — Struts/Tiles JSPs, Roller's opensearch servlet at **552
  bytes**. A buffer-size story alone cannot explain the data.
- **An error status with a custom `web.xml` error-page**: the `RequestDispatcher.forward` commits and
  closes inside `StandardHostValve`. Roller declares error pages, so its 1,215 B 404 loses the
  header; JSPWiki and JPetStore declare none, so their 404s render in `ErrorReportValve` *above* the
  boundary and survive.

Survives on redirects, sub-8 KB non-flushing responses, and static files **above** the 48 KB sendfile
threshold — a 35,891 B `mootools.js` loses it while a 51,058 B `jspwiki-common.js` keeps it, because
sendfile bypasses the `OutputBuffer`. Only this rule set explains that non-monotonicity.

The bias runs the wrong way: loss correlates with size and flushing, i.e. with the expensive requests
the tool exists to find. Second consequence: `CostSample`'s heap/thread/invariant inputs come from
the same headers, so where they are lost the cost model receives zeros and exploration degenerates to
latency-only ranking — which is why Roller's retained corpus collapsed to 2 entries.

### 2. Load mode's `violations.latency` is structurally zero

Load mode is passthrough (DD-029) and evaluates nothing, and the load driver JVM is never given
`-Dbasquin.invariant.latency.maxMs` (`LoadRun.java:52` defaults to `0` = disabled). Roller reported
`violations.latency: 0` at a p50 of 503 ms against an intended 250 ms budget.

`summaryJson` (`LoadRun.java:288`) additionally hardcodes `"heap":0,"thread":0`, which load mode
never evaluates at all.

### 3. The explore summary's `invariants` block measures the driver, not the target

`CoverageGuidedRun` wraps each request in the **driver JVM's** `Agent.beginIteration` (`:290`/`:305`)
with no thresholds configured — structurally zero on every app, presented as target data.

### 4. The leak detector throws in soft mode

`agent/Agent.java:242-250` throws unconditionally. Observed live: a Roller publish wrote its row and
the client received an empty `500`. Had the driver reached that step, the harness would have recorded
**its own exception as an app crash** while destroying the app's correct response.

## Non-goals

- Changing what is measured, or the invariant definitions.
- Authentication on `/__basquin/*` (unauthenticated by decision, DD-022; hardening is separate).
- Load-mode per-request invariant evaluation. Load stays passthrough (DD-029); item 2 is about
  reporting honestly, not starting to evaluate.

## Design

### A. A per-request-id side channel over the existing control surface

The driver already reaches `/__basquin/*` on the app's own port and the boundary already intercepts
it. Reuse it; no new listener, port, or operator wiring.

1. **The driver stamps each explore request** with `X-Basquin-Req: <id>`.
2. **`onEnter` reads it.** `RequestBoundary.onEnter(uri, query)` is deliberately Catalina-free, so the
   id is read in **both glue layers** (`TomcatBoundaryAdvice.enter`, `BasquinValve.invoke` —
   `Request.getHeader` is concrete, so DD-011's namespace-free trick applies), passed through a
   widened `onEnter` signature, and carried to `onExit` in a thread-local with the same removal
   discipline as `PHASE`.
3. **`onExit` stores `id → entry`** alongside `exitHeaders`, while still under `ITERATION_LOCK`.
4. **The driver GETs `/__basquin/result?id=<id>`**, which is `CONTROL_HANDLED` at `onEnter` — it never
   reaches the app and never begins an iteration.
5. **The response header remains** an opportunistic fast path.

#### A.1 The poll must not race the grace sleep — this is the critical detail

`Agent.end()` sleeps **25 ms before measuring** (`Agent.java:116-129`), and the map write happens
after that. For a committed response with `Content-Length`, and for the error-page class that
"commits and closes" inside `StandardHostValve`, the client reaches body EOF while the target is
still inside that sleep. A driver that polls ~1 ms later finds no entry and records a miss — **for
the motivating class, every time**. A naive implementation passes a mocked unit test and fails
in-cluster.

Therefore: **the `/__basquin/result` handler acquires `ITERATION_LOCK` (bounded wait, default 2 s)
before reading the map.** Explore is serialized under that fair lock, so the poll simply queues
behind the in-flight iteration and observes the entry after it is written. This costs nothing the
run was not already going to spend — the driver's *next* explore request would queue on the same
lock anyway. The bounded wait must be a timeout, not an indefinite block: a target wedged inside the
app must produce a miss, not hang the driver.

Chunked responses do not race (the terminal chunk is written after the advice exit); `Content-Length`
and closed responses do.

#### A.2 The map is internally synchronized; the lock provides attribution, not reader safety

`ITERATION_LOCK` makes the *write* provably belong to that request. It does **not** make the reader
safe: the poll is handled at `onEnter` on a different connector thread. An access-order
`LinkedHashMap` LRU mutates on `get`, so a concurrent read during a write can corrupt it. The map is
therefore internally synchronized (a synchronized wrapper, or `ConcurrentHashMap` with explicit
eviction). §A.1's lock acquisition is for *timing*, not for memory safety, and both are required.

#### A.3 The entry is built from `IterationContext`, not from `Agent`'s statics

`exitHeaders` reads process-global volatiles (`Agent.getLastInvariantViolations()`,
`lastCostCsv()`, published by `publishInvariantEvidence`). Those belong to the right request **only
because explore is serialized today**. That invariant is one refactor from silent misattribution —
presenting another request's numbers as this id's, which is the worst failure class this spec exists
to prevent. `IterationContext` already carries `latencyMs`, `heapDeltaBytes` and
`invariantViolations` per iteration; `endIteration` exposes the ctx and the entry is built from it.
This makes the channel concurrency-proof rather than concurrency-conditioned.

#### A.4 Ids are salted — an unsalted counter serves stale data as fresh

A bare "monotonic per-run" id collides in two real cases:

- **Two drivers against one target** (parallel campaigns, or a human `curl` mid-run) both count from
  1; driver B's poll for id 5 consumes driver A's entry (remove-on-read) and attributes A's
  measurements to B's input.
- **A long-lived target across sequential campaigns**: campaign 1 dies leaving unread entries;
  campaign 2's poll for id 173 returns campaign 1's entry as a valid result.

Both return **stale data as fresh**, which is worse than a miss. Ids are therefore
`<RUN_SALT>-<n>`, reusing DD-038's `RUN_SALT` (`LoadRun.java:518-525`), already engineered to be
per-pod unique for exactly this reason. A foreign or stale id then misses honestly.

#### A.5 The fast-path discriminator is the COST header, not the invariant header

`exitHeaders` emits `X-Basquin-Cost` **unconditionally** on every explore exit;
`X-Basquin-Invariant-Count` is absent on every clean request. Keying "poll only when absent" on the
invariant header would poll on nearly every request. Keyed on the **cost** header it is correct:
cost-header-absent means the response committed, or no iteration ran (target stuck in a load-mode
TTL, boundary not installed, `beginIteration` degrade path) — and in all of those, polling and
possibly missing is the honest outcome.

Useful corollary worth asserting: against an uninstrumented target, `reportMisses` ≈ iteration count.
Today that situation reads as a clean zero.

#### A.6 The poll must reach the pod that served the request

The result store is **per-JVM**. The driver's `baseURL` is a Service DNS name, so with more than one
target replica a poll to `<baseURL>/__basquin/result?id=` round-robins to a pod that never saw the
request and returns a `miss` — every time, for every request. The fix would appear to work at one
replica and silently degrade to "everything is a miss" the moment the target scales, which is
precisely the direction this project is heading (see DD-041).

This is a correctness requirement, not an optimisation, and it is cheap if designed in now:

**Correction (implementation, Task 3b): the pod-identity-header mechanism this section originally
specified cannot work, and never could.** `X-Basquin-Pod` would ride the same response headers as
`X-Basquin-Cost`, and the driver polls *iff* no cost header arrived — so the pod header is present
exactly when there is no poll, and absent exactly when there is one. The two are mutually exclusive
by construction. Any design that routes the poll using information carried on the response it is
compensating for has this defect.

What ships instead is **DNS fan-out over the target's pod addresses**:

- The driver resolves pod addresses with `InetAddress.getAllByName`, reusing DD-023's exact
  mechanism and loopback-collapse from `JacocoCoverageProvider`, from `-Dbasquin.report.podHost`,
  else the host of `-Dbasquin.coverage.jacoco` (the **headless coverage Service**, which
  `campaign_resources.go:81` already passes on every explore campaign — so no operator change was
  required), else the baseURL host.
- The poll tries each address until one returns the entry.
- **The fan-out's safety rests on §A.4 and §A.7, and holds for requests answered without a
  redirect**: ids are salted and entries are removed on read, so for such a request at most one pod
  can hold a given id, and a fan-out can be wrong only by finding nothing — a counted miss, never
  another pod's measurement.

  **Correction (post-implementation).** This spec originally claimed the "at most one pod" property
  unconditionally, and that is false. §A.5 has explore re-send the *same* id on a followed
  same-method redirect hop (final-hop-wins). On a multi-replica target the Service can route hop 2
  to a different pod, so two pods hold entries under the same id simultaneously, and the fan-out
  returns whichever answers first in DNS order. That may be hop 1's clean, cheap 302 for a request
  whose real work happened on hop 2 — recorded as `measured=true` with the wrong hop's numbers, and
  invisible to `reportMisses` because nothing missed. It is the same *"clean but wrong"* class as
  the method-changing-redirect gap the acceptance run measured, in a different configuration.
  Scope: single-replica targets cannot hit it; multi-replica targets are supported (DD-020) but
  unexercised. DD-039 removes the case entirely by replacing the JDK's auto-follow with an explicit
  hop loop that stamps each hop with its own id, so no per-hop id is ever shared between pods.
- A source that will not resolve polls nobody, including the VIP, and counts a miss.
- Single-replica behaviour is byte-identical: a source resolving to fewer than two addresses returns
  the base URL untouched.

Rejected during implementation: having the boundary report its pod IP for the driver to build a
directory (strictly weaker — the driver only learns pods that *returned headers*, knows none at run
start, and would still have to fan out over an incomplete set); and pod-name→address via headless
DNS (`<pod>.<svc>` only resolves with `spec.hostname`/`subdomain`, a StatefulSet convention, and
Basquin targets are Deployments).

Known limits, to be watched in the acceptance run: the pod port is assumed equal to the baseURL
port, so a Service with `port: 80` → `targetPort: 8080` makes every fan-out poll miss — visibly in
`reportMisses`, but nothing auto-detects it (`-Dbasquin.report.podPort` is the escape hatch). And
with `coverageService: false` plus a ClusterIP baseURL there is no pod source at all, so the poll
stays VIP-bound unless `-Dbasquin.report.podHost` is set.

Explicit **verification item**: with two target replicas behind a Service, the reported violation
count still matches the sum across both pods' logs. A single-replica-only test would pass against a
design that is broken for the multi-replica case.

#### A.7 Bounds

256-entry LRU keyed by salted id; removed on read; oldest evicted on overflow. `detail` is capped at
200 chars as the header path already does, so retention is ≈ 256 × ~0.5 KB ≤ **~150 KB** — a
one-time baseline shift inside the JVM whose heap deltas this tool reports, which is acceptable and
must be stated as a byte bound, not just an entry count.

#### A.8 The poll goes in a `finally`

When `request()` throws `serverError` (`CoverageGuidedRun.java:672-674`) the driver never reaches the
poll, so a 500-ing request's measurements rot in the map until eviction — and 500s are precisely the
interesting requests.

#### A.9 A miss yields *unmeasured*, never zero

This is the difference between fixing the defect and renaming it. On a miss the driver must **not**
fall back to `CostSample.EMPTY` (`CoverageGuidedRun.java:555`): that reproduces the exact
degeneration described in Problem §1, with `reportMisses` ticking cheerfully beside it. A miss sets
`measured=false`, skips `CostModel.score`, and lets `corpus.consider` proceed only on a coverage
find. The summary carries `reportMisses`, and a run with any misses marks its finding count as a
**lower bound** as first-class data (not prose). A run where misses are the majority fails loudly
rather than completing "clean".

#### A.10 Cumulative counters as a supplement

`/__basquin/violations` returns process-wide totals. Nearly free, and makes campaign totals robust to
a per-id miss. Not sufficient alone — per-input attribution is what the cost model needs — and it
requires probe-noise filtering (the kubelet readiness probe alone violates heapDelta ~12/min on
JSPWiki at idle). The id-keyed path sidesteps that entirely, since the driver reads only ids it
issued.

### B. Honest values for the structural zeros — all the way through the consumers

Omitting a field at the driver is **theater unless the consumers change too**, and the DD-035
precedent does not work the way it appears to:

- `operator/api/v1alpha1/basquincampaign_types.go:193-197` — `LoadViolations.{Latency,Heap,Thread}`
  are non-pointer `int32` with `omitempty`, so an omitted field unmarshals to `0`, **and**
  `omitempty` conflates a real measured zero with absent.
- `basquincampaign_controller.go:244-245` prints `"%d latency violations"` unconditionally in the
  Ready condition — the message operators actually read via `kubectl get basquincampaign`.
- `driftUnavailable` is parsed **nowhere** (no field in `LoadStatus`, no hit in `operator/` or
  `resources/`), and `resources/dashboard.html:317,325` renders `(ld.heapDriftKb||0)+' KiB'`, so a
  missing drift already displays as `0 KiB`.

So item B is:

- Pointer fields (`*int32`/`*int64`) or explicit unavailable markers in `LoadStatus`.
- A condition message that does not print counts it does not have.
- Removal of the dashboard's `||0` defaults for measured values.
- `summaryJson` omits `heap`/`thread` violations, which load mode never evaluates.

**Where `latencyMaxMs` comes from, and the trap.** `buildDriverJob` *already* propagates
`-Dbasquin.invariant.latency.maxMs` from `campaign.spec.driver.invariants`
(`campaign_resources.go:86-88`, both modes). The bench runs had none because the threshold lives on
the **BasquinTarget** (`deploy/bench/roller/basquin.yaml:26`), which only reaches the *target* JVM
(`injection.go:131`). An implementer could satisfy "propagate latencyMaxMs" by pointing at the
existing campaign field and closing the ticket with the trap intact. The rule: **the load driver
inherits the target's `invariants.latencyMaxMs`** (the reconciler already resolves the target), and
`campaign.spec.driver.invariants` overrides it when set.

### C. Soft mode must never alter the response — and a leak must actually be recorded

Two halves, and the spec's first draft only had one.

**Safety.** Gate the throw at `Agent.java:242-250` on the existing hard-mode read. Global mode
defaults to **hard** (`agent/Invariants.java:82`), so this cannot silently disable hard mode for
anyone who has not set the property — the leak demo and CI keep failing loudly. State whether leak
takes a per-invariant override (`basquin.invariant.leak.mode`) or follows global mode only.

**Recording.** "In soft mode a leak is recorded" is **currently false**: leak evidence lives nowhere
the driver can see. It is not in `lastInvariantViolations` (only `Invariants` violations reach there
via `recordInvariantEvidence`), therefore not in headers, and
`StatusReporter.recordIteration(..., leakDetected, ...)` is a no-op in a target JVM without
`-Dbasquin.status`. It is stderr only. So this change must **fold leak evidence into the per-id entry
(and/or `lastInvariantViolations`)**, or soft mode silently loses the finding it was supposed to
preserve — trading a false 500 for a lost defect.

## Relationship to DD-039

They cannot be implemented independently: both rewrite the core of `CoverageGuidedRun.request()`
(`:593-676`). **DD-040 lands first.** Its diff to `request()` is local (stamp, poll, parse), it is
the trust-critical fix, and DD-039's per-hop invariant records are only reliably *retrievable* once
this channel exists — the final hop of a redirect chain is exactly the committed-risk hop.

DD-039 then moves the stamp and poll into its hop loop, and **DD-039 owns the combined per-hop
semantics**, which neither document defines today: per-hop ids, per-hop poll-on-absent, and what a
missed hop does to DD-039's *summed* heap/thread cost (a sum with a missed hop is a lower bound, not
a measurement).

## Disposition of the already-published numbers

Roller's and JSPWiki's explore finding counts are **invalid** and get re-run after this lands.
JPetStore's are real (0% loss) and stand.

The recovered pod logs (`bench-results/violation-logs-2026-07-23/`, 9,023 violations) are
**corroboration for the acceptance test, not remediation**: per-input attribution is not recoverable
(target-side iteration numbers include probe traffic, and the mapping back to driver inputs is
approximate), a re-score cannot retroactively feed the cost model that ranked the corpus during the
run, and the logs do not always survive — JPetStore's had already rotated past its window.

## Verification

1. **The motivating case.** An explore request whose response exceeds 8 KB and violates an invariant
   is reported. Fails today.
2. A response that **flushes explicitly at 552 bytes** is reported.
3. A response with a **custom error-page** status is reported (agent boundary).
4. **The grace-sleep race.** A `Content-Length` response that commits early: the driver polls
   immediately after EOF and still gets the entry, because the handler waits on `ITERATION_LOCK`.
   This test must fail against an implementation that reads the map without acquiring the lock.
5. A poll whose lock wait times out returns `miss` and does not hang the driver.
6. A **foreign/stale id** (different `RUN_SALT`) misses rather than returning another run's entry.
7. A miss sets `measured=false` — no `CostModel.score`, no zero-filled `CostSample` — and increments
   `reportMisses`.
8. The map evicts oldest beyond capacity, removes on read, and stays within the stated byte bound
   when the driver never polls.
9. `/__basquin/result` and `/__basquin/violations` never reach the app, never call `beginIteration`,
   and do not appear in coverage.
10. **The load path is untouched**: a load-mode request performs no map write and no header read.
11. A 500-ing request is still polled (the `finally`).
12. Soft mode: a leak records a finding **that the driver actually receives**, does not throw, and
    the app's response reaches the client unaltered. Hard mode still fails the run.
13. A load summary with no configured threshold **omits** `violations.latency`; the operator's Go
    types round-trip the omission without defaulting to 0; the Ready condition message does not print
    a count it does not have; the dashboard does not render `0` for an absent value.
14. **Multi-replica.** With two target replicas behind a Service, the reported violation count still
    matches the sum across both pods' logs. A single-replica test would pass against a design that
    is broken the moment the target scales — which is the direction this project is heading (DD-041).
15. **Acceptance.** A Roller explore campaign reports a violation count within a small tolerance of
    the count in the target pod's log for the same window. Those two numbers differing by 1,906 is
    the bug.
16. Perturbation bound: the poll's own allocation inside the measured JVM is quantified and shown to
    be small against the heap threshold, rather than asserted to be zero.

## Rejected alternatives

- **Set headers before the app runs.** The values do not exist yet.
- **Piggyback request *N−1*'s result on request *N*'s response**, via headers set at `onEnter`
  (headers set before the app runs survive commit — the "values do not exist yet" objection does not
  apply to the *previous* request's values). Genuinely elegant: it removes the extra request, the
  perturbation, and the grace-sleep race in one move. Rejected as the primary mechanism because it
  makes every finding lag one request, which complicates the skip, abort, and end-of-run paths (the
  last request's result still needs a flush), and couples consecutive iterations that are otherwise
  independent. Worth revisiting as an optimization once the poll is correct — at which point the poll
  becomes a tail-flush only.
- **A response wrapper that defers commit.** Buffers unbounded bodies inside the JVM being measured
  (distorting the heap numbers this tool reports), defeats sendfile, breaks streaming, and is deeply
  invasive under the agent-advice integration. A larger `setBufferSize` would still lose the flush
  and error-page classes.
- **The target pushes to the dashboard.** The target never talks to the dashboard — the *driver*
  does (DD-013 deliberately keeps the measured JVM free of that plumbing).
- **Cumulative counters only.** Loses per-input attribution and needs probe-noise filtering. Adopted
  as a supplement (§A.9), rejected as the primary mechanism.
- **Re-score from pod logs instead of fixing the channel.** Cannot feed the cost model during a run,
  cannot attribute per input, and the logs do not always survive. Adopted once as corroboration; see
  "Disposition".
