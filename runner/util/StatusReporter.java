package runner.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AFL-style live status for a running harness. Enable with {@code -Dclosurejvm.status=true}.
 *
 * On a terminal it redraws a compact status block in place every interval
 * ({@code -Dclosurejvm.status.intervalMs}, default 1000). When output is not a TTY
 * (piped, CI), it prints a single summary line per interval instead of redrawing.
 * When active, the per-iteration metrics spam from {@link agent.Agent} is suppressed
 * so it does not fight the redraw (Agent checks {@link #isEnabled()}).
 *
 * Counters are updated once per iteration from a single serialized point, so a plain
 * synchronized snapshot is enough; there is no hot-path contention.
 */
public final class StatusReporter {

    private static final boolean ENABLED = Boolean.getBoolean("closurejvm.status");
    private static final long INTERVAL_MS = Long.getLong("closurejvm.status.intervalMs", 1000L);
    // Redraw in place when attached to a terminal; forceTty renders the box even when piped
    // (useful for capturing the rich view to a log).
    private static final boolean TTY =
            System.console() != null || Boolean.getBoolean("closurejvm.status.forceTty");

    private static final long START_NANOS = System.nanoTime();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    // All guarded by the class monitor.
    private static long iterations;
    private static long crashes;
    private static long leaks;
    private static long resets;
    private static long violLatency;
    private static long violHeap;
    private static long violThread;
    private static long lastLatencyMs;
    private static long maxLatencyMs;
    private static long sumLatencyMs;
    private static long lastHeapKb;
    private static long maxHeapKb;
    private static int lastThreads;

    private StatusReporter() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Start the render thread once, before the first iteration baseline (runners call this). */
    public static void ensureStarted() {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(StatusReporter::renderLoop, "ClosureJVM-Status");
        t.setDaemon(true);
        t.start();
    }

    /** Record one completed iteration's metrics and any violations/leak it carried. */
    public static synchronized void recordIteration(long latencyMs, long heapDeltaKb, int threads,
                                                    boolean leak, java.util.List<String> violations) {
        if (!ENABLED) return;
        iterations++;
        lastLatencyMs = latencyMs;
        if (latencyMs > maxLatencyMs) maxLatencyMs = latencyMs;
        sumLatencyMs += latencyMs;
        lastHeapKb = heapDeltaKb;
        if (heapDeltaKb > maxHeapKb) maxHeapKb = heapDeltaKb;
        lastThreads = threads;
        if (leak) leaks++;
        if (violations != null) {
            for (String v : violations) {
                if (v.startsWith("latency")) violLatency++;
                else if (v.startsWith("heap")) violHeap++;
                else if (v.startsWith("thread")) violThread++;
            }
        }
    }

    public static synchronized void recordCrash() { if (ENABLED) crashes++; }
    public static synchronized void recordReset() { if (ENABLED) resets++; }

    /** Render one final status frame — call when the run ends so the final tally always shows. */
    public static void renderFinal() {
        if (ENABLED) {
            render();
        }
    }

    private static void renderLoop() {
        while (true) {
            try {
                render();
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // never let the status thread crash the run
            }
        }
    }

    private static synchronized void render() {
        long elapsedNanos = Math.max(0L, System.nanoTime() - START_NANOS);
        long elapsedS = elapsedNanos / 1_000_000_000L;              // for the HH:MM:SS display
        double elapsedSec = elapsedNanos / 1_000_000_000.0;         // fractional, for the rate
        double rate = elapsedSec > 0.05 ? iterations / elapsedSec : 0.0;
        double meanLatency = iterations > 0 ? (double) sumLatencyMs / iterations : 0.0;

        if (!TTY) {
            System.out.printf(
                "[ClosureJVM] %s iters=%d (%.1f/s) crashes=%d leaks=%d inv[lat=%d heap=%d thr=%d] "
                + "lat(last/mean/max)=%d/%.0f/%dms heap(last/max)=%d/%dKB threads=%d resets=%d%n",
                fmt(elapsedS), iterations, rate, crashes, leaks, violLatency, violHeap, violThread,
                lastLatencyMs, meanLatency, maxLatencyMs, lastHeapKb, maxHeapKb, lastThreads, resets);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[H[2J"); // cursor home + clear screen
        sb.append("┌─ ClosureJVM ──────────────────────────────────┐\n");
        row(sb, "run time", fmt(elapsedS));
        row(sb, "iterations", String.format("%d  (%.1f/s)", iterations, rate));
        row(sb, "crashes", String.valueOf(crashes));
        row(sb, "leaks", String.valueOf(leaks));
        row(sb, "invariants", String.format("lat=%d  heap=%d  thread=%d", violLatency, violHeap, violThread));
        row(sb, "latency ms", String.format("last=%d  mean=%.0f  max=%d", lastLatencyMs, meanLatency, maxLatencyMs));
        row(sb, "heap Δ KB", String.format("last=%d  max=%d", lastHeapKb, maxHeapKb));
        row(sb, "threads", String.valueOf(lastThreads));
        row(sb, "resets", String.valueOf(resets));
        sb.append("└───────────────────────────────────────────────┘");
        System.out.println(sb);
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append(String.format("│ %-12s %-33s │%n", label, value));
    }

    private static String fmt(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
