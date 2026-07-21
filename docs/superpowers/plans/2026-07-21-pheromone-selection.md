# Cost-biased selection with credit assignment (DD-032) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in ε-greedy pheromone selection + immediate-parent credit assignment + evaporation to the exploration loop, biasing mutation toward parents that spawn expensive children — default off, A/B-instrumented.

**Architecture:** `CorpusEntry` gains a monitor-guarded `pheromone` field; `CostCorpus` gains `selectParent` (ε-greedy roulette, returns the entry), `reinforce` (capped deposit), `evaporate` (× decay), and pheromone-aware eviction, with an incrementally-maintained running total for O(1) weight bookkeeping. `CoverageGuidedRun` selects the parent entry, reinforces it before `consider`, evaporates on a fixed cadence, forces cost measurement on, and logs the seed + arm.

**Tech Stack:** Java 17, JUnit 4, Gradle; `runner/coverage`.

## Global Constraints

- **Default off.** `-Dbasquin.pheromone=off` (default) is byte-for-byte today's behavior: uniform selection, no reinforce/evaporate, DD-031 cost-based eviction, `emaCost`/pheromone untouched. `pheromone` is a SEPARATE flag from DD-031's `basquin.cost.enabled`.
- **ε is the coverage guardrail** (not the initial pheromone). Initial pheromone `= emaCost + cost` (EMA-relative, cross-app-meaningful); it is a tie-breaker among cheap entries only.
- **Sticky-spike defaults:** `decay=0.7`, `depositCap=10` (× EMA). A single deposit is `min(childCost, depositCap × emaCost)`.
- **Pheromone-aware eviction when on:** evict the min-`pheromone` non-coverage entry (min-`cost` when off); coverage-finds never evicted.
- **Reinforce runs BEFORE the child's `consider`.** consider is the only evictor; because selection→fire→reinforce→consider is one single-threaded iteration, the parent is always still live at reinforce — no `contains` check needed AND the running total stays exact. **Do not reorder reinforce after consider** (a stale parent would drift the total).
- **`pheromone=on` forces cost measurement on** (`costEnabled=true`) and logs `[Basquin] pheromone=on forces cost measurement on` — no silent partial state.
- **`-Dbasquin.seed`** (default 1) drives the RNG (today hardcoded `new Random(1)`) and is logged in the end-of-run line.
- **Evaporation cadence is deterministic:** the `evaporate()` check sits at the **top of the loop body, before any `continue`** (so the sequence/fresh branches don't change the effective decay cadence the defaults were tuned against).
- **`pheromone` field is package-private, mutated only via `CostCorpus`'s synchronized methods.**
- **Low-latency:** the per-iteration selection path is allocation-free with an incrementally-maintained `totalPheromone`; `selectParent` is a single O(n) locate scan (no re-sum); `evaporate` is O(n) only every N iterations. No Fenwick/lazy-scale (unjustified for the serialized HTTP-bound loop — documented in the spec, not built).
- Bot-authored branch `feat/pheromone-selection` (already created). `gradlew` has CRLF — normalize a copy to run builds, never stage it.

## File Structure

- `runner/coverage/CorpusEntry.java` (modify) — add package-private mutable `double pheromone`; fix the "Immutable" doc.
- `runner/coverage/CostCorpus.java` (modify) — pheromone flag + config; `selectParent`/`reinforce`/`evaporate`; pheromone-aware eviction; running total; EMA-relative initial pheromone; 3-arg constructor (2-arg delegates, pheromone=false).
- `test/CostCorpusPheromoneTest.java` (new) — pheromone unit tests.
- `runner/coverage/CoverageGuidedRun.java` (modify) — seed from `-D`; `selectParent`+track parent; reinforce-before-consider; evaporate-at-top; force-cost + log; end-of-run line.
- `test/` (modify/new) — loop-level kill-switch + reinforce ordering asserts.
- `docs/DESIGN-DECISIONS.md`, `docs/OPERATOR-USAGE.md` or a bench A/B doc, `runner`/`agent` CHANGELOG (modify).

---

### Task 1: `CostCorpus` pheromone core (+ `CorpusEntry.pheromone`)

**Files:** Modify `runner/coverage/CorpusEntry.java`, `runner/coverage/CostCorpus.java`; Test `test/CostCorpusPheromoneTest.java`.

**Interfaces:**
- Consumes: DD-031 `CorpusEntry`/`CostCorpus`.
- Produces: `CostCorpus(seeds, enabled, pheromone)`; `CorpusEntry selectParent(Random)`; `void reinforce(CorpusEntry, double)`; `void evaporate()`; `CorpusEntry.pheromone` (package-private). Used by Task 2.

- [ ] **Step 1: Add the `pheromone` field** — `runner/coverage/CorpusEntry.java`

Change the class doc from "Immutable." to note the pheromone caveat, and add the field (after `coverageFind`):

```java
/** An input in the exploration corpus plus the cost it produced when fired (DD-031). The cost fields
 *  are immutable; {@code pheromone} (DD-032) is monitor-guarded mutable state owned by CostCorpus. */
public final class CorpusEntry {
    // ... existing final fields ...
    /** DD-032 selection weight. Package-private and mutated ONLY by CostCorpus's synchronized methods. */
    double pheromone;
    // ... existing constructor unchanged (pheromone defaults to 0.0) ...
}
```

- [ ] **Step 2: Write the failing test** — `test/CostCorpusPheromoneTest.java`

```java
package test;

import runner.coverage.CorpusEntry;
import runner.coverage.CostCorpus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class CostCorpusPheromoneTest {

    @After public void clearProps() {
        for (String k : new String[]{"basquin.pheromone.epsilon", "basquin.pheromone.decay",
                "basquin.pheromone.depositCap", "basquin.corpus.max", "basquin.cost.minSamples",
                "basquin.cost.retainFactor", "basquin.cost.emaAlpha"}) System.clearProperty(k);
    }

    private static CorpusEntry find(CostCorpus c, String input) {
        return c.snapshotByCost().stream().filter(e -> e.input.equals(input)).findFirst().get();
    }

    @Test public void highPheromoneEntryDominatesWhenEpsilonZero() {
        System.setProperty("basquin.pheromone.epsilon", "0.0");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList("/a", "/b"), true, true);
        c.reinforce(find(c, "/a"), 1000.0);          // /a >> /b (which stays at 0)
        Random rnd = new Random(1);
        int a = 0;
        for (int i = 0; i < 300; i++) if (c.selectParent(rnd).input.equals("/a")) a++;
        Assert.assertTrue("high-pheromone entry dominates the roulette: " + a, a > 285);
    }

    @Test public void epsilonOneIsUniform() {
        System.setProperty("basquin.pheromone.epsilon", "1.0");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList("/a", "/b"), true, true);
        c.reinforce(find(c, "/a"), 1000.0);          // even a huge weight is ignored at eps=1
        Random rnd = new Random(2);
        int a = 0;
        for (int i = 0; i < 400; i++) if (c.selectParent(rnd).input.equals("/a")) a++;
        Assert.assertTrue("eps=1 => uniform ~50%: " + a, a > 150 && a < 250);
    }

    @Test public void pheromoneOffIsUniform() {
        CostCorpus c = new CostCorpus(Arrays.asList("/a", "/b"), true, false); // pheromone OFF
        c.reinforce(find(c, "/a"), 1000.0);          // no-op when off
        Assert.assertEquals(0.0, find(c, "/a").pheromone, 0.0);
        Random rnd = new Random(3);
        int a = 0;
        for (int i = 0; i < 400; i++) if (c.selectParent(rnd).input.equals("/a")) a++;
        Assert.assertTrue("off => uniform ~50%: " + a, a > 150 && a < 250);
    }

    @Test public void coldStartZeroTotalFallsBackToUniform() {
        System.setProperty("basquin.pheromone.epsilon", "0.0");   // no eps-explore; force the fallback path
        CostCorpus c = new CostCorpus(Arrays.asList("/a", "/b"), true, true); // all pheromone 0 => total 0
        Random rnd = new Random(4);
        int a = 0;
        for (int i = 0; i < 400; i++) if (c.selectParent(rnd).input.equals("/a")) a++;
        Assert.assertTrue("zero-total => uniform fallback, no crash: " + a, a > 150 && a < 250);
    }

    @Test public void depositIsCappedAtDepositCapTimesEma() {
        System.setProperty("basquin.cost.minSamples", "1");
        System.setProperty("basquin.cost.emaAlpha", "0.5");
        System.setProperty("basquin.pheromone.depositCap", "2.0");
        CostCorpus c = new CostCorpus(Arrays.asList("/s"), true, true);
        for (int i = 0; i < 6; i++) c.consider("/t" + i, 10, 1, 0, 0, 0, false); // train emaCost -> ~10
        CorpusEntry s = find(c, "/s");                                            // seed, pheromone 0
        c.reinforce(s, 1000.0);                       // cap = 2.0 * ema(~10) = ~20, NOT 1000
        Assert.assertTrue("deposit clamped to ~depositCap*EMA: " + s.pheromone, s.pheromone > 15 && s.pheromone < 25);
    }

    @Test public void evaporateDecaysPheromone() {
        System.setProperty("basquin.pheromone.decay", "0.7");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList("/a"), true, true);
        CorpusEntry a = find(c, "/a");
        c.reinforce(a, 100.0);                        // pheromone 100
        c.evaporate();
        Assert.assertEquals(70.0, a.pheromone, 0.001);
    }

    @Test public void evictionUsesPheromoneNotCostWhenOn() {
        System.setProperty("basquin.corpus.max", "2");
        System.setProperty("basquin.cost.minSamples", "1");
        System.setProperty("basquin.cost.emaAlpha", "0.01");   // slow EMA so both cost-finds retain
        System.setProperty("basquin.cost.retainFactor", "2.0");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList("/cov"), true, true); // /cov = coverage, never evicted
        for (int i = 0; i < 3; i++) c.consider("/w" + i, 5, 1, 0, 0, 0, false); // train EMA ~5 (not retained)
        c.consider("/cheapButHot", 20, 1, 0, 0, 0, false);   // retained (20 > 2*5); pheromone = ema+20
        c.reinforce(find(c, "/cheapButHot"), 100000.0);      // now hottest by pheromone despite modest cost
        c.consider("/richButCold", 100, 1, 0, 0, 0, false);  // retained; over cap(2) -> evict min-PHEROMONE
        List<CorpusEntry> snap = c.snapshotByCost();
        Assert.assertTrue("/cov (coverage) survives", snap.stream().anyMatch(e -> e.input.equals("/cov")));
        Assert.assertTrue("/cheapButHot survives on pheromone", snap.stream().anyMatch(e -> e.input.equals("/cheapButHot")));
        Assert.assertFalse("/richButCold evicted despite higher cost", snap.stream().anyMatch(e -> e.input.equals("/richButCold")));
    }
}
```

- [ ] **Step 3: Run it, verify it fails** — `./gradlew test --tests 'test.CostCorpusPheromoneTest'` → FAIL (no `selectParent`/`reinforce`/`evaporate`, no 3-arg ctor).

- [ ] **Step 4: Implement** — `runner/coverage/CostCorpus.java`

Add fields (after `samples`):
```java
    private final boolean pheromone;
    private final double epsilon;
    private final double decay;
    private final double depositCap;
    private double totalPheromone = 0.0;   // running sum of in-corpus pheromone (O(1)-maintained)
```

Replace the constructor with a 2-arg delegating overload + a 3-arg full one:
```java
    public CostCorpus(List<String> seeds, boolean enabled) { this(seeds, enabled, false); }

    public CostCorpus(List<String> seeds, boolean enabled, boolean pheromone) {
        this.enabled = enabled;
        this.pheromone = pheromone;
        this.maxSize = Integer.getInteger("basquin.corpus.max", 1000);
        this.retainFactor = dbl("basquin.cost.retainFactor", 3.0);
        this.minSamples = Integer.getInteger("basquin.cost.minSamples", 20);
        this.emaAlpha = dbl("basquin.cost.emaAlpha", 0.1);
        this.epsilon = dbl("basquin.pheromone.epsilon", 0.3);
        this.decay = dbl("basquin.pheromone.decay", 0.7);
        this.depositCap = dbl("basquin.pheromone.depositCap", 10.0);
        if (seeds != null) {
            for (String s : seeds) entries.add(new CorpusEntry(s, 0.0, 0, 0, 0, 0, true)); // pheromone 0 (ema=0 here)
        }
    }
```

Add the selection/credit/evaporation methods (near `randomParentInput`):
```java
    /**
     * ε-greedy parent selection (DD-032): with probability ε (or when pheromone is off / the total is
     * zero / cold start) draw UNIFORMLY — the coverage guardrail; otherwise roulette by pheromone.
     * Returns the ENTRY so the caller can reinforce it. Null only if the corpus is empty. Synchronized.
     * Single O(n) locate scan against the running total — no re-summing.
     */
    public synchronized CorpusEntry selectParent(Random rnd) {
        int n = entries.size();
        if (n == 0) return null;
        if (!pheromone || totalPheromone <= 0.0 || rnd.nextDouble() < epsilon) {
            return entries.get(rnd.nextInt(n));   // uniform (off / cold-start / ε-explore)
        }
        double r = rnd.nextDouble() * totalPheromone;
        double cum = 0.0;
        for (int i = 0; i < n; i++) {
            cum += entries.get(i).pheromone;
            if (cum >= r) return entries.get(i);
        }
        return entries.get(n - 1);   // float-rounding safety
    }

    /**
     * Deposit a fired child's cost onto the parent it was mutated from (DD-032 credit assignment),
     * capped at depositCap × EMA so one invariant-hit spike can't own the roulette. MUST be called
     * before the child's consider() (the only evictor), so parent is a live entry and the running total
     * stays exact — do not reorder. No contains-check needed for that reason. Synchronized.
     */
    public synchronized void reinforce(CorpusEntry parent, double childCost) {
        if (!pheromone || parent == null) return;
        double cap = depositCap * (emaCost > 0 ? emaCost : childCost);
        double deposit = Math.min(childCost, cap);
        parent.pheromone += deposit;
        totalPheromone += deposit;
    }

    /** Evaporate all pheromone by `decay` (DD-032). O(n), called every N iterations by the loop. */
    public synchronized void evaporate() {
        if (!pheromone) return;
        for (CorpusEntry e : entries) e.pheromone *= decay;
        totalPheromone *= decay;
    }
```

Reimplement `randomParentInput` to delegate (single selection path; still uniform when pheromone off):
```java
    /** Back-compat string accessor (DD-031). Uniform when pheromone is off. */
    public synchronized String randomParentInput(Random rnd) {
        CorpusEntry e = selectParent(rnd);
        return e == null ? "/" : e.input;
    }
```

In `consider`, set the initial pheromone + maintain the total on the retain path (replace the `entries.add(...)` line):
```java
        CorpusEntry e = new CorpusEntry(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind);
        if (pheromone) { e.pheromone = emaCost + cost; totalPheromone += e.pheromone; }
        entries.add(e);
        if (enabled) evictIfOverCap();
```

Make `evictIfOverCap` pheromone-aware and total-consistent:
```java
    private void evictIfOverCap() {
        while (entries.size() > maxSize) {
            int victim = -1;
            double worst = Double.MAX_VALUE;
            for (int i = 0; i < entries.size(); i++) {
                CorpusEntry e = entries.get(i);
                if (e.coverageFind) continue;                 // coverage backbone never evicted
                double key = pheromone ? e.pheromone : e.cost; // pheromone-aware when on (DD-032)
                if (key < worst) { worst = key; victim = i; }
            }
            if (victim < 0) break;
            totalPheromone -= entries.get(victim).pheromone;
            entries.remove(victim);
        }
    }
```

- [ ] **Step 5: Run tests, verify pass** — `./gradlew test --tests 'test.CostCorpusPheromoneTest'` → PASS (8). Then `./gradlew test` (full suite green, incl. DD-031's `CostCorpusTest` via the 2-arg overload).

- [ ] **Step 6: Commit** — `git add runner/coverage/CorpusEntry.java runner/coverage/CostCorpus.java test/CostCorpusPheromoneTest.java && git commit -m "feat(runner): pheromone selection/credit/evaporation in CostCorpus (DD-032)"`

---

### Task 2: Wire pheromone into the exploration loop

**Files:** Modify `runner/coverage/CoverageGuidedRun.java`; Test `test/` (loop-level assertions).

**Interfaces:** Consumes Task 1's `selectParent`/`reinforce`/`evaporate` + the `pheromone` flag semantics.

- [ ] **Step 1: Seed + flags + forced-cost** — replace the seed line and the cost-enabled/corpus setup (near `Random rnd = new Random(1);` and the `costEnabled`/`new CostCorpus(...)` block):

```java
        long seed = Long.getLong("basquin.seed", 1L);
        Random rnd = new Random(seed);
        // ...
        boolean pheromoneOn = "on".equals(System.getProperty("basquin.pheromone", "off"));
        boolean costEnabled = !"false".equals(System.getProperty("basquin.cost.enabled", "true"));
        if (pheromoneOn && !costEnabled) {
            System.out.println("[Basquin] pheromone=on forces cost measurement on");
            costEnabled = true;   // reinforcement needs a measured cost — no silent partial state
        }
        int evaporateEvery = Integer.getInteger("basquin.pheromone.evaporateEvery", 50);
        CostCorpus corpus = new CostCorpus(seeds, costEnabled, pheromoneOn);
        lastCorpus = corpus;
```

- [ ] **Step 2: Evaporate at the TOP of the loop (before any `continue`)** — first statements inside `for (int i = 0; ...)`, before the deadline/sequence branches:

```java
        for (int i = 0; i < iterations; i++) {
            if (pheromoneOn && i > 0 && i % evaporateEvery == 0) corpus.evaporate(); // fixed cadence, pre-continue
            if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos) break;
            // ... existing session/sequence logic ...
```

- [ ] **Step 3: Select the parent ENTRY + track it** — replace the two `corpus.randomParentInput(rnd)` selection sites so the parent entry is retained for reinforcement:

```java
            CorpusEntry parent = null;
            String input;
            if (i < seeds.size()) {
                input = seeds.get(i);
            } else if (grammar != null) {
                if (rnd.nextInt(100) < 30) {
                    input = grammar.randomRequest();
                } else {
                    parent = corpus.selectParent(rnd);
                    input = grammar.mutate(parent != null ? parent.input : "/");
                }
            } else {
                if (rnd.nextInt(100) < 30) {
                    input = seeds.get(rnd.nextInt(seeds.size()));
                } else {
                    parent = corpus.selectParent(rnd);
                    input = mutate(parent != null ? parent.input : "/", rnd);
                }
            }
```

- [ ] **Step 4: Reinforce BEFORE consider** — in the retention section, add the reinforce call immediately before the `corpus.consider(...)`:

```java
            // Credit assignment (DD-032): a fired child's cost reinforces the parent it came from.
            // BEFORE consider (the only evictor) so the parent is still live and the running total exact.
            if (pheromoneOn && parent != null && measured) corpus.reinforce(parent, cost);
            if (coverageFind || measured) {
                corpus.consider(input, cost, latMs, sample.heapDeltaKb, sample.threadDelta,
                        sample.invariantCount, coverageFind);
            }
```

- [ ] **Step 5: End-of-run line carries the arm + seed** — extend the final `printf`:

```java
        System.out.printf("CoverageGuidedRun done: corpus=%d coverage=%d/%d pheromone=%s seed=%d%n",
                corpus.size(), best, total, pheromoneOn ? "on" : "off", seed);
```

- [ ] **Step 6: Loop-level test** — add `test/PheromoneLoopTest.java` asserting the kill-switch + reinforce ordering at the seam that's testable without HTTP. Since the loop itself needs a live server, test the *observable contract* instead: (a) `pheromone=off` → the end-of-run summary still ranks replay by cost as DD-031 (already covered) and `CostCorpus` selection is uniform (Task 1); (b) a focused unit test that `reinforce` before `consider` keeps a reinforced cheap parent from being evicted by its own child. If a genuine loop-level harness exists (grep `test/` for how `CoverageGuidedRun` internals are unit-tested), assert `pheromoneOn` forcing `costEnabled`; otherwise assert it via a small extracted helper and note the coverage boundary in the report. Do NOT fabricate an HTTP server.

- [ ] **Step 7: Build + full suite** — `./gradlew test` green (incl. DD-031 tests + Task 1). Grep `randomParentInput` in `test/` — if a DD-031 test asserted uniform via it, it still holds (delegates to `selectParent` which is uniform when pheromone off).

- [ ] **Step 8: Commit** — `git add runner/coverage/CoverageGuidedRun.java test/ && git commit -m "feat(runner): opt-in pheromone selection loop wiring + seed/arm logging (DD-032)"`

---

### Task 3: Docs + bench A/B protocol

**Files:** Modify `docs/DESIGN-DECISIONS.md`, `docs/OPERATOR-USAGE.md` (or a new `docs/BENCH-AB.md`), `runner`/`agent` CHANGELOG.

- [ ] **Step 1: DD-032 record** — `docs/DESIGN-DECISIONS.md`. Cover: opt-in ε-greedy selection + immediate-parent credit + evaporation; **ε is the coverage guardrail**; EMA-relative initial pheromone; the sticky-spike defaults with the half-life; pheromone-aware eviction; `pheromone=on` forces cost on; deferred lineage + operator/CRD; the performance stance (running total + O(n) scan; Fenwick/lazy-scale documented-not-built). Reference the spec. **Two A/B-readout caveats must be recorded so results aren't misread:**
  - **The uniform arm is not a pure-uniform strawman.** The loop already spends ~30% of iterations on fresh/random expansion before ε applies, so roulette's real share is ≈ `0.7 × (1−ε)` ≈ 49% of iterations; the "off" arm is the same structure *minus roulette*. State this so effect size isn't misattributed.
  - **Winner-take-all inside the roulette is intended.** A consistently-selected parent's pheromone converges to ≈ `deposit-per-cycle / (1 − decay)` ≈ tens of EMA multiples; ε bounds the concentration. Heavy concentration on one parent in the readout is the design working, not a bug.

- [ ] **Step 2: Bench A/B protocol note** — document how to run the experiment (the "prove or kill" procedure): run the driver against a bench target twice per seed with `-Dbasquin.pheromone=off` then `=on`, seeds `1 2 3`, identical `-Dbasquin.run.duration`; grep the end-of-run line for `coverage=X/Y pheromone= seed=` and the `replay cost-ranked` top; compare the coverage% and top-cost **distributions** across the 3 seeds per arm — not single points. Note that operator/in-cluster enablement is intentionally deferred until this bench A/B favors pheromone.

- [ ] **Step 3: CHANGELOG** — `runner`: "Added (DD-032): opt-in `-Dbasquin.pheromone=on` ε-greedy cost-biased selection with immediate-parent credit assignment + evaporation; `-Dbasquin.seed`." (agent unchanged this feature; skip or note none.)

- [ ] **Step 4: Commit** — `git add docs/ runner/CHANGELOG.md && git commit -m "docs: record DD-032 pheromone selection + bench A/B protocol"`

---

## Final step: open the PR

- [ ] Push, open a bot-authored PR "feat: cost-biased pheromone selection (DD-032, opt-in)"; request review from @claude + the user; body explains: opt-in default-off (kill-switch is ε), the coverage guardrail, the A/B protocol + the "not a strawman / winner-take-all is intended" caveats, and bench-first/operator-deferred. Note the in-cluster e2e only smoke-tests the default-off path (pheromone isn't validated by CI — it's validated by the bench A/B).

## Self-Review notes (author)

- **Spec coverage:** field + core (T1), loop wiring incl. evaporate-at-top + forced-cost + seed (T2), docs incl. both A/B caveats (T3). All spec sections tasked.
- **Constraint coverage:** default-off == today (T1 2-arg overload + T2 `pheromoneOn` gates); ε guardrail + EMA-relative init (T1 consider); decay 0.7 + deposit cap (T1 reinforce); pheromone-aware eviction (T1 evictIfOverCap); reinforce-before-consider (T2 Step 4 ordering); forced cost + log (T2 Step 1); seed (T2 Step 1/5); evaporate cadence pre-continue (T2 Step 2); package-private field (T1 Step 1); running total maintained on add/reinforce/evaporate/evict (T1).
- **Type consistency:** `selectParent(Random)→CorpusEntry`, `reinforce(CorpusEntry,double)`, `evaporate()`, `CostCorpus(seeds,enabled,pheromone)`, `CorpusEntry.pheromone` (package-private) — consistent across T1/T2.
