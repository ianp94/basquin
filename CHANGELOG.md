# Changelog

Release-level changelog for Basquin. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are git tags (`vX.Y.Z`), and one tag versions everything — the four container images, the
Helm chart's `appVersion`, and the CLI are all stamped from it.

Component-level detail lives with each component:

| Component | Changelog |
|-----------|-----------|
| Java agent (in-JVM measurement) | [`agent/CHANGELOG.md`](agent/CHANGELOG.md) |
| Runner (drivers, fuzzing, load, dashboard) | [`runner/CHANGELOG.md`](runner/CHANGELOG.md) |
| Native JVMTI agent | [`native/CHANGELOG.md`](native/CHANGELOG.md) |
| Tomcat valve | [`tomcat-valve/CHANGELOG.md`](tomcat-valve/CHANGELOG.md) |
| Kubernetes operator + `basquin` CLI | [`operator/CHANGELOG.md`](operator/CHANGELOG.md) |
| Helm chart | [`deploy/helm/basquin-operator/CHANGELOG.md`](deploy/helm/basquin-operator/CHANGELOG.md) |

## [0.3.0] — 2026-07-22

**Load becomes real, steerable, and honest.** This release turns load-mode replay into a
first-class, faithful, and self-measuring path — from a lock-free driving model through
cost-ranked, method/session/sequence-aware replay to honest drift reporting — alongside a live
per-campaign dashboard. One tag stamps everything: the four ghcr images, the Helm chart
`appVersion`, and the `basquin` CLI.

### Added
- **Dashboard read-path auth (DD-028):** dashboard *reads* are now token-gated — a one-time
  token in the URL is handed off to an `HttpOnly` cookie — closing the open read path that 0.2.0
  shipped as a known limitation (writes were already token-authenticated).
- **Lock-free load mode (DD-029):** the driver toggles the target lock-free for the run, polls the
  app's real heap/thread drift, and counts 5xx — replay can finally be driven concurrently.
- **Server-side request boundary on the operator path (DD-030):** a ByteBuddy `premain` boundary
  (`-Dbasquin.boundary=agent`) shares `RequestBoundary` with the valve, bringing the
  availability-is-the-oracle model to instrumented Deployments.
- **Cost-ranked replay corpus (DD-031)** + **opt-in pheromone selection (DD-032):** explore scores
  each fired input (`X-Basquin-Cost`), retains + emits the replay corpus cost-descending, and can
  bias selection toward the expensive inputs (`-Dbasquin.pheromone=on`, ε-greedy with credit
  assignment + evaporation).
- **Load as a first-class citizen (DD-033):** live load-mode dashboard
  (throughput / p50·p90·p99 / heap-drift / 5xx) and a mode-aware CLI `status` (`MODE` column);
  metrics typed for a future OTLP export.
- **Running time-series graph (DD-034):** per-campaign dashboard sparklines, mode-aware
  (load: throughput/p99/heap-drift · explore: iterations/coverage/finds), history accumulated
  client-side (no server or API change).
- **Honest load (DD-035):** load-mode replay is method-, session-, and sequence-aware — corpus
  format v2 (a line = a TAB-separated ordered sequence of `METHOD? path( SP body )?` steps; a bare
  `/…` line = a 1-step GET, backward-compatible). Explore emits *whole* cost-ranked sequences (not
  orphaned tails); each worker replays a sequence in order with its own `JSESSIONID` cookie jar; a
  failed drift poll/toggle now surfaces as `driftUnavailable` instead of a fabricated
  `heapDriftKb:0`.

### Benchmarks
- Controlled in-cluster 3-way against purpose-built load tools (same JPetStore target, load mode,
  c=50, 2m, identical routes): **k6 10,856 rps · Locust (8-proc) 9,600 · Basquin 6,848 rps**
  (Basquin has the best p50). All three converge on the target's capacity — Basquin's load engine is
  competitive with dedicated tooling, and uniquely also captures server-side heap drift + invariant
  findings the others can't see. Full methodology and the method-unaware-replay finding in
  [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

Component-level detail: [agent](agent/CHANGELOG.md) · [runner](runner/CHANGELOG.md) ·
[tomcat-valve](tomcat-valve/CHANGELOG.md) · [operator](operator/CHANGELOG.md) ·
[Helm chart](deploy/helm/basquin-operator/CHANGELOG.md).

## [0.2.0] — 2026-07-21

First published release. Everything ships from this tag: the four multi-arch container images on
ghcr (`ghcr.io/ianp94/basquin-{operator,agents,runner,dashboard}`), the `basquin` CLI binaries
(linux/darwin/windows × amd64/arm64), the packaged Helm chart, and the self-updating Pages Helm
repo (`https://ianp94.github.io/basquin/charts`).

### Added
- **Kubernetes operator** (namespaced by design): `BasquinTarget` for reversible in-place
  instrumentation of unmodified Deployments; `BasquinCampaign` for complete test runs with
  `explore` (coverage-guided fuzzing) and `load` (corpus replay) modes, coverage % + findings in
  status, and an emitted corpus ConfigMap per explore run.
- **Per-campaign dashboard** with token-authenticated pushes (a per-campaign 256-bit Secret),
  findings clustered by fingerprint, rich input drill-down, and optional Claude-powered analysis.
- **`basquin` CLI**: `instrument`, `run`, `status`, `dashboard`, `version`.
- **Measurement layer**: availability invariants (latency / heap delta / thread delta, hard or
  soft), event-driven JVMTI thread-leak tracking, iteration contexts, classloader reset fallback.
- **Exploration**: JaCoCo coverage signal over HTTP (union-merged across replicas), request
  grammars + seed corpora, multi-step sequences with sessions, ddmin input minimization.
- **Tomcat valve**: one namespace-free jar for both `javax.servlet` (Tomcat 9) and
  `jakarta.servlet` (Tomcat 10+).

### Known limitations
- arm64 images are **build-validated only** (the native `.so` has not yet been loaded by a real
  arm64 JVM; a bad `-agentpath` library is fatal at JVM startup).
- Dashboard **reads** are unauthenticated (writes are token-authenticated) — single-tenant only.
- War-only target images: the coverage-classes initContainer needs an exploded `WEB-INF/classes`
  in the target image; an unexploded `ROOT.war` fails loud (`verify-classes`) rather than
  reporting a silent 0%. Workaround: explode the war at image-build time.

[0.2.0]: https://github.com/ianp94/basquin/releases/tag/v0.2.0
