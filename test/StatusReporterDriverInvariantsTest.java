package test;

import runner.util.StatusReporter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * DD-040: the summary's {@code invariants} block is the DRIVER JVM's own evaluation — the runner
 * wraps every request in {@code Agent.beginIteration}/{@code endIteration}, and those counters come
 * from {@code Agent.recordStatus} in THIS process, against THIS process's
 * {@code -Dbasquin.invariant.*} thresholds. A driver launched without a threshold never evaluates
 * that invariant, so printing {@code 0} for it is a fabricated "checked and clean" — exactly the
 * structural zero this task exists to remove. An unevaluated invariant must be absent and named in
 * {@code notEvaluated}, so an omitted key can never be mistaken for a measured zero.
 */
public class StatusReporterDriverInvariantsTest {

    private static final String LAT = "basquin.invariant.latency.maxMs";
    private static final String HEAP = "basquin.invariant.heapDelta.maxKb";
    private static final String THREAD = "basquin.invariant.threadDelta.max";

    @After public void clearThresholds() {
        System.clearProperty(LAT);
        System.clearProperty(HEAP);
        System.clearProperty(THREAD);
    }

    @Test public void noThresholdsMeansNoCountsAtAll() {
        clearThresholds();
        String j = StatusReporter.snapshotJson();

        Assert.assertFalse("a driver with no thresholds never checked latency", j.contains("\"latency\":"));
        Assert.assertFalse("...nor heap", j.contains("\"heap\":"));
        Assert.assertFalse("...nor threads", j.contains("\"thread\":"));
        Assert.assertTrue("and it must say so as data, not by omission alone",
                j.contains("\"notEvaluated\":[\"latency\",\"heap\",\"thread\"]"));
    }

    @Test public void aConfiguredThresholdReportsItsCountAndTheRestStayUnevaluated() {
        System.setProperty(LAT, "250");
        String j = StatusReporter.snapshotJson();

        Assert.assertTrue("a configured latency threshold IS evaluated, so its count is real",
                j.contains("\"latency\":"));
        Assert.assertFalse("heap was still never checked", j.contains("\"heap\":"));
        Assert.assertTrue(j.contains("\"notEvaluated\":[\"heap\",\"thread\"]"));
    }

    @Test public void allThresholdsConfiguredMeansNoUnevaluatedList() {
        System.setProperty(LAT, "250");
        System.setProperty(HEAP, "4096");
        System.setProperty(THREAD, "8");
        String j = StatusReporter.snapshotJson();

        Assert.assertTrue(j.contains("\"latency\":"));
        Assert.assertTrue(j.contains("\"heap\":"));
        Assert.assertTrue(j.contains("\"thread\":"));
        Assert.assertFalse("nothing was skipped, so no notEvaluated marker", j.contains("\"notEvaluated\""));
    }
}
