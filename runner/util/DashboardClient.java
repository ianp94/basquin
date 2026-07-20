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
import java.util.Map;
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
        boolean configPushed = false;
        while (true) {
            try {
                if (!configPushed) {
                    // Config is immutable for the run; push once so the dashboard can show what
                    // actually produced these results (params + the grammar source).
                    post(target, "/ingest/config", id, configJson());
                    configPushed = true;
                }
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
        // Required by DashboardServer: proves this is a deliberate client, not a cross-origin
        // form post from a page the operator happens to have open.
        c.setRequestProperty("X-ClosureJVM-Dashboard", "1");
        String token = System.getProperty("closurejvm.dashboard.token", "");
        if (!token.isEmpty()) c.setRequestProperty("X-ClosureJVM-Token", token);
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

    /**
     * The run's configuration: the parameters it was launched with, plus the grammar/corpus it is
     * driving from. Pushed so a result in the dashboard is interpretable — "23% coverage" means
     * little without knowing which grammar, which endpoints, and which thresholds produced it.
     *
     * Values that look like credentials are redacted: a driver's system properties are not a
     * deliberate secret store, but it costs nothing to avoid shipping one to a dashboard that may
     * be shared, and this payload is displayed verbatim in a browser.
     */
    private static String configJson() {
        StringBuilder params = new StringBuilder();
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("closurejvm.") || name.startsWith("examples.")) {
                sorted.put(name, System.getProperty(name));
            }
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (params.length() > 0) params.append(',');
            String value = looksSecret(e.getKey()) ? "(redacted)" : e.getValue();
            params.append('"').append(esc(e.getKey())).append("\":\"").append(esc(value)).append('"');
        }

        String grammarPath = System.getProperty("closurejvm.grammar", "");
        String grammarText = "";
        if (!grammarPath.isEmpty()) {
            try {
                grammarText = new String(Files.readAllBytes(Paths.get(grammarPath)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                grammarText = "(could not read " + grammarPath + ": " + e.getMessage() + ")";
            }
        }
        return "{\"startedAtMs\":" + START_MS
                + ",\"params\":{" + params + "}"
                + ",\"grammarPath\":\"" + esc(grammarPath) + "\""
                + ",\"grammarText\":\"" + esc(grammarText) + "\""
                + ",\"corpusDir\":\"" + esc(System.getProperty("closurejvm.corpusDir", "")) + "\"}";
    }

    private static final long START_MS = System.currentTimeMillis();

    private static boolean looksSecret(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.contains("key") || k.contains("secret") || k.contains("token")
                || k.contains("password") || k.contains("credential");
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

        // The actual input that produced this finding lives in the sibling .bin that FuzzIO wrote
        // (input-<ts>-<seq>.meta.txt <-> input-<ts>-<seq>.bin). Surfacing it is what makes a
        // finding reproducible: for HTTP-driven runs the input IS the route, so it can be replayed
        // directly; for byte-fuzz targets it's raw bytes, rendered as hex.
        Input input = readInput(meta);

        return "{\"file\":\"" + esc(meta.getFileName().toString()) + "\",\"classification\":\"" + esc(classification)
                + "\",\"timestamp\":\"" + esc(timestamp) + "\",\"text\":\"" + esc(body) + "\""
                + ",\"input\":\"" + esc(input.rendered) + "\",\"inputSize\":" + input.size
                + ",\"inputBinary\":" + input.binary + "}";
    }

    private static final class Input {
        final String rendered;
        final int size;
        final boolean binary;
        Input(String rendered, int size, boolean binary) {
            this.rendered = rendered; this.size = size; this.binary = binary;
        }
    }

    private static final int MAX_INPUT_PREVIEW_BYTES = 2048;

    private static Input readInput(Path meta) {
        String name = meta.getFileName().toString();
        if (!name.endsWith(".meta.txt")) return new Input("", 0, false);
        Path bin = meta.resolveSibling(name.substring(0, name.length() - ".meta.txt".length()) + ".bin");
        if (!Files.isRegularFile(bin)) return new Input("", 0, false);
        byte[] data;
        long fullSize;
        try {
            fullSize = Files.size(bin);
            data = Files.readAllBytes(bin);
        } catch (IOException e) {
            return new Input("", 0, false);
        }
        int shown = Math.min(data.length, MAX_INPUT_PREVIEW_BYTES);
        boolean binary = !mostlyPrintable(data, shown);
        String rendered;
        if (binary) {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0 && i % 16 == 0) hex.append('\n');
                else if (i > 0) hex.append(' ');
                hex.append(String.format("%02x", data[i] & 0xff));
            }
            rendered = hex.toString();
        } else {
            rendered = new String(data, 0, shown, StandardCharsets.UTF_8);
        }
        if (data.length > shown) rendered += "\n… (" + (fullSize - shown) + " more bytes)";
        return new Input(rendered, (int) fullSize, binary);
    }

    private static boolean mostlyPrintable(byte[] data, int len) {
        if (len == 0) return true;
        int printable = 0;
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xff;
            if ((b >= 0x20 && b < 0x7f) || b == '\n' || b == '\r' || b == '\t') printable++;
        }
        return printable * 10 >= len * 9; // >=90% printable reads as text
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
