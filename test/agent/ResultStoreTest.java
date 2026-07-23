package agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResultStoreTest {

    @Before public void reset() { ResultStore.clearForTest(); }

    @Test public void putThenTakeReturnsTheEntryExactlyOnce() {
        ResultStore.put("salt-1", new ResultStore.Entry("12,340,0", 2, "latency: 719ms > 250ms", false));
        ResultStore.Entry e = ResultStore.take("salt-1");
        assertNotNull(e);
        assertEquals(2, e.invariantCount());
        assertNull("remove-on-read: a second take must miss", ResultStore.take("salt-1"));
    }

    // DD-040: a foreign or stale id must MISS, never return another run's entry. An unsalted
    // counter collides across two drivers or two campaigns against one long-lived target, and
    // returning stale data as fresh is worse than returning nothing.
    @Test public void unknownIdMisses() {
        assertNull(ResultStore.take("other-salt-1"));
    }

    @Test public void evictsOldestBeyondCapacityAndStaysBounded() {
        for (int i = 0; i < ResultStore.CAPACITY + 50; i++) {
            ResultStore.put("s-" + i, new ResultStore.Entry("1,2,0", 0, null, false));
        }
        assertEquals(ResultStore.CAPACITY, ResultStore.size());
        assertNull("oldest evicted", ResultStore.take("s-0"));
        assertNotNull("newest retained", ResultStore.take("s-" + (ResultStore.CAPACITY + 49)));
    }

    // The store lives inside the JVM whose heap deltas this tool reports, so its footprint is
    // part of the measurement. detail is capped like the header path already caps it.
    @Test public void detailIsCappedSoRetentionIsBounded() {
        String huge = "x".repeat(5000);
        ResultStore.put("s-cap", new ResultStore.Entry("1,2,0", 1, huge, false));
        assertTrue(ResultStore.take("s-cap").detail().length() <= 200);
    }

    @Test public void concurrentPutAndTakeDoNotCorruptTheStore() throws Exception {
        // The poll runs on a different connector thread from the boundary write and never holds
        // ITERATION_LOCK, so the store itself must be safe.
        Thread w = new Thread(() -> { for (int i = 0; i < 2000; i++)
            ResultStore.put("c-" + i, new ResultStore.Entry("1,2,0", 0, null, false)); });
        Thread r = new Thread(() -> { for (int i = 0; i < 2000; i++) ResultStore.take("c-" + i); });
        w.start(); r.start(); w.join(); r.join();
        assertTrue(ResultStore.size() <= ResultStore.CAPACITY);
    }

    @Test public void violationsTotalAccumulatesAcrossEntries() {
        ResultStore.put("v-1", new ResultStore.Entry("1,2,0", 3, null, false));
        ResultStore.put("v-2", new ResultStore.Entry("1,2,0", 4, null, false));
        assertEquals(7, ResultStore.totalViolations());
    }
}
