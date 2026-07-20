package runner.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A tiny built-in web dashboard for a running harness. Enable with {@code -Dclosurejvm.dashboard}
 * (port via {@code -Dclosurejvm.dashboard.port}, default 7070). Serves:
 * - {@code /}             the dashboard page (live metric cards, coverage bar, findings table)
 * - {@code /api/status}   current metrics from {@link StatusReporter}
 * - {@code /api/findings} the saved triage bundles from the results dir
 *
 * Uses only the JDK's {@code com.sun.net.httpserver}, so it adds no dependency and can be started
 * from any run. This is the single-run foundation for the k8s dashboard (aggregating across pods)
 * and the optional Claude-API-backed analysis.
 */
public final class StatusServer {

    private static volatile boolean started = false;

    private StatusServer() {}

    public static synchronized void startIfEnabled() {
        if (started || !Boolean.getBoolean("closurejvm.dashboard")) {
            return;
        }
        int port = Integer.getInteger("closurejvm.dashboard.port", 7070);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/status", ex -> respond(ex, "application/json", StatusReporter.snapshotJson()));
            server.createContext("/api/findings", ex -> respond(ex, "application/json", findingsJson()));
            server.createContext("/", ex -> respond(ex, "text/html; charset=utf-8", PAGE));
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ClosureJVM-Dashboard");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            started = true;
            System.out.println("[ClosureJVM] dashboard at http://localhost:" + port);
        } catch (IOException e) {
            System.err.println("[ClosureJVM] dashboard failed to start: " + e);
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

    private static final String PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>ClosureJVM</title><style>"
        + ":root{color-scheme:dark}body{margin:0;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
        + "background:#0d1117;color:#c9d1d9}h1{font-size:16px;margin:0;color:#3fb950;font-weight:600}"
        + "header{display:flex;align-items:center;gap:10px;padding:14px 20px;border-bottom:1px solid #30363d}"
        + ".dot{width:10px;height:10px;border-radius:50%;background:#3fb950;box-shadow:0 0 8px #3fb950}"
        + ".wrap{padding:20px;max-width:1100px;margin:0 auto}"
        + ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}"
        + ".card{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px}"
        + ".card .k{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.05em}"
        + ".card .v{font-size:24px;margin-top:6px}.card .s{font-size:12px;color:#8b949e;margin-top:2px}"
        + ".bar{height:10px;border-radius:6px;background:#21262d;overflow:hidden;margin-top:8px}"
        + ".bar>div{height:100%;background:linear-gradient(90deg,#a371f7,#3fb950);width:0%}"
        + "table{width:100%;border-collapse:collapse;margin-top:14px;font-size:12px}"
        + "th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #21262d;vertical-align:top}"
        + "th{color:#8b949e;font-weight:600;text-transform:uppercase;font-size:10px;letter-spacing:.05em}"
        + ".tag{padding:2px 8px;border-radius:20px;font-size:11px;white-space:nowrap}"
        + ".Crash{background:#3d1418;color:#ff7b72}.Invariant,.Invariant-Remote{background:#3a2c05;color:#e3b341}"
        + ".Coverage{background:#26193d;color:#a371f7}pre{margin:0;white-space:pre-wrap;color:#8b949e;font-size:11px}"
        + "h2{font-size:13px;color:#8b949e;margin:24px 0 0;text-transform:uppercase;letter-spacing:.05em}"
        + "</style></head><body><header><span class=\"dot\"></span><h1>ClosureJVM</h1>"
        + "<span id=\"sub\" style=\"color:#8b949e;font-size:12px\"></span></header><div class=\"wrap\">"
        + "<div class=\"cards\" id=\"cards\"></div>"
        + "<div class=\"card\" style=\"margin-top:12px\"><div class=\"k\">coverage of app under test</div>"
        + "<div class=\"v\" id=\"covv\">—</div><div class=\"bar\"><div id=\"covbar\"></div></div></div>"
        + "<h2>Findings</h2><table><thead><tr><th>Type</th><th>When</th><th>Detail</th></tr></thead>"
        + "<tbody id=\"finds\"></tbody></table></div><script>"
        + "function card(k,v,s){return '<div class=\"card\"><div class=\"k\">'+k+'</div><div class=\"v\">'+v+"
        + "'</div><div class=\"s\">'+(s||'')+'</div></div>'}"
        + "async function tick(){try{"
        + "const st=await (await fetch('/api/status')).json();"
        + "document.getElementById('sub').textContent='running '+fmt(st.elapsedSec)+' · '+st.rate.toFixed(1)+'/s';"
        + "document.getElementById('cards').innerHTML="
        + "card('iterations',st.iterations,st.rate.toFixed(1)+'/s')+"
        + "card('crashes',st.crashes)+card('leaks',st.leaks)+"
        + "card('invariants',st.invariants.latency+st.invariants.heap+st.invariants.thread,'lat '+st.invariants.latency+' · heap '+st.invariants.heap+' · thr '+st.invariants.thread)+"
        + "card('latency ms',st.latencyMs.last,'mean '+st.latencyMs.mean+' · max '+st.latencyMs.max)+"
        + "card('corpus',st.exploration.corpus,st.exploration.rejected+' rejected');"
        + "const cv=st.exploration.coverage;var pct=cv.total>0?cv.pct:0;"
        + "document.getElementById('covv').textContent=cv.total>0?(pct.toFixed(1)+'%  ('+cv.covered+'/'+cv.total+' edges)'):'no coverage source';"
        + "document.getElementById('covbar').style.width=pct+'%';"
        + "const fs=await (await fetch('/api/findings')).json();"
        + "document.getElementById('finds').innerHTML=fs.map(f=>'<tr><td><span class=\"tag '+f.classification+'\">'+"
        + "(f.classification||'?')+'</span></td><td>'+new Date(+f.timestamp).toLocaleTimeString()+'</td>"
        + "<td><pre>'+esc(f.text)+'</pre></td></tr>').join('');"
        + "}catch(e){}}"
        + "function esc(s){return (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}"
        + "function fmt(s){s=s|0;return String((s/3600|0)).padStart(2,'0')+':'+String((s%3600/60|0)).padStart(2,'0')+':'+String(s%60).padStart(2,'0')}"
        + "tick();setInterval(tick,1500);</script></body></html>";
}
