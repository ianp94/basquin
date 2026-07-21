# DD-033 — Load as a first-class citizen (dashboard + CLI), OTel-shaped metrics — Design

**Status:** accepted (2026-07-21), pending implementation. Closes the mode-aware-dashboard roadmap gap
(TODO.md, PR #76); builds on DD-013 (dashboard push), DD-026 (load mode), DD-029 (lock-free load).

## Context — load is a second-class citizen everywhere it's surfaced

Load campaigns produce throughput / latency-percentiles / heap+thread-drift / 5xx (`LoadRun`), but:

- **The dashboard is hard-coded to explore.** `LoadRun` never calls `StatusReporter`/`DashboardClient`;
  `CoverageGuidedRun.main` starts the 2s push loop *before* branching into load, so a load campaign's
  dashboard shows `iterations=0` / `crashes=0` / "no coverage source" (empty explore scaffolding). The
  UI (`resources/dashboard.html`) renders only explore fields. `docs/LOAD-MODE-DESIGN.md:127` promised a
  dashboard push that was never built.
- **The CLI table is explore-shaped.** `operator/cmd/basquin/status.go` prints
  `NAME/TARGET/PHASE/COVERAGE/FINDINGS/DASHBOARD`, so a load campaign shows `<none>` coverage and `0`
  findings — even though `cp.Status.Load` (throughput/percentiles/drift, a `LoadStatus`) is right there.

The real load numbers only reach the pod termination message and `status.load` (via `kubectl`).

## Decision — make the one push path mode-aware; surface load in the CLI; type the metrics for OTel

### Components

1. **`StatusReporter` gains `mode` + a load block.** `snapshotJson()` emits `"mode":"explore"|"load"`
   and, in load, `"load":{throughputRps, latencyMs:{p50,p90,p99,max}, heapDriftKb, threadDrift,
   serverErrors, requests}`. New `setMode(String)` and `recordLoad(...)` API. Mode defaults to explore.

2. **`LoadRun` feeds it live.** `setMode("load")` at start; a lightweight snapshotter thread (every
   `basquin.dashboard.pushIntervalMs`, default 2000ms) computes throughput-so-far, percentiles from the
   **live** histogram (`hist` `AtomicLongArray`), current drift (poll `/__basquin/drift`), and 5xx, and
   calls `StatusReporter.recordLoad(...)`. The **existing** `DashboardClient` loop (already started by
   `CoverageGuidedRun.main`) carries it — so the dashboard updates **live during the soak**, not just at
   the end. No new push mechanism, no second HTTP path.

3. **Dashboard UI load card** (`resources/dashboard.html`): read `st.mode`; when `load`, render a load
   card — **throughput req/s, p50/p90/p99 latency, heap/thread drift, 5xx** — in place of the explore
   cards. Explore rendering is untouched.

4. **Campaign list shows mode** (`runner/util/DashboardServer.java`): the `/api/campaigns` light scrape
   (today greps `iterations`/`crashes`/`pct`) additionally scrapes `mode`, so the list distinguishes
   explore vs load at a glance. The server stays schema-agnostic (opaque blob + scrape).

5. **CLI `status` polish** (`operator/cmd/basquin/status.go`): add a **MODE** column and make the
   metrics column **mode-aware** — explore shows `cov% · N finds`, load shows `rps · p99ms` (from
   `cp.Status.Load`). No more misleading `<none>`/`0` for load campaigns.

### OTel-shaped metrics (design for a future export; do NOT build it here)

Each signal is defined by its **OTel-native type + a stable name + attributes**, so a later optional
OTLP exporter is a *thin adapter* over the same metric set — the bespoke JSON and a future exporter
become two renderings of one well-typed model. **No OpenTelemetry dependency is added in this DD.**

| Signal | OTel type | Name (intended) | Notes |
|---|---|---|---|
| Request latency | **histogram** | `basquin.load.latency` (ms) | already an `AtomicLongArray` histogram → maps directly; percentiles derive from it |
| Throughput | counter (+rate) | `basquin.load.requests` | rate = counter / window |
| Heap drift | **gauge** | `basquin.load.heap_drift` (KiB) | absolute over-time reading (DD-029) |
| Thread drift | **gauge** | `basquin.load.thread_drift` | |
| 5xx | counter | `basquin.load.server_errors` | |
| Iterations | counter | `basquin.explore.iterations` | |
| Coverage | gauge (ratio) | `basquin.explore.coverage` | |
| Findings / crashes | counter | `basquin.explore.findings` / `.crashes` | |

`mode` and the campaign id are **attributes**, not separate metrics. The `StatusReporter` JSON field
names align with these intended metric names, and latency stays a histogram — so the future OTLP
exporter (its own DD) reads the same registry and emits OTLP with zero re-modelling.

## Roadmap item this DD adds (to TODO.md)

- **Optional OTLP metrics export** (its own DD, off by default): emit the above metric set over OTLP so
  adopters' Prometheus/Grafana/Datadog stacks consume Basquin's numbers directly. **Alongside, never
  replacing, the bespoke dashboard** — Grafana can't express the fuzzing-domain UX (input viewer,
  finding clusters, triage). Explicitly ties to **clustered runners**: OTel histogram aggregation is
  what makes cross-runner latency-percentile merging honest (percentiles don't average).

## Testing

- **Unit — `StatusReporter`:** `snapshotJson()` emits `mode=explore` by default; after `setMode("load")`
  + `recordLoad(...)`, emits `mode=load` + a well-formed `load` block; explore fields unaffected.
- **Unit — `LoadRun` snapshot:** the live-histogram percentile snapshot matches the end-of-run
  computation for the same histogram state (extract the percentile/snapshot logic so it's testable
  without a server); `setMode("load")` is set before workers start and reverted (or left) sanely at end.
- **Go — CLI `status`:** a campaign with `Status.Load` set renders `load` mode + `rps · p99`; an explore
  campaign renders `explore` + `cov% · finds`. Table alignment holds.
- **Integration — e2e:** the existing load campaign + per-campaign dashboard already run; add an
  assertion that the dashboard received a `mode=load` status with a non-empty `load` block (query the
  dashboard `/api/campaign/{id}/status` from the e2e), closing the loop in-cluster.

## Non-goals / deferred

- **OTLP export** — its own DD (typed for here, not built).
- **Unified explore+load metrics model** — this DD is additive/mode-tagged, not a `StatusReporter`
  rewrite (the "full unified metrics model" option was not chosen).
- **Dashboard as a control plane** — unrelated (separate roadmap entry).

## Global constraints (carried into the plan)

- **Explore is byte-for-byte unchanged.** `mode` defaults to `explore`; the explore push/UI/CLI paths
  behave exactly as today. Load is purely additive.
- **One push path.** Load data flows through `StatusReporter` → the existing `DashboardClient` loop — no
  second pusher, no new endpoint.
- **Live, not just terminal.** The load dashboard updates during the soak (mid-run snapshots from the
  live histogram + periodic drift poll), not only at end-of-run.
- **Metrics are OTel-typed** (the table above): latency = histogram, throughput/5xx/iterations/findings
  = counters, drift/coverage = gauges, `mode`/id = attributes. **No OTel dependency is added.**
- **Server stays schema-agnostic** — the `mode` scrape is a light regex like the existing ones; the
  server never schema-parses the payload.
- **CLI:** a `MODE` column + a mode-aware metrics column; `cp.Status.Load` surfaced for load campaigns.
