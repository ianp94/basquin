package runner.coverage;

import agent.Agent;
import runner.util.FuzzIO;
import runner.util.StatusReporter;
import runner.util.TriageSink;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Coverage-<em>guided</em> exploration over HTTP: mutates request inputs (route + params), samples
 * the app-under-test's coverage from its JaCoCo agent after each request, and keeps the inputs
 * that reach new code — an AFL/Zest feedback loop where the coverage signal comes from the app,
 * over the wire (DD-012). Unlike the round-robin driver, this makes coverage climb.
 *
 * Config: {@code examples.http.baseUrl}, {@code closurejvm.coverage.jacoco=host:port},
 * {@code closurejvm.coverage.classes=<dir>}. Arg[0] = iterations.
 */
public final class CoverageGuidedRun {

    // A small grammar/dictionary of JPetStore routes and known ids to mutate over.
    private static final String[] CATS = {"FISH", "DOGS", "CATS", "BIRDS", "REPTILES"};
    private static final String[] PRODS = {"FI-SW-01", "FI-SW-02", "FI-FW-01", "FI-FW-02", "K9-BD-01",
            "K9-CW-01", "K9-DL-01", "K9-RT-01", "K9-RT-02", "RP-SN-01", "RP-LI-02", "AV-CB-01", "AV-SB-02",
            "FL-DSH-01", "FL-DLH-02"};
    private static final String[] ITEMS = new String[28];
    private static final String[] KW = {"fish", "dog", "cat", "snake", "bird", "angel", "koi", "tiger",
            "poodle", "dalmation", "iguana", "finch", "parrot"};
    static { for (int i = 0; i < 28; i++) ITEMS[i] = "EST-" + (i + 1); }

    public static void main(String[] args) throws Exception {
        TriageSink.ensureStarted();
        StatusReporter.ensureStarted();

        String baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        String jacoco = System.getProperty("closurejvm.coverage.jacoco", "localhost:6300");
        String classes = System.getProperty("closurejvm.coverage.classes");
        if (classes == null) { throw new IllegalArgumentException("set -Dclosurejvm.coverage.classes"); }
        String host = jacoco.substring(0, jacoco.indexOf(':'));
        int port = Integer.parseInt(jacoco.substring(jacoco.indexOf(':') + 1));
        JacocoCoverageProvider cov = new JacocoCoverageProvider(host, port, Paths.get(classes));

        Random rnd = new Random(1);
        List<String> corpus = new ArrayList<>();
        corpus.add("/actions/Catalog.action");
        long best = 0, total = 0;

        for (int i = 0; i < iterations; i++) {
            boolean fresh = corpus.isEmpty() || rnd.nextInt(100) < 30;
            String input = fresh ? randomRoute(rnd) : mutate(corpus.get(rnd.nextInt(corpus.size())), rnd);

            Agent.beginIteration();
            try {
                request(baseUrl, input);
            } catch (Throwable t) {
                StatusReporter.recordCrash();
                FuzzIO.saveInteresting(input.getBytes(StandardCharsets.UTF_8), t);
            } finally {
                Agent.endIteration();
            }

            // Coverage feedback: keep inputs that reached new code (JaCoCo dumps accumulate, so a
            // rising covered count means this input hit an edge nothing before it had).
            try {
                JacocoCoverageProvider.Coverage c = cov.sample();
                total = c.total;
                StatusReporter.recordCoverage(c.covered, c.total);
                if (c.covered > best) {
                    best = c.covered;
                    corpus.add(input);
                    StatusReporter.recordSaved("Coverage");
                }
            } catch (Throwable ignored) {
                // coverage agent blip; keep exploring
            }
        }
        StatusReporter.renderFinal();
        System.out.printf("CoverageGuidedRun done: corpus=%d coverage=%d/%d%n", corpus.size(), best, total);
    }

    private static void request(String base, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        String inv = c.getHeaderField("X-ClosureJVM-Invariant-Count");
        if (inv != null) {
            String detail = c.getHeaderField("X-ClosureJVM-Invariant-Detail");
            FuzzIO.saveWithMeta(path.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + path + "\ncount=" + inv + (detail != null ? "\ndetail=" + detail : ""));
        }
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* drain */ }
            }
        }
        if (code >= 500) { throw new RuntimeException("HTTP " + code + " for " + path); }
    }

    private static String randomRoute(Random r) {
        switch (r.nextInt(8)) {
            case 0: return "/";
            case 1: return "/actions/Catalog.action";
            case 2: return "/actions/Catalog.action?viewCategory=&categoryId=" + CATS[r.nextInt(CATS.length)];
            case 3: return "/actions/Catalog.action?viewProduct=&productId=" + PRODS[r.nextInt(PRODS.length)];
            case 4: return "/actions/Catalog.action?viewItem=&itemId=" + ITEMS[r.nextInt(ITEMS.length)];
            case 5: return "/actions/Catalog.action?searchProducts=&keyword=" + KW[r.nextInt(KW.length)];
            case 6: return "/actions/Cart.action?addItemToCart=&workingItemId=" + ITEMS[r.nextInt(ITEMS.length)];
            default: return "/actions/Cart.action?viewCart=";
        }
    }

    private static String mutate(String input, Random r) {
        if (r.nextBoolean() || !input.contains("=")) { return randomRoute(r); }
        if (input.contains("categoryId=")) return replaceParam(input, "categoryId=", CATS[r.nextInt(CATS.length)]);
        if (input.contains("productId=")) return replaceParam(input, "productId=", PRODS[r.nextInt(PRODS.length)]);
        if (input.contains("itemId=")) return replaceParam(input, "itemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("workingItemId=")) return replaceParam(input, "workingItemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("keyword=")) return replaceParam(input, "keyword=", KW[r.nextInt(KW.length)]);
        return randomRoute(r);
    }

    private static String replaceParam(String input, String key, String val) {
        int k = input.indexOf(key) + key.length();
        int end = input.indexOf('&', k);
        if (end < 0) end = input.length();
        return input.substring(0, k) + val + input.substring(end);
    }
}
