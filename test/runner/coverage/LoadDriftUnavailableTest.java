package runner.coverage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DD-035: when the target's drift can't be trusted (a failed baseline poll, or a load-mode toggle the
 * target never confirmed), the terminal summary must say so as a first-class {@code driftUnavailable}
 * signal — NOT silently print {@code heapDriftKb:0}, which is indistinguishable from a real flat heap.
 * Exercises the extracted {@link LoadRun#summaryJson} directly (no server needed).
 */
public class LoadDriftUnavailableTest {

    @Test
    public void driftUnavailableOmitsHeapAndThreadDrift() {
        // The drift delta's actual values are irrelevant when unavailable — they must never surface.
        LoadRun.DriftDelta drift = LoadRun.driftDelta(null, null);
        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0, true);

        assertTrue("driftUnavailable flag must be present", json.contains("\"driftUnavailable\":true"));
        assertFalse("heapDriftKb must be omitted, not faked as 0", json.contains("\"heapDriftKb\""));
        assertFalse("threadDrift must be omitted too", json.contains("\"threadDrift\""));
    }

    @Test
    public void availableDriftReportsHeapEvenWhenNonPositive() {
        // A real drift delta where heap SHRANK (-5 KB) — a legitimate ≤0 value, distinct from the
        // "we don't know" case above. It must print as-is, with no driftUnavailable key.
        LoadRun.Drift first = LoadRun.parseDrift("1000,10,1000");
        LoadRun.Drift last = LoadRun.parseDrift("995,10,2000");
        LoadRun.DriftDelta drift = LoadRun.driftDelta(first, last);

        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0, false);

        assertTrue("heapDriftKb must be present even when <= 0", json.contains("\"heapDriftKb\":-5"));
        assertTrue("threadDrift must be present", json.contains("\"threadDrift\":0"));
        assertFalse("no driftUnavailable key when drift IS available", json.contains("\"driftUnavailable\""));
    }

    // --- LoadRun.driftUnavailable(...) — the computation itself, not just its JSON rendering.
    // DD-035 review: the bug was that this computation only ever looked at the ONE-TIME baseline
    // sample, so a baseline that landed followed by a LATER poll failure (the current/terminal sample)
    // still reported driftUnavailable=false, with driftDelta() silently degrading the missing sample to
    // a fabricated zero. These cases pin the fixed computation directly.

    @Test
    public void driftAvailableWhenBaselineAndSampleBothOkAndModeConfirmed() {
        LoadRun.Drift baseline = LoadRun.parseDrift("1000,10,1000");
        LoadRun.Drift sample = LoadRun.parseDrift("995,10,2000");

        assertFalse(LoadRun.driftUnavailable(true, baseline, sample, true));
    }

    @Test
    public void driftUnavailableWhenSampleNullEvenThoughBaselineOk() {
        // THE bug: baseline poll succeeded but the current/terminal poll failed later (very plausible
        // once load ramps and the drift endpoint gets starved). Must be unavailable, not a fake zero.
        LoadRun.Drift baseline = LoadRun.parseDrift("1000,10,1000");

        assertTrue(LoadRun.driftUnavailable(true, baseline, null, true));
    }

    @Test
    public void driftUnavailableWhenBaselineNull() {
        LoadRun.Drift sample = LoadRun.parseDrift("995,10,2000");

        assertTrue(LoadRun.driftUnavailable(true, null, sample, true));
    }

    @Test
    public void driftUnavailableWhenModeNotConfirmed() {
        LoadRun.Drift baseline = LoadRun.parseDrift("1000,10,1000");
        LoadRun.Drift sample = LoadRun.parseDrift("995,10,2000");

        assertTrue(LoadRun.driftUnavailable(true, baseline, sample, false));
    }
}
