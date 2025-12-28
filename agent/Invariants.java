package agent;

/**
 * Minimal invariant framework for v0.2: configurable thresholds with hard/soft modes.
 * Defaults are disabled unless properties are set.
 *
 * Properties (global and per-invariant):
 * - closurejvm.invariant.mode = hard|soft (default: hard)
 * - closurejvm.invariant.latency.maxMs = <long>
 *   - closurejvm.invariant.latency.mode = hard|soft (optional override)
 * - closurejvm.invariant.heapDelta.maxKb = <long>
 *   - closurejvm.invariant.heapDelta.mode = hard|soft
 * - closurejvm.invariant.threadDelta.max = <int>
 *   - closurejvm.invariant.threadDelta.mode = hard|soft
 */
final class Invariants {

    private Invariants() {}

    static void evaluateAndMaybeFail(int iteration,
                                     long elapsedMs,
                                     long heapDeltaBytes,
                                     int threadsNow,
                                     int threadsDelta) {
        boolean anyViolation = false;

        // Latency
        Long latencyMax = getLongProp("closurejvm.invariant.latency.maxMs");
        if (latencyMax != null && elapsedMs > latencyMax) {
            anyViolation = true;
            logViolation(iteration, "latency", String.format("%dms > %dms", elapsedMs, latencyMax));
            if (isHard("closurejvm.invariant.latency.mode")) {
                throw new IllegalStateException("Latency invariant violated: elapsedMs=" + elapsedMs + " > maxMs=" + latencyMax);
            }
        }

        // Heap delta (Kb)
        Long heapMaxKb = getLongProp("closurejvm.invariant.heapDelta.maxKb");
        long heapDeltaKb = heapDeltaBytes / 1024L;
        if (heapMaxKb != null && heapDeltaKb > heapMaxKb) {
            anyViolation = true;
            logViolation(iteration, "heapDelta", String.format("%dKB > %dKB", heapDeltaKb, heapMaxKb));
            if (isHard("closurejvm.invariant.heapDelta.mode")) {
                throw new IllegalStateException("Heap delta invariant violated: deltaKb=" + heapDeltaKb + " > maxKb=" + heapMaxKb);
            }
        }

        // Thread count delta
        Integer thrMax = getIntProp("closurejvm.invariant.threadDelta.max");
        if (thrMax != null && threadsDelta > thrMax) {
            anyViolation = true;
            logViolation(iteration, "threadDelta", String.format("%d > %d (threadsNow=%d)", threadsDelta, thrMax, threadsNow));
            if (isHard("closurejvm.invariant.threadDelta.mode")) {
                throw new IllegalStateException("Thread delta invariant violated: delta=" + threadsDelta + " > max=" + thrMax);
            }
        }

        if (anyViolation && isHard(null)) {
            // Global mode hard with no per-invariant hard violation thrown above
            throw new IllegalStateException("Invariant(s) violated. See log for details.");
        }
    }

    private static void logViolation(int iteration, String name, String detail) {
        System.err.println("[ClosureJVM][Invariant] Iteration " + iteration + " violated '" + name + "': " + detail);
    }

    private static boolean isHard(String overrideKey) {
        String v = null;
        if (overrideKey != null) v = System.getProperty(overrideKey);
        if (v == null) v = System.getProperty("closurejvm.invariant.mode", "hard");
        return "hard".equalsIgnoreCase(v);
    }

    private static Long getLongProp(String key) {
        String v = System.getProperty(key);
        if (v == null) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private static Integer getIntProp(String key) {
        String v = System.getProperty(key);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { return null; }
    }
}

