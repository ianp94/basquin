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
        volatile long lastSeenMillis = System.currentTimeMillis();
    }

    private static final Map<String, Campaign> CAMPAIGNS = new ConcurrentHashMap<>();
    private static final long STALE_AFTER_MS = Long.getLong("closurejvm.dashboard.staleAfterMs", 10_000L);

    public static void main(String[] args) throws IOException {
        int port = Integer.getInteger("closurejvm.dashboard.server.port", 7070);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/ingest/status", ex -> ingest(ex, true));
        server.createContext("/ingest/findings", ex -> ingest(ex, false));
        server.createContext("/api/campaigns", DashboardServer::listCampaigns);
        server.createContext("/api/campaign/", DashboardServer::campaignDetail);
        server.createContext("/", ex -> respond(ex, "text/html; charset=utf-8", PAGE));

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

    private static void campaignDetail(HttpExchange ex) throws IOException {
        // path: /api/campaign/{id}/status or /api/campaign/{id}/findings
        String path = ex.getRequestURI().getPath();
        String[] parts = path.substring("/api/campaign/".length()).split("/", 2);
        if (parts.length != 2) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        Campaign c = CAMPAIGNS.get(parts[0]);
        if (c == null) {
            respond(ex, "application/json", "status".equals(parts[1]) ? "{}" : "[]");
            return;
        }
        respond(ex, "application/json", "status".equals(parts[1]) ? c.statusJson : c.findingsJson);
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

    private static final String PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ClosureJVM Dashboard</title><style>"
        + ":root{color-scheme:dark}body{margin:0;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
        + "background:#0d1117;color:#c9d1d9}h1{font-size:16px;margin:0;color:#3fb950;font-weight:600}"
        + "header{display:flex;align-items:center;gap:10px;padding:14px 20px;border-bottom:1px solid #30363d;position:sticky;top:0;background:#0d1117}"
        + "a{color:#a371f7;text-decoration:none}a:hover{text-decoration:underline}"
        + ".dot{width:10px;height:10px;border-radius:50%;background:#3fb950;box-shadow:0 0 8px #3fb950}"
        + ".dot.dead{background:#8b949e;box-shadow:none}"
        + ".wrap{padding:20px;max-width:1100px;margin:0 auto}"
        + ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}"
        + ".fleet{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-top:14px}"
        + ".card,.pod{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:14px}"
        + ".pod{cursor:pointer}.pod:hover{border-color:#3fb950}"
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
        + ".pid{font-size:13px}.age{font-size:11px;color:#8b949e}"
        + "</style></head><body>"
        + "<header><span class=\"dot\"></span><h1>ClosureJVM Dashboard</h1>"
        + "<span id=\"sub\" style=\"color:#8b949e;font-size:12px\">fleet view — no campaign selected</span>"
        + "<span style=\"flex:1\"></span><a href=\"#\" id=\"back\" style=\"display:none\">&larr; all campaigns</a></header>"
        + "<div class=\"wrap\">"
        + "<div id=\"fleetView\"><div class=\"fleet\" id=\"fleet\"></div></div>"
        + "<div id=\"campaignView\" style=\"display:none\">"
        + "<div class=\"cards\" id=\"cards\"></div>"
        + "<div class=\"card\" style=\"margin-top:12px\"><div class=\"k\">coverage of app under test</div>"
        + "<div class=\"v\" id=\"covv\">—</div><div class=\"bar\"><div id=\"covbar\"></div></div></div>"
        + "<h2>Findings</h2><table><thead><tr><th>Type</th><th>When</th><th>Detail</th></tr></thead>"
        + "<tbody id=\"finds\"></tbody></table></div>"
        + "</div><script>"
        + "let current=null;"
        + "function card(k,v,s){return '<div class=\"card\"><div class=\"k\">'+k+'</div><div class=\"v\">'+v+'</div><div class=\"s\">'+(s||'')+'</div></div>'}"
        + "function esc(s){return (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}"
        + "function selectCampaign(id){current=id;document.getElementById('fleetView').style.display='none';"
        + "document.getElementById('campaignView').style.display='block';document.getElementById('back').style.display='inline';"
        + "document.getElementById('sub').textContent='campaign: '+id;tick();}"
        + "function showFleet(){current=null;document.getElementById('fleetView').style.display='block';"
        + "document.getElementById('campaignView').style.display='none';document.getElementById('back').style.display='none';"
        + "document.getElementById('sub').textContent='fleet view — '+Object.keys(lastFleet||{}).length+' campaign(s)';tickFleet();}"
        + "document.getElementById('back').onclick=function(e){e.preventDefault();showFleet();};"
        + "var lastFleet={};"
        + "async function tickFleet(){try{const list=await (await fetch('/api/campaigns')).json();"
        + "lastFleet={};list.forEach(c=>lastFleet[c.id]=c);"
        + "document.getElementById('fleet').innerHTML=list.length?list.map(c=>"
        + "'<div class=\"pod\" onclick=\"selectCampaign(\\''+c.id+'\\')\">"
        + "<div style=\"display:flex;align-items:center;gap:8px\"><span class=\"dot'+(c.alive?'':' dead')+'\"></span>"
        + "<span class=\"pid\">'+esc(c.id)+'</span></div>"
        + "<div class=\"s\" style=\"margin-top:8px\">iters '+c.iterations+' · crashes '+c.crashes+' · cov '+(c.coveragePct>=0?c.coveragePct+'%':'—')+'</div>"
        + "<div class=\"age\">last seen '+c.ageSec+'s ago</div></div>').join('')"
        + ":'<div class=\"s\">No campaigns have reported yet. Start a driver with -Dclosurejvm.dashboard.push=host:'+location.port+'</div>';"
        + "}catch(e){}}"
        + "async function tick(){if(!current)return;try{"
        + "const st=await (await fetch('/api/campaign/'+current+'/status')).json();"
        + "document.getElementById('cards').innerHTML="
        + "card('iterations',st.iterations||0,(st.rate||0).toFixed(1)+'/s')+"
        + "card('crashes',st.crashes||0)+card('leaks',st.leaks||0)+"
        + "card('invariants',(st.invariants?(st.invariants.latency+st.invariants.heap+st.invariants.thread):0))+"
        + "card('latency ms',st.latencyMs?st.latencyMs.last:0,st.latencyMs?('mean '+st.latencyMs.mean+' · max '+st.latencyMs.max):'')+"
        + "card('corpus',st.exploration?st.exploration.corpus:0,st.exploration?(st.exploration.rejected+' rejected'):'');"
        + "const cv=(st.exploration||{}).coverage||{total:0};var pct=cv.total>0?cv.pct:0;"
        + "document.getElementById('covv').textContent=cv.total>0?(pct.toFixed(1)+'%  ('+cv.covered+'/'+cv.total+' edges)'):'no coverage source';"
        + "document.getElementById('covbar').style.width=pct+'%';"
        + "const fs=await (await fetch('/api/campaign/'+current+'/findings')).json();"
        + "document.getElementById('finds').innerHTML=fs.map(f=>'<tr><td><span class=\"tag '+f.classification+'\">'+"
        + "(f.classification||'?')+'</span></td><td>'+new Date(+f.timestamp).toLocaleTimeString()+'</td>"
        + "<td><pre>'+esc(f.text)+'</pre></td></tr>').join('');"
        + "}catch(e){}}"
        + "showFleet();setInterval(()=>current?tick():tickFleet(),1500);"
        + "</script></body></html>";
}
