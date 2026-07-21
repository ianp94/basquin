package runner.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AFL-style live status for a running harness. Enable with {@code -Dbasquin.status=true}.
 *
 * On a terminal it redraws a compact status block in place every interval
 * ({@code -Dbasquin.status.intervalMs}, default 1000). When output is not a TTY
 * (piped, CI), it prints a single summary line per interval instead of redrawing.
 * When active, the per-iteration metrics spam from {@link agent.Agent} is suppressed
 * so it does not fight the redraw (Agent checks {@link #isEnabled()}).
 *
 * Counters are updated once per iteration from a single serialized point, so a plain
 * synchronized snapshot is enough; there is no hot-path contention.
 */
public final class StatusReporter {

    private static final boolean ENABLED = Boolean.getBoolean("basquin.status");
    private static final long INTERVAL_MS = Long.getLong("basquin.status.intervalMs", 1000L);
    // Redraw in place when attached to a terminal; forceTty renders the box even when piped
    // (useful for capturing the rich view to a log).
    private static final boolean TTY =
            System.console() != null || Boolean.getBoolean("basquin.status.forceTty");

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
    // Exploration (fuzzing/corpus) counters, fed from the triage layer.
    private static long corpusSaved;
    private static long findCrash;
    private static long findInvariant;
    private static long rejected;   // expected input rejections (not crashes)
    private static long lastFindNanos;
    private static long lastFindIter;
    // Coverage of the code under test, reported by a coverage source (v0.10 server-side agent
    // over HTTP, or an in-process JaCoCo/JQF provider). Zero until a source reports.
    private static long coveredEdges;
    private static long totalEdges;
    // How many coverage sources (pods) answered the last sample, out of how many were expected.
    // When responded < expected the coverage % is UNDER-reporting (a replica is down/unreachable),
    // which is the exact failure mode multi-pod merging exists to prevent — so it is surfaced, not
    // hidden. Zero/zero means a single-source or not-yet-reported run.
    private static int coverageSourcesResponded;
    private static int coverageSourcesExpected;

    private StatusReporter() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Start the render thread once, before the first iteration baseline (runners call this). */
    public static void ensureStarted() {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(StatusReporter::renderLoop, "Basquin-Status");
        t.setDaemon(true);
        t.start();
        // Guarantee a final frame on exit for any run type (JQF, corpus, generic), not just the
        // runners that call renderFinal() explicitly.
        Runtime.getRuntime().addShutdownHook(new Thread(StatusReporter::renderFinal, "Basquin-Status-Final"));
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
    /** An expected input rejection (a target's declared "bad input" exception), not a crash. */
    public static synchronized void recordRejected() { if (ENABLED) rejected++; }

    /**
     * Record a saved exploration finding (from the triage layer). {@code classification} is the
     * triage label, e.g. "Crash", "Invariant", "Invariant-Remote". Drives the exploration panel.
     */
    public static synchronized void recordSaved(String classification) {
        if (!ENABLED) return;
        corpusSaved++;
        if (classification != null && classification.startsWith("Crash")) {
            findCrash++;
        } else if (classification != null && classification.startsWith("Invariant")) {
            findInvariant++;
        }
        lastFindNanos = System.nanoTime();
        lastFindIter = iterations;
    }

    /**
     * Report coverage of the code under test. {@code total} is the instrumentable denominator
     * (edges/branches) so the panel can show a real percentage. A coverage source — the v0.10
     * server-side agent reporting per-request coverage over HTTP, or an in-process JaCoCo/JQF
     * provider — calls this; the coverage row appears once total &gt; 0.
     */
    public static synchronized void recordCoverage(long covered, long total) {
        recordCoverage(covered, total, 0, 0);
    }

    /** As above, plus how many coverage sources answered vs were expected (fleet under-reporting). */
    public static synchronized void recordCoverage(long covered, long total, int sourcesResponded, int sourcesExpected) {
        if (!ENABLED) return;
        coveredEdges = covered;
        totalEdges = total;
        coverageSourcesResponded = sourcesResponded;
        coverageSourcesExpected = sourcesExpected;
    }

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
        boolean exploring = corpusSaved > 0 || Boolean.getBoolean("basquin.status.explore");

        if (!TTY) {
            String cov = totalEdges > 0
                ? String.format(" cov=%.1f%%", 100.0 * coveredEdges / totalEdges) : "";
            String explore = exploring
                ? String.format(" explore[corpus=%d crash=%d inv=%d rejected=%d%s lastFind=%s]",
                        corpusSaved, findCrash, findInvariant, rejected, cov, sinceLastFind())
                : "";
            System.out.printf(
                "[Basquin] %s iters=%d (%.1f/s) crashes=%d leaks=%d inv[lat=%d heap=%d thr=%d] "
                + "lat(last/mean/max)=%d/%.0f/%dms heap(last/max)=%d/%dKB threads=%d resets=%d%s%n",
                fmt(elapsedS), iterations, rate, crashes, leaks, violLatency, violHeap, violThread,
                lastLatencyMs, meanLatency, maxLatencyMs, lastHeapKb, maxHeapKb, lastThreads, resets, explore);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[H[2J"); // cursor home + clear screen
        sb.append("┌─ Basquin ──────────────────────────────────┐\n");
        row(sb, "run time", fmt(elapsedS));
        row(sb, "iterations", String.format("%d  (%.1f/s)", iterations, rate));
        row(sb, "crashes", String.valueOf(crashes));
        row(sb, "leaks", String.valueOf(leaks));
        row(sb, "invariants", String.format("lat=%d  heap=%d  thread=%d", violLatency, violHeap, violThread));
        row(sb, "latency ms", String.format("last=%d  mean=%.0f  max=%d", lastLatencyMs, meanLatency, maxLatencyMs));
        row(sb, "heap Δ KB", String.format("last=%d  max=%d", lastHeapKb, maxHeapKb));
        row(sb, "threads", String.valueOf(lastThreads));
        row(sb, "resets", String.valueOf(resets));
        if (exploring) {
            sb.append("├─ exploration ─────────────────────────────────┤\n");
            row(sb, "execs", String.format("%d  (%.1f/s)", iterations, rate));
            if (totalEdges > 0) {
                String src = (coverageSourcesExpected > 1 || coverageSourcesResponded < coverageSourcesExpected)
                        ? String.format("  [%d/%d pods]", coverageSourcesResponded, coverageSourcesExpected) : "";
                row(sb, "coverage", String.format("%.1f%%  (%d/%d edges)%s",
                        100.0 * coveredEdges / totalEdges, coveredEdges, totalEdges, src));
            }
            row(sb, "corpus", String.valueOf(corpusSaved));
            row(sb, "finds", String.format("crash=%d  invariant=%d", findCrash, findInvariant));
            row(sb, "rejected", String.valueOf(rejected));
            row(sb, "last find", sinceLastFind());
        }
        sb.append("└───────────────────────────────────────────────┘");
        System.out.println(sb);
    }

    /** Current metrics as a JSON object, for the dashboard API. */
    public static synchronized String snapshotJson() {
        long elapsedNanos = Math.max(0L, System.nanoTime() - START_NANOS);
        long elapsedS = elapsedNanos / 1_000_000_000L;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double rate = elapsedSec > 0.05 ? iterations / elapsedSec : 0.0;
        double meanLatency = iterations > 0 ? (double) sumLatencyMs / iterations : 0.0;
        double coveragePct = totalEdges > 0 ? 100.0 * coveredEdges / totalEdges : 0.0;
        return String.format(java.util.Locale.ROOT,
            "{\"elapsedSec\":%d,\"iterations\":%d,\"rate\":%.2f,\"crashes\":%d,\"leaks\":%d,\"resets\":%d,"
            + "\"invariants\":{\"latency\":%d,\"heap\":%d,\"thread\":%d},"
            + "\"latencyMs\":{\"last\":%d,\"mean\":%.0f,\"max\":%d},"
            + "\"heapKb\":{\"last\":%d,\"max\":%d},\"threads\":%d,"
            + "\"exploration\":{\"corpus\":%d,\"findCrash\":%d,\"findInvariant\":%d,\"rejected\":%d,"
            + "\"coverage\":{\"covered\":%d,\"total\":%d,\"pct\":%.1f,"
            + "\"sourcesResponded\":%d,\"sourcesExpected\":%d}}}",
            elapsedS, iterations, rate, crashes, leaks, resets,
            violLatency, violHeap, violThread,
            lastLatencyMs, meanLatency, maxLatencyMs,
            lastHeapKb, maxHeapKb, lastThreads,
            corpusSaved, findCrash, findInvariant, rejected,
            coveredEdges, totalEdges, coveragePct,
            coverageSourcesResponded, coverageSourcesExpected);
    }

    private static String sinceLastFind() {
        if (lastFindNanos == 0L) {
            return "none yet";
        }
        long agoS = Math.max(0L, (System.nanoTime() - lastFindNanos) / 1_000_000_000L);
        return String.format("%ds ago (@ iter %d)", agoS, lastFindIter);
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
