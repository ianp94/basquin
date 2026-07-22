# Changelog — Runner

Drivers, exploration, load mode, triage, and the dashboard (server + client).
Release-level changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## [Unreleased]

- **Response correlation (DD-036):** load- and explore-mode replay can capture a value from a response (a response header or an `<input>` field) and substitute it into a later request body via `${{name}}`, unlocking CSRF-protected write paths (JSPWiki `X-XSRF-TOKEN`, JPetStore Stripes `_sourcePage`) a static replay could never reach. Corpus format v3 (optional trailing `<<name=kind:arg` capture + `${{name}}` reference) is backward-compatible with v1/v2. The corpus stores only the recipe, never a token; `captureMisses`/`clientErrors` counters surface a broken correlation. Correlated `edit_save`/`order_with_sourcepage` example sequences included.
- **Dynamic-name field correlation (DD-037):** a capture can now grab a whole `name=value` form-input pair whose name is itself dynamic (`<<x=inputpair:<nameRegex>=<valueRegex>`, anchored regex on both halves), and a step can carry more than one capture — unlocking write paths guarded by randomized-name anti-CSRF/spam fields (JSPWiki's SpamFilter hash). Correlation encoding moved into the capture layer, so `${{…}}` substitution is now a verbatim splice; existing `input:`/`header:` captures are byte-identical.

## [0.3.0] — 2026-07-22

- **Dashboard read-path auth (DD-028):** the dashboard server token-gates the read path via a token-to-cookie handoff (one-time URL token → `HttpOnly` cookie); reads were previously open. Covered by `DashboardAuthTest`.
- **Lock-free load mode (DD-029):** `LoadRun` toggles the target lock-free for the run, polls `/__basquin/drift` for the app's real heap/thread drift, counts 5xx (`fire()` returns status), and reverts on exit.
- **Cost-ranked replay corpus (DD-031):** `CostModel` scores each fired input from the boundary's `X-Basquin-Cost` header (driver's own round-trip latency is the always-available floor); `CostCorpus`/`CorpusEntry` retain coverage finds plus EMA/cold-start-gated expensive inputs, never evict a coverage find, and emit the replay ConfigMap sorted by cost descending instead of insertion order. Driver-side kill-switch `-Dbasquin.cost.enabled=false` restores today's insertion-order behavior (A/B baseline). Top costs are logged (`[Basquin] replay cost-ranked (top N): ...`), not added to the termination summary JSON (kubelet's ~4KB budget is shared with the corpus).
- **Added (DD-032):** opt-in `-Dbasquin.pheromone=on` ε-greedy cost-biased selection with immediate-parent credit assignment + evaporation; `-Dbasquin.seed`.
- **Added (DD-033):** live load-mode dashboard (throughput/percentiles/drift/5xx) + mode-aware CLI status; metrics typed for a future OTLP export.
- **Added (DD-034):** running time-series sparklines on the per-campaign dashboard — mode-aware (load: throughput/p99/heap-drift, explore: iterations/coverage/finds), history accumulated client-side (the server keeps only the latest snapshot) as inline-SVG polylines, no server or API changes.
- **Honest load (DD-035):** load-mode replay is method-, session-, and sequence-aware. Corpus format v2 (a line is a TAB-separated ordered sequence of `METHOD? path( SP body )?` steps; a bare `/…` line = a 1-step GET, backward-compatible; new `RequestLine`). Explore emits the *whole* cost-ranked sequence (not the orphaned tail) and issues method+body+session; each `LoadRun` worker replays a random sequence in order with its own `JSESSIONID` cookie jar. Drift-poll/toggle failure now surfaces as `driftUnavailable` (heap/thread omitted) instead of a fabricated `heapDriftKb:0`. Replay-only sequences are guarded out of single-request mutation.


## [0.2.0] — 2026-07-21

First published release (ships inside `ghcr.io/ianp94/basquin-runner` and
`ghcr.io/ianp94/basquin-dashboard`).

### Added
- **Drivers**: `GenericRunner` (iteration loop over a corpus), `CorpusRunner` (deterministic
  replay), `MinimizeRunner` (ddmin input reduction), `HttpRouteDriveTarget` (client-side latency +
  5xx-as-crash + server-side invariant-header harvest), and opt-in JQF coverage-guided fuzzing.
- **Coverage-guided HTTP exploration** (`CoverageGuidedRun`): JaCoCo coverage signal read from the
  target JVM over TCP, union-merged across replicas (comma-separated endpoints or a headless
  Service name), keeping inputs that reach new code.
- **Request grammars + corpora** (DD-018/DD-020): grammar supplies structure
  (`~pattern`, `<int>`, `<string>`, …), corpus supplies values (`@values/file.txt`); session
  epochs (authenticated/anonymous), and `@sequence` blocks for coherent multi-step transactions.
- **Load / soak mode** (`LoadRun`, DD-026): replays an emitted corpus at fixed concurrency for a
  duration (keep-alive, warmup-excluded), reporting throughput, p50/p90/p99/max latency, and
  heap/thread drift.
- **Triage**: crash/invariant classification with a `CrashClassifier` for expected rejections,
  per-target results dirs, timestamps, sampled stacks, bounded handoff queue (`TriageSink`).
- **Live status screen** (`-Dbasquin.status`, AFL-style, TTY-aware) with an exploration panel
  (execs/sec, corpus size, finds, time-since-last-find, coverage %).
- **Dashboard**: standalone `DashboardServer` aggregator (fleet view, per-campaign drill-down,
  fingerprint clustering of findings, rich input viewer with curl replay, run-config capture,
  cross-target clustering) + `DashboardClient` push (`-Dbasquin.dashboard.push`), with
  `X-Basquin-Token` authentication on every push; optional Claude-API cluster analysis
  (explicit-click, server-side key only).

### Known limitations
- POST/form-body exploration not yet supported (GET query surface only).
- Dashboard persistence is in-memory (no TTL/store yet); reads are unauthenticated.
