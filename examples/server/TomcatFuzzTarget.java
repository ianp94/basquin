package examples.server;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TomcatFuzzTarget implements IterationTarget, InputReceiver {
    private EmbeddedTomcatApp app;
    private int port;
    private byte[] last;

    @Override
    public void initialize() {
        port = Integer.getInteger("examples.tomcat.port", 8088);
        app = new EmbeddedTomcatApp();
        app.start(port);
        // Wait briefly for server to accept connections
        waitUntilUp();
    }

    @Override
    public void close() {
        if (app != null) app.stop();
    }

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() throws Exception {
        String in = (last == null || last.length == 0) ? "CRASH:NPE" : new String(last, StandardCharsets.UTF_8);
        String path = mapInputToPath(in);
        int code = httpGet("http://localhost:" + port + path);
        if (code >= 500) {
            throw new RuntimeException("Server error: HTTP " + code + " for path=" + path);
        }
    }

    private static String mapInputToPath(String s) {
        s = s.trim().toUpperCase();
        if (s.startsWith("CRASH")) {
            String t = s.contains(":") ? s.substring(s.indexOf(':') + 1) : "NPE";
            return "/crash?type=" + t;
        }
        if (s.startsWith("LATENCY")) {
            int ms = extractInt(s);
            return "/latency?ms=" + ms;
        }
        if (s.startsWith("HEAP")) {
            int kb = extractInt(s);
            return "/heap?kb=" + kb;
        }
        // Heuristic: if digits present, choose latency; else crash
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return "/latency?ms=" + extractInt(s);
        return "/crash?type=NPE";
    }

    private static int extractInt(String s) {
        int val = 0; boolean found = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) { found = true; val = (val * 10) + (c - '0'); if (val > 100000) break; }
            else if (found) break;
        }
        if (!found) val = 0;
        return val;
    }

    private static int httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(1000);
        c.setReadTimeout(2000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) { /* drain */ }
        }
        return code;
    }

    private void waitUntilUp() {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            try {
                int code = httpGet("http://localhost:" + port + "/latency?ms=0");
                if (code < 500) return;
            } catch (Exception ignored) {}
            try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
    }
}
