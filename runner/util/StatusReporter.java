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

    // Opt-in CSV time-series sampler for headless benchmark capture: when -Dbasquin.sample.out is
    // set, write one row of the live counters every SAMPLE_INTERVAL_MS. Runs independently of the
    // TTY box (basquin.status) — but its presence flips TRACKING on so the counters actually advance.
    private static final String SAMPLE_OUT = System.getProperty("basquin.sample.out", "");
    private static final long SAMPLE_INTERVAL_MS = Long.getLong("basquin.sample.intervalMs", 5000L);
    private static final AtomicBoolean SAMPLER_STARTED = new AtomicBoolean(false);
    // Counters advance when EITHER the box or the sampler is active.
    private static final boolean TRACKING = ENABLED || !SAMPLE_OUT.isEmpty();
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

    // DD-033: mode + load-block state (load campaigns feed these via recordLoad).
    private static volatile String mode = "explore";
    private static double loadThroughputRps;
    private static int loadP50, loadP90, loadP99, loadMax, loadThreadDrift;
    private static long loadHeapDriftKb, loadServerErrors, loadRequests;
    private static boolean loadRecorded;
    // DD-035: true when the target's heap/thread drift is not a trustworthy number this interval (the
    // baseline drift poll never succeeded, or the target never confirmed load mode) — surfaced as
    // "driftUnavailable":true instead of a fake heapDriftKb:0.
    private static boolean loadDriftUnavailable;

    private StatusReporter() {}

    /**
     * True when the reporter is tracking — the TTY box (basquin.status) OR the CSV sampler
     * (basquin.sample.out) is active. Callers gate their record* calls on this, so returning true
     * for the sampler-only case is what makes the counters (and thus the CSV) populate.
     */
    public static boolean isEnabled() {
        return TRACKING;
    }

    /** Start the render thread and/or CSV sampler once, before the first iteration baseline. */
    public static void ensureStarted() {
        if (ENABLED && STARTED.compareAndSet(false, true)) {
            Thread t = new Thread(StatusReporter::renderLoop, "Basquin-Status");
            t.setDaemon(true);
            t.start();
            // Guarantee a final frame on exit for any run type (JQF, corpus, generic), not just the
            // runners that call renderFinal() explicitly.
            Runtime.getRuntime().addShutdownHook(new Thread(StatusReporter::renderFinal, "Basquin-Status-Final"));
        }
        if (!SAMPLE_OUT.isEmpty() && SAMPLER_STARTED.compareAndSet(false, true)) {
            Thread s = new Thread(StatusReporter::sampleLoop, "Basquin-Sampler");
            s.setDaemon(true);
            s.start();
            // A final row on exit so the last interval (up to SAMPLE_INTERVAL_MS of the run) isn't lost.
            Runtime.getRuntime().addShutdownHook(new Thread(StatusReporter::sampleFinal, "Basquin-Sampler-Final"));
        }
    }

    /** Record one completed iteration's metrics and any violations/leak it carried. */
    public static synchronized void recordIteration(long latencyMs, long heapDeltaKb, int threads,
                                                    boolean leak, java.util.List<String> violations) {
        if (!TRACKING) return;
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

    /** DD-033: campaign mode ("explore"|"load"); tags snapshotJson so the dashboard/CLI pick a view. */
    public static synchronized void setMode(String m) { mode = (m == null ? "explore" : m); }

    /** DD-033: publish the current load snapshot for the dashboard (fed live by LoadRun's snapshotter).
     *  DD-035: {@code driftUnavailable} is true when the heap/thread drift for THIS snapshot is not a
     *  trustworthy number (baseline poll never succeeded, or the target never confirmed load mode) —
     *  {@link #loadBlockJson} then omits heapDriftKb/threadDrift and emits driftUnavailable:true instead. */
    public static synchronized void recordLoad(double throughputRps, int p50, int p90, int p99, int max,
            long heapDriftKb, int threadDrift, long serverErrors, long requests, boolean driftUnavailable) {
        loadThroughputRps = throughputRps; loadP50 = p50; loadP90 = p90; loadP99 = p99; loadMax = max;
        loadHeapDriftKb = heapDriftKb; loadThreadDrift = threadDrift; loadServerErrors = serverErrors;
        loadRequests = requests; loadRecorded = true; loadDriftUnavailable = driftUnavailable;
    }

    /** The load block, or "" unless we're in load mode AND a load snapshot has been recorded. Gating on
     *  the mode (not just loadRecorded) keeps the invariant explicit — a load block appears iff mode==load
     *  — so an explore snapshot never carries a stale block (also removes a test-ordering dependency). */
    private static String loadBlockJson() {
        if (!loadRecorded || !"load".equals(mode)) return "";
        String driftJson = loadDriftUnavailable
                ? "\"driftUnavailable\":true"
                : "\"heapDriftKb\":" + loadHeapDriftKb + ",\"threadDrift\":" + loadThreadDrift;
        return String.format(java.util.Locale.ROOT,
            ",\"load\":{\"throughputRps\":\"%.1f\",\"latencyMs\":{\"p50\":%d,\"p90\":%d,\"p99\":%d,\"max\":%d},"
          + "%s,\"serverErrors\":%d,\"requests\":%d}",
            loadThroughputRps, loadP50, loadP90, loadP99, loadMax,
            driftJson, loadServerErrors, loadRequests);
    }

    public static synchronized void recordCrash() { if (TRACKING) crashes++; }
    public static synchronized void recordReset() { if (TRACKING) resets++; }
    /** An expected input rejection (a target's declared "bad input" exception), not a crash. */
    public static synchronized void recordRejected() { if (TRACKING) rejected++; }

    /**
     * Record a saved exploration finding (from the triage layer). {@code classification} is the
     * triage label, e.g. "Crash", "Invariant", "Invariant-Remote". Drives the exploration panel.
     */
    public static synchronized void recordSaved(String classification) {
        if (!TRACKING) return;
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
        if (!TRACKING) return;
        coveredEdges = covered;
        totalEdges = total;
        coverageSourcesResponded = sourcesResponded;
        coverageSourcesExpected = sourcesExpected;
    }

    /** Render one final status frame — call when the run ends so the final tally always shows. */
    public static void renderFinal() {
        if (ENABLED) { // the box only; a sampler-only run draws nothing to the terminal
            render();
        }
    }

    // --- CSV time-series sampler (-Dbasquin.sample.out) ---

    /** CSV header for the sampler; column order matches {@link #sampleLine(long)}. */
    public static String sampleHeader() {
        return "elapsedMs,iterations,itersPerSec,lastLatencyMs,meanLatencyMs,maxLatencyMs,"
             + "lastHeapKb,maxHeapKb,threads,crashes,leaks,violLatency,violHeap,violThread,"
             + "coveredEdges,totalEdges,coveragePct";
    }

    /** One CSV row of the live counters at {@code elapsedMs}. Locale-independent decimals (CSV-safe). */
    public static synchronized String sampleLine(long elapsedMs) {
        double secs = elapsedMs / 1000.0;
        double ips = secs > 0 ? iterations / secs : 0.0;
        long mean = iterations > 0 ? sumLatencyMs / iterations : 0L;
        double covPct = totalEdges > 0 ? (coveredEdges * 100.0) / totalEdges : 0.0;
        return elapsedMs + "," + iterations + "," + fmt2(ips) + ","
             + lastLatencyMs + "," + mean + "," + maxLatencyMs + ","
             + lastHeapKb + "," + maxHeapKb + "," + lastThreads + ","
             + crashes + "," + leaks + "," + violLatency + "," + violHeap + "," + violThread + ","
             + coveredEdges + "," + totalEdges + "," + fmt2(covPct);
    }

    // Root-locale so a decimal never renders as "3,14" and corrupts the CSV.
    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static void sampleLoop() {
        // Header once (truncate/create); a failure here disables sampling loudly but doesn't kill the run.
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(java.nio.file.Paths.get(SAMPLE_OUT))) {
            w.write(sampleHeader());
            w.write("\n");
        } catch (java.io.IOException e) {
            System.err.println("[Basquin] sampler: cannot write " + SAMPLE_OUT + ": " + e.getMessage());
            return;
        }
        try {
            while (true) {
                Thread.sleep(SAMPLE_INTERVAL_MS);
                appendSample();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void appendSample() {
        long elapsedMs = (System.nanoTime() - START_NANOS) / 1_000_000L;
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(java.nio.file.Paths.get(SAMPLE_OUT),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            w.write(sampleLine(elapsedMs));
            w.write("\n");
        } catch (java.io.IOException ignored) {
            // A transient append failure just drops one row; the next interval retries.
        }
    }

    /** Shutdown-hook final row so the last partial interval of a run is captured. */
    private static void sampleFinal() {
        if (!SAMPLE_OUT.isEmpty()) {
            appendSample();
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
        String explore = String.format(java.util.Locale.ROOT,
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
        String extra = ",\"mode\":\"" + mode + "\"" + loadBlockJson();
        return explore.substring(0, explore.length() - 1) + extra + "}";
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
