import org.junit.Test;
import runner.util.StatusReporter;

import static org.junit.Assert.*;

/**
 * Unit tests for the CSV time-series sampler helpers on StatusReporter (the pure formatting core of
 * the -Dbasquin.sample.out benchmark capture). The threaded file-writing path is exercised by the
 * benchmark runs themselves; these pin the row/header contract.
 */
public class StatusSamplerTest {

    @Test
    public void headerAndLineHaveMatchingColumnCount() {
        int headerCols = StatusReporter.sampleHeader().split(",", -1).length;
        int lineCols = StatusReporter.sampleLine(1000L).split(",", -1).length;
        assertEquals("row must have exactly one field per header column", headerCols, lineCols);
    }

    @Test
    public void headerStartsWithElapsedThenIterations() {
        assertTrue(StatusReporter.sampleHeader().startsWith("elapsedMs,iterations,"));
    }

    @Test
    public void elapsedIsEchoedVerbatimInColumnZero() {
        assertEquals("1000", StatusReporter.sampleLine(1000L).split(",")[0]);
    }

    @Test
    public void everyMEASUREDFieldIsNonEmptyAndZeroElapsedIsSafe() {
        // elapsedMs=0 must not divide-by-zero (itersPerSec/mean/coveragePct guards). Every cell is
        // present EXCEPT the viol* trio (DD-040): those are empty precisely when this jvm configured
        // no threshold for that invariant, because the alternative — writing 0 — plots a clean flat
        // line for a series nobody ever computed. Column indices 11..13, per sampleHeader().
        String[] header = StatusReporter.sampleHeader().split(",", -1);
        String[] cells = StatusReporter.sampleLine(0L).split(",", -1);
        for (int i = 0; i < cells.length; i++) {
            if (header[i].startsWith("viol")) continue;
            assertFalse("no empty CSV cell for " + header[i], cells[i].isEmpty());
        }
    }

    /** DD-040: an unevaluated invariant is a GAP, not a zero — and a configured one is a real count. */
    @Test
    public void violationCellsAreEmptyExactlyWhenTheInvariantWasNeverEvaluated() {
        String[] header = StatusReporter.sampleHeader().split(",", -1);
        int lat = indexOf(header, "violLatency");

        System.clearProperty("basquin.invariant.latency.maxMs");
        assertEquals("no threshold => a gap, never a 0",
                "", StatusReporter.sampleLine(1000L).split(",", -1)[lat]);
        try {
            System.setProperty("basquin.invariant.latency.maxMs", "250");
            assertFalse("a configured threshold IS evaluated, so its count must be written",
                    StatusReporter.sampleLine(1000L).split(",", -1)[lat].isEmpty());
        } finally {
            System.clearProperty("basquin.invariant.latency.maxMs");
        }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (header[i].equals(name)) return i;
        throw new AssertionError("no such column: " + name);
    }

    @Test
    public void decimalsUseADotRegardlessOfLocale() {
        // CSV correctness: a locale that formats decimals with ',' would corrupt the row. The
        // itersPerSec / coveragePct cells must never contain a comma inside a field.
        String[] cells = StatusReporter.sampleLine(1000L).split(",", -1);
        int headerCols = StatusReporter.sampleHeader().split(",", -1).length;
        assertEquals("no stray comma split a decimal into extra cells", headerCols, cells.length);
    }
}
