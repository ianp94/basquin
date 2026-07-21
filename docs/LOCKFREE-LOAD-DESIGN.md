# DD-029 — Lock-free load-mode instrumentation profile

**Status:** implemented (2026-07-21) — agent LoadMode + valve two-state + LoadRun toggle/drift/5xx + e2e. Extends [LOAD-MODE-DESIGN.md](LOAD-MODE-DESIGN.md)
(DD-026) and the concurrency decision in [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md) DD-005/DD-010.

## Context — load mode can't actually load

A `mode: load` campaign (DD-026) drives an **Injected** target — one whose Tomcat valve wraps every
request in a single global `ITERATION_LOCK` (DD-005), so per-request heap/thread deltas are
attributable. That lock **serializes every request**, capping concurrency at 1. The 2026-07-21
benchmark proved it empirically: k6 at 10 VUs against an instrumented JPetStore showed a flat
~256 ms (= 10 × the ~26 ms single-flight latency — pure queueing behind the lock), **not** app
throughput. So today's load mode reports numbers, but the target can't be loaded.

The naive fix — uninstrument the target to go fast — throws away the entire availability oracle, and
load mode degrades to plain k6. **Closure's thesis is that the measurement boundary stays in play
while throughput scales.** This DD makes that true for load by *splitting instrumentation by what
concurrency allows*.

## What concurrency does and doesn't allow (the key fact)

Per DD-010, of the four signals the valve captures per request:

| Signal | Concurrency-safe without the lock? |
|--------|-----------------------------------|
| Latency | **Yes** — each request self-times (context-scoped) |
| 5xx / crash | **Yes** — a per-request property of the response |
| Heap **delta** (per request) | **No** — overlapping requests' allocations land in each other's window |
| Thread **delta** (per request) | **No** — same reason |

But load mode never wanted per-request heap *attribution* — it wants **drift**: does heap climb, do
threads leak, over minutes of sustained load. Drift is a **process-global, absolute, over-time**
reading — which needs no lock at all.

## Decision — Option A: bypass the serialized path in load mode, measure drift separately

1. **Valve becomes a two-state hot path**, keyed on one `volatile` mode flag:
   - **explore (default):** serialized `ITERATION_LOCK` → `Agent.begin/end` with per-request
     heap/thread deltas — **exactly as today, untouched** (lowest regression risk).
   - **load:** passthrough — no lock, no deltas. Requests run concurrently at app speed.
2. **App-side drift via a pollable agent endpoint.** A cheap agent thread samples absolute
   `Runtime.totalMemory()-freeMemory()` and the thread count every few seconds and serves the series
   on a small endpoint; the driver polls it. **This endpoint is net-new agent-side server code**
   (#68 review): there is no HTTP/control surface in the agent or valve today — the valve only handles
   app traffic, and the one in-repo HTTP server (`DashboardServer`) is a separate isolated process.
   It mirrors the *coverage wiring shape* (a `host:port` string handed to the driver Job), but not any
   existing in-agent server — note JaCoCo coverage isn't even HTTP, it's JaCoCo's own raw-TCP
   remote-control protocol in a third-party jar (`JacocoCoverageProvider` uses `java.net.Socket`). So
   the drift server is built fresh; only the operator plumbing pattern is reused. **An endpoint, not
   response headers, on purpose:** the benchmark showed JSPWiki flushes its response early, so
   header-stamping fails on real apps; polling is robust to that. NB: this is a *fresh absolute read*,
   NOT the #59 CSV sampler's heap columns (those are the lock-dependent `ctx.heapDeltaBytes` delta —
   #63 review). The #59 sampler contributes the timer/CSV *cadence* idea, not the value.
3. **Latency + 5xx client-side in `LoadRun`.** It already times requests and runs N concurrent
   workers; **add 5xx/status detection** — new work (`LoadRun.fire()` currently swallows exceptions
   and ignores status, per DD-026 follow-ups and the #63 review). End-to-end latency is the correct
   load metric (what users feel; makes the k6 comparison apples-to-apples).
4. **The target enters load mode via a driver-owned runtime toggle on the app's own HTTP port.**
   The lock is all-or-nothing (a request skipping the lock corrupts a concurrent locked request's
   delta), so mode is **target-wide for the campaign's duration**, not per-request. The driver sends
   a **distinguished control request on the app's HTTP port, which the valve already intercepts**
   (there is no separate control endpoint today — #68 review) that flips the valve to load mode at
   campaign start and reverts at end; the agent **auto-reverts to explore after inactivity** as a
   crash-safety. **Rejected alternative:** the
   operator appends `-Dbasquin.mode=load` to the target's JVM opts and rolls the pod — because (a) it
   makes a *campaign* mutate the *target's* Deployment, violating the two-CRD boundary; (b) it needs a
   pod restart per load campaign; whereas the driver toggle needs no restart and reaches the target
   over its already-intercepted app port (no new listener needed just for the toggle).
5. **Operator change is small.** It already gates on Injected and launches the load driver for
   `spec.mode: load`; it additionally passes the **drift endpoint** to the driver (mirrors the
   coverage-endpoint wiring). No new CRD surface.

## What load mode reports after this

Real concurrent throughput; **end-to-end latency percentiles + 5xx** (client-side); and the app's true
**heap/thread drift over the soak** (server-side) — the availability signals that matter for a soak,
with instrumentation in play the whole time.

## Synergy — why this is foundational

Lock-free load is the concurrency substrate the rest of the distributed roadmap needs:

- **Cost-guided "pheromone" exploration** (backlog): the harness already scores each input's cost
  (latency/heap/thread). Feed that back into selection and a load run replays weighted by cost — but
  only a *concurrent* load engine can then **concentrate real load on the expensive states**. Lock-free
  load + pheromones = a load test that autonomously drives volume into exactly what hurts. This is the
  combination that makes both features far more than the sum.
- **Clustered runners** (roadmap): N concurrent drivers need a target that doesn't serialize — this
  removes the bottleneck they'd otherwise all queue behind.

## Where it activates (important scoping — corrected during implementation)

DD-029 operates on the **valve**. It is live wherever the valve is mounted — the docker-compose /
manual path (where the benchmark ran and the 256ms serialization was measured, and where this was
validated end-to-end). **The Kubernetes operator does not yet mount the valve** — it injects only the
`-javaagent` agent; valve mounting via a Tomcat `context.xml` entry is a separate deferred backlog
item (`injection.go`). So an operator-injected target has no valve to serialize (its load runs are
already concurrent) and no `/__basquin` control surface; `LoadRun`'s toggle/drift calls there simply
404 and no-op (drift reports 0, 5xx counting still works). **DD-029's operator-path benefit is
therefore gated on the deferred "mount the valve via the operator" item** — that is the natural next
step to make lock-free load truly operator-integrated. The feature itself is complete and correct
for the valve path today.

## Non-goals / deferred

- **Clustered-runner mode coordination** — who owns the target's mode when N drivers share it. MVP is
  single-driver (the driver owns the toggle); multi-driver coordination is the clustered-runners DD.
- **Adaptive back-pressure / concurrency ramp** — the user sets `driver.concurrency`; auto-ramp is a
  later refinement.
- **Server-side per-request findings in load mode** — that's explore's job; load reports aggregate
  drift + percentiles.

## Open implementation questions (for the plan)

- Control protocol for the mode toggle (magic path vs. header; auth in-cluster) and the auto-revert
  timeout.
- Drift endpoint format (JSON series vs. latest snapshot the driver deltas) and its port (share the
  agent/coverage surface or its own).
- How the driver interleaves drift polling with load generation without perturbing the latency it's
  measuring.
