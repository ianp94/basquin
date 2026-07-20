package runner.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Claude API (Messages API) client for optional, dashboard-triggered finding analysis
 * (DD-015). No SDK dependency — a single {@code HttpURLConnection} POST with hand-built JSON,
 * matching this project's existing no-JSON-library style (see DashboardServer's numField scrape,
 * FindingsClusterer). Only active when a key is configured; the key lives only in the standalone
 * {@link DashboardServer} process — it is never passed to a driver or anywhere near the app under
 * test, and analysis only runs when a user explicitly triggers it (never on the auto-refresh
 * poll), so cost/latency stay bounded and opt-in.
 */
public final class ClaudeAnalyzer {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-5";

    private ClaudeAnalyzer() {}

    public static boolean isConfigured() {
        return apiKey() != null;
    }

    private static String apiKey() {
        String v = System.getProperty("closurejvm.claude.apiKey");
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv("ANTHROPIC_API_KEY");
        return (v != null && !v.isEmpty()) ? v : null;
    }

    public static String analyze(String prompt) throws IOException {
        String key = apiKey();
        if (key == null) {
            throw new IOException("Claude API key not configured (set ANTHROPIC_API_KEY)");
        }
        String model = System.getProperty("closurejvm.claude.model", DEFAULT_MODEL);
        String body = "{\"model\":\"" + esc(model) + "\",\"max_tokens\":800,\"messages\":["
                + "{\"role\":\"user\",\"content\":\"" + esc(prompt) + "\"}]}";

        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = readAll(code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 300) {
            throw new IOException("Claude API error " + code + ": " + truncate(resp, 300));
        }
        return extractText(resp);
    }

    // Response shape: {"content":[{"type":"text","text":"..."}], ...}. Pull the first "text"
    // value with the iterative JsonScan (not a "(?:[^"\\]|\\.)*"-style regex, which recurses one
    // Java Pattern$Loop stack frame per character and overflows on the length of prose Claude can
    // return — see JsonScan's javadoc for how this was actually found).
    private static String extractText(String json) {
        String text = JsonScan.extract(json, "text", 0);
        return text != null ? text : "(no text in Claude response: " + truncate(json, 200) + ")";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
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

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
