package runner.coverage;

import agent.Agent;
import runner.util.FuzzIO;
import runner.util.DashboardClient;
import runner.util.StatusReporter;
import runner.util.TriageSink;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Coverage-<em>guided</em> exploration over HTTP: mutates request inputs (route + params), samples
 * the app-under-test's coverage from its JaCoCo agent after each request, and keeps the inputs
 * that reach new code — an AFL/Zest feedback loop where the coverage signal comes from the app,
 * over the wire (DD-012). Unlike the round-robin driver, this makes coverage climb.
 *
 * Config: {@code examples.http.baseUrl}, {@code basquin.coverage.jacoco=host:port},
 * {@code basquin.coverage.classes=<dir>}. Arg[0] = iterations.
 */
public final class CoverageGuidedRun {

    // Parameter dictionaries used to MUTATE seed routes. The set of reachable endpoints is NOT
    // hardcoded here — it comes from the seed corpus (-Dbasquin.corpusDir), so the exploration
    // surface is data, not compiled code. Baking routes in previously capped this run at 7 of
    // JPetStore's 21 handlers (all of Account and Order were unreachable) — see DD-016.
    private static final String[] CATS = {"FISH", "DOGS", "CATS", "BIRDS", "REPTILES"};
    private static final String[] PRODS = {"FI-SW-01", "FI-SW-02", "FI-FW-01", "FI-FW-02", "K9-BD-01",
            "K9-CW-01", "K9-DL-01", "K9-RT-01", "K9-RT-02", "RP-SN-01", "RP-LI-02", "AV-CB-01", "AV-SB-02",
            "FL-DSH-01", "FL-DLH-02"};
    private static final String[] ITEMS = new String[28];
    private static final String[] KW = {"fish", "dog", "cat", "snake", "bird", "angel", "koi", "tiger",
            "poodle", "dalmation", "iguana", "finch", "parrot"};
    static { for (int i = 0; i < 28; i++) ITEMS[i] = "EST-" + (i + 1); }

    /** Load the request grammar if one is configured and readable; null to fall back to seeds. */
    private static RequestGrammar loadGrammar(Random rnd) {
        String path = System.getProperty("basquin.grammar");
        if (path == null || path.isEmpty()) return null;
        try {
            RequestGrammar g = RequestGrammar.load(Paths.get(path), rnd);
            if (g.isEmpty()) {
                System.err.println("[Basquin] grammar " + path + " has no route templates; ignoring it");
                return null;
            }
            System.out.println("[Basquin] loaded grammar " + path + ": "
                    + g.routeCount() + " route template(s), " + g.ruleCount() + " rule(s)");
            return g;
        } catch (Exception e) {
            System.err.println("[Basquin] failed to load grammar " + path + ": " + e);
            return null;
        }
    }

    /** Seed routes loaded from the corpus dir; falls back to a bare catalog hit if none found. */
    private static List<String> loadSeeds() {
        String dir = System.getProperty("basquin.corpusDir", "examples/corpus/jpetstore");
        List<String> seeds = new ArrayList<>();
        java.nio.file.Path root = Paths.get(dir);
        if (java.nio.file.Files.isDirectory(root)) {
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path p : s.filter(java.nio.file.Files::isRegularFile)
                        .collect(java.util.stream.Collectors.toList())) {
                    for (String line : new String(java.nio.file.Files.readAllBytes(p),
                            StandardCharsets.UTF_8).split("\n")) {
                        String t = line.trim();
                        // firstPath (not a bare startsWith) so a TAB-joined multi-step sequence line —
                        // whose first field is "POST /..." rather than "/..." — isn't dropped; it's
                        // routed to pendingSequences below instead of being fired as one garbled URL.
                        if (!t.isEmpty() && RequestLine.firstPath(t).startsWith("/")) seeds.add(t);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Basquin] failed reading seed corpus " + dir + ": " + e);
            }
        }
        if (seeds.isEmpty()) {
            System.err.println("[Basquin] no seeds in " + dir + "; falling back to a single catalog route. "
                    + "Exploration will be badly limited — point -Dbasquin.corpusDir at a seed corpus.");
            seeds.add("/actions/Catalog.action");
        } else {
            System.out.println("[Basquin] loaded " + seeds.size() + " seed route(s) from " + dir);
        }
        return seeds;
    }

    /** Result of {@link #splitSeeds}: single-step seeds (fired via {@code request}) separated from
     *  multi-step sequences (a TAB-joined line — e.g. round-tripped from an emitted replay corpus —
     *  merged into {@code pendingSequences} and run in order instead). */
    static final class SeedSplit {
        final List<String> singleStep;
        final List<List<String>> sequences;
        SeedSplit(List<String> singleStep, List<List<String>> sequences) {
            this.singleStep = singleStep;
            this.sequences = sequences;
        }
    }

    /**
     * Partition loaded seed lines: a bare line (no TAB) is a single request, fired via {@code
     * request(base, line)}. A TAB-joined line is a whole transaction — firing it as one URL would
     * build garbage (the TABs and later steps would land in the path/query of the first). Package-
     * private for testing.
     */
    /**
     * Format a raw multi-step sequence (as fired by {@link #runSequence}) into the TAB-joined
     * corpus-emission form used for whole-sequence coverage finds — the whole ordered transaction,
     * method-aware, not just its last step. Package-private for testing.
     */
    static String formatSequenceForCorpus(List<String> sequence) {
        List<RequestLine> parsed = new ArrayList<>();
        for (String step : sequence) parsed.add(RequestLine.parse(step));
        return RequestLine.formatSequence(parsed);
    }

    static SeedSplit splitSeeds(List<String> rawSeeds) {
        List<String> singleStep = new ArrayList<>();
        List<List<String>> sequences = new ArrayList<>();
        for (String s : rawSeeds) {
            if (s.indexOf('\t') >= 0) {
                List<String> steps = new ArrayList<>();
                for (RequestLine r : RequestLine.parseSequence(s)) steps.add(r.format());
                sequences.add(steps);
            } else {
                singleStep.add(s);
            }
        }
        return new SeedSplit(singleStep, sequences);
    }

    public static void main(String[] args) throws Exception {
        TriageSink.ensureStarted();
        StatusReporter.ensureStarted();
        DashboardClient.ensureStarted();

        // Load/soak mode (DD-026): replay a saved corpus at volume instead of coverage-guided
        // exploration. No grammar, no coverage sampling, no -Dbasquin.coverage.classes needed.
        if ("load".equals(System.getProperty("basquin.mode"))) {
            LoadRun.run();
            return;
        }

        String baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        // Time-boxed exit (operator campaigns, DD-025): stop the loop cleanly at the deadline so the
        // summary still gets written — a Job activeDeadlineSeconds SIGKILL would skip it. When a
        // duration is set without an explicit iteration count, the deadline governs.
        String durationStr = System.getProperty("basquin.run.duration");
        long deadlineNanos = 0L;
        if (durationStr != null && !durationStr.isEmpty()) {
            deadlineNanos = System.nanoTime() + parseDurationMillis(durationStr) * 1_000_000L;
            if (args.length == 0) iterations = Integer.MAX_VALUE;
        }
        // Machine-readable end-of-run summary for the operator to read (DD-025 §7a). Written via a
        // shutdown hook so it lands on a normal exit and a deadline-triggered one alike.
        String summaryOut = System.getProperty("basquin.summary.out");
        if (summaryOut != null && !summaryOut.isEmpty()) {
            // The summary reuses StatusReporter's counters, which are all no-ops unless the status
            // layer is enabled. Without it the summary would be valid JSON full of zeros — a silently
            // wrong "clean run", worse than an error. Warn loudly (the operator campaign always sets
            // -Dbasquin.status=true; this catches a hand-run misconfiguration).
            if (!StatusReporter.isEnabled()) {
                System.err.println("[Basquin] WARNING: -Dbasquin.summary.out is set but "
                        + "-Dbasquin.status is not — the summary will report ALL ZEROS. "
                        + "Add -Dbasquin.status=true.");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> writeSummary(summaryOut),
                    "Basquin-Summary"));
        }
        String jacoco = System.getProperty("basquin.coverage.jacoco", "localhost:6300");
        String classes = System.getProperty("basquin.coverage.classes");
        if (classes == null) { throw new IllegalArgumentException("set -Dbasquin.coverage.classes"); }
        // Accepts host:port or a comma-separated list; a headless-service host expands to all pods.
        JacocoCoverageProvider cov = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(jacoco), Paths.get(classes));

        long seed = Long.getLong("basquin.seed", 1L);
        Random rnd = new Random(seed);

        // Input source, in order of preference:
        //  1. a request grammar (routes + parameter value space, both as data, with generators)
        //  2. a plain seed corpus (routes only, fixed values)
        // Either way the reachable surface is data, never compiled-in (DD-016/DD-017).
        RequestGrammar grammar = loadGrammar(rnd);
        List<String> loadedSeeds = grammar != null ? grammar.expandAll() : loadSeeds();
        // Multi-step seed lines (TAB-joined, e.g. round-tripped from an emitted replay corpus) must
        // not be fired as a single URL — split them out now so `seeds` below is single-step-only and
        // the sequence steps instead feed pendingSequences (initialized further down).
        SeedSplit seedSplit = splitSeeds(loadedSeeds);
        List<String> seeds = seedSplit.singleStep;
        // Start the corpus as the full seed set so every seeded endpoint is exercised, rather than
        // relying on random discovery to stumble onto them. Cost-driven retention/eviction/ranking
        // (DD-031) is gated behind basquin.cost.enabled; disabled restores today's grow-only,
        // coverage-only, insertion-ordered behavior exactly.
        boolean pheromoneOn = "on".equals(System.getProperty("basquin.pheromone", "off"));
        boolean costEnabled = resolveCostEnabled(pheromoneOn,
                !"false".equals(System.getProperty("basquin.cost.enabled", "true")));
        // Decay cadence for pheromone (DD-032): fixed, independent of which loop branch ran this
        // iteration (sequence/fresh branches `continue` before reaching the bottom of the loop).
        int evaporateEvery = Math.max(1, Integer.getInteger("basquin.pheromone.evaporateEvery", 50));
        CostCorpus corpus = new CostCorpus(seeds, costEnabled, pheromoneOn);
        // Publish the live corpus so the end-of-run summary can emit a capped "replay corpus" the
        // operator persists (DD-026 PR 1). Same object, populated as the run finds new coverage.
        lastCorpus = corpus;
        long best = 0, total = 0;

        // Alternate session epochs: sign on for a stretch (so account/cart/order handlers run
        // their real logic), then go anonymous (so the unauthenticated paths still get probed).
        int epochLength = Integer.getInteger("basquin.session.epoch", 40);
        boolean sessionsEnabled = !"false".equals(System.getProperty("basquin.session", "true"));

        // Multi-step transactions: some code (order placement) is only reachable after an ordered
        // sequence of requests against one session — a populated cart, not just a login. Run each
        // declared sequence once up front, then mix them in probabilistically.
        // NOT gated on grammar != null: a grammar-less run (plain seed corpus) must still replay any
        // seeded multi-step sequences (seedSplit.sequences) — only the grammar-authored half was
        // previously conditional.
        List<List<String>> pendingSequences = new ArrayList<>();
        if (grammar != null && grammar.hasSequences()) pendingSequences.addAll(grammar.expandAllSequences());
        pendingSequences.addAll(seedSplit.sequences);
        int sequencePercent = Integer.getInteger("basquin.sequencePercent", 25);

        // DD-040: the target's own cumulative violation total, sampled around the run window. It is
        // what gives the campaign a target-side number to check the reported findings against —
        // "the driver says 0, the pod evaluated 1,906" is the defect this whole change exists to
        // make impossible to miss. -1 means the target could not be asked (no boundary, old agent).
        long targetViolationsAtStart = readTargetViolations(baseUrl);

        for (int i = 0; i < iterations; i++) {
            if (pheromoneOn && i > 0 && i % evaporateEvery == 0) corpus.evaporate(); // fixed cadence, pre-continue
            if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos) break;  // clean time-box exit
            if (sessionsEnabled && i % epochLength == 0) {
                boolean authenticated = (i / epochLength) % 2 == 0;
                if (authenticated) login(baseUrl); else resetSession();
            }

            // A sequence is one coherent transaction: run its steps in order on the current
            // session and score coverage for the whole thing, not per step.
            List<String> sequence = null;
            if (!pendingSequences.isEmpty()) {
                sequence = pendingSequences.remove(0);
            } else if (grammar != null && grammar.hasSequences() && rnd.nextInt(100) < sequencePercent) {
                sequence = grammar.randomSequence();
            }
            if (sequence != null) {
                runSequence(baseUrl, sequence);
                long coveredAfterSeq = sampleCoverage(cov);
                total = lastCoverageTotal;   // else a sequence-only run reports coverage=N/0
                if (coveredAfterSeq > best) {
                    best = coveredAfterSeq;
                    // A sequence coverage-find: cost of a whole sequence isn't a single request, so
                    // record it as a coverage-find with zero cost — retained, never evicted. Emit the
                    // WHOLE ordered sequence (not just its last step) so replay re-runs the full
                    // transaction instead of firing an orphaned tail step that 500s on its own.
                    corpus.consider(formatSequenceForCorpus(sequence), 0.0, 0, 0, 0, 0, true);
                    StatusReporter.recordSaved("Coverage");
                }
                continue;
            }

            // Selected parent entry, tracked (not just its input string) so a fired child can credit
            // it via reinforce() below (DD-032). Only set on the mutate branches; null otherwise —
            // the seed and fresh-expansion branches don't mutate an existing entry.
            CorpusEntry parent = null;
            String input;
            if (i < seeds.size()) {
                // Deterministic first pass: hit every seed once so no endpoint is left to chance.
                input = seeds.get(i);
            } else if (grammar != null) {
                // Grammar-driven: 30% a fresh expansion of any route template, otherwise re-expand
                // the template behind a corpus entry that previously found new coverage.
                if (rnd.nextInt(100) < 30) {
                    input = grammar.randomRequest();
                } else {
                    parent = corpus.selectParent(rnd);
                    input = grammar.mutate(parent != null ? parent.input : "/");
                }
            } else {
                boolean fresh = rnd.nextInt(100) < 30;
                if (fresh) {
                    input = seeds.get(rnd.nextInt(seeds.size()));
                } else {
                    parent = corpus.selectParent(rnd);
                    input = mutate(parent != null ? parent.input : "/", rnd);
                }
            }

            Agent.beginIteration();
            double cost = 0.0; boolean measured = false; long latMs = 0;
            CostSample sample = CostSample.UNMEASURED;
            long t0 = System.nanoTime();
            try {
                sample = request(baseUrl, input);
                latMs = (System.nanoTime() - t0) / 1_000_000L;
                // DD-040: cost measurement being ENABLED is not the same as this request having BEEN
                // measured. Scoring an unmeasured sample means scoring zeros — which is how a run
                // that saw 1,906 violations reported none and collapsed its corpus to 2 entries.
                if (scoreable(costEnabled, sample)) {
                    cost = CostModel.score(latMs, sample.heapDeltaKb, sample.threadDelta, sample.invariantCount);
                    measured = true;
                }
            } catch (Throwable t) {
                StatusReporter.recordCrash();
                FuzzIO.saveInteresting(input.getBytes(StandardCharsets.UTF_8), t);
            } finally {
                Agent.endIteration();
            }

            // Coverage feedback: keep inputs that reached new code (JaCoCo dumps accumulate, so a
            // rising covered count means this input hit an edge nothing before it had).
            long covered = sampleCoverage(cov);
            total = lastCoverageTotal;
            boolean coverageFind = covered > best;
            if (coverageFind) best = covered;
            if (coverageFind) StatusReporter.recordSaved("Coverage");
            // Offer the input to the pool: coverage finds always retained; when cost was measured,
            // the pool also retains notably-expensive inputs (cold-start + EMA gated) and evicts.
            // Credit assignment (DD-032): a fired child's cost reinforces the parent it came from.
            // BEFORE consider (the only evictor) so the parent is still live and the running total exact.
            if (pheromoneOn && parent != null && measured) corpus.reinforce(parent, cost);
            if (coverageFind || measured) {
                corpus.consider(input, cost, latMs, sample.heapDeltaKb, sample.threadDelta,
                        sample.invariantCount, coverageFind);
            }
        }
        // DD-040: close the window BEFORE the summary is rendered/written (the summary rides a
        // shutdown hook off StatusReporter.snapshotJson).
        long targetViolationsAtEnd = readTargetViolations(baseUrl);
        if (targetViolationsAtStart >= 0 && targetViolationsAtEnd >= targetViolationsAtStart) {
            StatusReporter.recordTargetViolations(targetViolationsAtEnd - targetViolationsAtStart);
        }
        StatusReporter.renderFinal();
        System.out.printf("CoverageGuidedRun done: corpus=%d coverage=%d/%d pheromone=%s seed=%d%n",
                corpus.size(), best, total, pheromoneOn ? "on" : "off", seed);
        reportReportingHealth(reportMeasured, reportMisses,
                targetViolationsAtStart >= 0 && targetViolationsAtEnd >= targetViolationsAtStart
                        ? targetViolationsAtEnd - targetViolationsAtStart : -1L);
        if (costEnabled) {
            StringBuilder top = new StringBuilder();
            int n = 0;
            for (CorpusEntry e : corpus.snapshotByCost()) {
                if (n++ >= 5) break;
                top.append(n == 1 ? "" : ", ").append(e.input).append('=').append(String.format("%.0f", e.cost));
            }
            System.out.println("[Basquin] replay cost-ranked (top " + Math.min(5, corpus.size()) + "): " + top);
        }
    }

    /**
     * Whether cost measurement should be on: the raw {@code basquin.cost.enabled} property, forced
     * true when pheromone selection is on (DD-032) — reinforcement needs a measured cost, so
     * pheromone=on with cost measurement off would silently reinforce with nothing. Package-private
     * for testing (this is the whole decision, extracted so it's checkable without booting the run).
     */
    static boolean resolveCostEnabled(boolean pheromoneOn, boolean rawCostEnabled) {
        if (pheromoneOn && !rawCostEnabled) {
            System.out.println("[Basquin] pheromone=on forces cost measurement on");
            return true;   // reinforcement needs a measured cost — no silent partial state
        }
        return rawCostEnabled;
    }

    /**
     * Parse a duration like {@code 10m}, {@code 30s}, {@code 500ms}, {@code 2h}, or a bare number
     * (seconds) into milliseconds. Package-private for testing.
     */
    static long parseDurationMillis(String s) {
        s = s.trim().toLowerCase();
        long mult;
        String num;
        if (s.endsWith("ms"))      { mult = 1L;         num = s.substring(0, s.length() - 2); }
        else if (s.endsWith("h"))  { mult = 3_600_000L; num = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m"))  { mult = 60_000L;    num = s.substring(0, s.length() - 1); }
        else if (s.endsWith("s"))  { mult = 1_000L;     num = s.substring(0, s.length() - 1); }
        else                       { mult = 1_000L;     num = s; }   // bare number = seconds
        return (long) (Double.parseDouble(num.trim()) * mult);
    }

    /** The run's live corpus, published for the end-of-run summary's replay-corpus emission (DD-026). */
    static volatile CostCorpus lastCorpus; // package-private for the combined-size test

    /** Absolute upper bound on the replay-corpus bytes (also overridable); the effective budget is the
     *  smaller of this and what's left of the ~4 KiB termination message after the metrics JSON. */
    static final int REPLAY_CORPUS_MAX_BYTES =
            Integer.getInteger("basquin.corpus.out.maxBytes", 3000);

    /** Keep the whole summary (metrics + corpus) safely under kubelet's 4096-byte termination cap. */
    private static final int TERMINATION_MSG_BUDGET = 3900;

    /**
     * Write the end-of-run summary (StatusReporter's metrics JSON, plus a capped {@code replayCorpus}
     * array of the interesting inputs) to {@code path} for the operator to read back (DD-025 §7a,
     * DD-026 PR 1). The corpus is best-effort and is built in a separate try so a corpus failure (e.g.
     * a shutdown-time hiccup) can never take down the metrics summary the operator depends on.
     */
    static void writeSummary(String path) { // package-private for testing
        String snap;
        try {
            snap = StatusReporter.snapshotJson(); // a complete JSON object: {...}
        } catch (Exception e) {
            return; // no metrics to write
        }
        String corpusJson = "[]";
        try {
            // CostCorpus.snapshotByCost() is internally synchronized, so no ConcurrentModificationException
            // races the main loop's consider()s on a SIGTERM. Cost-descending order (when enabled) means the
            // most expensive inputs survive replayCorpusJson's byte-budget truncation below.
            CostCorpus lc = lastCorpus;
            List<String> snapshot = new ArrayList<>();
            if (lc != null) {
                for (CorpusEntry e : lc.snapshotByCost()) snapshot.add(e.input);
            }
            // Budget the corpus to what fits the termination message alongside the actual metrics size.
            int snapBytes = snap.getBytes(StandardCharsets.UTF_8).length;
            int overhead = ",\"replayCorpus\":".length(); // the closing '}' is already counted in snap
            int budget = Math.min(REPLAY_CORPUS_MAX_BYTES, TERMINATION_MSG_BUDGET - snapBytes - overhead);
            corpusJson = replayCorpusJson(snapshot, Math.max(2, budget)); // >=2 so "[]" always fits
        } catch (Exception ignored) {
            corpusJson = "[]"; // corpus is best-effort; fall through and still write the metrics
        }
        try {
            // Splice "replayCorpus":[...] before the closing brace; the operator ignores the field if it
            // doesn't parse it, and materializes status.corpusConfigMap if it does.
            String merged = snap.endsWith("}")
                    ? snap.substring(0, snap.length() - 1) + ",\"replayCorpus\":" + corpusJson + "}"
                    : snap;
            java.nio.file.Files.write(Paths.get(path), merged.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // never let summary-writing break the run's exit
        }
    }

    /**
     * A JSON array of distinct corpus inputs, added in order until the encoded UTF-8 size would exceed
     * {@code maxBytes} (a true byte budget — the termination message is byte-limited). Package-private
     * for testing.
     */
    static String replayCorpusJson(List<String> corpus, int maxBytes) {
        StringBuilder sb = new StringBuilder("[");
        int bytes = 1 + 1; // '[' + ']'
        if (corpus != null) {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(corpus);
            boolean first = true;
            for (String entry : seen) {
                String enc = jsonString(entry);
                int encBytes = enc.getBytes(StandardCharsets.UTF_8).length + (first ? 0 : 1); // + comma
                if (bytes + encBytes > maxBytes) {
                    break;
                }
                if (!first) {
                    sb.append(',');
                }
                sb.append(enc);
                bytes += encBytes;
                first = false;
            }
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string encoder (quotes + escapes) — avoids a JSON dependency for one field. */
    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Cookie jar for the current session "epoch". JPetStore keeps the signed-on account in the
     * HTTP session, so without carrying JSESSIONID every authenticated handler sees a null account
     * bean and 500s instead of doing real work — which caps coverage no matter how good the
     * request grammar is. Periodically reset so both anonymous and authenticated states get
     * explored (DD-018).
     */
    private static volatile String sessionCookie = null;

    /** Package-private (was private): the suite shares one JVM and {@code sessionCookie} is a static
     *  field, so a redirect test that seeds a session would otherwise leak it into every later test
     *  in the JVM, including ExploreCorrelationTest. Nothing in production calls this from outside. */
    static void resetSession() { sessionCookie = null; }

    // ------------------------------------------------------------------ DD-039 hop loop + reconcile

    /**
     * Spec §2: at most FIVE REQUESTS in a chain — hop 0 plus at most four follows. {@code hops}
     * counts requests ISSUED, so the guard is {@code hops >= MAX_HOPS} AFTER the increment: five
     * requests, not six. The same number is {@code ResultStore.MAX_HOPS_PER_ID}; two caps that
     * disagree would silently drop a hop, and a silently dropped hop is a lost violation.
     */
    static final int MAX_HOPS = 5;

    /**
     * Read this hop's response to EOF and discard it, so the connection returns to the keep-alive
     * pool. Tomcat's {@code sendRedirect} emits a boilerplate HTML body; abandoning it unread leaks
     * sockets at explore's iteration rate and silently inflates measured latency, which is a
     * cost-model input. {@code LoadRun} drains for exactly this reason.
     *
     * <p>{@code java.io.IOException} is fully qualified: CoverageGuidedRun does not import it
     * ({@code :9-18} imports BufferedReader, InputStream, InputStreamReader and nothing else from
     * java.io).
     */
    private static void drain(HttpURLConnection c) {
        try {
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return;
            byte[] buf = new byte[4096];
            try (InputStream in = is) {
                while (in.read(buf) >= 0) { /* discard */ }
            }
        } catch (java.io.IOException ignored) {
            // A hop we are abandoning anyway; failing to drain it must never fail the request.
        }
    }

    /**
     * Capture/refresh JSESSIONID from THIS hop — the indexed walk lifted verbatim from the
     * pre-DD-039 header scan. Runs on EVERY hop, deliberately: the session cookie is a transport
     * concern, not a document concern (spec §5), and Spring Security issues the rotated JSESSIONID
     * ON the 302, whose headers the JDK's auto-follow discarded before re-issuing.
     */
    private static void captureSessionCookieFrom(HttpURLConnection c) {
        for (int i = 0; ; i++) {
            String key = c.getHeaderFieldKey(i);
            String val = c.getHeaderField(i);
            if (key == null && val == null) break;
            if (key != null && key.equalsIgnoreCase("Set-Cookie") && val != null
                    && val.startsWith("JSESSIONID=")) {
                sessionCookie = val.split(";", 2)[0];
            }
        }
    }

    /** The header-derived {@code Invariant-Remote} records, one per breaching hop. Called from
     *  {@link #reconcile} and NOWHERE else — see that method's javadoc for why the distinction is
     *  the bug fix rather than a tidy-up. Each element is {@code {hop, rawCount, detail, podMeta}}. */
    private static void saveHeaderInvariants(String label, java.util.List<String[]> breaches) {
        for (String[] b : breaches) {
            FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + label + "\ncount=" + b[1] + "\nhop=" + b[0]
                            + (b[2] != null ? "\ndetail=" + b[2] : "") + b[3]);
        }
    }

    /**
     * DD-039: the ONE place that decides what a request reports, and the only place that saves.
     *
     * <p><b>Why the extraction IS the fix (round 3's C4).</b> DD-040 saved the header-derived
     * {@code Invariant-Remote} record the instant it read {@code X-Basquin-Invariant-Count}. A later
     * decision to poll instead therefore could not un-save it — the record was already on disk, and
     * the same violation was filed twice AND counted twice into {@code CostSample.invariantCount},
     * which feeds {@link CostModel#score}. Clearing a {@code headerReported} flag does not unlink a
     * file. That call site is DELETED by this task; the record is held in {@code hdrBreaches} and
     * emitted, or not, here. C4 (suppress the header save) and I11 (poll-and-merge, not
     * poll-and-replace) are ONE decision: landing the loop without this ships a double-save.
     *
     * <p>The rule:
     * <ul>
     *   <li>{@code hops == 1} with a reporting header — the header describes the one hop that
     *       happened, so it is complete. Save from it and do NOT poll: remove-on-read would take the
     *       store's copy of the same violation.</li>
     *   <li>{@code hops > 1} — a header can only ever describe the FINAL hop, so poll for all of
     *       them. <b>The poll wins when it is well-formed; the header record is emitted only when the
     *       poll returned nothing at all.</b> That keeps "never both, for the same hop" true without
     *       needing to know which hops the poll happened to recover — and it is why a forced poll
     *       that misses falls back to the header reading instead of recording a miss.</li>
     *   <li>Poll returned nothing and there was no header either — a miss, exactly as DD-040.</li>
     * </ul>
     */
    static CostSample reconcile(String base, String reqId, String label, int hops,
            boolean headerReported, CostSample headerSample, java.util.List<String[]> hdrBreaches) {
        // Never sent (an unsupported method): nothing to poll, nothing to count. The pre-DD-039 early
        // return sat before the try/finally and counted nothing, and that is preserved exactly.
        if (hops == 0) return CostSample.UNMEASURED;
        if (headerReported && hops == 1) {
            saveHeaderInvariants(label, hdrBreaches);
            reportMeasured++;
            return headerSample;
        }
        CostSample polled = pollResultOrNull(base, reqId, label, hops);
        if (polled != null) return polled;                 // reportMeasured++ happened inside
        if (headerReported) {
            // The poll found nothing, so it took nothing and saved nothing: emitting the header
            // record here cannot double-count.
            saveHeaderInvariants(label, hdrBreaches);
            reportMeasured++;
            return headerSample;
        }
        return recordMiss();
    }

    // ------------------------------------------------------------------ DD-039 redirect decisions
    // Pure, package-private, and off the network on purpose: an unparseable Location, a default-port
    // comparison and HEAD-stays-HEAD are decisions, and a decision only reachable through a server
    // is a decision nobody tests at its edges.

    /** A 3xx that may carry a Location. -1 (an unparseable status line, which getResponseCode()
     *  returns) is deliberately NOT a redirect. */
    static boolean isRedirect(int code) {
        return code >= 300 && code < 400;
    }

    /**
     * {@code location} resolved against {@code requestUrl}, or null if either will not parse.
     *
     * <p><b>Never throws.</b> {@code request(...)} is {@code throws Exception} and both its callers
     * catch {@code Throwable} into {@link StatusReporter#recordCrash()} +
     * {@link FuzzIO#saveInteresting} ({@code :310-312}, {@code :537-539}), so an escaping
     * {@code URISyntaxException} files a false crash finding AGAINST THE APP carrying a stack that
     * points into driver code. {@code URI} throws on unencoded space, <code>{}</code>, {@code |} and
     * {@code ^} — exactly the bytes a fuzzer reflects into a query string.
     */
    static java.net.URI resolveLocation(String requestUrl, String location) {
        if (location == null || location.isEmpty()) return null;
        try {
            return new java.net.URI(requestUrl).resolve(new java.net.URI(location));
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            warnOnce("bad-location", "unparseable Location; the 3xx becomes the final response "
                    + "(first occurrence: " + location + ")");
            return null;
        }
    }

    /** A parsed URI, or null. The never-throwing parse for a URL built from the SUBSTITUTED request
     *  path ({@code runSequence} substitutes at {@code :518}) — a more hostile string than a
     *  {@code Location}, and the one {@code new URL(...)} used to accept leniently. */
    static java.net.URI safeUri(String url) {
        if (url == null) return null;
        try {
            return new java.net.URI(url);
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Same scheme + host (case-insensitive) + port, with an absent port normalized to the scheme
     *  default. A fuzzed input inducing an open redirect must not turn the explorer into a client
     *  for another server, or attribute another host's cost to the app. */
    static boolean sameOrigin(java.net.URI a, java.net.URI b) {
        if (a == null || b == null) return false;
        String sa = a.getScheme(), sb = b.getScheme();
        if (sa == null || sb == null || !sa.equalsIgnoreCase(sb)) return false;
        String ha = a.getHost(), hb = b.getHost();
        if (ha == null || hb == null || !ha.equalsIgnoreCase(hb)) return false;
        return defaultPort(a) == defaultPort(b);
    }

    /** The URI's port, or the scheme default when absent — {@code URI.getPort()} returns -1 for
     *  {@code http://svc/foo}, which must compare equal to a base of {@code http://svc:80}. */
    static int defaultPort(java.net.URI u) {
        if (u == null) return -1;
        if (u.getPort() != -1) return u.getPort();
        String s = u.getScheme();
        if ("https".equalsIgnoreCase(s)) return 443;
        if ("http".equalsIgnoreCase(s)) return 80;
        return -1;
    }

    /**
     * The method the follow hop must use. This PRESERVES today's JDK behaviour rather than choosing
     * new behaviour (spec §3, Evidence TESTs A and B): deviating would be the change and would need
     * its own justification.
     *
     * <p>301/302/303 rewrite to GET only when the original carried a body — the body and its
     * {@code Content-Type} are dropped together, because carrying
     * {@code application/x-www-form-urlencoded} onto a bodyless GET makes some filters attempt form
     * parsing on an empty stream. Everything else keeps its method, notably HEAD: a grammar can
     * express HEAD, and silently promoting it to GET pulls a full body the caller never asked for,
     * inflating the input's measured latency and heap and corrupting its cost rank.
     */
    static String followMethod(int code, String method) {
        if (code == 307 || code == 308) return method;
        boolean carriesBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        if ((code == 301 || code == 302 || code == 303) && carriesBody) return "GET";
        return method;
    }

    /**
     * {@code scheme://host[:port]/path} — query and fragment REMOVED. Every recorded hop URL goes
     * through this, and hop 0's URL is built at construction time from the SUBSTITUTED
     * {@link RequestLine} (DD-038 substitutes the path deliberately, {@code :518}), so a step
     * {@code GET /edit?csrf=${{tok}}} would otherwise write a live CSRF token into a finding meta
     * file. The existing DD-036 guard test puts its token in the BODY and cannot see this.
     */
    static String strippedUrl(String url) {
        if (url == null) return "";
        int cut = url.length();
        int q = url.indexOf('?');
        if (q >= 0) cut = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        return url.substring(0, cut);
    }

    private static final java.util.Set<String> WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** One warn per distinct {@code key} for the whole run. Keyed rather than message-keyed because a
     *  refusal on every iteration, each naming a different URL, would bury the log line it exists to
     *  make diagnosable. */
    static void warnOnce(String key, String msg) {
        if (WARNED.add(key)) System.err.println("[Basquin] explore: " + msg);
    }

    /** Track the last coverage total so both the single-request and sequence paths can report it. */
    private static volatile long lastCoverageTotal = 0;

    /**
     * Run one transaction: every step in order against the current session. A failing step is
     * recorded as a finding but does not abort the sequence — later steps may still reach code,
     * and stopping early would hide it.
     */
    static void runSequence(String baseUrl, List<String> steps) {
        java.util.Map<String, String> bindings = new java.util.HashMap<>();
        for (String step : steps) {
            RequestLine r = RequestLine.parse(step);
            if (r.needsSubstitution()) {
                // DD-038: substitute the FULL request line — a generator marker (e.g. @nonce) is
                // usually authored in the query, not the body. path is never null; body is null-
                // guarded so a path-only marker with no body never reaches substitute() with null.
                String p = LoadRun.substitute(r.path(), bindings);
                String b = (r.body() == null) ? null : LoadRun.substitute(r.body(), bindings);
                if (p == null || (r.body() != null && b == null)) {
                    // A required ${{name}} never bound (its capture step didn't match / didn't run):
                    // skip firing rather than send a literal ${{...}} the app can't use.
                    System.err.println("[Basquin] explore: unresolved correlation ref in " + r.format() + "; step skipped");
                    continue;
                }
                r = new RequestLine(r.method(), p, b, r.captures());
            }
            Agent.beginIteration();
            try {
                // DD-036 invariant: the persisted label must be the raw recipe (`step`, with
                // ${{name}} un-substituted), never r.format() of the substituted RequestLine —
                // the substituted form carries the real captured token, which must never reach
                // disk. Only the recipe is persisted, mirroring formatSequenceForCorpus. The
                // request itself still fires the substituted `r` — the real token still goes
                // over the wire, which is correct; only the recorded finding text changes.
                request(baseUrl, r, bindings, step);
            } catch (Throwable t) {
                StatusReporter.recordCrash();
                FuzzIO.saveInteresting(step.getBytes(StandardCharsets.UTF_8), t);
            } finally {
                Agent.endIteration();
            }
        }
    }

    /** Sample coverage, keeping the panel updated; returns covered probes (0 if unavailable). */
    private static long sampleCoverage(JacocoCoverageProvider cov) {
        try {
            JacocoCoverageProvider.Coverage c = cov.sample();
            lastCoverageTotal = c.total;
            StatusReporter.recordCoverage(c.covered, c.total, c.sourcesResponded, c.sourcesTotal);
            return c.covered;
        } catch (Throwable ignored) {
            return 0; // agent blip; treat as "no new coverage" rather than failing the run
        }
    }

    /** Establish a signed-on session so authenticated handlers run their real logic. */
    private static void login(String base) {
        resetSession();
        try {
            request(base, "/actions/Account.action?signonForm=");
            request(base, "/actions/Account.action?signon=&username=j2ee&password=j2ee");
        } catch (Throwable ignored) {
            // A failed login just means this epoch explores as an anonymous user.
        }
    }

    /**
     * Target-side cost components for one request, obtained either from the response headers (the
     * opportunistic fast path) or from the {@code /__basquin/result} poll (the reliable one).
     *
     * <p>DD-040: {@code measured} is the field that stops a silence being reported as a zero. False
     * means the driver never learned this request's measurements — no {@code X-Basquin-Cost} header
     * (the response had already committed) and the poll missed, or the request was never sent at
     * all. A {@code measured == false} sample must never be scored: feeding a zero-filled sample to
     * {@link CostModel} is exactly how 1,906 evaluated violations were reported as 0.
     */
    static final class CostSample {
        final long heapDeltaKb; final int threadDelta; final int invariantCount; final boolean measured;
        CostSample(long h, int t, int inv, boolean measured) {
            heapDeltaKb = h; threadDelta = t; invariantCount = inv; this.measured = measured;
        }
        /** No measurement — deliberately NOT named EMPTY: it is not "a measured zero". */
        static final CostSample UNMEASURED = new CostSample(0, 0, 0, false);
    }

    /**
     * DD-040: requests whose measurements never reached the driver (no cost header AND a missed or
     * failed poll). Written only from the single-threaded explore loop's request path, as the other
     * run counters are. Published in the summary because a run that cannot see is not a clean run.
     */
    static volatile long reportMisses;

    /** DD-040: requests whose measurements the driver DID obtain, by either channel. */
    static volatile long reportMeasured;

    /** DD-040: per-request id sequence; salted with {@link LoadRun#RUN_SALT} so a foreign or stale id
     *  misses honestly instead of returning another run's entry as fresh (spec §A.4). */
    private static final java.util.concurrent.atomic.AtomicLong REQ_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * DD-040: the result poll's read timeout. It MUST exceed the target handler's quiescence bound
     * ({@code LoadModeControl.QUIESCENCE_WAIT_MS}, 3.5 s on ITERATION_LOCK) — a poll that gives up first turns
     * "queued behind the iteration that is about to write my entry" into a systematic miss, on
     * exactly the committed-response class this channel exists to recover.
     */
    static final int POLL_READ_TIMEOUT_MS = Integer.getInteger("basquin.report.pollTimeoutMs", 4000);

    /** Wire miss token from {@code ResultStore.MISS}; any unparseable body is treated as a miss too. */
    private static final String POLL_MISS = "miss";

    /** DD-039: the result body is now up to {@code ResultStore.MAX_HOPS_PER_ID} lines. Bound:
     *  5 hops × (200-char detail + costCsv + count + separators) ≈ 1.2 KB; 8 KB is generous headroom
     *  for a version-skewed target without letting a hostile body grow unbounded in the driver. */
    private static final int POLL_BODY_MAX = 8192;

    /**
     * Whether this sample may be scored. Extracted (and package-private) so "a miss must not be
     * scored" is assertable directly rather than inferred from the loop. DD-040: cost measurement
     * being enabled is not enough — the sample must actually carry a measurement.
     */
    static boolean scoreable(boolean costEnabled, CostSample s) {
        return costEnabled && s != null && s.measured;
    }

    /**
     * DD-040: true when the driver could not see the majority of what it did. Against an
     * uninstrumented (or wrong-image, or load-mode-stuck) target this is ~every request — the exact
     * situation that used to read as a clean run with zero findings. Pure, so the threshold is
     * testable without a run.
     */
    static boolean missesAreTheMajority(long measured, long misses) {
        long total = measured + misses;
        return total > 0 && misses * 2 > total;
    }

    /**
     * Report the run's reporting health and, when most of it was invisible, FAIL rather than exit 0
     * with a tidy "0 findings". Exiting non-zero is the point: a Kubernetes Job that completes
     * successfully having measured nothing is indistinguishable from a clean campaign, and that
     * indistinguishability is what published four invalid campaigns.
     *
     * <p>Shutdown hooks (the summary writer) still run on {@link System#exit}, so the operator keeps
     * the machine-readable numbers that explain the failure.
     */
    private static void reportReportingHealth(long measured, long misses, long targetViolationsDelta) {
        System.out.printf("[Basquin] reporting: measured=%d misses=%d%s%n", measured, misses,
                targetViolationsDelta >= 0 ? " targetViolations=" + targetViolationsDelta
                                           : " targetViolations=unavailable");
        if (misses > 0) {
            System.out.println("[Basquin] NOTE: " + misses + " request(s) reported no measurement; "
                    + "this run's finding counts are a LOWER BOUND (findingsLowerBound=true).");
        }
        if (missesAreTheMajority(measured, misses)
                && !"false".equals(System.getProperty("basquin.report.failOnMissMajority", "true"))) {
            System.err.println("[Basquin] FATAL: " + misses + " of " + (measured + misses)
                    + " requests reported no measurement at all. This run cannot speak for the target"
                    + " — a boundary that is not installed, a target stuck in load mode, or an"
                    + " unreachable /__basquin/result. Failing rather than reporting a clean zero."
                    + " (-Dbasquin.report.failOnMissMajority=false to downgrade to a warning.)");
            System.exit(3);
        }
    }

    static CostSample request(String base, String step) throws Exception {
        RequestLine r = RequestLine.parse(step);
        if (r.needsSubstitution()) {
            // DD-038: a single-step fire (no sequence, no prior captures) can still carry a
            // self-filling ${{@nonce}} marker — substitute path+body with an empty bindings map
            // (a nonce needs no binding; any OTHER ref here is genuinely unbound with no sequence
            // to have captured it, so it skips below exactly like a sequence step would).
            java.util.Map<String, String> none = java.util.Map.of();
            String p = LoadRun.substitute(r.path(), none);
            String b = (r.body() == null) ? null : LoadRun.substitute(r.body(), none);
            if (p == null || (r.body() != null && b == null)) return CostSample.UNMEASURED; // unbound ref: don't fire literal
            r = new RequestLine(r.method(), p, b, r.captures());
        }
        // Recorded-finding text must stay the RAW input on this path — byte-for-byte unchanged
        // from before DD-036 — since RequestLine.format() canonicalizes a no-body GET by dropping
        // an explicit "GET " prefix, which would silently rewrite what a real finding records.
        return request(base, r, null, step);
    }

    /**
     * Core of the request path, taking an explicit {@code label} for the recorded-finding text
     * (at the {@code X-Basquin-Invariant-*} save sites and the {@code serverError} throw).
     *
     * <p>Task 5 (DD-036): when {@code r.captures()} is non-empty and {@code bindings} is non-null,
     * also retains the response body and runs {@link Capture#extract} against it, recording a new
     * binding on success — mirroring {@link LoadRun#fire}'s capture branch, including its
     * {@code bindings != null} guard (required: the 2-arg overload above passes null).
     *
     * <p>{@code label} MUST NOT be derived from a substituted {@code r} — a correlated step's
     * substituted body carries the real captured token (e.g. a live CSRF value), and that must
     * never reach disk (DD-036 invariant). The 2-arg overload above passes the raw {@code step}
     * text. {@link #runSequence} — the other caller — passes its own raw, un-substituted
     * {@code step} too, even though it fires the substituted {@code r} over the wire; only the
     * safe recipe is ever recorded in a finding.
     */
    private static CostSample request(String base, RequestLine r, java.util.Map<String, String> bindings,
            String label) throws Exception {
        // DD-039: explore follows redirects ITSELF. setInstanceFollowRedirects(true) made the JDK
        // discard the intermediate hop's response headers before re-issuing (so a Set-Cookie on the
        // 3xx was unreachable and the hop's X-Basquin-* were thrown away) and issue the follow hop
        // with NO Cookie and NO X-Basquin-Req at all on a method rewrite. See the spec's JDK probe.
        String method = r.method();
        String reqBody = r.body();          // NOT `body`: StringBuilder body is declared below
        String url = base + r.path();
        // DD-040: stamp every hop with this id so the boundary can publish its per-hop measurements
        // under it; ResultStore accumulates them and reconcile() polls once for the whole chain.
        String reqId = LoadRun.RUN_SALT + "-" + REQ_SEQ.getAndIncrement();
        java.util.LinkedHashSet<String> visited = new java.util.LinkedHashSet<>();
        HttpURLConnection c = null;         // hoisted: the post-loop body read / capture / serverError
        int code = -1;                      // block below needs both
        int hops = 0;
        boolean headerReported = false;
        long heapKb = 0; int threadDelta = 0; int invCount = 0;
        java.util.List<String[]> hdrBreaches = new ArrayList<>();
        CostSample sample = CostSample.UNMEASURED;
        try {
            while (true) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(10000);
                try {
                    c.setRequestMethod(method);
                } catch (java.net.ProtocolException pe) {
                    System.err.println("[Basquin] explore: unsupported HTTP method " + method
                            + " for " + r.path() + "; not sent");
                    break;                  // hops is still 0; reconcile treats that as "never sent"
                }
                c.setInstanceFollowRedirects(false);
                // BOTH headers on EVERY hop: the JDK carries NEITHER onto a method-rewritten hop
                // (spec Evidence TEST A — hop 2 arrived with cookie=null). That single fact is
                // DD-039's motivating defect and DD-040's 189-violation residual.
                c.setRequestProperty("X-Basquin-Req", reqId);
                if (sessionCookie != null) c.setRequestProperty("Cookie", sessionCookie);
                if (reqBody != null) {
                    byte[] bodyBytes = reqBody.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (java.io.OutputStream out = c.getOutputStream()) { out.write(bodyBytes); }
                }
                code = c.getResponseCode();
                hops++;
                // Stripped AT CONSTRUCTION (DD-036): hop 0's URL comes from the SUBSTITUTED
                // RequestLine (runSequence substitutes the path), so an unstripped key would put a
                // live captured token into `visited` — which a Redirect-Loop finding writes out.
                visited.add(strippedUrl(url));
                captureSessionCookieFrom(c);            // every hop: transport, not document (spec §5)

                // Read this hop's reporting headers. NOTHING IS SAVED HERE — reconcile() owns every
                // save, because a save that has already fired cannot be undone by clearing a flag.
                String podHdr = c.getHeaderField("X-Basquin-Pod");
                String podMeta = podHdr == null ? "" : "\npod=" + podHdr;
                String inv = c.getHeaderField("X-Basquin-Invariant-Count");
                if (inv != null) {
                    headerReported = true;
                    try { invCount += Integer.parseInt(inv.trim()); }
                    catch (NumberFormatException ignored) { }
                    hdrBreaches.add(new String[]{String.valueOf(hops - 1), inv,
                            c.getHeaderField("X-Basquin-Invariant-Detail"), podMeta});
                }
                // The fast-path discriminator is the COST header, which the boundary emits on EVERY
                // explore exit — not the invariant header, which is absent on every clean request and
                // would make the driver poll almost always (DD-040 §A.5).
                String costHdr = c.getHeaderField("X-Basquin-Cost");   // "latencyMs,heapDeltaKb,threadDelta"
                if (costHdr != null) {
                    headerReported = true;
                    String[] p = costHdr.split(",");
                    if (p.length == 3) {
                        try {
                            heapKb += Long.parseLong(p[1].trim());     // summed across hops (spec §4)
                            threadDelta += Integer.parseInt(p[2].trim());
                        } catch (NumberFormatException ignored) { }
                    }
                }

                // ---- terminal conditions. EVERY ONE breaks BEFORE the drain, so the final hop's
                // ---- body survives for the capture step and the post-loop read.
                if (!isRedirect(code)) break;                       // the ordinary terminal response
                if (hops >= MAX_HOPS) break;                        // Task 5 files the finding here
                java.net.URI next = resolveLocation(url, c.getHeaderField("Location"));
                if (next == null) break;                            // absent/unparseable: the 3xx is final
                java.net.URI here = safeUri(url);                    // NEVER URI.create: substituted path
                if (here == null || !sameOrigin(here, next)) break;  // Task 5 counts the refusal here
                String nextUrl = strippedUrl(next.toString());
                if (visited.contains(nextUrl)) break;               // Task 5 files the finding here

                drain(c);                                            // only now is this hop discardable
                String nextMethod = followMethod(code, method);
                if (!nextMethod.equals(method)) reqBody = null;      // body AND Content-Type together
                method = nextMethod;
                url = next.toString();                               // the FULL url is dialled; only
                                                                     // the recorded key is stripped
            }
            if (headerReported) sample = new CostSample(heapKb, threadDelta, invCount, true);
            if (hops > 0) {
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder body = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            // Keep the body for server errors (the app's own stack) OR — DD-036 — for a
                            // capture step, so Capture.extract has something to search; still capped, and
                            // draining continues to EOF regardless so keep-alive is preserved either way.
                            if ((code >= 500 && body.length() < 16384)
                                    || (!r.captures().isEmpty() && bindings != null && code < 500 && body.length() < 262144)) {
                                body.append(line).append('\n');
                            }
                        }
                    }
                }
                // Capture from any code < 500, INCLUDING a 4xx — intentional, not an oversight: a form GET
                // that returns 4xx can still carry the token in its (error-stream) body, and explore reads
                // getErrorStream() above for code >= 400, so the value is there to extract. This is the
                // deliberate load-vs-explore asymmetry noted in the DD-036 PR: LoadRun.fire uses
                // getInputStream() (throws on 4xx) and so cannot capture from a 4xx; explore can.
                if (!r.captures().isEmpty() && bindings != null && code < 500) {
                    for (Capture cap : r.captures()) {
                        String val = cap.extract(c::getHeaderField, body.toString());
                        if (val != null) bindings.put(cap.name(), val);
                    }
                }
                if (code >= 500) {
                    throw serverError(code, label, body.toString());
                }
            }
        } finally {
            // The SOLE save/measure/miss decision for this request. In a finally because a 500 — the
            // most interesting request there is — throws above, and its measurements would otherwise
            // rot in the target's store until eviction (spec §A.8). A `return` from here would swallow
            // that 500, so we only reassign; the throw still propagates.
            sample = reconcile(base, reqId, label, hops, headerReported, sample, hdrBreaches);
        }
        return sample;
    }

    /**
     * DD-040/DD-039: recover a request's measurements from the target's id-keyed result store, for
     * the responses that could not carry a header because they had already committed.
     *
     * <p>Two things this method must never do, both learned the expensive way: <b>throw</b> (it is
     * called from a {@code finally}; an exception escaping there REPLACES the in-flight
     * {@code serverError} and the 500 finding is lost), and <b>stop at recovering the count</b> (a
     * recovered violation is only a FINDING once it is saved via {@link FuzzIO#saveWithMeta} →
     * {@code StatusReporter.recordSaved} → {@code findInvariant}).
     *
     * <p><b>DD-039: the body is one line per hop.</b> Explore stamps every hop of a redirect chain
     * with one id and {@code ResultStore} accumulates them, so this sums invariant counts, sums heap
     * and thread deltas, ORs the leak flags, and saves ONE {@code Invariant-Remote} record per
     * BREACHING hop carrying {@code hop=<n>}. A single summed record reads "6 violations on
     * POST /login" when 3 of them were the dashboard render — the multi-hop case this exists to
     * capture is exactly the one a sum erases.
     *
     * <p><b>What {@code hop=<n>} means.</b> The position in the sequence returned by {@code take},
     * which is publish order, which is hop order because the driver issues hops sequentially into
     * one JVM. When a chain's hops were served by DIFFERENT replicas the sequence is the
     * concatenation of each pod's list in poll order, so the index is a position in the recovered
     * sequence rather than a proof of hop order. The count and the cost are unaffected.
     *
     * <p><b>Pod fan-out.</b> {@code hops <= 1}: at most one pod can hold the id, so the first
     * well-formed answer is the whole answer and asking further pods is pure latency — DD-040's
     * behaviour exactly. {@code hops > 1}: the Service VIP routes each hop's connection
     * independently, so hop 0's list can sit on pod A and hop 1's on pod B; stopping at the first
     * hit would return a plausible-looking, silently TRUNCATED result as {@code measured=true},
     * which {@code reportMisses} cannot see. So every base is asked and the answers are merged.
     */
    static CostSample pollResult(String base, String reqId, String label) {
        return pollResult(base, reqId, label, 1);
    }

    /** As above, for a chain of {@code hops} requests. A miss is COUNTED here. */
    static CostSample pollResult(String base, String reqId, String label, int hops) {
        CostSample s = pollResultOrNull(base, reqId, label, hops);
        return s != null ? s : recordMiss();
    }

    /**
     * The poll itself: the recovered sample, or {@code null} when nothing well-formed came back.
     * Never throws and never counts a miss — the CALLER decides whether "nothing" is a miss or a
     * fall-back to the header reading, which is what stops a forced multi-hop poll from converting a
     * measured request into a miss (see {@link #reconcile}).
     */
    private static CostSample pollResultOrNull(String base, String reqId, String label, int hops) {
        List<String> pollBases = PodPollTargets.pollBases(base);
        // Pod addressing was demanded and DNS could not say where the pods are. Guessing via the VIP
        // would restore the 1-in-N lottery behind a poll that looks like it worked (§A.6).
        if (pollBases.isEmpty()) return null;
        List<String> bodies = new ArrayList<>();
        List<String> servedBy = new ArrayList<>();
        for (String pollBase : pollBases) {
            String candidate = fetchResult(pollBase, reqId);
            // '|' is the field separator: anything without it is `miss` or garbage, and the next pod
            // may still hold the real entry. Only a well-formed body counts as an answer.
            if (candidate == null || candidate.isEmpty() || POLL_MISS.equals(candidate)
                    || candidate.indexOf('|') < 0) {
                continue;
            }
            bodies.add(candidate);
            servedBy.add(pollBases.size() > 1 ? pollBase : null);   // only meaningful when we chose
            if (hops <= 1) break;                                   // see the javadoc: one pod, one answer
        }
        if (bodies.isEmpty()) return null;

        int totalCount = 0; long heapKb = 0; int threadDelta = 0; boolean anyLeak = false;
        int hop = 0; int parsedLines = 0;
        String leakPod = "";
        List<String[]> breaching = new ArrayList<>();     // {hop, count, detail-or-null, podMeta}
        for (int b = 0; b < bodies.size(); b++) {
            String podMeta = servedBy.get(b) == null ? "" : "\npod=" + servedBy.get(b);
            for (String line : bodies.get(b).split("\n")) {
                if (line.isEmpty()) continue;
                // 4-field limit: `detail` is app-derived and a version-skewed target could still emit
                // a separator. The count and the cost come from fields the app cannot reach.
                String[] f = line.split("\\|", 4);
                if (f.length < 2) continue;              // a truncated tail line: discard, never shift
                int count;
                try { count = Integer.parseInt(f[1].trim()); }
                catch (NumberFormatException e) { continue; }
                parsedLines++;
                totalCount += count;
                String[] cost = f[0].split(",");
                if (cost.length == 3) {
                    try {
                        heapKb += Long.parseLong(cost[1].trim());
                        threadDelta += Integer.parseInt(cost[2].trim());
                    } catch (NumberFormatException ignored) { }
                }
                if (f.length > 3 && "leak".equals(f[3].trim())) { anyLeak = true; leakPod = podMeta; }
                if (count > 0) {
                    String detail = f.length > 2 && !f[2].isEmpty() ? f[2] : null;
                    breaching.add(new String[]{String.valueOf(hop), String.valueOf(count), detail, podMeta});
                }
                hop++;
            }
        }
        if (parsedLines == 0) return null;   // an unparseable body is a miss; it is not a measured zero

        try {
            // The step that makes the campaign's number move. `label` is the RAW recipe (DD-036) —
            // never a substituted request line, which would write a live CSRF token to disk.
            for (String[] r : breaching) {
                FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                        "route=" + label + "\ncount=" + r[1] + "\nhop=" + r[0]
                                + (r[2] != null ? "\ndetail=" + r[2] : "") + r[3]);
            }
            if (anyLeak) {
                FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Leak-Remote",
                        "route=" + label + "\nleak=true" + leakPod);
            }
        } catch (Throwable ignored) {
            // Saving is best-effort against the never-throw contract above; the measurement stands.
        }
        reportMeasured++;
        return new CostSample(heapKb, threadDelta, totalCount, true);
    }

    /**
     * One {@code /__basquin/result} GET against one address. Returns the response body, or null for
     * anything that is not a 200 — unreachable pod, timeout, no boundary installed. Never throws:
     * {@link #pollResult} is called from a {@code finally} and an escape there would replace the
     * in-flight {@code serverError}, losing the 500 that is the whole point of that request.
     */
    private static String fetchResult(String base, String reqId) {
        try {
            HttpURLConnection pc = (HttpURLConnection) new URL(
                    base + "/__basquin/result?id=" + java.net.URLEncoder.encode(reqId, "UTF-8")).openConnection();
            pc.setConnectTimeout(2000);
            // Must outlast the handler's bounded ITERATION_LOCK wait, or a poll queued behind the
            // very iteration that is about to write this entry is scored as a miss, every time.
            pc.setReadTimeout(POLL_READ_TIMEOUT_MS);
            pc.setRequestMethod("GET");
            StringBuilder sb = new StringBuilder();
            InputStream is = pc.getResponseCode() >= 400 ? pc.getErrorStream() : pc.getInputStream();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    // DD-039: PRESERVE the '\n'. readLine() strips the terminator and the pre-DD-039
                    // loop appended none back, so an accumulated N-hop body arrived as ONE
                    // concatenated string and pollResult's split("\\|", 4) read hop 0's count and
                    // silently discarded hops 1..N-1. One missing character was the difference
                    // between closing DD-040's 189-violation gap and closing none of it.
                    //
                    // The cap is also checked AFTER the append now: checked before, it truncated the
                    // body one line early. A tail line cut by the cap fails its own per-line parse
                    // in pollResult and is discarded, which is the honest outcome.
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line);
                        if (sb.length() >= POLL_BODY_MAX) break;
                    }
                }
            }
            return pc.getResponseCode() == 200 ? sb.toString().trim() : null;
        } catch (Throwable t) {
            return null;   // unreachable target, timeout, no boundary installed: a miss, never a zero
        }
    }

    /** A miss: counted, published, and explicitly NOT turned into a zero-filled sample. */
    private static CostSample recordMiss() {
        reportMisses++;
        StatusReporter.recordReportMiss();
        return CostSample.UNMEASURED;
    }

    /**
     * DD-040: the target's process-wide invariant-violation total ({@code /__basquin/violations}),
     * or -1 when it cannot be read. Sampled at run start and end; the delta is the target-side number
     * a campaign's reported findings are compared against.
     */
    static long readTargetViolations(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/__basquin/violations").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(POLL_READ_TIMEOUT_MS);
            if (c.getResponseCode() != 200) return -1L;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                return line == null ? -1L : Long.parseLong(line.trim());
            }
        } catch (Throwable t) {
            return -1L;   // no boundary, old agent, unreachable: unavailable, not zero
        }
    }

    /**
     * Build an exception that carries the APP'S failure, not ours.
     *
     * A driver-side {@code new RuntimeException("HTTP 500")} records
     * {@code CoverageGuidedRun.request(...)} as the stack, which says nothing about why the app
     * failed — the real NPE only existed in the server log. The container's 500 page already
     * contains the server-side exception and stack, so parse it out and install it as this
     * throwable's type/message/stack. FuzzIO then saves the app's stack into the finding, and the
     * dashboard shows a directly triageable record instead of pointing at the harness.
     */
    private static RuntimeException serverError(int code, String path, String body) {
        String stackText = extractServerStack(body);
        if (stackText == null) {
            return new RuntimeException("HTTP " + code + " for " + path
                    + " (no server stack in response; check the app's logs or its error-page config)");
        }
        String[] lines = stackText.split("\n");
        String header = lines[0].trim();
        RuntimeException e = new RuntimeException("HTTP " + code + " for " + path + " — " + header);
        List<StackTraceElement> frames = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            StackTraceElement f = parseFrame(lines[i]);
            if (f != null) frames.add(f);
        }
        if (!frames.isEmpty()) {
            e.setStackTrace(frames.toArray(new StackTraceElement[0]));
        }
        return e;
    }

    /**
     * Pull the exception + stack out of a servlet container's error page.
     *
     * Tomcat emits two {@code <pre>} blocks: the wrapping framework exception first, then a
     * "Root Cause" block with the actual failure and the application's own frames. The root cause
     * is the useful one — the wrapper just says "unhandled exception in exception handler".
     */
    private static String extractServerStack(String body) {
        if (body == null || body.isEmpty()) return null;
        int searchFrom = 0;
        int rootCause = body.indexOf("Root Cause");
        if (rootCause >= 0) searchFrom = rootCause;      // prefer the deepest cause
        int pre = body.indexOf("<pre>", searchFrom);
        if (pre < 0) pre = body.indexOf("<pre>");        // no root-cause section; take what's there
        if (pre < 0) return null;
        int end = body.indexOf("</pre>", pre);
        String block = unescapeHtml(body.substring(pre + 5, end > 0 ? end : body.length())).trim();
        return block.isEmpty() ? null : block;
    }

    private static StackTraceElement parseFrame(String line) {
        String t = line.trim();
        // Tomcat's error page renders frames WITHOUT the "at " prefix that a logged stack has,
        // so accept both forms.
        if (t.startsWith("at ")) t = t.substring(3).trim();
        if (t.isEmpty() || !t.endsWith(")")) return null;
        int paren = t.indexOf('(');
        if (paren < 0) return null;
        String qualified = t.substring(0, paren);
        String location = t.substring(paren + 1, t.endsWith(")") ? t.length() - 1 : t.length());
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot < 0) return null;
        String cls = qualified.substring(0, lastDot);
        String method = qualified.substring(lastDot + 1);
        String file = location;
        int lineNo = -1;
        int colon = location.lastIndexOf(':');
        if (colon > 0) {
            file = location.substring(0, colon);
            try { lineNo = Integer.parseInt(location.substring(colon + 1).trim()); } catch (Exception ignored) { }
        }
        return new StackTraceElement(cls, method, file, lineNo);
    }

    static String unescapeHtml(String s) {
        String out = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ");
        // Tomcat escapes '/' in class paths as &#47;, so resolve numeric entities too.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d{1,5});").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(m.group(1)))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    /** Package-private for testing. */
    static String mutate(String input, Random r) {
        // Replay-only guard: a TAB-joined multi-step sequence is not a single mutatable request —
        // scanning past the TAB with replaceParam's indexOf/substring logic would splice or corrupt
        // steps. Matches the method's existing "un-mutatable input returned as-is" contract.
        if (input.indexOf('\t') >= 0) return input;
        // No route synthesis here: an un-mutatable input is returned as-is and the caller's
        // seed-picking branch supplies variety. Endpoints come from the corpus, not from code.
        if (!input.contains("=")) { return input; }
        if (input.contains("categoryId=")) return replaceParam(input, "categoryId=", CATS[r.nextInt(CATS.length)]);
        if (input.contains("productId=")) return replaceParam(input, "productId=", PRODS[r.nextInt(PRODS.length)]);
        if (input.contains("itemId=")) return replaceParam(input, "itemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("workingItemId=")) return replaceParam(input, "workingItemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("keyword=")) return replaceParam(input, "keyword=", KW[r.nextInt(KW.length)]);
        // Params we have no dictionary for (orderId, username, …): leave the seed untouched so
        // its endpoint still gets exercised.
        return input;
    }

    private static String replaceParam(String input, String key, String val) {
        int k = input.indexOf(key) + key.length();
        int end = input.indexOf('&', k);
        if (end < 0) end = input.length();
        return input.substring(0, k) + val + input.substring(end);
    }
}
