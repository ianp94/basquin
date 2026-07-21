package runner.coverage;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * DD-032: covers the loop-wiring contract that {@link CoverageGuidedRun#main} itself can't be
 * exercised for without a live server (it opens an HTTP connection and requires
 * {@code -Dbasquin.coverage.classes}). Two things the loop's wiring depends on, tested at the
 * seam that IS reachable without a server:
 *
 * <ol>
 *   <li>{@code pheromone=on} forces cost measurement on — via
 *       {@link CoverageGuidedRun#resolveCostEnabled}, a package-private static extracted from
 *       {@code main} specifically so this decision is checkable in isolation (same pattern as
 *       {@code parseDurationMillis}/{@code replayCorpusJson} elsewhere in this class).</li>
 *   <li>the loop calls {@code corpus.reinforce(parent, cost)} BEFORE {@code corpus.consider(...)}
 *       — verified here directly on {@link CostCorpus}, since that's the invariant the ordering
 *       protects: reinforcing first keeps the parent entry live (not yet evicted) and keeps
 *       {@code totalPheromone} exactly equal to the sum of live entries' pheromone. Calling them
 *       in the other order would still compile and would still look right locally (reinforce
 *       doesn't check whether {@code parent} is still in the corpus) — it would just silently
 *       drift the running total against an evicted entry, corrupting selectParent's roulette
 *       over time. That failure mode is exactly what this test pins.</li>
 * </ol>
 *
 * Coverage boundary: this does NOT exercise {@code CoverageGuidedRun.main}'s loop body itself
 * (the {@code CorpusEntry parent} tracking, the evaporate-at-top-of-loop placement, the
 * end-of-run printf). Those require a running JPetStore + JaCoCo endpoint and are out of reach
 * for a unit test; they were verified by reading the diff against DD-032 task-2-brief.md.
 */
public class PheromoneLoopTest {

    @After public void clearProps() {
        for (String k : new String[]{"basquin.pheromone.depositCap", "basquin.corpus.max",
                "basquin.cost.minSamples", "basquin.cost.retainFactor", "basquin.cost.emaAlpha"}) {
            System.clearProperty(k);
        }
    }

    // --- resolveCostEnabled: the pheromoneOn-forces-costEnabled decision --------------------

    @Test public void pheromoneOnForcesCostEnabledWhenPropertySaysFalse() {
        Assert.assertTrue(CoverageGuidedRun.resolveCostEnabled(true, false));
    }

    @Test public void pheromoneOnLeavesCostEnabledTrueAlone() {
        Assert.assertTrue(CoverageGuidedRun.resolveCostEnabled(true, true));
    }

    @Test public void pheromoneOffNeverForcesCostEnabled() {
        Assert.assertFalse(CoverageGuidedRun.resolveCostEnabled(false, false));
        Assert.assertTrue(CoverageGuidedRun.resolveCostEnabled(false, true)); // unchanged, either way
    }

    // --- reinforce-before-consider ordering (the loop's Step 4 invariant) ------------------

    private static CorpusEntry find(CostCorpus c, String input) {
        return c.snapshotByCost().stream().filter(e -> e.input.equals(input)).findFirst().get();
    }

    /**
     * The loop's exact sequence: select a parent, fire its child, reinforce the parent with the
     * child's cost, THEN consider() the child (which may evict). Reinforcing first means the
     * parent — even though it started out the cheaper/lower-pheromone of the two entries — is no
     * longer the minimum by the time consider()'s over-cap eviction runs, so it survives; the
     * running total also stays exact (sum of live entries == totalPheromone via evictIfOverCap's
     * bookkeeping).
     */
    @Test public void reinforceBeforeConsiderProtectsTheParentFromItsOwnChildsEviction() {
        System.setProperty("basquin.corpus.max", "2");
        System.setProperty("basquin.cost.minSamples", "0");
        System.setProperty("basquin.cost.retainFactor", "0.0"); // every non-coverage cost retains
        System.setProperty("basquin.cost.emaAlpha", "1.0");     // EMA snaps to the latest cost
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList(), true, true);

        // Cost-retention needs a trained EMA baseline before it can retain anything (the very first
        // consider() only trains emaCost from 0; it can't be retained against itself). Warm up with
        // a throwaway input, then seed two low-pheromone entries at the corpus's cap of 2.
        c.consider("/warmup", 1, 1, 0, 0, 0, false);       // trains emaCost -> 1, not retained (emaCost was 0)
        c.consider("/parent", 1, 1, 0, 0, 0, false);       // retained: cost(1) > 0*ema; pheromone = ema+cost
        c.consider("/sibling", 1, 1, 0, 0, 0, false);      // retained too; corpus now AT cap (2)
        CorpusEntry parent = find(c, "/parent");
        double parentPheromoneBefore = parent.pheromone;

        // The parent's child fires with a large cost. Loop order: reinforce, THEN consider.
        double childCost = 500.0;
        c.reinforce(parent, childCost);
        Assert.assertTrue("reinforce raised the parent's pheromone",
                parent.pheromone > parentPheromoneBefore);
        c.consider("/child", childCost, 1, 0, 0, 0, false); // pushes size to 3 -> over cap -> evict min-pheromone

        List<CorpusEntry> snap = c.snapshotByCost();
        Assert.assertTrue("reinforced parent survives its own child's eviction pass",
                snap.stream().anyMatch(e -> e.input.equals("/parent")));
        Assert.assertTrue("the child that credited it is present",
                snap.stream().anyMatch(e -> e.input.equals("/child")));
        // /sibling was never reinforced, so once /child (high pheromone) and /parent (boosted by
        // reinforce) are both in, /sibling is the new minimum and is the one evicted.
        Assert.assertFalse("low-pheromone /sibling evicted instead of the reinforced parent",
                snap.stream().anyMatch(e -> e.input.equals("/sibling")));
    }

    /**
     * The failure mode the ordering avoids, made concrete: if reinforce() ran AFTER consider() had
     * already evicted the parent, reinforce()'s unconditional {@code parent.pheromone += deposit;
     * totalPheromone += deposit;} still executes (it has no contains-check by design — see
     * CostCorpus.reinforce's javadoc) and silently pushes {@code totalPheromone} above the sum of
     * the pheromone actually held by live entries. That drift corrupts every future
     * {@code selectParent} roulette draw (its cumulative scan is bounded by {@code totalPheromone},
     * so a bar taller than what live entries can reach lands on undershoot, not a picked entry, or
     * skews toward whichever entry the scan reaches last). This test pins that the corrupted-order
     * outcome is real (so nobody "simplifies" the loop by swapping the two calls back).
     */
    @Test public void reinforceAfterEvictionWouldCorruptTheRunningTotal() {
        System.setProperty("basquin.corpus.max", "1");
        System.setProperty("basquin.cost.minSamples", "0");
        System.setProperty("basquin.cost.retainFactor", "0.0");
        System.setProperty("basquin.cost.emaAlpha", "1.0");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList(), true, true);
        c.consider("/warmup", 1, 1, 0, 0, 0, false);  // trains emaCost, not retained
        c.consider("/parent", 1, 1, 0, 0, 0, false);  // retained; corpus at cap (1)
        CorpusEntry parent = find(c, "/parent");

        // WRONG order: consider() first. /parent is the only (hence lowest-pheromone) candidate,
        // so it's evicted the moment the over-cap child is added.
        c.consider("/child", 500, 1, 0, 0, 0, false);
        List<CorpusEntry> afterConsider = c.snapshotByCost();
        Assert.assertFalse("/parent was evicted by its own child (this IS the bug the ordering avoids)",
                afterConsider.stream().anyMatch(e -> e.input.equals("/parent")));

        double sumBefore = afterConsider.stream().mapToDouble(e -> e.pheromone).sum();
        // Reinforcing the now-orphaned parent reference still silently succeeds...
        c.reinforce(parent, 500.0);
        double sumAfter = c.snapshotByCost().stream().mapToDouble(e -> e.pheromone).sum();
        // ...and the live entries' pheromone sum is unchanged (the deposit landed on an entry no
        // longer in the corpus), proving totalPheromone (which DID move) has drifted away from it.
        Assert.assertEquals("live entries' pheromone sum is untouched by reinforcing an evicted entry",
                sumBefore, sumAfter, 0.0001);
    }
}
