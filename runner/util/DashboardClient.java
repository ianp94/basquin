package runner.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pushes this harness run's status + findings to a standalone {@link DashboardServer} — the
 * driver process never hosts a web server itself, so the dashboard's lifetime and blast radius
 * are fully decoupled from the process doing the actual measuring and driving. See
 * docs/DESIGN-DECISIONS.md DD-013.
 *
 * Enable with {@code -Dclosurejvm.dashboard.push=host:port}. The campaign id defaults to the
 * {@code HOSTNAME} env var (a pod's name in Kubernetes, giving natural per-pod identity for the
 * fleet dashboard) and falls back to the local hostname, then a random id.
 */
public final class DashboardClient {

    private static volatile boolean started = false;

    private DashboardClient() {}

    public static synchronized void ensureStarted() {
        if (started) return;
        String target = System.getProperty("closurejvm.dashboard.push");
        if (target == null || target.isEmpty()) {
            return;
        }
        started = true;
        String id = resolveId();
        long intervalMs = Long.getLong("closurejvm.dashboard.pushIntervalMs", 2000L);
        Thread t = new Thread(() -> pushLoop(target, id, intervalMs), "ClosureJVM-DashboardPush");
        t.setDaemon(true);
        t.start();
        System.out.println("[ClosureJVM] pushing status to dashboard at " + target + " as \"" + id + "\"");
    }

    private static String resolveId() {
        String explicit = System.getProperty("closurejvm.dashboard.id");
        if (explicit != null && !explicit.isEmpty()) return explicit;
        String hostname = System.getenv("HOSTNAME"); // the pod name, when running in Kubernetes
        if (hostname != null && !hostname.isEmpty()) return hostname;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "closurejvm-" + Long.toHexString(System.nanoTime());
        }
    }

    private static void pushLoop(String target, String id, long intervalMs) {
        int consecutiveFailures = 0;
        while (true) {
            try {
                post(target, "/ingest/status", id, StatusReporter.snapshotJson());
                post(target, "/ingest/findings", id, findingsJson());
                consecutiveFailures = 0;
            } catch (Throwable t) {
                consecutiveFailures++;
                // Don't spam on every tick if the dashboard is briefly unreachable; report the
                // first failure and then only occasionally so a real outage is still visible.
                if (consecutiveFailures == 1 || consecutiveFailures % 30 == 0) {
                    System.err.println("[ClosureJVM] dashboard push failed (" + consecutiveFailures + "x): " + t);
                }
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void post(String target, String path, String id, String body) throws IOException {
        String urlStr = "http://" + target + path + "?id=" + urlEncode(id);
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code >= 300) {
            throw new IOException("dashboard responded " + code + " for " + path);
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // --- findings JSON: same shape/logic the old embedded dashboard used, now built client-side
    // since only the driver process has the local results directory. ---

    private static String findingsJson() {
        String dir = System.getProperty("closurejvm.fuzz.resultsDir", "fuzz-results");
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> metas = s.filter(p -> p.toString().endsWith(".meta.txt"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .limit(200)
                    .collect(Collectors.toList());
            for (Path m : metas) {
                items.add(metaToJson(m));
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String metaToJson(Path meta) {
        String classification = "", timestamp = "", text;
        try {
            text = new String(Files.readAllBytes(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        for (String line : text.split("\n")) {
            if (line.startsWith("classification=")) classification = line.substring("classification=".length());
            else if (line.startsWith("timestamp=")) timestamp = line.substring("timestamp=".length());
        }
        String body = text.length() > 800 ? text.substring(0, 800) + "…" : text;
        return "{\"file\":\"" + esc(meta.getFileName().toString()) + "\",\"classification\":\"" + esc(classification)
                + "\",\"timestamp\":\"" + esc(timestamp) + "\",\"text\":\"" + esc(body) + "\"}";
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
