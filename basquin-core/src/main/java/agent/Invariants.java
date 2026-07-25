package agent;

import java.util.ArrayList;
import java.util.List;

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
 * - basquin.invariant.leak.mode = hard|soft — leak detection (Agent.end()) is not threshold-driven
 *   and lives in Agent, but resolves its mode through {@link #isHard(String)} like every other
 *   invariant, so one global switch covers all of them.
 *
 * <p>This class lives in {@code basquin-core}, a separate compilation unit from {@code agent.Agent}
 * and {@code agent.IterationContext} (both stay in the root project). It keeps {@code package agent}
 * rather than moving to a package of its own — see the Package Decision in
 * docs/superpowers/plans/2026-07-25-dd043-pr1-basquin-core.md: {@code GenericRunner}'s reset
 * ClassLoader loads anything matching {@code "agent."} parent-first, so a rename would make this
 * class load child-first instead and produce a fresh {@link ResultStore} per reset. {@code
 * Invariants} cannot call back into {@code Agent} to record evidence or throw — that would be a
 * circular project dependency (basquin-core -> root -> basquin-core), which Gradle cannot build.
 *
 * <p>So {@link #evaluateAndMaybeFail} takes only primitives and returns a {@link Result} instead
 * of mutating a context or throwing. The caller ({@code Agent.end()}) records evidence and decides
 * whether to throw, at the exact same point and in the exact same order as before — see the call
 * site there for the one-line justification. Evaluation order, per-invariant early-exit, log
 * lines and exception messages are byte-for-byte unchanged; only which method physically executes
 * the "record evidence" / "throw" side effects moved, and it moved to the same synchronous call
 * stack frame, one level up.
 */
final class Invariants {

    private Invariants() {}

    public static class Violation {
        final String name;
        final String detail;
        Violation(String name, String detail) { this.name = name; this.detail = detail; }
    }

    /**
     * Outcome of one evaluation: every violation found (possibly empty), and — only when
     * evaluation decided the iteration must fail hard — the exact message the caller should
     * throw as an {@code IllegalStateException}. {@code hardFailureMessage == null} means the
     * caller must NOT throw (soft mode, or no violations).
     */
    static final class Result {
        final List<Violation> violations;
        final String hardFailureMessage;
        private Result(List<Violation> violations, String hardFailureMessage) {
            this.violations = violations;
            this.hardFailureMessage = hardFailureMessage;
        }
    }

    static Result evaluateAndMaybeFail(int iterationNumber,
                                     long elapsedMs,
                                     long heapDeltaBytes,
                                     int threadsNow,
                                     int threadsDelta) {
        List<Violation> violations = new ArrayList<>();

        // Latency
        Long latencyMax = getLongProp("basquin.invariant.latency.maxMs");
        if (latencyMax != null && elapsedMs > latencyMax) {
            violations.add(new Violation("latency", String.format("%dms > %dms", elapsedMs, latencyMax)));
            logViolation(iterationNumber, "latency", String.format("%dms > %dms", elapsedMs, latencyMax));
            if (isHard("basquin.invariant.latency.mode")) {
                return new Result(violations, "Latency invariant violated: elapsedMs=" + elapsedMs + " > maxMs=" + latencyMax);
            }
        }

        // Heap delta (Kb)
        Long heapMaxKb = getLongProp("basquin.invariant.heapDelta.maxKb");
        long heapDeltaKb = heapDeltaBytes / 1024L;
        if (heapMaxKb != null && heapDeltaKb > heapMaxKb) {
            violations.add(new Violation("heapDelta", String.format("%dKB > %dKB", heapDeltaKb, heapMaxKb)));
            logViolation(iterationNumber, "heapDelta", String.format("%dKB > %dKB", heapDeltaKb, heapMaxKb));
            if (isHard("basquin.invariant.heapDelta.mode")) {
                return new Result(violations, "Heap delta invariant violated: deltaKb=" + heapDeltaKb + " > maxKb=" + heapMaxKb);
            }
        }

        // Thread count delta
        Integer thrMax = getIntProp("basquin.invariant.threadDelta.max");
        if (thrMax != null && threadsDelta > thrMax) {
            violations.add(new Violation("threadDelta", String.format("%d > %d (threadsNow=%d)", threadsDelta, thrMax, threadsNow)));
            logViolation(iterationNumber, "threadDelta", String.format("%d > %d (threadsNow=%d)", threadsDelta, thrMax, threadsNow));
            if (isHard("basquin.invariant.threadDelta.mode")) {
                return new Result(violations, "Thread delta invariant violated: delta=" + threadsDelta + " > max=" + thrMax);
            }
        }

        if (!violations.isEmpty() && isHard(null)) {
            // Global mode hard with no per-invariant hard violation returned above
            return new Result(violations, "Invariant(s) violated. See log for details.");
        }
        return new Result(violations, null);
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
