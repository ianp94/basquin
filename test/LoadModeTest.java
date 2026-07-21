import org.junit.After;
import org.junit.Test;
import agent.LoadMode;

import static org.junit.Assert.*;

/**
 * Unit tests for the load-mode flag (DD-029): the valve reads it to choose serialized (explore) vs
 * lock-free (load) request handling, and it auto-reverts after its TTL as a crash-safety.
 */
public class LoadModeTest {

    @After public void reset() { LoadMode.setExplore(); }

    @Test public void defaultsToExplore() {
        LoadMode.setExplore();
        assertFalse(LoadMode.isLoad(System.currentTimeMillis()));
    }

    @Test public void loadUntilTtlThenAutoReverts() {
        LoadMode.setLoad(500);
        long entered = LoadMode.enteredAt();
        assertTrue("in load before ttl", LoadMode.isLoad(entered + 100));
        assertFalse("auto-reverts after ttl (crash-safety)", LoadMode.isLoad(entered + 600));
    }

    @Test public void explicitExploreEndsLoadImmediately() {
        LoadMode.setLoad(10_000);
        LoadMode.setExplore();
        assertFalse(LoadMode.isLoad(LoadMode.enteredAt()));
    }

    @Test public void driftSnapshotHasThreeNumericFields() {
        String[] f = LoadMode.driftSnapshotCsv().split(",");
        assertEquals("heapKb,threads,epochMillis", 3, f.length);
        for (String x : f) Long.parseLong(x); // must be numeric (CSV-safe, no locale decimals)
    }

    @Test public void driftHeapAndThreadsArePositive() {
        String[] f = LoadMode.driftSnapshotCsv().split(",");
        assertTrue("used heap KB > 0", Long.parseLong(f[0]) > 0);
        assertTrue("live threads > 0", Long.parseLong(f[1]) > 0);
    }
}
