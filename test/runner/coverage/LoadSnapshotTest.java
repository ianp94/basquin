package runner.coverage;

import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLongArray;

public class LoadSnapshotTest {

    @Test public void snapshotMatchesTerminalComputationForTheSameHistogramState() {
        AtomicLongArray hist = new AtomicLongArray(30_002);
        // 100 requests: 50 at 10ms, 40 at 40ms, 10 at 200ms
        for (int i = 0; i < 50; i++) hist.incrementAndGet(10);
        for (int i = 0; i < 40; i++) hist.incrementAndGet(40);
        for (int i = 0; i < 10; i++) hist.incrementAndGet(200);
        long total = 100, serverErrors = 3;
        double windowSec = 2.0; // 100 req / 2s = 50 rps
        LoadRun.LoadSnapshot s = LoadRun.computeLoadSnapshot(hist, total, serverErrors, windowSec, 1500, 4);
        Assert.assertEquals(50.0, s.throughputRps, 0.001);
        Assert.assertEquals(10, s.p50);   // median at 10ms
        Assert.assertEquals(40, s.p90);
        Assert.assertEquals(200, s.p99);  // p99 lands in the 200ms bucket
        Assert.assertEquals(200, s.max);
        Assert.assertEquals(1500, s.heapDriftKb);
        Assert.assertEquals(4, s.threadDrift);
        Assert.assertEquals(3, s.serverErrors);
        Assert.assertEquals(100, s.requests);
    }
}
