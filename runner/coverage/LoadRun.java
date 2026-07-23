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
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int CAPTURE_MAX_BYTES = 262_144; // 256KB cap on a retained response body (DD-036)

    /** DD-036: a sequence, plus whether ANY step's body references {@code ${{name}}} — computed once at
     *  corpus-read time so the hot worker loop never re-scans for it. When false, the worker loop skips
     *  allocating a bindings map at all (identical no-alloc path to before this feature). */
    record Seq(List<RequestLine> steps, boolean correlated) {}

    public static void run() throws Exception {
        String baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        String corpusDir = System.getProperty("basquin.corpusDir", "");
        List<Seq> read = readCorpus(corpusDir);
        if (read.isEmpty()) {
            System.err.println("[Basquin] load: no corpus routes under " + corpusDir + "; nothing to replay");
        }
        final List<Seq> corpus = read.isEmpty()
                ? List.of(new Seq(List.of(new RequestLine("GET", "/", null)), false)) : read;
        long durationMs = CoverageGuidedRun.parseDurationMillis(System.getProperty("basquin.run.duration", "60s"));
        long warmupMs = System.getProperty("basquin.warmup", "").isEmpty()
                ? 0L : CoverageGuidedRun.parseDurationMillis(System.getProperty("basquin.warmup"));
        int concurrency = Integer.getInteger("basquin.concurrency", 10);
        long latencyMaxMs = Long.getLong("basquin.invariant.latency.maxMs", 0L);

        System.out.printf("[Basquin] load: %d sequence(s), concurrency=%d, warmup=%dms, duration=%dms%n",
                corpus.size(), concurrency, warmupMs, durationMs);
        if (latencyMaxMs <= 0) {
            // DD-040: say it up front. In load mode the target's valve is in lock-free passthrough and
            // evaluates nothing, so with no -Dbasquin.invariant.latency.maxMs here NOTHING checks
            // latency for the whole run — and the summary will report it as unevaluated, not as 0.
            System.err.println("[Basquin] load: no -Dbasquin.invariant.latency.maxMs — latency is NOT "
                    + "being checked by anyone this run (the target's valve is in passthrough); "
                    + "set invariants.latencyMaxMs on the BasquinTarget or the campaign's driver");
        }

        final long startNanos = System.nanoTime();
        final long measureFromNanos = startNanos + warmupMs * 1_000_000L; // metrics start after warmup
        final long deadlineNanos = measureFromNanos + durationMs * 1_000_000L;

        final AtomicLongArray hist = new AtomicLongArray(MAX_MS + 2); // 0..MAX_MS + overflow bucket
        final AtomicLong requests = new AtomicLong();
        final AtomicLong latencyViol = new AtomicLong();
        final AtomicLong serverError = new AtomicLong(); // 5xx responses (DD-029)
        final AtomicLong captureMisses = new AtomicLong(); // DD-036: a ${{name}} ref whose capture never bound
        final AtomicLong clientErrors = new AtomicLong(); // 4xx responses (DD-036)
        final AtomicLong redirects = new AtomicLong(); // 3xx responses (DD-038: no longer auto-followed)
        // DD-038: Location classification -> count, bounded to a top-12-by-count report so a fuzzed
        // Location can't grow this map unboundedly; same-page redirects key by the Location's PATH
        // segment (normalizeLocation) so every SUCCESS 302 (e.g. JSPWiki's save echo to its one view
        // route) shares one key and can't evict the real rejects — while a same-page REJECT route
        // (e.g. /PageModified.jsp?page=<ownPage>) keeps its own key instead of hiding among them.
        final java.util.concurrent.ConcurrentHashMap<String, LongAdder> redirectTargets =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicReference<Drift> baselineDrift = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean baselined = new java.util.concurrent.atomic.AtomicBoolean(false);

        // DD-029: put the target's valve in lock-free load mode for the run (+ slack) so it can be driven
        // concurrently. The TTL auto-reverts if this driver dies mid-run; we also revert explicitly below.
        // DD-035: whether the target actually CONFIRMED load mode feeds driftUnavailable below — a
        // failed/unconfirmed toggle means the target may still be serializing (or unreachable), so any
        // drift number we'd report is not trustworthy.
        boolean modeConfirmed = setTargetMode(baseUrl, "load", warmupMs + durationMs + 30_000L);
        runner.util.StatusReporter.setMode("load");

        // DD-033: the snapshotter owns the ONE drift poller (both live pushes and the terminal
        // number reuse its last poll) and stores it here so the terminal path doesn't poll again.
        final java.util.concurrent.atomic.AtomicReference<Drift> lastDrift = new java.util.concurrent.atomic.AtomicReference<>();
        Thread snapshotter = new Thread(() -> {
            long intervalMs = Long.getLong("basquin.dashboard.pushIntervalMs", 2000L);
            while (System.nanoTime() < deadlineNanos) {
                try { Thread.sleep(intervalMs); } catch (InterruptedException e) { break; }
                if (System.nanoTime() < measureFromNanos || !baselined.get()) continue; // not measuring yet
                long total = requests.get();
                double windowSec = (System.nanoTime() - measureFromNanos) / 1e9; // SAME window as terminal
                Drift cur = pollDrift(baseUrl);                                   // the ONE poller
                if (cur != null) lastDrift.set(cur);
                DriftDelta d = driftDelta(baselineDrift.get(), cur);
                LoadSnapshot s = computeLoadSnapshot(hist, total, serverError.get(), windowSec,
                        d.heapDriftKb, d.threadDrift);
                // DD-035: current-state drift honesty, same rule as the terminal check below — a
                // baseline that never landed (not yet baselined, or the poll itself came back null),
                // OR this poll (cur) coming back null, OR a target that never confirmed load mode.
                boolean driftUnavailableNow = driftUnavailable(baselined.get(), baselineDrift.get(), cur, modeConfirmed);
                runner.util.StatusReporter.recordLoad(s.throughputRps, s.p50, s.p90, s.p99, s.max,
                        s.heapDriftKb, s.threadDrift, s.serverErrors, s.requests, driftUnavailableNow);
            }
        }, "Basquin-Load-Snapshot");
        snapshotter.setDaemon(true);
        snapshotter.start();

        Thread[] workers = new Thread[Math.max(1, concurrency)];
        for (int w = 0; w < workers.length; w++) {
            final long seed = w;
            workers[w] = new Thread(() -> {
                Random rnd = new Random(seed);
                // Each worker owns one session jar for its whole run: sequences replay as a logged-in
                // user would exercise them (e.g. POST /signon then authenticated GETs), so the cookie
                // set on step 1 must still be present on step N.
                java.util.Map<String, String> jar = new java.util.HashMap<>();
                while (System.nanoTime() < deadlineNanos) {
                    Seq seq = corpus.get(rnd.nextInt(corpus.size()));
                    // DD-036: only a correlated sequence pays for a bindings map AND the per-step
                    // substitution scan — an uncorrelated one takes the identical no-alloc, no-scan
                    // path as before this feature (correlated == anyMatch(needsSubstitution), so an
                    // uncorrelated seq can have no substitutable step to skip anyway).
                    boolean correlated = seq.correlated();
                    java.util.Map<String, String> bindings = correlated ? new java.util.HashMap<>(4) : null;
                    for (RequestLine step : seq.steps()) {
                        // A long sequence must not run past the deadline: check BEFORE firing each step,
                        // not just at the outer loop, so a worker can't overshoot the window mid-sequence.
                        if (System.nanoTime() >= deadlineNanos) break;
                        RequestLine toFire = step;
                        if (correlated && step.needsSubstitution()) {
                            // DD-038: a generator marker (e.g. @nonce) is usually authored in the
                            // query, not the body — substitute the FULL request line. path is never
                            // null (RequestLine.parseCore always sets "" or a substring); body IS
                            // null when the step has none, so it's guarded and left null rather than
                            // passed through substitute() (which would NPE on a null CharSequence).
                            String p = substitute(step.path(), bindings);
                            String b = (step.body() == null) ? null : substitute(step.body(), bindings);
                            if (p == null || (step.body() != null && b == null)) {
                                if (System.nanoTime() >= measureFromNanos) captureMisses.incrementAndGet();
                                continue; // skip this step: a required prior capture never bound
                            }
                            toFire = new RequestLine(step.method(), p, b, step.captures());
                        }
                        long t0 = System.nanoTime();
                        FireResult fr = fireR(baseUrl, toFire, jar, bindings);
                        int code = fr.code();
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
                        else if (code >= 400 && code < 500) clientErrors.incrementAndGet();
                        else if (code >= 300 && code < 400) {
                            // DD-038: classify the redirect target so a REJECTED write (e.g. JSPWiki's
                            // SessionExpired/PageModified) is visible in the summary instead of vanishing
                            // (no longer auto-followed to a final 200 — see fireR).
                            redirects.incrementAndGet();
                            // BOTH the path/query and the body are consulted for the request's own
                            // page=: a step can carry it in either (jspwiki's edit_save is
                            // body-leading `page=…`, but `POST /Edit.jsp?page=X` with an unrelated
                            // body is just as valid). Checking only one leaves reqPage null for the
                            // other shape → the same-page fold never fires → routine SUCCESS 302s eat
                            // the 12 slots and push the real rejects into "other" (F1).
                            // isEmpty(), not just null: paramValue returns "" for a `page=` that is
                            // PRESENT BUT BLANK, and a blank page name is a real corpus shape here
                            // (three jspwiki routes carry one, and each bounces to /Login.jsp). Testing
                            // only for null lets a blank path `page=` shadow a real body `page=`, so
                            // reqPage stays "" -> samePage()'s isEmpty() guard always fails -> the
                            // same-page fold never fires for that request. That direction is safe
                            // (extra keys, never a hidden reject) but it is the under-folding class
                            // this rule exists to close.
                            String reqPage = paramValue(toFire.path(), "page");
                            if ((reqPage == null || reqPage.isEmpty()) && toFire.body() != null) {
                                String fromBody = paramValue(toFire.body(), "page");
                                if (fromBody != null && !fromBody.isEmpty()) reqPage = fromBody;
                            }
                            String key = normalizeLocation(fr.location(), reqPage);
                            LongAdder a = redirectTargets.get(key);
                            if (a == null) {
                                key = admitKey(redirectTargets, key);
                                a = redirectTargets.computeIfAbsent(key, k -> new LongAdder());
                            }
                            a.increment();
                        }
                        if (latencyMaxMs > 0 && elapsedMs > latencyMaxMs) {
                            latencyViol.incrementAndGet();
                        }
                    }
                }
            }, "Basquin-Load-" + w);
            workers[w].start();
        }
        for (Thread t : workers) {
            t.join();
        }

        // DD-033: stop the snapshotter now that the workers are done pushing metrics.
        snapshotter.interrupt();
        try { snapshotter.join(3000); } catch (InterruptedException ignored) { }

        long total = requests.get();
        // Actual measured window (warmup-end → now), not the configured duration — a worker's in-flight
        // request can finish past the deadline, so the real window is slightly longer; using it keeps
        // throughput honest (esp. when the app is slow, which is exactly when it matters).
        double measuredSec = Math.max(0.001, (System.nanoTime() - measureFromNanos) / 1e9);
        // DD-029: drift is the TARGET's heap/threads (polled over /__basquin/drift), first→last — not the
        // driver JVM's. Then leave load mode so the target serializes again for any later explore run.
        // DD-033: the terminal drift reuses the snapshotter's last poll (one source of truth) instead of
        // polling again; fall back to a single poll only if the snapshotter never ran.
        Drift terminalDrift = lastDrift.get();
        if (terminalDrift == null) terminalDrift = pollDrift(baseUrl);
        DriftDelta drift = driftDelta(baselineDrift.get(), terminalDrift);

        // DD-035: driftUnavailable is a first-class signal, not a fake heapDriftKb:0. "The baseline
        // drift poll never succeeded" covers two cases: (a) !baselined.get() — no worker ever reached
        // the first post-warmup sample (e.g. duration shorter than warmup), so the CAS in the worker
        // loop never fired at all; (b) baselined is true but pollDrift returned null (target
        // unreachable when the baseline WAS attempted), leaving baselineDrift.get() == null. A THIRD
        // case (the review fix): the baseline landed fine but terminalDrift itself is null — the drift
        // endpoint got starved once load ramped, a failed poll on the OTHER side of the delta.
        // driftDelta() silently degrades any of these to a zero delta — indistinguishable from a real
        // flat heap — so it must not be relied on here; check the raw state instead. The final case is
        // the target never confirming load mode at all.
        boolean driftUnavailable = driftUnavailable(baselined.get(), baselineDrift.get(), terminalDrift, modeConfirmed);

        // DD-033: push the terminal snapshot to the dashboard before reverting the target's mode, so the
        // authoritative numbers land there too.
        LoadSnapshot fin = computeLoadSnapshot(hist, total, serverError.get(), measuredSec, drift.heapDriftKb, drift.threadDrift);
        runner.util.StatusReporter.recordLoad(fin.throughputRps, fin.p50, fin.p90, fin.p99, fin.max,
                fin.heapDriftKb, fin.threadDrift, fin.serverErrors, fin.requests, driftUnavailable);
        setTargetMode(baseUrl, "explore", 0);

        // DD-038: top-12-by-count view of redirectTargets for the summary (the live map itself stays
        // unbounded-by-count-but-capped-by-distinct-keys at 12 — see the worker's "other" fallback).
        java.util.Map<String, Long> redirectTargetsTop12 = redirectTargets.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().sum(), e1.getValue().sum()))
                .limit(REDIRECT_TARGETS_CAP)
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, e -> e.getValue().sum(),
                        (x, y) -> x, java.util.LinkedHashMap::new));

        String json = summaryJson(total, total / measuredSec,
                percentile(hist, total, 0.50), percentile(hist, total, 0.90),
                percentile(hist, total, 0.99), maxBucket(hist),
                drift, serverError.get(), latencyViol.get(),
                // DD-040: without a threshold the driver never compares anything, so latencyViol is
                // structurally 0 — report it as unevaluated rather than as "checked and clean".
                latencyMaxMs > 0,
                captureMisses.get(), clientErrors.get(), driftUnavailable,
                redirects.get(), redirectTargetsTop12);

        System.out.println("[Basquin] load done: " + json);
        String summaryOut = System.getProperty("basquin.summary.out");
        if (summaryOut != null && !summaryOut.isEmpty()) {
            try {
                Files.write(Paths.get(summaryOut), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { /* never let summary-writing break exit */ }
        }
    }

    /**
     * Build the terminal load-summary JSON (DD-026), extracted from {@link #run} so it's unit-testable
     * without a server. DD-035: when {@code driftUnavailable} is true, the block carries
     * {@code "driftUnavailable":true} and OMITS {@code heapDriftKb}/{@code threadDrift} entirely — a
     * heap/thread number the target never actually confirmed must never be printed as if it were real
     * (even a real flat heap prints {@code heapDriftKb:0}, so silently defaulting to it here would be
     * indistinguishable from that). When false, heap/thread print as-is (a value may legitimately be
     * ≤ 0, e.g. a heap that shrank) and no {@code driftUnavailable} key appears.
     *
     * <p>DD-038: {@code redirects} is the 3xx count (no longer auto-followed, see {@link #fireR}) and
     * {@code redirectTargets} is a top-12-by-count {@code Location} classification (see
     * {@link #normalizeLocation}) so a rejected write (e.g. a session-expired save) is visible instead
     * of vanishing into a followed 200. Keys are already {@link #safeKey}-restricted to
     * {@code [A-Za-z0-9._-]} by the caller, so they're emitted verbatim with no further JSON escaping.
     *
     * <p>DD-040: the {@code violations} block no longer fabricates zeros. Load mode is lock-free
     * passthrough (DD-029) — the target's valve evaluates <em>nothing</em> — so the driver is the only
     * evaluator, and it only evaluates <em>latency</em>, and only when {@code
     * basquin.invariant.latency.maxMs} was actually configured ({@code latencyEvaluated}). The old
     * block hardcoded {@code "heap":0,"thread":0} and always printed a latency count; a Roller run
     * therefore reported {@code violations.latency: 0} at a p50 of 503 ms against an intended 250 ms
     * budget. A count that was never checked is omitted, and every omitted count is NAMED in
     * {@code notEvaluated} — so "absent" is data an operator (and the Go {@code LoadViolations}
     * pointers) can act on, never something inferred from a missing key. A real measured zero still
     * prints as {@code "latency":0}, and now means what it says: checked, and clean.
     */
    static String summaryJson(long total, double rps, int p50, int p90, int p99, int max, DriftDelta drift,
            long serverErr, long latViol, boolean latencyEvaluated, long captureMisses, long clientErrors,
            boolean driftUnavailable, long redirects, java.util.Map<String, Long> redirectTargets) {
        String driftJson = driftUnavailable
                ? "\"driftUnavailable\":true"
                : "\"heapDriftKb\":" + drift.heapDriftKb + ",\"threadDrift\":" + drift.threadDrift;
        // heap/thread are ALWAYS unevaluated in load mode; latency only when a threshold was set.
        String violJson = (latencyEvaluated ? "\"latency\":" + latViol + "," : "")
                + "\"notEvaluated\":[" + (latencyEvaluated ? "" : "\"latency\",") + "\"heap\",\"thread\"]";
        StringBuilder rt = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : redirectTargets.entrySet()) {
            if (rt.length() > 0) rt.append(',');
            rt.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return String.format(java.util.Locale.ROOT,
                "{\"load\":{\"requests\":%d,\"throughputRps\":\"%.1f\","
              + "\"latencyMs\":{\"p50\":%d,\"p90\":%d,\"p99\":%d,\"max\":%d},"
              + "%s,\"serverErrors\":%d,\"captureMisses\":%d,\"clientErrors\":%d,"
              + "\"redirects\":%d,\"redirectTargets\":{%s},"
              + "\"violations\":{%s}}}",
                total, rps, p50, p90, p99, max, driftJson, serverErr, captureMisses, clientErrors,
                redirects, rt, violJson);
    }

    /**
     * Fire one request — method, body, and session-aware (Task 3) — drain the response, and return the
     * HTTP status (or -1 on a transport error). A status ≥ 500 is a server error — the availability
     * signal load mode DOES want (DD-029; the old "measures behavior, not crashes" swallow is why 5xx
     * were invisible).
     *
     * <p>{@code jar} is a per-worker session cookie store, keyed by cookie name (just {@code
     * JSESSIONID} in practice): it's read to build the request's {@code Cookie} header and written from
     * the response's {@code Set-Cookie}, so a sequence's later steps see the session its earlier steps
     * established.
     *
     * <p>DD-038: test-facing convenience wrapper — no production caller remains (the worker calls
     * {@link #fireR}, which also yields the {@code Location}). Kept because Java can't overload on
     * return type and it leaves {@code LoadFireTest}'s status-only assertions untouched.
     */
    static int fire(String base, RequestLine step, java.util.Map<String, String> jar) {
        return fireR(base, step, jar, null).code();
    }

    /**
     * Task 4 (DD-036) overload: same as {@link #fire(String, RequestLine, java.util.Map)} but, when
     * {@code step.captures()} is non-empty and {@code bindings} is non-null, also retains the response
     * body (capped at {@link #CAPTURE_MAX_BYTES}) — while STILL draining to EOF, so the connection can
     * still be keep-alive'd — and runs {@link Capture#extract} against it, recording a new binding on
     * success. Review fix (single-count captureMisses): a failed extraction does NOT increment any
     * miss counter here — see the capture branch below for why.
     *
     * <p>DD-038: likewise a test-facing convenience wrapper — no production caller remains; the worker
     * calls {@link #fireR} directly for the {@code Location}.
     */
    static int fire(String base, RequestLine step, java.util.Map<String, String> jar,
            java.util.Map<String, String> bindings) {
        return fireR(base, step, jar, bindings).code();
    }

    /**
     * DD-038 Task 3: a fired request's HTTP status plus the raw {@code Location} header value.
     * {@code location} is {@code null} for any non-redirect status and on the {@code -1}
     * transport-failure path — and ALSO on the exception path when the status itself was a 3xx: if
     * the body read throws (e.g. a read timeout on a 302), {@code fireR}'s catch block recovers the
     * real {@code code} from the connection but returns no {@code Location}. So a 3xx {@code code}
     * with a {@code null} {@code location} is reachable, and callers must treat it as unclassifiable
     * ({@link #normalizeLocation} maps it to {@code "?"}) rather than impossible.
     */
    record FireResult(int code, String location) {}

    /**
     * Fire one request — method, body, and session-aware (Task 3) — drain the response, and return the
     * HTTP status (or -1 on a transport error) plus its {@code Location} header when 3xx. A status ≥
     * 500 is a server error — the availability signal load mode DOES want (DD-029; the old "measures
     * behavior, not crashes" swallow is why 5xx were invisible).
     *
     * <p>{@code jar} is a per-worker session cookie store, keyed by cookie name (just {@code
     * JSESSIONID} in practice): it's read to build the request's {@code Cookie} header and written from
     * the response's {@code Set-Cookie}, so a sequence's later steps see the session its earlier steps
     * established.
     *
     * <p>DD-038: redirects are NOT auto-followed ({@code setInstanceFollowRedirects(false)}) — a save's
     * 302 is a REJECTED-write signal (e.g. JSPWiki's {@code SessionExpired}/{@code PageModified}) that
     * must be classified by the caller (see {@link #normalizeLocation}), not silently followed into a
     * final 200 where it's indistinguishable from a normal request.
     *
     * <p>Same body as the pre-DD-038 4-arg {@code fire} (still holds the DD-037 capture-into-bindings
     * branch, the retain-≤{@link #CAPTURE_MAX_BYTES} drain-to-EOF, and {@link #captureSessionCookie}) —
     * only the follow-redirects flag, the added {@code Location} read, and the two return sites changed.
     */
    static FireResult fireR(String base, RequestLine step, java.util.Map<String, String> jar,
            java.util.Map<String, String> bindings) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + step.path()).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(MAX_MS);
            c.setInstanceFollowRedirects(false);
            try {
                c.setRequestMethod(step.method());
            } catch (java.net.ProtocolException pe) {
                System.err.println("[Basquin] load: unsupported HTTP method " + step.method() + " for " + step.path() + "; not sent");
                return new FireResult(-1, null);   // fail loud: do NOT fall through to a silent GET
            }
            byte[] bodyBytes = null;
            if (step.body() != null) {
                bodyBytes = step.body().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            }
            if (!jar.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : jar.entrySet()) {
                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                    cookieHeader.append(e.getKey()).append('=').append(e.getValue());
                }
                c.setRequestProperty("Cookie", cookieHeader.toString());
            }
            if (bodyBytes != null) {
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(bodyBytes);
                }
            }
            int code = c.getResponseCode();
            captureSessionCookie(c, jar);
            String loc = (code >= 300 && code < 400) ? c.getHeaderField("Location") : null;
            if (step.captures().isEmpty() || bindings == null) {
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    while (in.read(buf) != -1) { /* drain so the connection can be keep-alive'd */ }
                }
            } else {
                // Retain at most CAPTURE_MAX_BYTES of the body while still draining to EOF (keep-alive).
                java.io.ByteArrayOutputStream retained = new java.io.ByteArrayOutputStream(8192);
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (retained.size() < CAPTURE_MAX_BYTES) {
                            int room = CAPTURE_MAX_BYTES - retained.size();
                            retained.write(buf, 0, Math.min(n, room));
                        }
                        // keep reading past the cap so the connection still drains to EOF
                    }
                }
                String body = retained.toString(StandardCharsets.UTF_8);
                // Single source of truth for captureMisses (review fix): a failed extraction here is
                // only meaningful if a LATER step actually references it — and when it does,
                // substitute() in the worker loop returns null and THAT site counts one warmup-gated
                // miss and skips the request. A capture that fails but is never referenced downstream
                // is a no-op and must not inflate the metric, so this branch does nothing on a miss
                // beyond simply not populating bindings.
                for (Capture cap : step.captures()) {
                    String val = cap.extract(c::getHeaderField, body);
                    if (val != null) bindings.put(cap.name(), val);
                }
            }
            return new FireResult(code, loc);
        } catch (Exception ignored) {
            int code = -1;
            if (c != null) {
                try { code = c.getResponseCode(); } catch (Exception e) { /* keep -1 */ }
                captureSessionCookie(c, jar);
                try (java.io.InputStream err = c.getErrorStream()) {
                    if (err != null) { byte[] b = new byte[8192]; while (err.read(b) != -1) {} }
                } catch (Exception ignored2) { /* drain error stream too, for keep-alive */ }
            }
            return new FireResult(code, null);
        }
    }

    private static final int LOC_KEY_MAX = 64;

    /** DD-038: hard bound on distinct {@code redirectTargets} keys (see {@link #admitKey}). */
    static final int REDIRECT_TARGETS_CAP = 12;

    /**
     * DD-038 admission decision for {@code redirectTargets}: return {@code key} if it may take (or
     * already holds) a slot, else the reserved overflow key {@code "other"}. A key ALREADY in the map
     * is always admitted even when the map is full — diverting it to {@code "other"} would fragment
     * its count across two buckets. Only a NEW key on a full map overflows, which is what keeps a
     * fuzzed/reflected {@code Location} from growing the map (and the ~4 KB termination-message
     * summary) without bound. Best-effort under concurrency: a benign overshoot ≤ worker-count is
     * fine in-memory, since the summary emits only the top-N anyway.
     */
    static String admitKey(java.util.Map<String, LongAdder> targets, String key) {
        return (targets.containsKey(key) || targets.size() < REDIRECT_TARGETS_CAP) ? key : "other";
    }

    /**
     * Classify a redirect's {@code Location} into a short, JSON-safe key for the summary's
     * {@code redirectTargets} counter (DD-038, rule revised after the first live c8 run).
     *
     * <p>A {@code Location} carrying a {@code page=} param whose value names a <em>different</em> page
     * than the firing request's own ({@link #samePage}) keys by that page name (JSPWiki's own idiom:
     * {@code /Wiki.jsp?page=SessionExpired} → {@code "SessionExpired"}). But when the {@code page}
     * matches the request's own page, the page name carries no signal — the PATH does: a successful
     * JSPWiki save echoes {@code /Wiki.jsp?page=<ownPage>} while a rejected concurrent edit answers
     * {@code /PageModified.jsp?page=<ownPage>} (both verified live on 2.12.4). The original rule keyed
     * every same-page redirect to one reserved {@code "self"} — which folded that reject into the
     * success bucket: over-folding that hides rejects, the exact invisibility DD-038 exists to kill.
     * Same-page redirects therefore key by the Location's last path segment: every success shares the
     * ONE view route's key (e.g. {@code "Wiki.jsp"} — still can't crowd the bounded map, however many
     * pages the corpus saves), while a reject route keeps its own key ({@code "PageModified.jsp"}).
     * {@code "self"} remains reserved only as the degenerate same-page fallback (empty path segment,
     * e.g. a root-path echo {@code /?page=X}).
     *
     * <p>No {@code page=} at all falls back to the last path segment (query/fragment stripped).
     * {@code null}/empty location (the {@code -1} transport-failure path never reaches here anyway,
     * since the worker only classifies {@code code} in {@code [300,400)}) returns {@code "?"}.
     */
    static String normalizeLocation(String location, String requestPage) {
        if (location == null || location.isEmpty()) return "?";
        String page = paramValue(location, "page");                    // (^|[?&])page= match
        String key;
        if (page != null && !(requestPage != null && samePage(requestPage, page))) {
            key = page;                       // cross-page redirect: the page name IS the signal
        } else {
            key = lastSegment(location);      // same-page or no page=: the path is the signal
            if (key.isEmpty()) key = (page != null) ? "self" : "?";
        }
        if (key.length() > LOC_KEY_MAX) key = key.substring(0, LOC_KEY_MAX);
        return safeKey(key);                                            // charset-restrict for JSON (N3)
    }

    /** The Location's last path segment, query/fragment stripped; may be empty (root path). */
    private static String lastSegment(String location) {
        int q = location.indexOf('?'); int h = location.indexOf('#');
        String noQuery = location.substring(0, minPos(location.length(), q, h));
        int slash = noQuery.lastIndexOf('/');
        return slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
    }

    /**
     * The same-page test behind {@link #normalizeLocation}'s key choice (DD-038 fix): TRUE on exact
     * match, and ALSO when the two names differ only by the case of their FIRST character. Wiki-style
     * CMS targets canonicalize a page name by capitalizing its first letter — JSPWiki 2.12's
     * {@code DefaultCommandResolver} runs an unresolved name through {@code MarkupParser.cleanLink} →
     * {@code TextUtil.cleanString}, which uppercases the first kept character and preserves the rest
     * (verified live: saving {@code page=ndws} answers {@code Location: /Wiki.jsp?page=Ndws};
     * MediaWiki titles behave the same) — so a byte-equality test misses every lowercase-page save
     * and files the routine SUCCESS 302 under the page's own capitalized key, crowding the bounded
     * map with successes (the exact eviction the same-page fold exists to prevent). Deliberately NOT
     * a full {@code equalsIgnoreCase}: beyond the first character the canonicalization preserves
     * case, so a later case difference distinguishes genuinely different targets — a rejected save of
     * a page named {@code sessionexpired} (canonical {@code Sessionexpired}) redirecting to
     * {@code SessionExpired} must keep its own {@code "SessionExpired"} key, not merge into the
     * success route's bucket. Allocation-free on the hot path: the non-equal comparison runs only
     * when lengths already match.
     */
    static boolean samePage(String requestPage, String locationPage) {
        if (requestPage.equals(locationPage)) return true;
        return requestPage.length() == locationPage.length() && !requestPage.isEmpty()
                && Character.toUpperCase(requestPage.charAt(0)) == Character.toUpperCase(locationPage.charAt(0))
                && requestPage.regionMatches(1, locationPage, 1, requestPage.length() - 1);
    }

    /**
     * The only name any production caller asks for is {@code page} (the worker's 3xx path calls
     * {@code paramValue} twice per redirect, in the loop that sets the driver's throughput ceiling),
     * so it is compiled once instead of per call.
     */
    private static final Pattern PAGE_PARAM = Pattern.compile("(^|[?&])page=([^&#]*)");

    /** (^|[?&])<name>=<value up to & or #>, exact-boundary so frompage= doesn't false-match. */
    static String paramValue(String s, String name) {
        java.util.regex.Matcher m = ("page".equals(name) ? PAGE_PARAM
                : Pattern.compile("(^|[?&])" + Pattern.quote(name) + "=([^&#]*)")).matcher(s);
        return m.find() ? m.group(2) : null;
    }

    private static int minPos(int len, int... ps) { int r = len; for (int p : ps) if (p >= 0 && p < r) r = p; return r; }

    /** Restrict to a JSON-safe, budget-safe charset so a fuzzed Location can't void the summary (N3). */
    static String safeKey(String k) {
        StringBuilder b = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) { char c = k.charAt(i);
            b.append((c=='.'||c=='_'||c=='-'|| (c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')) ? c : '_'); }
        return b.length() == 0 ? "?" : b.toString();
    }

    private static final Pattern CORRELATION_REF_PATTERN = Pattern.compile("\\$\\{\\{([^}]+)\\}\\}");

    // DD-038: a per-fire unique payload token. RUN_SALT is per-process; NONCE is the within-run counter.
    // millis+pid alone is NOT enough here: the driver runs as a Kubernetes Job, and under a container's
    // own PID namespace every driver's main process is renumbered from a small integer (commonly 1), so
    // two pods started in the same millisecond — a parallel Job, or two campaigns off one controller
    // tick — would share a salt and re-emit the same token stream, reviving the very saveText() no-op
    // this feature exists to kill. HOSTNAME is the pod name in Kubernetes (DD-013), which makes the
    // salt unique per POD, not merely per host-process; it falls back to pid when unset (local runs).
    static final String RUN_SALT = buildRunSalt(System.getenv("HOSTNAME"), System.currentTimeMillis(),
            ProcessHandle.current().pid());

    /** Package-private + pure so the salt's collision-resistance is testable without spawning pods. */
    static String buildRunSalt(String hostname, long millis, long pid) {
        String node = (hostname == null || hostname.isBlank()) ? Long.toString(pid) : safeKey(hostname);
        return Long.toString(millis) + "x" + node + "x" + Long.toString(pid);
    }

    static final java.util.concurrent.atomic.AtomicLong NONCE = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Replaces each {@code ${{name}}} reference in {@code body} with the (already URL-encoded) value
     * bound to {@code name} in {@code bindings}. Returns null if ANY referenced name is missing a
     * binding — the worker loop treats a null return as "skip firing this step" (a required prior
     * capture never bound). The special ref {@code ${{@nonce}}} (DD-038) is never looked up in
     * {@code bindings} — it's generated fresh every call from {@link #RUN_SALT}/{@link #NONCE}, so it
     * NEVER causes a null return, unlike every other (real, captured) ref.
     */
    static String substitute(String body, java.util.Map<String, String> bindings) {
        Matcher m = CORRELATION_REF_PATTERN.matcher(body);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String name = m.group(1);
            out.append(body, last, m.start());
            if (name.equals("@nonce")) {
                out.append(RUN_SALT).append('-').append(NONCE.getAndIncrement());
            } else {
                String value = bindings == null ? null : bindings.get(name);
                if (value == null) return null;
                out.append(value);   // bindings already hold the URL-encoded, body-ready text (DD-037 model A)
            }
            last = m.end();
        }
        out.append(body, last, body.length());
        return out.toString();
    }

    /** Pull {@code JSESSIONID=<value>} out of any {@code Set-Cookie} response headers into {@code jar},
     *  stripping attributes (Path, HttpOnly, Max-Age, …) at the first {@code ;}. Walks the indexed
     *  header API (not {@code getHeaderFields().get("Set-Cookie")}) — that map is keyed by the exact
     *  case the server sent (e.g. JDK's {@code HttpServer} sends {@code Set-cookie}), so an exact-case
     *  lookup silently misses it. */
    private static void captureSessionCookie(HttpURLConnection c, java.util.Map<String, String> jar) {
        for (int i = 0; ; i++) {
            String key = c.getHeaderFieldKey(i);
            if (key == null) {
                if (c.getHeaderField(i) == null) break; // key null only for the status line at index 0
                continue;
            }
            if (!key.equalsIgnoreCase("Set-Cookie")) continue;
            String sc = c.getHeaderField(i);
            int eq = sc.indexOf("JSESSIONID=");
            if (eq == -1) continue;
            String rest = sc.substring(eq + "JSESSIONID=".length());
            int semi = rest.indexOf(';');
            String value = semi == -1 ? rest : rest.substring(0, semi);
            jar.put("JSESSIONID", value.trim());
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

    /**
     * DD-035 review fix: whether the drift value about to be reported (fed to {@link #driftDelta}) is a
     * FABRICATED zero rather than a real measurement. {@link #driftDelta} silently degrades to
     * {@code (0, 0)} when EITHER sample is missing — so driftUnavailable must be true whenever either
     * side of the delta is missing, not just the one-time baseline. The original bug checked only
     * {@code baselineOk}/{@code baseline}: a baseline that landed fine followed by a LATER poll failure
     * (the {@code sample} argument here — the current live poll or the terminal poll) still reported
     * {@code driftUnavailable=false} with a fabricated {@code heapDriftKb:0}/{@code threadDrift:0} — the
     * exact false-negative DD-035 exists to prevent. Package-private so it's directly unit-testable.
     */
    static boolean driftUnavailable(boolean baselineOk, Drift baseline, Drift sample, boolean modeConfirmed) {
        return !baselineOk || baseline == null || sample == null || !modeConfirmed;
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

    /**
     * Toggle the target's valve into/out of load mode over its own HTTP port; best-effort. Returns
     * whether the target confirmed LOAD mode specifically — {@code true} iff the response body is
     * exactly {@code "ok:load"} (the token {@link agent.LoadModeControl#handle} returns for
     * {@code to=load}) — regardless of what {@code to} was requested here, so a caller toggling
     * "explore" simply gets {@code false} back (it doesn't care). Logs one line when the response
     * doesn't match what THIS request asked for (mismatch or transport failure) — not silent.
     */
    static boolean setTargetMode(String base, String to, long ttlMs) {
        String expected = "ok:" + to;
        try {
            String q = "to=" + to + (ttlMs > 0 ? "&ttlMs=" + ttlMs : "");
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/__basquin/mode?" + q).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.getResponseCode();
            String body;
            try (java.io.InputStream in = c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!expected.equals(body)) {
                System.err.println("[Basquin] load: mode " + to + " toggle not confirmed (got \"" + body + "\")");
            }
            return "ok:load".equals(body);
        } catch (Exception e) {
            System.err.println("[Basquin] load: mode " + to + " toggle failed (" + e + ")");
            return false;
        }
    }

    /**
     * Route sequences from every file under the corpus dir (the mounted replay corpus). A line is kept
     * iff its FIRST STEP's path starts with '/' ({@link RequestLine#firstPath(String)}) — this excludes
     * grammar value files (e.g. {@code values/keyword.txt}, one bare token like {@code cat} per line)
     * while still keeping v2 sequences whose first token is an HTTP method (e.g.
     * {@code "POST /actions/Account.action?signon= u=j2ee"}), which a raw {@code startsWith("/")} check
     * on the line itself would incorrectly drop.
     */
    static List<Seq> readCorpus(String dir) {
        List<Seq> sequences = new ArrayList<>();
        if (dir == null || dir.isEmpty()) return sequences;
        java.nio.file.Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) return sequences;
        try (java.util.stream.Stream<java.nio.file.Path> s = Files.walk(root)) {
            for (java.nio.file.Path p : s.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (RequestLine.firstPath(t).startsWith("/")) {
                        List<RequestLine> steps = RequestLine.parseSequence(t);
                        boolean correlated = steps.stream().anyMatch(RequestLine::needsSubstitution);
                        lintCorrelationOrdering(steps);
                        sequences.add(new Seq(steps, correlated));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Basquin] load: failed reading corpus " + dir + ": " + e);
        }
        return sequences;
    }

    // DD-036: best-effort, log-once for the whole run — a ${{name}} referenced before any earlier step
    // in the sequence captures it will simply never bind, which is silent and confusing without a hint.
    // This flag is JVM-lifetime (process-scoped), not per-run: adequate because run() executes exactly
    // once per driver process, so "once for the whole run" and "once for the JVM" coincide here.
    private static final java.util.concurrent.atomic.AtomicBoolean CORRELATION_LINT_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Walks {@code steps} in order, accumulating names captured so far; for each step's path AND body,
     *  flags any {@code ${{name}}} reference whose name was not captured by a STRICTLY EARLIER step.
     *  Logs at most one warning line for the whole run (not per sequence). Never throws — lint only.
     *  DD-038: {@code ${{@nonce}}} is exempt — it's self-filling every fire (Task 1), never a
     *  captured-value reference, so it has no preceding capture to require and must not trip this. */
    private static void lintCorrelationOrdering(List<RequestLine> steps) {
        java.util.Set<String> capturedSoFar = new java.util.HashSet<>();
        for (RequestLine step : steps) {
            lintRefs(step.path(), capturedSoFar);
            lintRefs(step.body(), capturedSoFar);
            for (Capture cap : step.captures()) capturedSoFar.add(cap.name());
        }
    }

    private static void lintRefs(String text, java.util.Set<String> capturedSoFar) {
        if (text == null) return;
        Matcher m = CORRELATION_REF_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name.equals("@nonce")) continue;   // self-filling; never a captured-value reference
            if (!capturedSoFar.contains(name) && CORRELATION_LINT_WARNED.compareAndSet(false, true)) {
                System.err.println("[Basquin] load: correlation ref ${{" + name + "}} has no preceding <<"
                        + name + "= capture in a sequence; it will never bind");
            }
        }
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

    /** A single load snapshot (DD-033) — the same fields live and terminal, so they converge. */
    static final class LoadSnapshot {
        public final double throughputRps; public final int p50, p90, p99, max;
        public final long heapDriftKb; public final int threadDrift; public final long serverErrors, requests;
        LoadSnapshot(double t, int p50, int p90, int p99, int max, long h, int td, long se, long req) {
            this.throughputRps = t; this.p50 = p50; this.p90 = p90; this.p99 = p99; this.max = max;
            this.heapDriftKb = h; this.threadDrift = td; this.serverErrors = se; this.requests = req;
        }
    }

    /** Compute a snapshot from the LIVE histogram + counters + a drift delta. Torn reads are acceptable
     *  (approximate live percentiles); the terminal call uses the same code so live converges to terminal.
     *  Package-private for testing (no server needed). */
    static LoadSnapshot computeLoadSnapshot(AtomicLongArray hist, long total, long serverErrors,
            double windowSec, long heapDriftKb, int threadDrift) {
        double rps = total / Math.max(0.001, windowSec);
        return new LoadSnapshot(rps,
            percentile(hist, total, 0.50), percentile(hist, total, 0.90),
            percentile(hist, total, 0.99), maxBucket(hist),
            heapDriftKb, threadDrift, serverErrors, total);
    }
}
