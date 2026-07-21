# Cost-ranked replay corpus (DD-031) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure each explored input's cost (latency + best-effort target heap/thread/invariants) and hand load a replay corpus ranked by cost, so DD-029 lock-free load concentrates on the states that hurt.

**Architecture:** A new `X-Basquin-Cost` response header (from the shared `RequestBoundary`) carries target-side per-request cost; the driver combines it with its own round-trip latency into one number via `CostModel`; a `CostCorpus` pool holds `CorpusEntry` items, expands retention to expensive inputs (bounded, coverage-preserving eviction), and emits the replay corpus cost-ranked. Exploration selection stays uniform.

**Tech Stack:** Java 17, JUnit 4, Gradle; the runner (`runner/coverage`) and agent modules; bash e2e.

## Global Constraints

- Exploration parent **selection stays uniform** — this changes retention + replay ordering, not selection weighting.
- **Concurrency:** eviction is the corpus's first *removal*; the parent reads at `CoverageGuidedRun.java:204,208` are unsynchronized (safe only while grow-only). All corpus reads/writes go through `CostCorpus`, which synchronizes internally — no bare `corpus.get(rnd.nextInt(size))`.
- **Cold start:** cost-retention activates only after `basquin.cost.minSamples` (default 20) observations; the baseline is an **EMA** (`basquin.cost.emaAlpha`, default 0.1), never a cumulative mean.
- **Attributability:** the target cost statics are read in `RequestBoundary.onExit` **before** `ITERATION_LOCK` is released (where the invariant header is already computed).
- **Kill-switch is driver-side:** the boundary emits `X-Basquin-Cost` unconditionally (a target property, inert if unread). `basquin.cost.enabled=false` makes the *driver* skip reading it, cost computation, expanded retention, eviction, and cost-ranked replay — restoring today's behavior exactly (the A/B baseline).
- **Coverage-find entries are never evicted.**
- Replay **ConfigMap format unchanged** (plain route lines) → no operator/`LoadRun` change.
- Java 17 bytecode; the runner classes live in the `coverage` source set; unit tests in `test/` reach it via `testImplementation sourceSets.coverage.output` (already wired in `build.gradle`).
- Bot-authored branch `feat/cost-guided-replay` (already created). `gradlew` on this checkout has CRLF — normalize a copy to run builds, never stage a change to it.

## File Structure

- `runner/coverage/CostModel.java` (new) — pure cost scoring from `-D` coefficients.
- `runner/coverage/CorpusEntry.java` (new) — immutable corpus entry: input + cost + components + `coverageFind`.
- `runner/coverage/CostCorpus.java` (new) — the pool: uniform parent draw, expanded+gated retention, bounded coverage-preserving eviction, cost-ranked replay ordering; all synchronized; kill-switch-aware.
- `agent/Agent.java` (modify) — publish the last iteration's latency/heap/thread cost statics.
- `agent/RequestBoundary.java` (modify) — emit `X-Basquin-Cost` on the `EXPLORE_BEGAN` exit path.
- `test/RequestBoundaryTest.java` (modify) — assert the cost header shape/gating.
- `runner/coverage/CoverageGuidedRun.java` (modify) — parse the cost header, compute cost, drive `CostCorpus`, emit cost-ranked replay.
- `test/CostModelTest.java`, `test/CostCorpusTest.java` (new) — unit tests.
- `deploy/e2e/e2e.sh`, `docs/DESIGN-DECISIONS.md`, `docs/USAGE.md` or `OPERATOR-USAGE.md`, component `CHANGELOG.md` (modify).

---

### Task 1: `CostModel` — pure cost scoring

**Files:** Create `runner/coverage/CostModel.java`; Test `test/CostModelTest.java`.

**Interfaces:**
- Produces: `static double CostModel.score(double latencyMs, double heapDeltaKb, int threadDelta, int invariantCount)`. Used by Tasks 3 (indirectly, tests) and 6.

- [ ] **Step 1: Write the failing test** — `test/CostModelTest.java`

```java
package test;

import runner.coverage.CostModel;
import org.junit.Assert;
import org.junit.Test;

public class CostModelTest {
    @Test public void monotonicInEachComponent() {
        double base = CostModel.score(10, 100, 0, 0);
        Assert.assertTrue(CostModel.score(20, 100, 0, 0) > base);   // latency up
        Assert.assertTrue(CostModel.score(10, 200, 0, 0) > base);   // heap up
        Assert.assertTrue(CostModel.score(10, 100, 1, 0) > base);   // thread leak
        Assert.assertTrue(CostModel.score(10, 100, 0, 1) > base);   // invariant hit
    }
    @Test public void heapGrowerBeatsFastCleanRequest() {
        double fastClean = CostModel.score(5, 0, 0, 0);
        double slowGrower = CostModel.score(5, 8192, 0, 0); // +8MB
        Assert.assertTrue(slowGrower > fastClean);
    }
    @Test public void negativeDeltasDoNotReduceCost() {
        // a request that freed memory / dropped threads must not score BELOW a neutral one
        Assert.assertTrue(CostModel.score(10, -5000, -3, 0) >= CostModel.score(10, 0, 0, 0));
    }
    @Test public void invariantAndThreadAreHeavilyWeighted() {
        Assert.assertTrue(CostModel.score(0, 0, 0, 1) >= 500);   // an invariant hit is a big deal
        Assert.assertTrue(CostModel.score(0, 0, 1, 0) >= 250);   // a leaked thread too
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew test --tests 'test.CostModelTest'` → FAIL (no `CostModel`).

- [ ] **Step 3: Implement** — `runner/coverage/CostModel.java`

```java
package runner.coverage;

/**
 * Turns a request's observed cost components into one comparable number (DD-031). Higher = more
 * "expensive" = more worth hammering under load. Coefficients are -D overridable; defaults weight the
 * rare, high-signal events (invariant hits, leaked threads) far above ordinary latency, and heap growth
 * — the availability thesis — above latency too. Pure and stateless.
 */
public final class CostModel {

    private static final double C_LAT    = dbl("basquin.cost.latencyWeight", 1.0);
    private static final double C_HEAP   = dbl("basquin.cost.heapWeight", 0.0625);   // per KB (16KB => 1)
    private static final double C_THREAD = dbl("basquin.cost.threadWeight", 500.0);  // per leaked thread
    private static final double C_INV    = dbl("basquin.cost.invariantWeight", 1000.0);

    private CostModel() {}

    /** Negative deltas (freed heap / dropped threads) contribute 0, never a discount. */
    public static double score(double latencyMs, double heapDeltaKb, int threadDelta, int invariantCount) {
        return C_LAT    * Math.max(0.0, latencyMs)
             + C_HEAP   * Math.max(0.0, heapDeltaKb)
             + C_THREAD * Math.max(0,   threadDelta)
             + C_INV    * Math.max(0,   invariantCount);
    }

    private static double dbl(String key, double def) {
        try { String v = System.getProperty(key); return v == null ? def : Double.parseDouble(v); }
        catch (RuntimeException e) { return def; }
    }
}
```

- [ ] **Step 4: Run tests, verify pass** — `./gradlew test --tests 'test.CostModelTest'` → PASS (4 tests).

- [ ] **Step 5: Commit** — `git add runner/coverage/CostModel.java test/CostModelTest.java && git commit -m "feat(runner): CostModel — per-input cost scoring (DD-031)"`

---

### Task 2: `CorpusEntry` — a corpus entry carrying its cost

**Files:** Create `runner/coverage/CorpusEntry.java`.

**Interfaces:**
- Produces: `CorpusEntry(String input, double cost, long latencyMs, long heapDeltaKb, int threadDelta, int invariantCount, boolean coverageFind)` with public final fields. Used by Tasks 3 and 6.

- [ ] **Step 1: Implement** (no standalone test — it is a data holder exercised by `CostCorpusTest` in Task 3) — `runner/coverage/CorpusEntry.java`

```java
package runner.coverage;

/** An input in the exploration corpus plus the cost it produced when fired (DD-031). Immutable. */
public final class CorpusEntry {
    public final String input;
    public final double cost;
    public final long latencyMs;
    public final long heapDeltaKb;
    public final int threadDelta;
    public final int invariantCount;
    /** true = retained because it hit new coverage (never evicted); false = retained purely for cost. */
    public final boolean coverageFind;

    public CorpusEntry(String input, double cost, long latencyMs, long heapDeltaKb,
                       int threadDelta, int invariantCount, boolean coverageFind) {
        this.input = input;
        this.cost = cost;
        this.latencyMs = latencyMs;
        this.heapDeltaKb = heapDeltaKb;
        this.threadDelta = threadDelta;
        this.invariantCount = invariantCount;
        this.coverageFind = coverageFind;
    }
}
```

- [ ] **Step 2: Build** — `./gradlew compileCoverageJava` (or `jar`) → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit** — `git add runner/coverage/CorpusEntry.java && git commit -m "feat(runner): CorpusEntry — corpus item with its cost (DD-031)"`

---

### Task 3: `CostCorpus` — the pool (retention, eviction, ranking, concurrency, kill-switch)

**Files:** Create `runner/coverage/CostCorpus.java`; Test `test/CostCorpusTest.java`.

**Interfaces:**
- Consumes: `CorpusEntry` (Task 2).
- Produces:
  - `new CostCorpus(List<String> seeds, boolean enabled)` — seeds are coverage-finds; reads `-D` for max/retainFactor/minSamples/emaAlpha.
  - `String randomParentInput(java.util.Random rnd)` — synchronized uniform draw of an entry's input.
  - `void consider(String input, double cost, long latencyMs, long heapDeltaKb, int threadDelta, int invariantCount, boolean coverageFind)` — synchronized retention (+ EMA/min-sample gate) + eviction.
  - `java.util.List<CorpusEntry> snapshotByCost()` — synchronized copy, cost-desc when enabled, else insertion order.
  - `int size()`.
  Used by Task 6.

- [ ] **Step 1: Write the failing test** — `test/CostCorpusTest.java`

```java
package test;

import runner.coverage.CorpusEntry;
import runner.coverage.CostCorpus;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class CostCorpusTest {

    private static CostCorpus enabled(String... seeds) {
        System.setProperty("basquin.cost.minSamples", "3");
        System.setProperty("basquin.cost.retainFactor", "2.0");
        System.setProperty("basquin.corpus.max", "5");
        System.setProperty("basquin.cost.emaAlpha", "0.5");
        return new CostCorpus(Arrays.asList(seeds), true);
    }

    @Test public void seedsAreCoverageFindsAndSelectable() {
        CostCorpus c = enabled("/a", "/b");
        Assert.assertEquals(2, c.size());
        String p = c.randomParentInput(new Random(1));
        Assert.assertTrue(p.equals("/a") || p.equals("/b"));
    }

    @Test public void coverageFindIsAlwaysRetained() {
        CostCorpus c = enabled("/a");
        c.consider("/new", 0.0, 1, 0, 0, 0, true);   // zero cost but a coverage find
        Assert.assertEquals(2, c.size());
    }

    @Test public void costRetentionGatedByMinSamplesThenThreshold() {
        CostCorpus c = enabled("/a");
        // first minSamples(3) non-coverage costs: NONE retained (cold start), but they train the EMA
        c.consider("/c1", 10, 1, 0, 0, 0, false);
        c.consider("/c2", 10, 1, 0, 0, 0, false);
        c.consider("/c3", 10, 1, 0, 0, 0, false);
        Assert.assertEquals("cold-start: no cost retention yet", 1, c.size());
        // now a cheap one (below 2x EMA≈10) is NOT retained...
        c.consider("/cheap", 12, 1, 0, 0, 0, false);
        Assert.assertEquals(1, c.size());
        // ...an expensive one (> 2x EMA) IS retained
        c.consider("/expensive", 100, 1, 0, 0, 0, false);
        Assert.assertEquals(2, c.size());
    }

    @Test public void overCapEvictsCheapestCostFindNeverACoverageFind() {
        CostCorpus c = enabled("/cov");                 // 1 coverage-find, cap=5
        // train EMA past cold start with modest costs, then add expensive cost-finds
        for (int i = 0; i < 3; i++) c.consider("/t" + i, 5, 1, 0, 0, 0, false); // cold-start, not retained
        // add cost-finds well above 2x EMA(≈5): costs 50,60,70,80,90 — cap=5 so evictions happen
        int[] costs = {50, 60, 70, 80, 90, 100};
        for (int i = 0; i < costs.length; i++) c.consider("/e" + i, costs[i], 1, 0, 0, 0, false);
        Assert.assertTrue("bounded at cap", c.size() <= 5);
        List<CorpusEntry> snap = c.snapshotByCost();
        // the coverage-find survives despite being the cheapest (cost 0)
        Assert.assertTrue(snap.stream().anyMatch(e -> e.input.equals("/cov") && e.coverageFind));
        // the cheapest retained cost-find (/e0=50) was evicted in favor of dearer ones
        Assert.assertFalse(snap.stream().anyMatch(e -> e.input.equals("/e0")));
    }

    @Test public void snapshotIsCostDescendingWhenEnabled() {
        CostCorpus c = enabled("/a");
        for (int i = 0; i < 3; i++) c.consider("/w" + i, 10, 1, 0, 0, 0, false); // train EMA
        c.consider("/mid", 40, 1, 0, 0, 0, false);
        c.consider("/hi", 90, 1, 0, 0, 0, false);
        List<CorpusEntry> snap = c.snapshotByCost();
        for (int i = 1; i < snap.size(); i++) {
            Assert.assertTrue("cost descending", snap.get(i - 1).cost >= snap.get(i).cost);
        }
    }

    @Test public void disabledBehavesLikeTodayInsertionOrderCoverageOnly() {
        CostCorpus c = new CostCorpus(Arrays.asList("/a"), false);
        c.consider("/expensive", 9999, 1, 9999, 5, 5, false); // huge cost but NOT a coverage find
        Assert.assertEquals("disabled: cost never retains", 1, c.size());
        c.consider("/cov", 0, 1, 0, 0, 0, true);
        List<CorpusEntry> snap = c.snapshotByCost();
        Assert.assertEquals("insertion order preserved when disabled", "/a", snap.get(0).input);
        Assert.assertEquals("/cov", snap.get(1).input);
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew test --tests 'test.CostCorpusTest'` → FAIL (no `CostCorpus`).

- [ ] **Step 3: Implement** — `runner/coverage/CostCorpus.java`

```java
package runner.coverage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The exploration corpus as cost-bearing entries (DD-031). Encapsulates: uniform parent selection,
 * expanded retention (coverage OR notably-expensive, gated by a cold-start minimum and an EMA baseline),
 * bounded size with coverage-preserving eviction, and cost-ranked replay ordering. ALL access is
 * synchronized on this instance — eviction can shrink the list, so unsynchronized index reads would
 * race (the whole reason this class exists rather than a bare List). When {@code enabled} is false it
 * behaves exactly like today's grow-only, coverage-only, insertion-ordered corpus.
 */
public final class CostCorpus {

    private final List<CorpusEntry> entries = new ArrayList<>();
    private final boolean enabled;
    private final int maxSize;
    private final double retainFactor;
    private final int minSamples;
    private final double emaAlpha;

    private double emaCost = 0.0;   // EMA baseline of observed cost (heavy-tail-robust vs a mean)
    private int samples = 0;

    public CostCorpus(List<String> seeds, boolean enabled) {
        this.enabled = enabled;
        this.maxSize = Integer.getInteger("basquin.corpus.max", 1000);
        this.retainFactor = dbl("basquin.cost.retainFactor", 3.0);
        this.minSamples = Integer.getInteger("basquin.cost.minSamples", 20);
        this.emaAlpha = dbl("basquin.cost.emaAlpha", 0.1);
        if (seeds != null) {
            for (String s : seeds) entries.add(new CorpusEntry(s, 0.0, 0, 0, 0, 0, true));
        }
    }

    /** Uniform parent draw (selection is NOT cost-biased in this phase). Synchronized with mutation. */
    public synchronized String randomParentInput(Random rnd) {
        if (entries.isEmpty()) return "/";
        return entries.get(rnd.nextInt(entries.size())).input;
    }

    /**
     * Offer a just-fired input. Coverage finds are always retained. When enabled, a non-coverage input
     * is retained iff cost-retention is active (>= minSamples observed) and cost exceeds retainFactor ×
     * the EMA baseline. Every enabled observation trains the EMA. Over-cap triggers eviction.
     */
    public synchronized void consider(String input, double cost, long latencyMs, long heapDeltaKb,
                                      int threadDelta, int invariantCount, boolean coverageFind) {
        boolean retain = coverageFind;
        if (enabled && !coverageFind) {
            samples++;
            boolean active = samples > minSamples;      // cold-start guard
            if (active && emaCost > 0 && cost > retainFactor * emaCost) retain = true;
            // train the EMA AFTER the threshold check so an input isn't measured against itself
            emaCost = emaCost == 0.0 ? cost : emaAlpha * cost + (1 - emaAlpha) * emaCost;
        }
        if (!retain) return;
        entries.add(new CorpusEntry(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind));
        if (enabled) evictIfOverCap();
    }

    /** Remove the cheapest NON-coverage entry when over the cap. Coverage finds are never evicted. */
    private void evictIfOverCap() {
        while (entries.size() > maxSize) {
            int victim = -1;
            double cheapest = Double.MAX_VALUE;
            for (int i = 0; i < entries.size(); i++) {
                CorpusEntry e = entries.get(i);
                if (!e.coverageFind && e.cost < cheapest) { cheapest = e.cost; victim = i; }
            }
            if (victim < 0) break;   // only coverage-finds remain — stop (the cap yields to coverage)
            entries.remove(victim);
        }
    }

    /** A copy for replay emission: cost-descending when enabled, else insertion order (today's behavior). */
    public synchronized List<CorpusEntry> snapshotByCost() {
        List<CorpusEntry> copy = new ArrayList<>(entries);
        if (enabled) copy.sort(Comparator.comparingDouble((CorpusEntry e) -> e.cost).reversed());
        return copy;
    }

    public synchronized int size() { return entries.size(); }

    private static double dbl(String key, double def) {
        try { String v = System.getProperty(key); return v == null ? def : Double.parseDouble(v); }
        catch (RuntimeException e) { return def; }
    }
}
```

- [ ] **Step 4: Run tests, verify pass** — `./gradlew test --tests 'test.CostCorpusTest'` → PASS (6 tests). Then `./gradlew test` (full suite green).

- [ ] **Step 5: Commit** — `git add runner/coverage/CostCorpus.java test/CostCorpusTest.java && git commit -m "feat(runner): CostCorpus — bounded, cost-ranked, concurrency-safe corpus (DD-031)"`

---

### Task 4: Publish the target's last-iteration cost from `Agent`

**Files:** Modify `agent/Agent.java`.

**Interfaces:**
- Produces: `Agent.lastCostCsv()` → `"<latencyMs>,<heapDeltaKb>,<threadDelta>"` for the just-ended iteration. Used by Task 5.

- [ ] **Step 1: Add cost statics + getter, populated where invariants are** — `agent/Agent.java`

Next to `lastInvariantViolations`/`lastInvariantStack` (declared ~line 46), add:

```java
    private static volatile long lastLatencyMs = 0L;
    private static volatile long lastHeapDeltaKb = 0L;
    private static volatile int lastThreadDelta = 0;
```

In `publishInvariantEvidence(IterationContext ctx)` (~line 402), add alongside the existing assignments:

```java
        lastLatencyMs = ctx.latencyMs;
        lastHeapDeltaKb = ctx.heapDeltaBytes / 1024L;
        lastThreadDelta = ctx.threadDelta;
```

After `getLastInvariantStack()`, add the getter:

```java
    /** The just-ended iteration's cost, "latencyMs,heapDeltaKb,threadDelta" (DD-031). Read under the
     *  iteration lock (before the next begin() overwrites it) for per-request attributability. */
    public static String lastCostCsv() {
        return lastLatencyMs + "," + lastHeapDeltaKb + "," + lastThreadDelta;
    }
```

Confirm `publishInvariantEvidence` is called from `end(ctx)` (so these are set on every ended iteration). If it is not already, call it from `end` after `ctx` is populated — check the existing call site rather than assume.

- [ ] **Step 2: Build** — `./gradlew jar` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit** — `git add agent/Agent.java && git commit -m "feat(agent): publish last-iteration cost (latency/heap/thread) for the boundary (DD-031)"`

---

### Task 5: Emit `X-Basquin-Cost` from the boundary

**Files:** Modify `agent/RequestBoundary.java`; Test `test/RequestBoundaryTest.java`.

**Interfaces:**
- Consumes: `Agent.lastCostCsv()` (Task 4).
- Produces: an `X-Basquin-Cost` header in the `EXPLORE_BEGAN` exit path; none otherwise.

- [ ] **Step 1: Add the failing test** — append to `test/RequestBoundaryTest.java`

```java
    @Test public void exploreExitEmitsCostHeader() {
        LoadMode.setExplore();
        RequestBoundary.onEnter("/app/page", null);           // EXPLORE_BEGAN
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
        String cost = r.headers.get("X-Basquin-Cost");
        Assert.assertNotNull("explore exit must emit X-Basquin-Cost", cost);
        Assert.assertTrue("cost is latencyMs,heapDeltaKb,threadDelta", cost.matches("^-?\\d+,-?\\d+,-?\\d+$"));
    }

    @Test public void loadAndControlEmitNoCostHeader() {
        LoadMode.setLoad(60_000L);
        RequestBoundary.onEnter("/app", null);                 // LOAD_PASSTHROUGH
        Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Cost"));
        LoadMode.setExplore();
        RequestBoundary.onEnter("/__basquin/drift", null);     // CONTROL_HANDLED
        Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Cost"));
    }
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew test --tests 'test.RequestBoundaryTest'` → FAIL (no cost header).

- [ ] **Step 3: Implement** — in `agent/RequestBoundary.java`, replace the body of `invariantHeaders()` so it ALWAYS builds a map when there is anything to report and adds the cost header. Rename to `exitHeaders()` for accuracy and update the single call site in `onExit`:

```java
    /** Headers to set on the EXPLORE exit: invariant evidence (if any) + the always-present cost header.
     *  Read here — before onExit releases ITERATION_LOCK — so the numbers belong to THIS request. */
    private static Map<String, String> exitHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        try {
            List<String> violations = Agent.getLastInvariantViolations();
            if (violations != null && !violations.isEmpty()) {
                h.put("X-Basquin-Invariant-Count", String.valueOf(violations.size()));
                String first = violations.get(0);
                if (first != null) {
                    if (first.length() > 200) first = first.substring(0, 200);
                    h.put("X-Basquin-Invariant-Detail", first);
                }
            }
        } catch (Throwable ignored) {
            // invariant reporting is best-effort
        }
        try {
            h.put("X-Basquin-Cost", Agent.lastCostCsv());
        } catch (Throwable ignored) {
            // cost reporting is best-effort
        }
        return h.isEmpty() ? NO_HEADERS : h;
    }
```

Update the call in `onExit`'s `finally`: `headers = exitHeaders();` (was `invariantHeaders()`).

- [ ] **Step 4: Run tests, verify pass** — `./gradlew test --tests 'test.RequestBoundaryTest'` → PASS (all, incl. the 2 new). Then `./gradlew test` full suite green.

- [ ] **Step 5: Commit** — `git add agent/RequestBoundary.java test/RequestBoundaryTest.java && git commit -m "feat(agent): emit X-Basquin-Cost on the explore boundary exit (DD-031)"`

---

### Task 6: Drive the corpus by cost in `CoverageGuidedRun`

**Files:** Modify `runner/coverage/CoverageGuidedRun.java`.

**Interfaces:**
- Consumes: `CostModel` (T1), `CorpusEntry` (T2), `CostCorpus` (T3), the `X-Basquin-Cost` header (T5).
- Produces: cost-driven retention + cost-ranked replay; `lastCorpus` becomes a `CostCorpus`. `request()` returns a `CostSample`.

- [ ] **Step 1: Add a `CostSample` holder + make `request()` return it (parsing both target headers)**

Add a small static nested class and change `request`'s signature from `void` to `CostSample`. It still throws on 500 (crash path unchanged); on a normal return it reports the target-side components (0 when a header is absent — best-effort delivery):

```java
    /** Target-side cost components parsed from response headers (0 when a header wasn't sent). */
    static final class CostSample {
        final long heapDeltaKb; final int threadDelta; final int invariantCount;
        CostSample(long h, int t, int inv) { heapDeltaKb = h; threadDelta = t; invariantCount = inv; }
        static final CostSample EMPTY = new CostSample(0, 0, 0);
    }
```

In `request(...)`, after the existing `X-Basquin-Invariant-Count` block, parse the invariant count and the cost header, and return a `CostSample` at the end (replace the trailing `if (code >= 500) throw ...;` so the throw stays but the normal path returns):

```java
        int invCount = 0;
        String inv = c.getHeaderField("X-Basquin-Invariant-Count");
        if (inv != null) {
            try { invCount = Integer.parseInt(inv.trim()); } catch (NumberFormatException ignored) {}
            String detail = c.getHeaderField("X-Basquin-Invariant-Detail");
            FuzzIO.saveWithMeta(path.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + path + "\ncount=" + inv + (detail != null ? "\ndetail=" + detail : ""));
        }
        long heapKb = 0; int threadDelta = 0;
        String costHdr = c.getHeaderField("X-Basquin-Cost");  // "latencyMs,heapDeltaKb,threadDelta"
        if (costHdr != null) {
            String[] p = costHdr.split(",");
            if (p.length == 3) {
                try { heapKb = Long.parseLong(p[1].trim()); threadDelta = Integer.parseInt(p[2].trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        // ...(existing body-read + 500 throw stay here)...
        if (code >= 500) {
            throw serverError(code, path, body.toString());
        }
        return new CostSample(heapKb, threadDelta, invCount);
```

Update the OTHER callers of `request()` (`login`, `runSequence`, any warm-up) to ignore the return — they already call it as a statement, so no change is needed unless a caller is in an expression context (grep `request(` to confirm; they are all statements).

- [ ] **Step 2: Replace the `List<String> corpus` with a `CostCorpus`; change `lastCorpus` type**

- At the declaration (`~line 148`): 
```java
        boolean costEnabled = !"false".equals(System.getProperty("basquin.cost.enabled", "true"));
        CostCorpus corpus = new CostCorpus(seeds, costEnabled);
        lastCorpus = corpus;
```
- Change the static field (`~line 252`): `static volatile CostCorpus lastCorpus;` (was `List<String>`).
- Sequence branch parent-independent add (`~line 192`): replace `synchronized (corpus) { corpus.add(sequence.get(...)); }` with:
```java
                    corpus.consider(sequence.get(sequence.size() - 1), 0.0, 0, 0, 0, 0, true);
```
  (a sequence coverage-find; cost of a whole sequence isn't a single request, so record it as a coverage-find with zero cost — retained, never evicted.)
- Parent reads (`~line 204` and `~line 208`): replace both `corpus.get(rnd.nextInt(corpus.size()))` with `corpus.randomParentInput(rnd)`.
- Final `printf` (`~line 258`): `corpus.size()` still works (method exists).

- [ ] **Step 3: Capture cost in the fire block and drive retention through `CostCorpus`**

Replace the fire+retention block (the `Agent.beginIteration()` … through the `if (covered > best)` block, `~lines 217-229`) with:

```java
            Agent.beginIteration();
            double cost = 0.0; boolean measured = false; long latMs = 0;
            CostSample sample = CostSample.EMPTY;
            long t0 = System.nanoTime();
            try {
                sample = request(baseUrl, input);
                latMs = (System.nanoTime() - t0) / 1_000_000L;
                if (costEnabled) {
                    cost = CostModel.score(latMs, sample.heapDeltaKb, sample.threadDelta, sample.invariantCount);
                    measured = true;
                }
            } catch (Throwable t) {
                StatusReporter.recordCrash();
                FuzzIO.saveInteresting(input.getBytes(StandardCharsets.UTF_8), t);
            } finally {
                Agent.endIteration();
            }

            long covered = sampleCoverage(cov);
            total = lastCoverageTotal;
            boolean coverageFind = covered > best;
            if (coverageFind) best = covered;
            if (coverageFind) StatusReporter.recordSaved("Coverage");
            // Offer the input to the pool: coverage finds always retained; when cost was measured,
            // the pool also retains notably-expensive inputs (cold-start + EMA gated) and evicts.
            if (coverageFind || measured) {
                corpus.consider(input, cost, latMs, sample.heapDeltaKb, sample.threadDelta,
                        sample.invariantCount, coverageFind);
            }
```

(Note: retention is now inside `CostCorpus.consider`, which is synchronized — the old `synchronized (corpus) { corpus.add(...) }` is gone.)

- [ ] **Step 4: Emit the replay corpus cost-ranked** — update `writeSummary` and `replayCorpusJson`

In `writeSummary`, the snapshot section becomes (the pool already returns ordered entries; extract the inputs):
```java
            CostCorpus lc = lastCorpus;
            List<String> snapshot = new java.util.ArrayList<>();
            if (lc != null) {
                for (CorpusEntry e : lc.snapshotByCost()) snapshot.add(e.input);
            }
```
`replayCorpusJson(List<String> corpus, int maxBytes)` is UNCHANGED — it already dedups (via `LinkedHashSet`, which preserves the given order) and byte-budgets in the order given. Because `snapshotByCost()` returns cost-descending order (when enabled), the most expensive inputs now survive truncation. When disabled, `snapshotByCost()` returns insertion order → identical to today.

- [ ] **Step 5: Build + run the corpus/replay tests + full suite**

Run: `./gradlew test` — all green. If an existing test referenced `lastCorpus` as `List<String>` (the "combined-size test" mentioned at the field), update it to construct/read a `CostCorpus` (grep `lastCorpus` in `test/`). Add a focused test that with `basquin.cost.enabled=true` a high-cost input lands at the front of `snapshotByCost()`, and with `=false` the replay order is insertion order (kill-switch).

- [ ] **Step 6: Commit** — `git add runner/coverage/CoverageGuidedRun.java test/ && git commit -m "feat(runner): cost-driven retention + cost-ranked replay corpus (DD-031)"`

---

### Task 7: e2e proof + docs

**Files:** Modify `deploy/e2e/e2e.sh`, `docs/DESIGN-DECISIONS.md`, `docs/OPERATOR-USAGE.md` (or `USAGE.md`), `runner/CHANGELOG.md` + `agent/CHANGELOG.md`.

- [ ] **Step 1: e2e assertion** — in `deploy/e2e/e2e.sh`, in the explore-campaign block where `apod` is in scope (near the DD-030 checks added earlier), assert the target serves the cost header (proof the cost channel is live in-cluster via the DD-030 boundary):

```bash
  scost="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s -D - -o /dev/null http://localhost:8080/ | tr -d '\r' | awk -F': ' '/^X-Basquin-Cost/{print \$2}'" 2>/dev/null || true)"
  check "DD-031: boundary emits X-Basquin-Cost (latency,heap,thread) in-cluster" "echo '$scost' | grep -qE '^[0-9-]+,[0-9-]+,[0-9-]+$'"
  echo "  (cost header: ${scost:-<none>})"
```
(A request to `/` runs the explore boundary; the header is present regardless of whether a violation fired. If the app never serializes a plain `/` through the boundary in this campaign, assert instead against a corpus route already used by the existing checks.)

- [ ] **Step 2: Syntax check** — `bash -n deploy/e2e/e2e.sh && echo OK`.

- [ ] **Step 3: Docs** — add a DD-031 record to `docs/DESIGN-DECISIONS.md` (cost-ranked replay; why not true pheromone yet; the `-D` knobs + kill-switch A/B; references the spec). Add a short note to the usage guide that load now hammers the most expensive discovered states, tunable via `basquin.cost.*`. CHANGELOG Unreleased entries for `agent` (X-Basquin-Cost header) and `runner` (CostModel/CostCorpus, cost-ranked replay).

- [ ] **Step 4: Commit** — `git add deploy/e2e/e2e.sh docs/ agent/CHANGELOG.md runner/CHANGELOG.md && git commit -m "test(e2e)+docs: assert X-Basquin-Cost in-cluster; record DD-031"`

---

## Final step: open the PR

- [ ] Push and open a bot-authored PR "feat: cost-ranked replay corpus (DD-031)"; request review from @claude and the user; body explains the explore→load cost handoff, the driver-side kill-switch A/B, and that exploration selection is unchanged. The in-cluster e2e is the integration proof that the cost channel is live via the DD-030 boundary.

## Self-Review notes (author)

- **Spec coverage:** cost header (T4/T5), CostModel (T1), CorpusEntry (T2), CostCorpus with cold-start/EMA/eviction/concurrency/kill-switch (T3), integration + cost-ranked replay (T6), e2e + docs (T7). All spec sections tasked.
- **Constraint coverage:** uniform selection preserved (T6 uses `randomParentInput`, no weighting); concurrency handled by `CostCorpus` synchronization (T3, T6 removes bare reads); cold-start + EMA in `consider` (T3); attributability — cost read in `exitHeaders()` before unlock (T5); kill-switch driver-side (`costEnabled` gates cost compute + `CostCorpus(enabled=false)` gates retention/evict/ordering) (T3/T6); coverage-finds never evicted (T3 `evictIfOverCap`); ConfigMap unchanged (T6 keeps `replayCorpusJson` + plain lines).
- **Type consistency:** `CostSample(heapDeltaKb, threadDelta, invariantCount)`, `CostModel.score(latencyMs, heapDeltaKb, threadDelta, invariantCount)`, `CostCorpus.consider(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind)`, `snapshotByCost()→List<CorpusEntry>` — consistent across T1/T2/T3/T6. `Agent.lastCostCsv()` shape matches the header parsed in T6.
