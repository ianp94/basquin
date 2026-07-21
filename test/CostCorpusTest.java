package test;

import runner.coverage.CorpusEntry;
import runner.coverage.CostCorpus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class CostCorpusTest {

    @After public void clearProps() {
        // CostCorpus reads these once per instance; clear so tests don't order-couple through globals.
        for (String k : new String[]{"basquin.cost.minSamples", "basquin.cost.retainFactor",
                "basquin.corpus.max", "basquin.cost.emaAlpha"}) {
            System.clearProperty(k);
        }
    }

    private static CostCorpus enabled(String... seeds) {
        System.setProperty("basquin.cost.minSamples", "3");
        System.setProperty("basquin.cost.retainFactor", "2.0");
        System.setProperty("basquin.corpus.max", "5");
        // Small alpha => a SLOW baseline that stays near the cold-start cost (~5), so the six expensive
        // entries (50..100) all clear the 2x threshold and force evictions. A large alpha would let the
        // EMA chase the rising costs, reject the later entries, and the corpus would never reach the cap
        // — a miscalibration that would tempt an implementer to "fix" correct code. Keep this small.
        System.setProperty("basquin.cost.emaAlpha", "0.01");
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
