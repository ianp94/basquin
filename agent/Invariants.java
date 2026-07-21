package agent;

/**
 * Minimal invariant framework for v0.2: configurable thresholds with hard/soft modes.
 * Defaults are disabled unless properties are set.
 *
 * Properties (global and per-invariant):
 * - basquin.invariant.mode = hard|soft (default: hard)
 * - basquin.invariant.latency.maxMs = <long>
 *   - basquin.invariant.latency.mode = hard|soft (optional override)
 * - basquin.invariant.heapDelta.maxKb = <long>
 *   - basquin.invariant.heapDelta.mode = hard|soft
 * - basquin.invariant.threadDelta.max = <int>
 *   - basquin.invariant.threadDelta.mode = hard|soft
 */
final class Invariants {

    private Invariants() {}

    public static class Violation {
        final String name;
        final String detail;
        Violation(String name, String detail) { this.name = name; this.detail = detail; }
    }

    static void evaluateAndMaybeFail(IterationContext ctx,
                                     long elapsedMs,
                                     long heapDeltaBytes,
                                     int threadsNow,
                                     int threadsDelta) {
        int iteration = ctx.iterationNumber;
        java.util.List<Violation> violations = new java.util.ArrayList<>();

        // Latency
        Long latencyMax = getLongProp("basquin.invariant.latency.maxMs");
        if (latencyMax != null && elapsedMs > latencyMax) {
            violations.add(new Violation("latency", String.format("%dms > %dms", elapsedMs, latencyMax)));
            logViolation(iteration, "latency", String.format("%dms > %dms", elapsedMs, latencyMax));
            if (isHard("basquin.invariant.latency.mode")) {
                agent.Agent.recordInvariantEvidence(ctx, violations);
                throw new IllegalStateException("Latency invariant violated: elapsedMs=" + elapsedMs + " > maxMs=" + latencyMax);
            }
        }

        // Heap delta (Kb)
        Long heapMaxKb = getLongProp("basquin.invariant.heapDelta.maxKb");
        long heapDeltaKb = heapDeltaBytes / 1024L;
        if (heapMaxKb != null && heapDeltaKb > heapMaxKb) {
            violations.add(new Violation("heapDelta", String.format("%dKB > %dKB", heapDeltaKb, heapMaxKb)));
            logViolation(iteration, "heapDelta", String.format("%dKB > %dKB", heapDeltaKb, heapMaxKb));
            if (isHard("basquin.invariant.heapDelta.mode")) {
                agent.Agent.recordInvariantEvidence(ctx, violations);
                throw new IllegalStateException("Heap delta invariant violated: deltaKb=" + heapDeltaKb + " > maxKb=" + heapMaxKb);
            }
        }

        // Thread count delta
        Integer thrMax = getIntProp("basquin.invariant.threadDelta.max");
        if (thrMax != null && threadsDelta > thrMax) {
            violations.add(new Violation("threadDelta", String.format("%d > %d (threadsNow=%d)", threadsDelta, thrMax, threadsNow)));
            logViolation(iteration, "threadDelta", String.format("%d > %d (threadsNow=%d)", threadsDelta, thrMax, threadsNow));
            if (isHard("basquin.invariant.threadDelta.mode")) {
                agent.Agent.recordInvariantEvidence(ctx, violations);
                throw new IllegalStateException("Thread delta invariant violated: delta=" + threadsDelta + " > max=" + thrMax);
            }
        }

        agent.Agent.recordInvariantEvidence(ctx, violations);
        if (!violations.isEmpty() && isHard(null)) {
            // Global mode hard with no per-invariant hard violation thrown above
            throw new IllegalStateException("Invariant(s) violated. See log for details.");
        }
    }

    private static void logViolation(int iteration, String name, String detail) {
        System.err.println("[Basquin][Invariant] Iteration " + iteration + " violated '" + name + "': " + detail);
    }

    static boolean isHard(String overrideKey) {
        String v = null;
        if (overrideKey != null) v = System.getProperty(overrideKey);
        if (v == null) v = System.getProperty("basquin.invariant.mode", "hard");
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
