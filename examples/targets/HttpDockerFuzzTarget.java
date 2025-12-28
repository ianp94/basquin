package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;
import runner.util.FuzzIO;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpDockerFuzzTarget implements IterationTarget, InputReceiver {
    private String baseUrl;
    private byte[] last;

    @Override
    public void initialize() {
        baseUrl = System.getProperty("examples.tomcat.baseUrl", "http://localhost:8080");
    }

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() throws Exception {
        String in = (last == null || last.length == 0) ? "CRASH:NPE" : new String(last, StandardCharsets.UTF_8);
        String path = mapInputToPath(in);
        int code;
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setConnectTimeout(1000);
        c.setReadTimeout(2000);
        c.setRequestMethod("GET");
        code = c.getResponseCode();
        // Save invariant if server signals via headers
        String invCount = c.getHeaderField("X-ClosureJVM-Invariant-Count");
        if (invCount != null) {
            String detail = c.getHeaderField("X-ClosureJVM-Invariant-Detail");
            FuzzIO.saveWithMeta(last != null ? last : new byte[0], "Invariant-Remote", "count=" + invCount + (detail != null ? "\ndetail=" + detail : ""));
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) { /* drain */ }
        }
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
}

