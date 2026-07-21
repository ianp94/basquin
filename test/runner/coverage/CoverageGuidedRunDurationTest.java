package runner.coverage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@code -Dbasquin.run.duration} is how an operator campaign (DD-025) time-boxes a run so it exits
 * cleanly and still writes its summary. A misparse would silently under- or over-run, so the parser
 * is pinned here. Lives in {@code runner.coverage} to reach the package-private method.
 */
public class CoverageGuidedRunDurationTest {

    @Test
    public void parsesSuffixedUnits() {
        assertEquals(500L, CoverageGuidedRun.parseDurationMillis("500ms"));
        assertEquals(30_000L, CoverageGuidedRun.parseDurationMillis("30s"));
        assertEquals(600_000L, CoverageGuidedRun.parseDurationMillis("10m"));
        assertEquals(7_200_000L, CoverageGuidedRun.parseDurationMillis("2h"));
    }

    @Test
    public void bareNumberIsSeconds() {
        assertEquals(45_000L, CoverageGuidedRun.parseDurationMillis("45"));
    }

    @Test
    public void toleratesWhitespaceAndCase() {
        assertEquals(600_000L, CoverageGuidedRun.parseDurationMillis(" 10M "));
    }

    @Test
    public void acceptsFractional() {
        assertEquals(1_500L, CoverageGuidedRun.parseDurationMillis("1.5s"));
    }
}
