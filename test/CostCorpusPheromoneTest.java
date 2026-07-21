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
        Assert.assertEquals(0.0, find(c, "/a").pheromone(), 0.0);
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
        Assert.assertTrue("deposit clamped to ~depositCap*EMA: " + s.pheromone(), s.pheromone() > 15 && s.pheromone() < 25);
    }

    @Test public void evaporateDecaysPheromone() {
        System.setProperty("basquin.pheromone.decay", "0.7");
        System.setProperty("basquin.pheromone.depositCap", "1000000");
        CostCorpus c = new CostCorpus(Arrays.asList("/a"), true, true);
        CorpusEntry a = find(c, "/a");
        c.reinforce(a, 100.0);                        // pheromone 100
        c.evaporate();
        Assert.assertEquals(70.0, a.pheromone(), 0.001);
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
