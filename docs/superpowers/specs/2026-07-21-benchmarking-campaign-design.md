# Basquin benchmarking campaign — design spec

**Date:** 2026-07-21
**Status:** approved (brainstorm), pending implementation plan

## Goal

Produce a rigorous, data-backed demonstration of what Basquin catches that crash-only tools miss,
run against **real, unmodified CMS-shaped JVM web apps**, and package it as a findings report that
survives a skeptical read. Primary audience: the author's CMS-provider employer (a natural adopter);
secondary: the public project's credibility. The current terminal-recording SVGs show the tool
*running* but not the *findings as data* — this campaign fixes that.

## Targets

CMS-shaped Java/Tomcat apps, so the pathologies shown (render latency, per-request heap growth,
thread/executor leaks under sustained load) map directly to what a CMS provider feels in production.
All run **unmodified** via the namespace-free Tomcat valve (DD-009/DD-011).

| App | Role | Servlet ns / Tomcat | Storage | Status |
|-----|------|---------------------|---------|--------|
| **JPetStore-6** (MyBatis) | proven baseline; re-run for comparable data | `javax` / Tomcat 9 | in-memory HSQLDB | done (docs/THIRD-PARTY-APPS.md) |
| **Apache JSPWiki** | core new CMS target | TBD (verify 2.11 `javax` vs 2.12 `jakarta`) | filesystem page store (no external DB) | **verify in Phase 0** |
| **Apache Roller** *(stretch)* | blog CMS | TBD | needs embedded DB (Derby) | **stretch — only if Phase 0 build is clean** |

**Fallback:** if Roller won't build cleanly within a bounded effort, swap for a lighter CMS-shaped
app (candidate pool: Apache Guacamole client, a JSP-based CMS, or drop to 2 apps) and document the
swap. Never let a fighting build block the campaign.

Every app version is pinned to a commit/release (like `JPETSTORE_SHA`) and recorded in the report.

## Run design (per app)

Fills a ~1-hour Basquin budget while feeding *both* the findings story and the comparison:

1. **~5 min naive round-robin baseline** — establishes the coverage floor and a "dumb driver"
   reference point.
2. **~25 min coverage-guided explore** — per-app request grammar + seed corpus; surfaces diverse
   findings and yields the guided-vs-naive coverage delta.
3. **~30 min load/soak replay** — replay the interesting corpus at fixed concurrency for the
   remainder of the hour; the drift story (heap/thread over time, latency percentiles under load).
4. **Separate ~10 min k6 run** on the same workload — the caveated throughput/latency side-by-side.

Thresholds (soft mode, to collect rather than fail-fast) documented per app; start from the
JPetStore precedent (latency > 5ms, heap > 64KB) and tune per app so findings are meaningful, not
noise. The exact thresholds used go in the report's methodology.

## Data capture

- **Per-finding triage** already exists: `fuzz-results/<app>/` (`.bin` + `.meta.txt`:
  classification, timestamp, invariant, stacks). Feeds the findings counts + example drill-downs.
- **Time-series (new, small addition):** the over-time charts (heap/thread/latency drift) need
  periodic samples the harness doesn't dump today. Build a **periodic machine-readable sampler**
  (CSV or JSON line every N seconds: elapsed, iterations, iters/sec, latency last/mean/p99, heap
  delta, threads, coverage %). This is already a backlog item ("periodic machine-readable status
  line") — building it here serves the product, not just the report. Kept minimal and opt-in
  (`-Dbasquin.sample.out=<file>` / interval flag), no change to existing output.

## Comparison design (staged by rigor)

**Tier 1 — self-contained ability metrics (bulletproof, lead with these):**
- **Invariant-only findings** — count of iterations that violated an availability invariant but did
  *not* throw/5xx. This is the headline: a crash-only oracle (a plain fuzzer, a load tool's error
  count) finds **zero** of these. Directly quantifies the differentiator.
- **Coverage-guided vs. naive** — covered edges/% reached by the guided run vs. the round-robin
  baseline, same time budget. Shows the exploration engine earns its keep.
- Distinct latency-spike routes, peak heap/request, crashes (real faults) — cross-app.

**Tier 2 — caveated k6 side-by-side (supporting color):**
- Run k6 (or JMeter) on the same workload; show throughput + client-side latency parity, then the
  findings Basquin layers on top ("same p99, but here are the 37 inputs that caused it and the
  44MB/req heap"). **Explicitly caveated** as indicative, not a controlled head-to-head — the tools
  measure different things; the point is complementarity, not a speed contest.

## Deliverable: findings report

An HTML report built with the **dataviz** skill. **Every number comes from a real run — nothing
fabricated.** Contents:

- **Cross-app comparative table** (headline): invariant-only findings, coverage guided-vs-naive,
  distinct latency-spike routes, peak heap/req, crashes.
- **Per-app panels:** latency percentile distribution, heap-growth-over-time, coverage-climb curve
  (guided vs naive).
- **k6 side-by-side** panel (caveated).
- **Methodology:** pinned app versions/commits, exact invariant thresholds, reproducible commands,
  the k6 caveat. So it survives scrutiny.

**Homes (both):** a committed `docs/` site page (fits the existing static site, linked from nav)
**and** a shareable Artifact for the pitch.

## Honesty guardrails (non-negotiable)

- No fabricated or projected numbers — real runs only; if a run fails or an app is dropped, say so.
- App versions pinned to commits/releases; recorded in the report.
- Invariant thresholds documented; runs reproducible from committed commands.
- The k6 comparison labeled indicative, with its methodology and limits stated.
- Don't overclaim: Basquin surfaces availability pathologies; it is not a throughput benchmark.

## Phasing (de-risk before burning hours)

- **Phase 0 — de-risk builds.** Build each WAR, deploy with the valve, confirm findings flow on a
  ~2-min smoke run. Decide Roller in/out here. No long runs until this is green per app.
- **Phase 1 — instrument.** Per-app grammar + seed corpus; the periodic sampler; a short guided run
  confirming coverage climbs and findings land.
- **Phase 2 — timed runs.** The ~1-hour baseline/guided/soak sequence per app (background).
- **Phase 3 — comparison.** k6 runs on the same workloads.
- **Phase 4 — report.** dataviz HTML: cross-app table + per-app charts + k6 panel + methodology;
  commit the `docs/` page, produce the Artifact.

## Open risks

- **App buildability** (JSPWiki/Roller heavier than JPetStore) — mitigated by Phase 0 + fallback.
- **Wall-clock cost** — multi-hour campaign; runs go in background; report can be built
  incrementally as apps complete (JPetStore + JSPWiki alone is a complete story; Roller augments).
- **Threshold tuning** — too-tight thresholds = noise, too-loose = nothing found; tune in Phase 1.
- **k6 apples-to-oranges** — mitigated by the explicit caveat + leading with Tier-1 metrics.
