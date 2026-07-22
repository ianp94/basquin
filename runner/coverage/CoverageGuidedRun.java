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
            CostSample sample = CostSample.EMPTY;
            long t0 = System.nanoTime();
            try {
                sample = request(baseUrl, input);
                latMs = (System.nanoTime() - t0) / 1_000_000L;
                if (costEnabled) {
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
        StatusReporter.renderFinal();
        System.out.printf("CoverageGuidedRun done: corpus=%d coverage=%d/%d pheromone=%s seed=%d%n",
                corpus.size(), best, total, pheromoneOn ? "on" : "off", seed);
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

    private static void resetSession() { sessionCookie = null; }

    /** Track the last coverage total so both the single-request and sequence paths can report it. */
    private static volatile long lastCoverageTotal = 0;

    /**
     * Run one transaction: every step in order against the current session. A failing step is
     * recorded as a finding but does not abort the sequence — later steps may still reach code,
     * and stopping early would hide it.
     */
    private static void runSequence(String baseUrl, List<String> steps) {
        for (String step : steps) {
            Agent.beginIteration();
            try {
                request(baseUrl, step);
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

    /** Target-side cost components parsed from response headers (0 when a header wasn't sent). */
    static final class CostSample {
        final long heapDeltaKb; final int threadDelta; final int invariantCount;
        CostSample(long h, int t, int inv) { heapDeltaKb = h; threadDelta = t; invariantCount = inv; }
        static final CostSample EMPTY = new CostSample(0, 0, 0);
    }

    static CostSample request(String base, String step) throws Exception {
        RequestLine r = RequestLine.parse(step);
        HttpURLConnection c = (HttpURLConnection) new URL(base + r.path()).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(10000);
        try {
            c.setRequestMethod(r.method());
        } catch (java.net.ProtocolException pe) {
            System.err.println("[Basquin] explore: unsupported HTTP method " + r.method()
                    + " for " + r.path() + "; not sent");
            return CostSample.EMPTY;   // fail loud: do NOT fall through to a silent GET
        }
        c.setInstanceFollowRedirects(true);
        if (sessionCookie != null) {
            c.setRequestProperty("Cookie", sessionCookie);
        }
        if (r.body() != null) {
            byte[] bodyBytes = r.body().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(bodyBytes);
            }
        }
        int code = c.getResponseCode();
        // Capture/refresh JSESSIONID so subsequent requests stay in the same session.
        for (int i = 0; ; i++) {
            String key = c.getHeaderFieldKey(i);
            String val = c.getHeaderField(i);
            if (key == null && val == null) break;
            if (key != null && key.equalsIgnoreCase("Set-Cookie") && val != null
                    && val.startsWith("JSESSIONID=")) {
                sessionCookie = val.split(";", 2)[0];
            }
        }
        int invCount = 0;
        String inv = c.getHeaderField("X-Basquin-Invariant-Count");
        if (inv != null) {
            try { invCount = Integer.parseInt(inv.trim()); } catch (NumberFormatException ignored) {}
            String detail = c.getHeaderField("X-Basquin-Invariant-Detail");
            FuzzIO.saveWithMeta(step.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + step + "\ncount=" + inv + (detail != null ? "\ndetail=" + detail : ""));
        }
        long heapKb = 0; int threadDelta = 0;
        String costHdr = c.getHeaderField("X-Basquin-Cost");  // "latencyMs,heapDeltaKb,threadDelta"
        if (costHdr != null) {
            String[] p = costHdr.split(",");
            if (p.length == 3) {
                try { heapKb = Long.parseLong(p[1].trim()); threadDelta = Integer.parseInt(p[2].trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder body = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // Keep the body only for server errors — that's where the app's own stack is.
                    if (code >= 500 && body.length() < 16384) body.append(line).append('\n');
                }
            }
        }
        if (code >= 500) {
            throw serverError(code, step, body.toString());
        }
        return new CostSample(heapKb, threadDelta, invCount);
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

    private static String unescapeHtml(String s) {
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
