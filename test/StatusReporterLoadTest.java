package test;

import runner.util.StatusReporter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class StatusReporterLoadTest {

    // StatusReporter is static singleton state; reset the mode after each test so these tests don't
    // order-couple through it (matches LoadModeTest/CostCorpusTest/RequestBoundaryTest convention).
    @After public void resetMode() { StatusReporter.setMode("explore"); }

    @Test public void defaultModeIsExploreAndCarriesNoLoadBlock() {
        // A fresh reporter (explore) must carry mode=explore and no "load" block.
        String j = StatusReporter.snapshotJson();
        Assert.assertTrue("explore mode tag", j.contains("\"mode\":\"explore\""));
        Assert.assertFalse("no load block in explore", j.contains("\"load\":"));
    }

    @Test public void recordLoadEmitsModeLoadAndAWellFormedLoadBlock() {
        StatusReporter.setMode("load");
        StatusReporter.recordLoad(2139.5, 12, 40, 76, 256, 15600, 3, 5, 96294);
        String j = StatusReporter.snapshotJson();
        Assert.assertTrue(j.contains("\"mode\":\"load\""));
        Assert.assertTrue(j.contains("\"throughputRps\":\"2139.5\""));
        Assert.assertTrue(j.contains("\"latencyMs\":{\"p50\":12,\"p90\":40,\"p99\":76,\"max\":256}"));
        Assert.assertTrue(j.contains("\"heapDriftKb\":15600"));
        Assert.assertTrue(j.contains("\"threadDrift\":3"));
        Assert.assertTrue(j.contains("\"serverErrors\":5"));
        Assert.assertTrue(j.contains("\"requests\":96294"));
    }
}
