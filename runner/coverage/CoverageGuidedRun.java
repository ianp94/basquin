package runner.coverage;

import agent.Agent;
import runner.util.FuzzIO;
import runner.util.DashboardClient;
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

    // Parameter dictionaries used to MUTATE seed routes. The set of reachable endpoints is NOT
    // hardcoded here — it comes from the seed corpus (-Dclosurejvm.corpusDir), so the exploration
    // surface is data, not compiled code. Baking routes in previously capped this run at 7 of
    // JPetStore's 21 handlers (all of Account and Order were unreachable) — see DD-016.
    private static final String[] CATS = {"FISH", "DOGS", "CATS", "BIRDS", "REPTILES"};
    private static final String[] PRODS = {"FI-SW-01", "FI-SW-02", "FI-FW-01", "FI-FW-02", "K9-BD-01",
            "K9-CW-01", "K9-DL-01", "K9-RT-01", "K9-RT-02", "RP-SN-01", "RP-LI-02", "AV-CB-01", "AV-SB-02",
            "FL-DSH-01", "FL-DLH-02"};
    private static final String[] ITEMS = new String[28];
    private static final String[] KW = {"fish", "dog", "cat", "snake", "bird", "angel", "koi", "tiger",
            "poodle", "dalmation", "iguana", "finch", "parrot"};
    static { for (int i = 0; i < 28; i++) ITEMS[i] = "EST-" + (i + 1); }

    /** Load the request grammar if one is configured and readable; null to fall back to seeds. */
    private static RequestGrammar loadGrammar(Random rnd) {
        String path = System.getProperty("closurejvm.grammar");
        if (path == null || path.isEmpty()) return null;
        try {
            RequestGrammar g = RequestGrammar.load(Paths.get(path), rnd);
            if (g.isEmpty()) {
                System.err.println("[ClosureJVM] grammar " + path + " has no route templates; ignoring it");
                return null;
            }
            System.out.println("[ClosureJVM] loaded grammar " + path + ": "
                    + g.routeCount() + " route template(s), " + g.ruleCount() + " rule(s)");
            return g;
        } catch (Exception e) {
            System.err.println("[ClosureJVM] failed to load grammar " + path + ": " + e);
            return null;
        }
    }

    /** Seed routes loaded from the corpus dir; falls back to a bare catalog hit if none found. */
    private static List<String> loadSeeds() {
        String dir = System.getProperty("closurejvm.corpusDir", "examples/corpus/jpetstore");
        List<String> seeds = new ArrayList<>();
        java.nio.file.Path root = Paths.get(dir);
        if (java.nio.file.Files.isDirectory(root)) {
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path p : s.filter(java.nio.file.Files::isRegularFile)
                        .collect(java.util.stream.Collectors.toList())) {
                    for (String line : new String(java.nio.file.Files.readAllBytes(p),
                            StandardCharsets.UTF_8).split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && t.startsWith("/")) seeds.add(t);
                    }
                }
            } catch (Exception e) {
                System.err.println("[ClosureJVM] failed reading seed corpus " + dir + ": " + e);
            }
        }
        if (seeds.isEmpty()) {
            System.err.println("[ClosureJVM] no seeds in " + dir + "; falling back to a single catalog route. "
                    + "Exploration will be badly limited — point -Dclosurejvm.corpusDir at a seed corpus.");
            seeds.add("/actions/Catalog.action");
        } else {
            System.out.println("[ClosureJVM] loaded " + seeds.size() + " seed route(s) from " + dir);
        }
        return seeds;
    }

    public static void main(String[] args) throws Exception {
        TriageSink.ensureStarted();
        StatusReporter.ensureStarted();
        DashboardClient.ensureStarted();

        String baseUrl = System.getProperty("examples.http.baseUrl", "http://localhost:8080");
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        String jacoco = System.getProperty("closurejvm.coverage.jacoco", "localhost:6300");
        String classes = System.getProperty("closurejvm.coverage.classes");
        if (classes == null) { throw new IllegalArgumentException("set -Dclosurejvm.coverage.classes"); }
        String host = jacoco.substring(0, jacoco.indexOf(':'));
        int port = Integer.parseInt(jacoco.substring(jacoco.indexOf(':') + 1));
        JacocoCoverageProvider cov = new JacocoCoverageProvider(host, port, Paths.get(classes));

        Random rnd = new Random(1);

        // Input source, in order of preference:
        //  1. a request grammar (routes + parameter value space, both as data, with generators)
        //  2. a plain seed corpus (routes only, fixed values)
        // Either way the reachable surface is data, never compiled-in (DD-016/DD-017).
        RequestGrammar grammar = loadGrammar(rnd);
        List<String> seeds = grammar != null ? grammar.expandAll() : loadSeeds();
        // Start the corpus as the full seed set so every seeded endpoint is exercised, rather than
        // relying on random discovery to stumble onto them.
        List<String> corpus = new ArrayList<>(seeds);
        long best = 0, total = 0;

        // Alternate session epochs: sign on for a stretch (so account/cart/order handlers run
        // their real logic), then go anonymous (so the unauthenticated paths still get probed).
        int epochLength = Integer.getInteger("closurejvm.session.epoch", 40);
        boolean sessionsEnabled = !"false".equals(System.getProperty("closurejvm.session", "true"));

        for (int i = 0; i < iterations; i++) {
            if (sessionsEnabled && i % epochLength == 0) {
                boolean authenticated = (i / epochLength) % 2 == 0;
                if (authenticated) login(baseUrl); else resetSession();
            }
            String input;
            if (i < seeds.size()) {
                // Deterministic first pass: hit every seed once so no endpoint is left to chance.
                input = seeds.get(i);
            } else if (grammar != null) {
                // Grammar-driven: 30% a fresh expansion of any route template, otherwise re-expand
                // the template behind a corpus entry that previously found new coverage.
                input = rnd.nextInt(100) < 30
                        ? grammar.randomRequest()
                        : grammar.mutate(corpus.get(rnd.nextInt(corpus.size())));
            } else {
                boolean fresh = rnd.nextInt(100) < 30;
                input = fresh ? seeds.get(rnd.nextInt(seeds.size()))
                              : mutate(corpus.get(rnd.nextInt(corpus.size())), rnd);
            }

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

    /**
     * Cookie jar for the current session "epoch". JPetStore keeps the signed-on account in the
     * HTTP session, so without carrying JSESSIONID every authenticated handler sees a null account
     * bean and 500s instead of doing real work — which caps coverage no matter how good the
     * request grammar is. Periodically reset so both anonymous and authenticated states get
     * explored (DD-018).
     */
    private static volatile String sessionCookie = null;

    private static void resetSession() { sessionCookie = null; }

    /** Establish a signed-on session so authenticated handlers run their real logic. */
    private static void login(String base) {
        resetSession();
        try {
            request(base, "/actions/Account.action?signonForm=");
            request(base, "/actions/Account.action?signon=&username=j2ee&password=j2ee");
        } catch (Throwable ignored) {
            // A failed login just means this epoch explores as an anonymous user.
        }
    }

    private static void request(String base, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        if (sessionCookie != null) {
            c.setRequestProperty("Cookie", sessionCookie);
        }
        int code = c.getResponseCode();
        // Capture/refresh JSESSIONID so subsequent requests stay in the same session.
        for (int i = 0; ; i++) {
            String key = c.getHeaderFieldKey(i);
            String val = c.getHeaderField(i);
            if (key == null && val == null) break;
            if (key != null && key.equalsIgnoreCase("Set-Cookie") && val != null
                    && val.startsWith("JSESSIONID=")) {
                sessionCookie = val.split(";", 2)[0];
            }
        }
        String inv = c.getHeaderField("X-ClosureJVM-Invariant-Count");
        if (inv != null) {
            String detail = c.getHeaderField("X-ClosureJVM-Invariant-Detail");
            FuzzIO.saveWithMeta(path.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + path + "\ncount=" + inv + (detail != null ? "\ndetail=" + detail : ""));
        }
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder body = new StringBuilder();
        if (is != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Keep the body only for server errors — that's where the app's own stack is.
                    if (code >= 500 && body.length() < 16384) body.append(line).append('\n');
                }
            }
        }
        if (code >= 500) {
            throw serverError(code, path, body.toString());
        }
    }

    /**
     * Build an exception that carries the APP'S failure, not ours.
     *
     * A driver-side {@code new RuntimeException("HTTP 500")} records
     * {@code CoverageGuidedRun.request(...)} as the stack, which says nothing about why the app
     * failed — the real NPE only existed in the server log. The container's 500 page already
     * contains the server-side exception and stack, so parse it out and install it as this
     * throwable's type/message/stack. FuzzIO then saves the app's stack into the finding, and the
     * dashboard shows a directly triageable record instead of pointing at the harness.
     */
    private static RuntimeException serverError(int code, String path, String body) {
        String stackText = extractServerStack(body);
        if (stackText == null) {
            return new RuntimeException("HTTP " + code + " for " + path
                    + " (no server stack in response; check the app's logs or its error-page config)");
        }
        String[] lines = stackText.split("\n");
        String header = lines[0].trim();
        RuntimeException e = new RuntimeException("HTTP " + code + " for " + path + " — " + header);
        List<StackTraceElement> frames = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            StackTraceElement f = parseFrame(lines[i]);
            if (f != null) frames.add(f);
        }
        if (!frames.isEmpty()) {
            e.setStackTrace(frames.toArray(new StackTraceElement[0]));
        }
        return e;
    }

    /**
     * Pull the exception + stack out of a servlet container's error page.
     *
     * Tomcat emits two {@code <pre>} blocks: the wrapping framework exception first, then a
     * "Root Cause" block with the actual failure and the application's own frames. The root cause
     * is the useful one — the wrapper just says "unhandled exception in exception handler".
     */
    private static String extractServerStack(String body) {
        if (body == null || body.isEmpty()) return null;
        int searchFrom = 0;
        int rootCause = body.indexOf("Root Cause");
        if (rootCause >= 0) searchFrom = rootCause;      // prefer the deepest cause
        int pre = body.indexOf("<pre>", searchFrom);
        if (pre < 0) pre = body.indexOf("<pre>");        // no root-cause section; take what's there
        if (pre < 0) return null;
        int end = body.indexOf("</pre>", pre);
        String block = unescapeHtml(body.substring(pre + 5, end > 0 ? end : body.length())).trim();
        return block.isEmpty() ? null : block;
    }

    private static StackTraceElement parseFrame(String line) {
        String t = line.trim();
        // Tomcat's error page renders frames WITHOUT the "at " prefix that a logged stack has,
        // so accept both forms.
        if (t.startsWith("at ")) t = t.substring(3).trim();
        if (t.isEmpty() || !t.endsWith(")")) return null;
        int paren = t.indexOf('(');
        if (paren < 0) return null;
        String qualified = t.substring(0, paren);
        String location = t.substring(paren + 1, t.endsWith(")") ? t.length() - 1 : t.length());
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot < 0) return null;
        String cls = qualified.substring(0, lastDot);
        String method = qualified.substring(lastDot + 1);
        String file = location;
        int lineNo = -1;
        int colon = location.lastIndexOf(':');
        if (colon > 0) {
            file = location.substring(0, colon);
            try { lineNo = Integer.parseInt(location.substring(colon + 1).trim()); } catch (Exception ignored) { }
        }
        return new StackTraceElement(cls, method, file, lineNo);
    }

    private static String unescapeHtml(String s) {
        String out = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ");
        // Tomcat escapes '/' in class paths as &#47;, so resolve numeric entities too.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d{1,5});").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(m.group(1)))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    private static String mutate(String input, Random r) {
        // No route synthesis here: an un-mutatable input is returned as-is and the caller's
        // seed-picking branch supplies variety. Endpoints come from the corpus, not from code.
        if (!input.contains("=")) { return input; }
        if (input.contains("categoryId=")) return replaceParam(input, "categoryId=", CATS[r.nextInt(CATS.length)]);
        if (input.contains("productId=")) return replaceParam(input, "productId=", PRODS[r.nextInt(PRODS.length)]);
        if (input.contains("itemId=")) return replaceParam(input, "itemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("workingItemId=")) return replaceParam(input, "workingItemId=", ITEMS[r.nextInt(ITEMS.length)]);
        if (input.contains("keyword=")) return replaceParam(input, "keyword=", KW[r.nextInt(KW.length)]);
        // Params we have no dictionary for (orderId, username, …): leave the seed untouched so
        // its endpoint still gets exercised.
        return input;
    }

    private static String replaceParam(String input, String key, String val) {
        int k = input.indexOf(key) + key.length();
        int end = input.indexOf('&', k);
        if (end < 0) end = input.length();
        return input.substring(0, k) + val + input.substring(end);
    }
}
