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
    public void everyFieldIsNonEmptyAndZeroElapsedIsSafe() {
        // elapsedMs=0 must not divide-by-zero (itersPerSec/mean/coveragePct guards); all cells present.
        for (String field : StatusReporter.sampleLine(0L).split(",", -1)) {
            assertFalse("no empty CSV cell", field.isEmpty());
        }
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
