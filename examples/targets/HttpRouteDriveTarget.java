package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;
import runner.util.FuzzIO;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side HTTP driver: issues one request per iteration to a configured route of a running
 * web app, so the harness measures real end-to-end latency and treats 5xx as a crash. When the
 * app has the Basquin valve/filter, this also harvests the server-side invariant findings it
 * exposes via {@code X-Basquin-Invariant-*} headers and saves them as triage.
 *
 * Complements the in-server valve (DD-009): the valve captures heap/thread/leak inside the app
 * JVM; this driver captures request latency and crashes from the outside and explores routes.
 *
 * Config:
 * - {@code -Dexamples.http.baseUrl}  base URL (default http://localhost:8080)
 * - {@code -Dexamples.http.routes}   comma-separated paths; defaults to a JPetStore route set
 *
 * Route selection: fuzz input (via InputReceiver) picks a route by hash; with no input, routes
 * are round-robined so a plain run exercises all of them deterministically.
 */
public class HttpRouteDriveTarget implements IterationTarget, InputReceiver {

    private static final String DEFAULT_ROUTES = String.join(",",
            "/",
            "/actions/Catalog.action",
            "/actions/Catalog.action?viewCategory=&categoryId=FISH",
            "/actions/Catalog.action?viewCategory=&categoryId=DOGS",
            "/actions/Catalog.action?viewCategory=&categoryId=REPTILES",
            "/actions/Catalog.action?viewCategory=&categoryId=CATS",
            "/actions/Catalog.action?viewCategory=&categoryId=BIRDS",
            "/actions/Catalog.action?viewProduct=&productId=FI-SW-01",
            "/actions/Catalog.action?viewProduct=&productId=K9-BD-01",
            "/actions/Catalog.action?viewItem=&itemId=EST-1");

    private String baseUrl;
    private String[] routes;
    private final AtomicInteger cursor = new AtomicInteger();
    private byte[] input;

    @Override
    public void initialize() {
        baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        routes = System.getProperty("examples.http.routes", DEFAULT_ROUTES).split(",");
    }

    @Override
    public void accept(byte[] data) {
        this.input = data;
    }

    @Override
    public void executeIteration() throws Exception {
        String route = pickRoute();
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + route).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();

        // Harvest server-side invariant evidence surfaced by the valve/filter.
        String invCount = c.getHeaderField("X-Basquin-Invariant-Count");
        if (invCount != null) {
            String detail = c.getHeaderField("X-Basquin-Invariant-Detail");
            FuzzIO.saveWithMeta(route.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + route + "\ncount=" + invCount + (detail != null ? "\ndetail=" + detail : ""));
        }

        // getErrorStream() is null for a 4xx with no body; guard so that is not an NPE
        // miscounted as a crash. Only a genuine 5xx (below) is a crash.
        java.io.InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (body != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* drain so the connection can be reused */ }
            }
        }

        if (code >= 500) {
            throw new RuntimeException("Server error: HTTP " + code + " for route=" + route);
        }
    }

    private String pickRoute() {
        if (input != null && input.length > 0) {
            int h = 0;
            for (byte b : input) h = h * 31 + (b & 0xff);
            return routes[Math.floorMod(h, routes.length)];
        }
        return routes[Math.floorMod(cursor.getAndIncrement(), routes.length)];
    }
}
