package runner.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Standalone dashboard/aggregator process — deliberately NOT embedded in the harness driver or
 * anywhere near the app under test. Many harness/driver processes (one per campaign, one per pod
 * in a cluster) push status + findings snapshots here; this process just stores the latest raw
 * JSON per campaign id and serves it back to the browser. It never parses the payload beyond a
 * light best-effort scrape of a few display numbers, so it has no schema coupling to the harness
 * and adds no dependency.
 *
 * This is the aggregation point the auto-injection operator vision points at: each pod's driver
 * (or an injected sidecar) pushes here under its pod name; one dashboard shows the whole fleet.
 * See docs/DESIGN-DECISIONS.md DD-013.
 *
 * Run standalone: {@code ./gradlew runDashboard [-Dclosurejvm.dashboard.server.port=7070]}
 */
public final class DashboardServer {

    private static final class Campaign {
        volatile String statusJson = "{}";
        volatile String findingsJson = "[]";
        volatile String configJson = "{}";
        volatile long lastSeenMillis = System.currentTimeMillis();
    }

    private static final Map<String, Campaign> CAMPAIGNS = new ConcurrentHashMap<>();
    private static final long STALE_AFTER_MS = Long.getLong("closurejvm.dashboard.staleAfterMs", 10_000L);

    public static void main(String[] args) throws IOException {
        int port = Integer.getInteger("closurejvm.dashboard.server.port", 7070);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/ingest/status", ex -> ingest(ex, true));
        server.createContext("/ingest/findings", ex -> ingest(ex, false));
        server.createContext("/ingest/config", DashboardServer::ingestConfig);
        server.createContext("/api/campaigns", DashboardServer::listCampaigns);
        server.createContext("/api/clusters", DashboardServer::fleetClusters);
        server.createContext("/api/campaign/", DashboardServer::campaignDetail);
        server.createContext("/api/analyze/", DashboardServer::analyze);
        server.createContext("/", ex -> respond(ex, "text/html; charset=utf-8", page()));

        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ClosureJVM-DashboardServer");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[ClosureJVM] dashboard server listening on :" + port);
    }

    // --- ingest (called by DashboardClient, one campaign per push) ---

    private static void ingest(HttpExchange ex, boolean isStatus) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String id = queryParam(ex, "id");
        if (id == null || id.isEmpty()) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        String body = readBody(ex);
        Campaign c = CAMPAIGNS.computeIfAbsent(id, k -> new Campaign());
        if (isStatus) {
            c.statusJson = body;
        } else {
            c.findingsJson = body;
        }
        c.lastSeenMillis = System.currentTimeMillis();
        respond(ex, "text/plain", "ok");
    }

    /** Config is immutable per run, so it is pushed once rather than on every interval. */
    private static void ingestConfig(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        String id = queryParam(ex, "id");
        if (id == null || id.isEmpty()) { ex.sendResponseHeaders(400, -1); return; }
        Campaign c = CAMPAIGNS.computeIfAbsent(id, k -> new Campaign());
        c.configJson = readBody(ex);
        c.lastSeenMillis = System.currentTimeMillis();
        respond(ex, "text/plain", "ok");
    }

    // --- read side (called by the browser) ---

    private static void listCampaigns(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        String json = CAMPAIGNS.entrySet().stream().map(e -> {
            String id = e.getKey();
            Campaign c = e.getValue();
            long ageMs = now - c.lastSeenMillis;
            boolean alive = ageMs < STALE_AFTER_MS;
            return "{\"id\":\"" + jsonEsc(id) + "\",\"ageSec\":" + (ageMs / 1000)
                    + ",\"alive\":" + alive
                    + ",\"iterations\":" + numField(c.statusJson, "iterations")
                    + ",\"crashes\":" + numField(c.statusJson, "crashes")
                    + ",\"coveragePct\":" + numField(c.statusJson, "pct") + "}";
        }).collect(Collectors.joining(",", "[", "]"));
        respond(ex, "application/json", json);
    }

    /** Fleet-wide clusters: the same defect seen on several targets merges into one row (DD-020). */
    private static void fleetClusters(HttpExchange ex) throws IOException {
        Map<String, String> byCampaign = new java.util.LinkedHashMap<>();
        CAMPAIGNS.forEach((id, c) -> byCampaign.put(id, c.findingsJson));
        respond(ex, "application/json",
                FindingsClusterer.toJson(FindingsClusterer.clusterAcross(byCampaign)));
    }

    private static void campaignDetail(HttpExchange ex) throws IOException {
        // path: /api/campaign/{id}/status | findings | clusters
        String path = ex.getRequestURI().getPath();
        String[] parts = path.substring("/api/campaign/".length()).split("/", 2);
        if (parts.length != 2) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        String id = parts[0], sub = parts[1];
        Campaign c = CAMPAIGNS.get(id);
        if (c == null) {
            respond(ex, "application/json", "status".equals(sub) ? "{}" : "[]");
            return;
        }
        switch (sub) {
            case "status": respond(ex, "application/json", c.statusJson); return;
            case "findings": respond(ex, "application/json", c.findingsJson); return;
            case "config": respond(ex, "application/json", c.configJson); return;
            case "clusters":
                respond(ex, "application/json", FindingsClusterer.toJson(FindingsClusterer.cluster(c.findingsJson)));
                return;
            default: ex.sendResponseHeaders(404, -1);
        }
    }

    /** POST /api/analyze/{id} — opt-in Claude-API analysis of this campaign's clusters (DD-015). */
    private static void analyze(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String id = ex.getRequestURI().getPath().substring("/api/analyze/".length());
        Campaign c = CAMPAIGNS.get(id);
        if (c == null) {
            respond(ex, "application/json", "{\"error\":\"unknown campaign\"}");
            return;
        }
        if (!ClaudeAnalyzer.isConfigured()) {
            respond(ex, "application/json",
                "{\"error\":\"Claude API key not configured on the dashboard server (set ANTHROPIC_API_KEY)\"}");
            return;
        }
        try {
            List<FindingsClusterer.Cluster> clusters = FindingsClusterer.cluster(c.findingsJson);
            String prompt = buildAnalysisPrompt(id, c.statusJson, clusters);
            String analysis = ClaudeAnalyzer.analyze(prompt);
            respond(ex, "application/json", "{\"analysis\":\"" + jsonEsc(analysis) + "\"}");
        } catch (IOException e) {
            respond(ex, "application/json", "{\"error\":\"" + jsonEsc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private static String buildAnalysisPrompt(String id, String statusJson, List<FindingsClusterer.Cluster> clusters) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are triaging results from ClosureJVM, a fuzzing/availability-testing harness. ")
          .append("Campaign \"").append(id).append("\" status: ").append(statusJson).append("\n\n")
          .append("Findings have already been deterministically clustered by fingerprint ")
          .append("(same invariant kind + same route shape, or same exception class). ")
          .append("Each line below is one cluster: kind, route pattern, how many times it fired, ")
          .append("how many distinct concrete routes, and the magnitude range observed.\n\n");
        int shown = 0;
        for (FindingsClusterer.Cluster cl : clusters) {
            if (shown++ >= 20) { sb.append("(").append(clusters.size() - 20).append(" more clusters omitted)\n"); break; }
            sb.append("- ").append(cl.classification).append(" / ").append(cl.kind)
              .append(" @ ").append(cl.routePattern.isEmpty() ? "(no route)" : cl.routePattern)
              .append(" — fired ").append(cl.count).append("x across ").append(cl.distinctRoutes.size())
              .append(" concrete route(s), magnitude ").append(cl.minMagnitude).append("-").append(cl.maxMagnitude)
              .append('\n');
        }
        sb.append("\nIn under 200 words: which clusters look like the same underlying systemic behavior ")
          .append("(e.g. proportional-to-payload-size allocation, expected) versus a genuine bug worth ")
          .append("investigating, and what would you check first?");
        return sb.toString();
    }

    // --- helpers ---

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return kv.substring(i + 1);
            }
        }
        return null;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    // Best-effort scrape of a top-level numeric field out of a JSON blob we produced ourselves
    // (StatusReporter.snapshotJson), only for the campaign-list summary cards. Never fails; "-1"
    // means not found, rendered as "—" by the UI. Deliberately not a JSON parser: this server
    // stores and relays payloads verbatim and has no schema coupling to the harness.
    private static String numField(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        return m.find() ? m.group(1) : "-1";
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * The dashboard page, loaded from the classpath (resources/dashboard.html). Kept as a real
     * file rather than a Java string literal: the UI outgrew being maintainable as escaped
     * concatenation (it caused several escaping bugs), and this way it can be edited and diffed
     * like the HTML it is.
     */
    private static String page() {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) {
                return "<!doctype html><meta charset=utf-8><body style=\"font-family:monospace\">"
                     + "dashboard.html not found on the classpath. APIs still work: "
                     + "<a href=/api/campaigns>/api/campaigns</a></body>";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "<!doctype html><meta charset=utf-8><body>failed to load dashboard.html: " + e + "</body>";
        }
    }
}
