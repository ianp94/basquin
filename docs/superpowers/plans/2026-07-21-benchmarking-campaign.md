# Basquin Benchmarking Campaign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a data-backed findings report demonstrating Basquin against real unmodified
CMS-shaped JVM apps, with staged-rigor comparison, and package it as a `docs/` page + shareable Artifact.

**Architecture:** A phased campaign — de-risk builds, add one small code component (a periodic
time-series sampler), run per-app timed campaigns (naive → guided → soak), run a caveated k6
comparison, then build a dataviz report from the captured data. Only real numbers.

**Tech Stack:** Java 17 / Gradle (harness), Docker Compose + Tomcat 9/10 (app deploy via the valve),
Maven 3.9 (build third-party WARs), k6 (comparison), dataviz skill (report).

## Global Constraints

- **No fabricated numbers** — every figure in the report comes from a real run; dropped apps/failed
  runs are stated, not hidden.
- **App versions pinned** to a commit/release (like `JPETSTORE_SHA`), recorded in the report.
- **Invariant thresholds documented** per app; runs reproducible from committed commands.
- **k6 comparison labeled indicative**, not a controlled head-to-head.
- **Apps run unmodified** via the Tomcat valve (never the in-WAR filter — they are mutually exclusive).
- **Branch-per-change + PR + Claude review** (repo workflow); spec at
  `docs/superpowers/specs/2026-07-21-benchmarking-campaign-design.md`.

---

## Phase 0 — De-risk builds (no new code; gate before any long run)

### Task 0.1: Build + smoke JSPWiki with the valve

**Files:**
- Create: `deploy/bench/jspwiki/` (compose + any config), `deploy/bench/README.md`

- [ ] **Step 1: Pin + build the WAR.** Pick a JSPWiki release tag; `git clone --depth 1 --branch <tag>`;
  build with Maven 3.9 (install as `operator-e2e.yml` does). Determine servlet namespace
  (`unzip -l *.war | grep -E 'jakarta|javax'` + `web.xml` root) → Tomcat 9 (`javax`) or 10 (`jakarta`).
- [ ] **Step 2: Configure a filesystem page store** (VersioningFileProvider) so no external DB is needed;
  mount a seeded pages dir so routes have content to render.
- [ ] **Step 3: Deploy with the valve** — adapt `docker-compose.valve9.yml`/`valve.yml`, `WAR_PATH=` the
  built WAR, mount `deploy/valve/context.xml`, inject the agent via `CATALINA_OPTS`.
- [ ] **Step 4: Smoke run (~2 min).** `curl` several real routes (`/Wiki.jsp?page=Main`, search, edit
  preview). **Verify:** `Basquin Agent initialized` in the log, WAR deploys with no `SEVERE`, routes
  serve 200, and `X-Basquin-Invariant-*` headers / `[Basquin][Invariant]` log lines appear.
- [ ] **Step 5: Gate.** If green, JSPWiki is IN. Record the tag, namespace, and threshold starting point
  in `deploy/bench/README.md`. Commit.

### Task 0.2: Build + smoke Roller (or DB-backed substitute) with the valve

**Files:**
- Create: `deploy/bench/roller/` (compose with a DB container)

- [ ] **Step 1: Pin + build Roller** (Maven 3.9). If Roller's build fights within ~1 bounded attempt,
  invoke the fallback order from the spec (lighter DB-backed CMS → file-store CMS → drop to 2 apps) and
  record the swap in `deploy/bench/README.md`.
- [ ] **Step 2: Orchestrate the backend** — Compose with the app on Tomcat + valve + agent alongside a DB
  container (Derby/MySQL/Postgres per the app). Seed enough content to render non-trivial pages.
- [ ] **Step 3: Smoke run (~2 min)** — same verification as 0.1, plus confirm DB-backed routes serve
  (a page that hits the DB).
- [ ] **Step 4: Gate + commit.** Green → IN (this is the DB-driven-pathology target). Not green after the
  fallback → ship 2 apps, note it. Record decision in the README.

**Phase 0 exit:** JPetStore (known-good) + JSPWiki + (Roller or substitute or none) each confirmed to
deploy + emit findings. No long runs before this.

---

## Phase 1 — Periodic time-series sampler + per-app exploration setup

### Task 1.1: Add `StatusReporter` CSV time-series sampler (TDD)

**Files:**
- Modify: `runner/util/StatusReporter.java`
- Test: `test/StatusSamplerTest.java`

**Interfaces:**
- Produces: `static String sampleHeader()` (CSV header) and
  `static synchronized String sampleLine(long elapsedMs)` (one CSV row from the current counters:
  `elapsedMs,iterations,itersPerSec,lastLatencyMs,meanLatencyMs,maxLatencyMs,lastHeapKb,maxHeapKb,threads,crashes,leaks,violLatency,violHeap,violThread,coveredEdges,totalEdges,coveragePct`).
  Sampling is gated on `-Dbasquin.sample.out=<path>` (independent of `basquin.status`), interval
  `-Dbasquin.sample.intervalMs` (default 5000).

- [ ] **Step 1: Write the failing test.**
```java
import org.junit.Test;
import runner.util.StatusReporter;
import static org.junit.Assert.*;

public class StatusSamplerTest {
    @Test public void sampleLineHasAllColumnsInHeaderOrder() {
        String header = StatusReporter.sampleHeader();
        String line = StatusReporter.sampleLine(1000L);
        assertEquals(header.split(",").length, line.split(",").length);
        assertTrue(header.startsWith("elapsedMs,iterations,"));
        // elapsedMs is column 0, echoed back verbatim
        assertEquals("1000", line.split(",")[0]);
    }
}
```
- [ ] **Step 2: Run to verify it fails.** `./gradlew test --tests StatusSamplerTest` → FAIL (methods missing).
- [ ] **Step 3: Implement `sampleHeader()`/`sampleLine()`** on `StatusReporter` reading the existing
  static counters (compute `itersPerSec` from `elapsedMs`, `meanLatencyMs` from `sumLatencyMs/iterations`
  guarding div-by-zero, `coveragePct` from `coveredEdges/totalEdges`). Add a sampler daemon thread started
  when `basquin.sample.out` is set: write `sampleHeader()` once, then append `sampleLine()` every interval,
  flushing each write; register a shutdown-hook final row. Start it from `ensureStarted()` (relax the
  `ENABLED` guard so the sampler runs even without the TTY box).
- [ ] **Step 4: Run to verify it passes + full suite green.** `./gradlew test --tests StatusSamplerTest`
  then `./gradlew test`.
- [ ] **Step 5: Commit** (`feat(runner): periodic CSV time-series sampler`), open PR, request `@claude` review.

### Task 1.2: Per-app request grammar + seed corpus, and a guided confirmation run

**Files:**
- Create: `examples/grammar/jspwiki.grammar`, `examples/corpus/jspwiki/` (+ `roller` if IN)

- [ ] **Step 1:** Author a grammar covering the app's real routes (view/search/edit/attach for a wiki;
  entry/comment/archive for a blog), with value files under `corpus/<app>/values/` (globally-unique
  basenames — DD-018). Follow `examples/grammar/jpetstore.grammar` structure.
- [ ] **Step 2:** Short guided run (~3 min) against the Phase-0 deployment with the sampler on:
  `runHttpDriveCoverage`-style, `-Dbasquin.grammar=`, `-Dbasquin.corpusDir=`, `-Dbasquin.sample.out=`.
  **Verify:** coverage climbs vs. the first sample, findings land in `fuzz-results/<app>/`, the sample CSV
  has increasing rows. Tune thresholds so findings are meaningful (not every request).
- [ ] **Step 3: Commit** the grammar/corpus; record tuned thresholds in `deploy/bench/README.md`.

---

## Phase 2 — Timed runs (per app; background)

### Task 2.N: One ~1-hour campaign per app (JPetStore, JSPWiki, [Roller])

**Files:**
- Create: `bench-results/<app>/` (samples CSVs + copied `fuzz-results/<app>/` + a `run.json` manifest)

- [ ] **Step 1: Naive baseline (~5 min).** Round-robin driver (no grammar guidance), sampler →
  `bench-results/<app>/naive.csv`. Captures the coverage floor.
- [ ] **Step 2: Coverage-guided explore (~25 min).** Grammar + corpus, sampler →
  `bench-results/<app>/guided.csv`; findings accumulate in `fuzz-results/<app>/`.
- [ ] **Step 3: Load/soak replay (~30 min).** Replay the interesting corpus at fixed concurrency, sampler →
  `bench-results/<app>/soak.csv`.
- [ ] **Step 4: Snapshot** the run: copy `fuzz-results/<app>/`, write `run.json` (app, pinned version,
  namespace, thresholds, phase durations, concurrency, start/stop timestamps). **Verify** each CSV is
  non-empty and monotonic in `elapsedMs`; commit `bench-results/<app>/`.

(Runs go in background; the report can be built from whichever apps finish — JPetStore + JSPWiki is a
complete story on its own.)

---

## Phase 3 — k6 comparison (per app)

### Task 3.N: k6 run on the same workload

**Files:**
- Create: `deploy/bench/k6/<app>.js`, `bench-results/<app>/k6.json`

- [ ] **Step 1:** Write a k6 script hitting the same route mix as the app's grammar (a virtual-user
  workload matching the soak concurrency). Install k6 if absent (single binary).
- [ ] **Step 2:** Run ~10 min; export `k6.json` summary (throughput, p50/p90/p99/max latency, error rate).
- [ ] **Step 3: Verify + commit.** Confirm the JSON has the percentile block; commit `bench-results/<app>/k6.json`.

---

## Phase 4 — Findings report (dataviz)

### Task 4.1: Build the report from captured data

**Files:**
- Create: `docs/benchmarks.html` (site page), `bench-results/report-data.json` (aggregated)
- Modify: nav/footer links across `docs/*.html`, `README.md` (badge/link)

- [ ] **Step 1: Aggregate.** Parse every `bench-results/<app>/{naive,guided,soak}.csv`, `fuzz-results/<app>/`
  (`.meta.txt`), `run.json`, and `k6.json` into `report-data.json`: cross-app table rows + per-app series.
  Compute **invariant-only findings** (invariant violations with no crash/5xx), guided-vs-naive coverage,
  distinct latency-spike routes, peak heap/req, real crashes.
- [ ] **Step 2: Load the dataviz skill**, then build `docs/benchmarks.html`: headline cross-app table;
  per-app latency-percentile, heap-over-time, and coverage-climb (guided vs naive) charts; the caveated k6
  side-by-side panel; a methodology section (pinned versions, thresholds, commands, k6 caveat). Theme-aware,
  self-contained.
- [ ] **Step 3: Wire** the page into the site nav/footer + a README link; **verify** every number traces to
  a `bench-results/` file (no hardcoded figures). Commit, open PR, request `@claude` review.
- [ ] **Step 4: Publish the Artifact.** Load `artifact-design`, render `docs/benchmarks.html` as a shareable
  Artifact for the pitch.

---

## Self-review notes

- **Spec coverage:** targets (0.1/0.2), run design (2.N), sampler/data capture (1.1), grammars (1.2),
  Tier-1 metrics + Tier-2 k6 (3.N, 4.1), deliverable both homes (4.1), phasing + honesty (Global
  Constraints + each phase gate). All spec sections map to a task.
- **No fabricated data:** Phase 4 Step 3 explicitly verifies every figure traces to a `bench-results/` file.
- **Resumability:** each phase commits its artifacts, so a fresh session resumes from the last committed
  `bench-results/` / `deploy/bench/` state.
