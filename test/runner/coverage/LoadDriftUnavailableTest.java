package runner.coverage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DD-035: when the target's drift can't be trusted (a failed baseline poll, or a load-mode toggle the
 * target never confirmed), the terminal summary must say so as a first-class {@code driftUnavailable}
 * signal — NOT silently print {@code heapDriftKb:0}, which is indistinguishable from a real flat heap.
 * Exercises the extracted {@link LoadRun#summaryJson} directly (no server needed).
 */
public class LoadDriftUnavailableTest {

    /**
     * DD-038: a populated {@code redirectTargets} must serialize as VALID, bounded JSON. The operator
     * parses the terminal summary with {@code json.Unmarshal} and silently drops the WHOLE summary if
     * it's malformed — so a fuzzed {@code Location} (quotes, control chars) must already have been
     * charset-restricted by {@code safeKey} before it reaches the JSON.
     */
    @Test
    public void redirectTargetsSerializeAsValidJson() {
        LoadRun.DriftDelta drift = LoadRun.driftDelta(
                LoadRun.parseDrift("1000,10,1000"), LoadRun.parseDrift("1200,10,2000"));
        java.util.Map<String, Long> targets = new java.util.LinkedHashMap<>();
        targets.put("self", 42L);
        targets.put("SessionExpired", 5L);
        targets.put(LoadRun.normalizeLocation("/Wiki.jsp?page=a\"b<c", null), 1L);  // fuzzed → safeKey'd
        String json = LoadRun.summaryJson(100, 50.0, 1, 2, 3, 4, drift, 0, 0, true, 0, 0, false, 47, targets);

        assertTrue("redirects count present", json.contains("\"redirects\":47"));
        assertTrue("self-fold key present", json.contains("\"self\":42"));
        assertTrue("reject key present", json.contains("\"SessionExpired\":5"));
        assertFalse("a fuzzed Location must not leak a raw quote into the JSON", json.contains("a\"b"));
        // balanced braces/quotes => parseable; a stray quote from an unescaped key would break this
        assertEquals("every quote must be paired", 0, json.chars().filter(c -> c == '"').count() % 2);
        assertEquals("braces must balance",
                json.chars().filter(c -> c == '{').count(), json.chars().filter(c -> c == '}').count());
    }

    @Test
    public void driftUnavailableOmitsHeapAndThreadDrift() {
        // The drift delta's actual values are irrelevant when unavailable — they must never surface.
        LoadRun.DriftDelta drift = LoadRun.driftDelta(null, null);
        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0, true, 0, 0, true, 0, java.util.Map.of());

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

        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0, true, 0, 0, false, 0, java.util.Map.of());

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

    // --- DD-040: the STRUCTURAL zeros in the violations block. Load mode is lock-free passthrough
    // (DD-029), so the target evaluates nothing and the DRIVER is the only evaluator — and it only
    // evaluates latency, only when a threshold was configured. Roller reported
    // "violations":{"latency":0,"heap":0,"thread":0} at a p50 of 503ms against an intended 250ms
    // budget, because the load driver JVM was never given -Dbasquin.invariant.latency.maxMs. A count
    // of 0 for something that was never checked is a fabricated "checked and clean".

    @Test
    public void noLatencyThresholdOmitsTheCountAndSaysSo() {
        LoadRun.DriftDelta drift = LoadRun.driftDelta(
                LoadRun.parseDrift("1000,10,1000"), LoadRun.parseDrift("1000,10,1000"));

        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0,
                /* latencyEvaluated */ false, 0, 0, false, 0, java.util.Map.of());

        assertFalse("an unevaluated latency invariant must NOT print a count",
                json.contains("\"latency\":"));
        assertTrue("and must say, in data, that it was not evaluated",
                json.contains("\"notEvaluated\":[\"latency\",\"heap\",\"thread\"]"));
    }

    @Test
    public void heapAndThreadViolationsAreNeverCountedInLoadMode() {
        LoadRun.DriftDelta drift = LoadRun.driftDelta(
                LoadRun.parseDrift("1000,10,1000"), LoadRun.parseDrift("1000,10,1000"));

        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 7,
                /* latencyEvaluated */ true, 0, 0, false, 0, java.util.Map.of());

        assertTrue("a configured latency threshold reports its real count", json.contains("\"latency\":7"));
        assertFalse("load mode never evaluates heap; a 0 there is fabricated", json.contains("\"heap\":"));
        assertFalse("load mode never evaluates threads either", json.contains("\"thread\":"));
        assertTrue("the omission must be explicit, not silent",
                json.contains("\"notEvaluated\":[\"heap\",\"thread\"]"));
    }

    @Test
    public void aRealMeasuredZeroIsStillReportedAsZero() {
        // The whole point of the change: 0 must remain expressible — it just has to MEAN
        // "checked and clean" rather than "never checked".
        LoadRun.DriftDelta drift = LoadRun.driftDelta(
                LoadRun.parseDrift("1000,10,1000"), LoadRun.parseDrift("1000,10,1000"));

        String json = LoadRun.summaryJson(100, 50.0, 10, 20, 30, 40, drift, 0, 0,
                /* latencyEvaluated */ true, 0, 0, false, 0, java.util.Map.of());

        assertTrue("a measured zero must still print", json.contains("\"latency\":0"));
        assertFalse("and must not be listed as unevaluated", json.contains("\"latency\",\"heap\""));
    }
}
