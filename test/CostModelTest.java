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
