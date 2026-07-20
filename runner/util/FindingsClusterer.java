package runner.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, no-LLM noise reduction for the findings view: groups saved findings by a
 * fingerprint so a human reviewing "genuine issues" sees "heap growth on the catalog route,
 * seen 94 times across 12 routes, 191KB-6144KB" instead of 94 nearly-identical rows.
 *
 * This operates only on the DASHBOARD'S READ PATH. It never touches what {@link TriageSink} /
 * {@code FuzzIO} save to disk — every interesting input is still kept (DD-006's "never drop a
 * finding" is about the corpus, which exploration needs whole). Clustering is presentation only,
 * and it's a hard deterministic grouping rather than a model call, matching the project's
 * "enforcement/hard-checks over inference" default (agents.md) for anything the oracle relies on
 * — the optional Claude-API analysis (DD-015) is a separate, opt-in layer on top of this, for
 * explaining clusters a human is already looking at, not for deciding what counts as a finding.
 *
 * Fingerprint:
 * - Crash with an {@code exception=} line: {@code crash:<ExceptionClass>@<routePattern-or-none>}
 * - Otherwise (invariant): {@code <classification>:<kind>@<routePattern-or-none>}
 *   where kind is the word before ':' in the {@code detail=} line (e.g. "heapDelta", "latency")
 *   and routePattern is the {@code route=} line with parameter VALUES stripped (keeps the shape:
 *   which route + which params varied, not which concrete id) — same-shape requests that all
 *   trip the same invariant are almost always the same systemic behavior, not distinct bugs.
 */
public final class FindingsClusterer {

    private FindingsClusterer() {}

    public static final class Cluster {
        public String fingerprint;
        public String classification;
        public String kind = "";
        public String routePattern = "";
        public int count;
        public long firstSeenMs = Long.MAX_VALUE;
        public long lastSeenMs = 0;
        public long minMagnitude = Long.MAX_VALUE;
        public long maxMagnitude = Long.MIN_VALUE;
        public final TreeSet<String> distinctRoutes = new TreeSet<>();
        /** Which campaigns (target instances) this defect was seen on — fleet view. */
        public final TreeSet<String> campaigns = new TreeSet<>();
        public String exampleText = "";
        /** Bounded sample of the actual inputs that produced this cluster, for the viewer. */
        public final List<Sample> samples = new ArrayList<>();
    }

    /** One concrete input that produced a finding — what you'd replay to reproduce it. */
    public static final class Sample {
        public final long timestamp;
        public final String input;
        public final int inputSize;
        public final boolean inputBinary;
        public final String text;
        Sample(long timestamp, String input, int inputSize, boolean inputBinary, String text) {
            this.timestamp = timestamp; this.input = input; this.inputSize = inputSize;
            this.inputBinary = inputBinary; this.text = text;
        }
    }

    private static final int MAX_SAMPLES_PER_CLUSTER = 10;

    /**
     * Cluster a findings JSON array in the shape DashboardClient produces
     * ({@code [{"file":..,"classification":..,"timestamp":..,"text":..}, ...]}). Field order in
     * each object is fixed by the producer (see DashboardClient.metaToJson): walk the array once,
     * pulling classification/timestamp/text out of each object in that order with an iterative,
     * non-regex scanner ({@link JsonScan}) — no general JSON parser needed (same trust boundary
     * as DD-013's numField scrape: this server controls the producer format), and no risk of the
     * stack-overflow-by-recursion-depth a naive "match an escaped string" regex hits on
     * production-sized text (verified against 935 real saved findings during development).
     */
    /**
     * Cluster across many campaigns at once — the fleet view of findings.
     *
     * With several instances of the app under test (e.g. one driver per pod), the SAME defect is
     * found independently by each, and per-campaign clustering shows it as N unrelated clusters in
     * N places. Fingerprints are instance-independent by construction (invariant kind + route
     * shape, or exception class), so the same defect merges here regardless of which target found
     * it, and {@code campaigns} records where it was seen. See DD-020.
     */
    public static List<Cluster> clusterAcross(Map<String, String> campaignToFindingsJson) {
        Map<String, Cluster> byFingerprint = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : campaignToFindingsJson.entrySet()) {
            clusterInto(e.getValue(), e.getKey(), byFingerprint);
        }
        List<Cluster> out = new ArrayList<>(byFingerprint.values());
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    public static List<Cluster> cluster(String findingsJsonArray) {
        Map<String, Cluster> byFingerprint = new LinkedHashMap<>();
        clusterInto(findingsJsonArray, null, byFingerprint);
        List<Cluster> out = new ArrayList<>(byFingerprint.values());
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    private static void clusterInto(String findingsJsonArray, String campaign,
                                    Map<String, Cluster> byFingerprint) {
        int cursor = 0;
        while (true) {
            int classStart = JsonScan.valueStart(findingsJsonArray, "classification", cursor);
            if (classStart < 0) break;
            int classEnd = JsonScan.stringEnd(findingsJsonArray, classStart);
            if (classEnd < 0) break;
            String classification = JsonScan.unescape(findingsJsonArray.substring(classStart, classEnd));

            String timestamp = JsonScan.extract(findingsJsonArray, "timestamp", classEnd);
            String text = JsonScan.extract(findingsJsonArray, "text", classEnd);
            String input = JsonScan.extract(findingsJsonArray, "input", classEnd);
            String inputSizeRaw = JsonScan.rawNumber(findingsJsonArray, "inputSize", classEnd);
            boolean inputBinary = "true".equals(JsonScan.rawNumber(findingsJsonArray, "inputBinary", classEnd));
            cursor = classEnd + 1;
            if (timestamp == null || text == null) continue;
            if (input == null) input = "";

            long ts = parseLongSafe(timestamp);

            String exception = field(text, "exception=");
            String detail = extractDetail(text);

            // Prefer an explicit route= line (HTTP-driven invariant finds write one). Crash finds
            // don't, but for HTTP targets the saved input IS the route — so fall back to it rather
            // than reporting route=(none) and collapsing every crash on every endpoint into one
            // cluster keyed only by exception class.
            String route = field(text, "route=");
            if (route.isEmpty() && input.startsWith("/")) {
                route = input.split("\\s", 2)[0];
            }
            String routePattern = route.isEmpty() ? "" : route.replaceAll("=[^&]*", "=");

            String fingerprint;
            String kind;
            if (exception != null && !exception.isEmpty()) {
                fingerprint = "crash:" + shortClassName(exception) + "@" + (routePattern.isEmpty() ? "none" : routePattern);
                kind = shortClassName(exception);
            } else {
                kind = detail.isEmpty() ? "?" : detail.substring(0, Math.max(0, detail.indexOf(':'))).trim();
                if (kind.isEmpty()) kind = detail.isEmpty() ? "?" : detail;
                fingerprint = classification + ":" + kind + "@" + (routePattern.isEmpty() ? "none" : routePattern);
            }

            String finalClassification = classification, finalKind = kind, finalRoutePattern = routePattern, finalText = text;
            Cluster c = byFingerprint.computeIfAbsent(fingerprint, k -> {
                Cluster nc = new Cluster();
                nc.fingerprint = k;
                nc.classification = finalClassification;
                nc.kind = finalKind;
                nc.routePattern = finalRoutePattern;
                nc.exampleText = finalText;
                return nc;
            });
            c.count++;
            if (campaign != null) c.campaigns.add(campaign);
            c.firstSeenMs = Math.min(c.firstSeenMs, ts);
            c.lastSeenMs = Math.max(c.lastSeenMs, ts);
            if (c.samples.size() < MAX_SAMPLES_PER_CLUSTER && !input.isEmpty()) {
                c.samples.add(new Sample(ts, input, (int) parseLongSafe(
                        inputSizeRaw == null ? "0" : inputSizeRaw), inputBinary, text));
            }
            if (!route.isEmpty() && c.distinctRoutes.size() < 12) {
                c.distinctRoutes.add(route);
            }
            long mag = extractMagnitude(detail);
            if (mag >= 0) {
                c.minMagnitude = Math.min(c.minMagnitude, mag);
                c.maxMagnitude = Math.max(c.maxMagnitude, mag);
            }
        }
    }

    public static String toJson(List<Cluster> clusters) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < clusters.size(); i++) {
            if (i > 0) sb.append(',');
            Cluster c = clusters.get(i);
            sb.append("{\"fingerprint\":\"").append(esc(c.fingerprint)).append('"')
              .append(",\"classification\":\"").append(esc(c.classification)).append('"')
              .append(",\"kind\":\"").append(esc(c.kind)).append('"')
              .append(",\"routePattern\":\"").append(esc(c.routePattern)).append('"')
              .append(",\"count\":").append(c.count)
              .append(",\"firstSeenMs\":").append(c.firstSeenMs == Long.MAX_VALUE ? 0 : c.firstSeenMs)
              .append(",\"lastSeenMs\":").append(c.lastSeenMs)
              .append(",\"minMagnitude\":").append(c.minMagnitude == Long.MAX_VALUE ? -1 : c.minMagnitude)
              .append(",\"maxMagnitude\":").append(c.maxMagnitude == Long.MIN_VALUE ? -1 : c.maxMagnitude)
              .append(",\"distinctRoutes\":").append(c.distinctRoutes.size())
              .append(",\"campaigns\":").append(c.campaigns.size())
              .append(",\"campaignNames\":\"").append(esc(String.join(", ", c.campaigns))).append('"')
              .append(",\"exampleText\":\"").append(esc(c.exampleText)).append('"')
              .append(",\"samples\":[");
            for (int j = 0; j < c.samples.size(); j++) {
                if (j > 0) sb.append(',');
                Sample s = c.samples.get(j);
                sb.append("{\"timestamp\":").append(s.timestamp)
                  .append(",\"input\":\"").append(esc(s.input)).append('"')
                  .append(",\"inputSize\":").append(s.inputSize)
                  .append(",\"inputBinary\":").append(s.inputBinary)
                  .append(",\"text\":\"").append(esc(s.text)).append("\"}");
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    // --- extraction helpers ---

    private static String field(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    // Two saved-finding shapes exist (see FuzzIO): HTTP-driven finds (HttpRouteDriveTarget,
    // CoverageGuidedRun) write an explicit "detail=kind: value > threshold" line. Local/JQF finds
    // (FuzzIO.saveWithMeta with details = Agent.getLastInvariantViolations() joined by "\n") put
    // the bare "kind: value > threshold" line directly in the body, no "detail=" prefix at all —
    // verified against the local-corpus subset of the 936 real findings used to test this class,
    // where relying on "detail=" alone left every local finding's kind unresolved ("?").
    private static String extractDetail(String text) {
        String explicit = field(text, "detail=");
        if (!explicit.isEmpty()) return explicit;
        for (String line : text.split("\n")) {
            // A bare "word: value" line — not a structural field like "route="/"count="/"stack="
            // which use '=' rather than ': ' right after the field name.
            if (line.matches("^[A-Za-z]+:\\s.*")) {
                return line.trim();
            }
        }
        return "";
    }

    private static long extractMagnitude(String detail) {
        Matcher m = Pattern.compile("(\\d+)").matcher(detail);
        return m.find() ? parseLongSafe(m.group(1)) : -1;
    }

    private static String shortClassName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
