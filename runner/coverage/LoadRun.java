package runner.coverage;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Load / soak driver (DD-026 PR 2). Replays a saved "replay corpus" of route strings at a fixed
 * concurrency for a duration — NO mutation, NO coverage sampling (that's exploration overhead) — and
 * reports client-observed throughput + latency percentiles + heap/thread drift. The point: <em>fuzz
 * to discover the interesting states, then hammer those states under load and watch the invariants
 * hold.</em>
 *
 * Reached from {@link CoverageGuidedRun} when {@code -Dbasquin.mode=load}. Config:
 * {@code examples.http.baseUrl}, {@code basquin.corpusDir} (the mounted replay corpus),
 * {@code basquin.run.duration}, {@code basquin.concurrency}, {@code basquin.warmup},
 * {@code basquin.invariant.latency.maxMs}, {@code basquin.summary.out}.
 */
public final class LoadRun {

    private static final int MAX_MS = 30_000; // latency histogram ceiling; anything slower buckets here

    public static void run() throws Exception {
        String baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        String corpusDir = System.getProperty("basquin.corpusDir", "");
        List<String> corpus = readCorpus(corpusDir);
        if (corpus.isEmpty()) {
            System.err.println("[Basquin] load: no corpus routes under " + corpusDir + "; nothing to replay");
            corpus.add("/");
        }
        long durationMs = CoverageGuidedRun.parseDurationMillis(System.getProperty("basquin.run.duration", "60s"));
        long warmupMs = System.getProperty("basquin.warmup", "").isEmpty()
                ? 0L : CoverageGuidedRun.parseDurationMillis(System.getProperty("basquin.warmup"));
        int concurrency = Integer.getInteger("basquin.concurrency", 10);
        long latencyMaxMs = Long.getLong("basquin.invariant.latency.maxMs", 0L);

        System.out.printf("[Basquin] load: %d route(s), concurrency=%d, warmup=%dms, duration=%dms%n",
                corpus.size(), concurrency, warmupMs, durationMs);

        final long startNanos = System.nanoTime();
        final long measureFromNanos = startNanos + warmupMs * 1_000_000L; // metrics start after warmup
        final long deadlineNanos = measureFromNanos + durationMs * 1_000_000L;

        final AtomicLongArray hist = new AtomicLongArray(MAX_MS + 2); // 0..MAX_MS + overflow bucket
        final AtomicLong requests = new AtomicLong();
        final AtomicLong latencyViol = new AtomicLong();
        final AtomicLong serverError = new AtomicLong(); // 5xx responses (DD-029)
        final java.util.concurrent.atomic.AtomicReference<Drift> baselineDrift = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean baselined = new java.util.concurrent.atomic.AtomicBoolean(false);

        // DD-029: put the target's valve in lock-free load mode for the run (+ slack) so it can be driven
        // concurrently. The TTL auto-reverts if this driver dies mid-run; we also revert explicitly below.
        setTargetMode(baseUrl, "load", warmupMs + durationMs + 30_000L);

        Thread[] workers = new Thread[Math.max(1, concurrency)];
        for (int w = 0; w < workers.length; w++) {
            final long seed = w;
            workers[w] = new Thread(() -> {
                Random rnd = new Random(seed);
                while (System.nanoTime() < deadlineNanos) {
                    String path = corpus.get(rnd.nextInt(corpus.size()));
                    long t0 = System.nanoTime();
                    int code = fire(baseUrl, path);
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (System.nanoTime() < measureFromNanos) {
                        continue; // warmup: don't record
                    }
                    // First post-warmup sample: snapshot the TARGET's drift baseline exactly once (DD-029).
                    if (baselined.compareAndSet(false, true)) {
                        baselineDrift.set(pollDrift(baseUrl));
                    }
                    hist.incrementAndGet((int) Math.min(elapsedMs, MAX_MS + 1));
                    requests.incrementAndGet();
                    if (code >= 500) serverError.incrementAndGet();
                    if (latencyMaxMs > 0 && elapsedMs > latencyMaxMs) {
                        latencyViol.incrementAndGet();
                    }
                }
            }, "Basquin-Load-" + w);
            workers[w].start();
        }
        for (Thread t : workers) {
            t.join();
        }

        long total = requests.get();
        // Actual measured window (warmup-end → now), not the configured duration — a worker's in-flight
        // request can finish past the deadline, so the real window is slightly longer; using it keeps
        // throughput honest (esp. when the app is slow, which is exactly when it matters).
        double measuredSec = Math.max(0.001, (System.nanoTime() - measureFromNanos) / 1e9);
        // DD-029: drift is the TARGET's heap/threads (polled over /__basquin/drift), first→last — not the
        // driver JVM's. Then leave load mode so the target serializes again for any later explore run.
        DriftDelta drift = driftDelta(baselineDrift.get(), pollDrift(baseUrl));
        setTargetMode(baseUrl, "explore", 0);
        long heapDrift = drift.heapDriftKb;
        int threadDrift = drift.threadDrift;

        String json = String.format(java.util.Locale.ROOT,
                "{\"load\":{\"requests\":%d,\"throughputRps\":\"%.1f\","
              + "\"latencyMs\":{\"p50\":%d,\"p90\":%d,\"p99\":%d,\"max\":%d},"
              + "\"heapDriftKb\":%d,\"threadDrift\":%d,\"serverErrors\":%d,"
              // Only latency is threshold-gated in this first cut; heap/thread are reported as drift
              // above, not as violation counts (see LoadViolations godoc / DD-026 deferred items).
              + "\"violations\":{\"latency\":%d,\"heap\":0,\"thread\":0}}}",
                total, total / measuredSec,
                percentile(hist, total, 0.50), percentile(hist, total, 0.90),
                percentile(hist, total, 0.99), maxBucket(hist),
                heapDrift, threadDrift, serverError.get(), latencyViol.get());

        System.out.println("[Basquin] load done: " + json);
        String summaryOut = System.getProperty("basquin.summary.out");
        if (summaryOut != null && !summaryOut.isEmpty()) {
            try {
                Files.write(Paths.get(summaryOut), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { /* never let summary-writing break exit */ }
        }
    }

    /**
     * Fire one request, drain the body, and return the HTTP status (or -1 on a transport error). A
     * status ≥ 500 is a server error — the availability signal load mode DOES want (DD-029; the old
     * "measures behavior, not crashes" swallow is why 5xx were invisible).
     */
    private static int fire(String base, String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(MAX_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                while (in.read(buf) != -1) { /* drain so the connection can be keep-alive'd */ }
            }
            return code;
        } catch (Exception ignored) {
            int code = -1;
            if (c != null) {
                try { code = c.getResponseCode(); } catch (Exception e) { /* keep -1 */ }
                try (java.io.InputStream err = c.getErrorStream()) {
                    if (err != null) { byte[] b = new byte[8192]; while (err.read(b) != -1) {} }
                } catch (Exception ignored2) { /* drain error stream too, for keep-alive */ }
            }
            return code;
        }
    }

    // --- DD-029: target-side drift over /__basquin/drift (the APP's absolute heap/threads) ---

    /** One target-side drift sample. */
    public static final class Drift {
        public final long heapKb; public final int threads; public final long ts;
        Drift(long heapKb, int threads, long ts) { this.heapKb = heapKb; this.threads = threads; this.ts = ts; }
    }
    /** first→last drift of the app's heap/threads. */
    public static final class DriftDelta {
        public final long heapDriftKb; public final int threadDrift;
        DriftDelta(long heapDriftKb, int threadDrift) { this.heapDriftKb = heapDriftKb; this.threadDrift = threadDrift; }
    }

    /** Parse a {@code "<heapKb>,<threads>,<epochMillis>"} drift snapshot; null on anything malformed. */
    public static Drift parseDrift(String csv) {
        if (csv == null) return null;
        String[] f = csv.trim().split(",");
        if (f.length != 3) return null;
        try {
            return new Drift(Long.parseLong(f[0].trim()), Integer.parseInt(f[1].trim()), Long.parseLong(f[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Drift = last − first; if either sample is missing (a failed poll), report zero rather than throw. */
    public static DriftDelta driftDelta(Drift first, Drift last) {
        if (first == null || last == null) return new DriftDelta(0, 0);
        return new DriftDelta(last.heapKb - first.heapKb, last.threads - first.threads);
    }

    /** Poll the target's absolute drift snapshot; null if unreachable. */
    private static Drift pollDrift(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/__basquin/drift").openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(3000);
            try (java.io.InputStream in = c.getInputStream()) {
                return parseDrift(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Toggle the target's valve into/out of load mode over its own HTTP port; best-effort. */
    private static void setTargetMode(String base, String to, long ttlMs) {
        try {
            String q = "to=" + to + (ttlMs > 0 ? "&ttlMs=" + ttlMs : "");
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/__basquin/mode?" + q).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.getResponseCode();
            try (java.io.InputStream in = c.getInputStream()) { in.readAllBytes(); }
        } catch (Exception e) {
            System.err.println("[Basquin] load: mode " + to + " toggle failed (" + e + ")");
        }
    }

    /** Route lines (start with '/') from every file under the corpus dir (the mounted replay corpus). */
    static List<String> readCorpus(String dir) {
        List<String> routes = new ArrayList<>();
        if (dir == null || dir.isEmpty()) return routes;
        java.nio.file.Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) return routes;
        try (java.util.stream.Stream<java.nio.file.Path> s = Files.walk(root)) {
            for (java.nio.file.Path p : s.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("/")) routes.add(t);
                }
            }
        } catch (Exception e) {
            System.err.println("[Basquin] load: failed reading corpus " + dir + ": " + e);
        }
        return routes;
    }

    /** The p-th percentile latency (ms) from the histogram, or 0 if no samples. Package-private for tests. */
    static int percentile(AtomicLongArray hist, long total, double p) {
        if (total <= 0) return 0;
        long target = (long) Math.ceil(p * total);
        long cum = 0;
        for (int ms = 0; ms < hist.length(); ms++) {
            cum += hist.get(ms);
            if (cum >= target) return ms;
        }
        return hist.length() - 1;
    }

    private static int maxBucket(AtomicLongArray hist) {
        for (int ms = hist.length() - 1; ms >= 0; ms--) {
            if (hist.get(ms) > 0) return ms;
        }
        return 0;
    }
}
