import org.junit.Test;
import runner.coverage.LoadRun;

import static org.junit.Assert.*;

/**
 * Unit tests for LoadRun's target-side drift helpers (DD-029): the driver polls the target's
 * /__basquin/drift snapshot and reports first→last drift of the APP's heap/threads (not the driver's).
 */
public class LoadRunDriftTest {

    @Test public void parseDriftReadsThreeFieldCsv() {
        LoadRun.Drift d = LoadRun.parseDrift("2048,37,1000");
        assertEquals(2048, d.heapKb);
        assertEquals(37, d.threads);
        assertEquals(1000, d.ts);
    }

    @Test public void driftDeltaIsLastMinusFirst() {
        LoadRun.Drift a = LoadRun.parseDrift("1000,10,0");
        LoadRun.Drift b = LoadRun.parseDrift("1600,14,5000");
        LoadRun.DriftDelta delta = LoadRun.driftDelta(a, b);
        assertEquals(600, delta.heapDriftKb);
        assertEquals(4, delta.threadDrift);
    }

    @Test public void parseDriftReturnsNullOnGarbage() {
        assertNull(LoadRun.parseDrift(null));
        assertNull(LoadRun.parseDrift("not,enough"));
        assertNull(LoadRun.parseDrift("a,b,c"));
    }

    @Test public void driftDeltaWithAMissingSampleIsZero() {
        // If a poll failed (null), drift is reported as zero rather than throwing.
        LoadRun.DriftDelta delta = LoadRun.driftDelta(null, LoadRun.parseDrift("1600,14,5000"));
        assertEquals(0, delta.heapDriftKb);
        assertEquals(0, delta.threadDrift);
    }
}
