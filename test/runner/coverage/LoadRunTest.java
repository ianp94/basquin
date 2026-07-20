package runner.coverage;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The load driver's latency percentiles come from a histogram of client-observed request times
 * (DD-026 PR 2). A percentile miscompute would silently misreport the app's behavior under load, so
 * the histogram math is pinned here. Package {@code runner.coverage} to reach the package-private method.
 */
public class LoadRunTest {

    @Test
    public void percentilesOfAUniformHistogram() {
        // 100 samples, one each at 1..100 ms.
        AtomicLongArray h = new AtomicLongArray(200);
        for (int ms = 1; ms <= 100; ms++) h.set(ms, 1);
        long total = 100;
        assertEquals(50, LoadRun.percentile(h, total, 0.50));
        assertEquals(90, LoadRun.percentile(h, total, 0.90));
        assertEquals(99, LoadRun.percentile(h, total, 0.99));
    }

    @Test
    public void percentilesWithASkewedTail() {
        // 990 fast (5 ms) + 10 slow (500 ms): p50/p90 fast, p99 in the tail.
        AtomicLongArray h = new AtomicLongArray(1000);
        h.set(5, 990);
        h.set(500, 10);
        long total = 1000;
        assertEquals(5, LoadRun.percentile(h, total, 0.50));
        assertEquals(5, LoadRun.percentile(h, total, 0.90));
        assertTrue("p99 must reach the slow tail", LoadRun.percentile(h, total, 0.99) >= 5);
        assertEquals(500, LoadRun.percentile(h, total, 0.999));
    }

    @Test
    public void emptyHistogramIsZero() {
        assertEquals(0, LoadRun.percentile(new AtomicLongArray(10), 0, 0.5));
    }
}
