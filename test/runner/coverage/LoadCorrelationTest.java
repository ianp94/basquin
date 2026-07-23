package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Task 4 (DD-036): the LOAD driver actually USES {@link Capture}/{@link RequestLine#needsSubstitution()}
 * — capture a value out of one response and substitute it into a later request's body via
 * {@code ${{name}}}. Covers {@link LoadRun#substitute}, the 4-arg {@link LoadRun#fire} overload, and
 * (review fix) {@link LoadRun#readCorpus}'s real correlated/uncorrelated computation via
 * {@link LoadRun.Seq#correlated()}.
 */
public class LoadCorrelationTest {

    private HttpServer server;
    private String base;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void extractUrlEncodesPlusAndEquals() {
        // DD-037 model A: encoding responsibility moved from LoadRun.substitute into Capture.extract.
        Capture x = Capture.parse("<<x=input:F");

        String extracted = x.extract(h -> null, "<input name=\"F\" value=\"a+b=c\">");

        assertEquals("a%2Bb%3Dc", extracted);
    }

    @Test
    public void substituteReturnsNullWhenBindingMissing() {
        Map<String, String> bindings = new HashMap<>();

        String out = LoadRun.substitute("X-XSRF-TOKEN=${{csrf}}&page=Main", bindings);

        assertNull(out);
    }

    @Test
    public void twoStepCorrelatedSequenceCapturesAndSubstitutes() throws IOException {
        final String[] seenBody = new String[1];

        server.createContext("/login", (HttpExchange ex) -> {
            String resp = "<input name=\"X-XSRF-TOKEN\" value=\"tok123\">";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        server.createContext("/submit", (HttpExchange ex) -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seenBody[0] = new String(body, StandardCharsets.UTF_8);
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Capture csrf = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        RequestLine step1 = new RequestLine("GET", "/login", null, List.of(csrf));
        RequestLine step2 = RequestLine.parse("POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main");

        Map<String, String> jar = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();

        int code1 = LoadRun.fire(base, step1, jar, bindings);
        assertEquals(200, code1);
        assertEquals("tok123", bindings.get("csrf"));

        String substituted = LoadRun.substitute(step2.body(), bindings);
        assertEquals("X-XSRF-TOKEN=tok123&page=Main", substituted);

        RequestLine toFire = new RequestLine(step2.method(), step2.path(), substituted, step2.captures());
        int code2 = LoadRun.fire(base, toFire, jar, bindings);

        assertEquals(200, code2);
        assertEquals("X-XSRF-TOKEN=tok123&page=Main", seenBody[0]);
    }

    @Test
    public void captureMissLeavesBindingsUnchangedAndSubstituteSignalsTheMiss() throws IOException {
        server.createContext("/login", (HttpExchange ex) -> {
            String resp = "<html>no token here</html>";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Capture csrf = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        RequestLine step1 = new RequestLine("GET", "/login", null, List.of(csrf));
        RequestLine step2 = RequestLine.parse("POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main");

        Map<String, String> jar = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();

        int code1 = LoadRun.fire(base, step1, jar, bindings);
        assertEquals(200, code1);
        assertTrue("bindings must stay unchanged on a capture miss", bindings.isEmpty());

        // fire() no longer counts captureMisses itself (review fix): the ONLY place a miss is counted
        // is the worker loop, gated on substitute() returning null for a step that actually references
        // the unbound name. Prove that signal fires here, downstream of the failed capture.
        String substituted = LoadRun.substitute(step2.body(), bindings);
        assertNull("substitute must return null so the worker loop skips + counts the miss", substituted);
    }

    @Test
    public void readCorpusComputesCorrelatedFlagFromRealSequences() throws Exception {
        // Production path (review fix): drive LoadRun.readCorpus() itself instead of hand-building a
        // Seq, so the actual computation under test —
        // steps.stream().anyMatch(RequestLine::needsSubstitution) — gets real coverage.
        Path dir = Files.createTempDirectory("load-correlation-corpus");
        dir.toFile().deleteOnExit();
        Path corpusFile = dir.resolve("corpus.txt");

        // Line 1: a correlated 2-step sequence in the on-disk v2 format (tab-separated steps; the
        // capture directive is the LAST space-separated token of its step, per RequestLine.parse).
        String correlatedLine = "GET /login <<csrf=input:X-XSRF-TOKEN"
                + "\t" + "POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main";
        // Line 2: a plain, uncorrelated single-step route.
        String plainLine = "/catalog";

        Files.write(corpusFile, String.join("\n", correlatedLine, plainLine, "")
                .getBytes(StandardCharsets.UTF_8));
        corpusFile.toFile().deleteOnExit();

        List<LoadRun.Seq> sequences = LoadRun.readCorpus(dir.toString());

        assertEquals("both lines must parse as kept route sequences", 2, sequences.size());

        LoadRun.Seq correlatedSeq = sequences.get(0);
        assertEquals(2, correlatedSeq.steps().size());
        assertTrue("step2's ${{csrf}} ref makes this sequence correlated", correlatedSeq.correlated());

        LoadRun.Seq plainSeq = sequences.get(1);
        assertEquals(1, plainSeq.steps().size());
        assertFalse("a plain route has no ${{name}} ref, so it must not be correlated",
                plainSeq.correlated());
    }

    @Test
    public void substituteFillsNonceUniquelyAndNeedsNoBinding() {
        String a = LoadRun.substitute("x=${{@nonce}}", new java.util.HashMap<>());
        String b = LoadRun.substitute("x=${{@nonce}}", new java.util.HashMap<>());
        assertNotNull(a); assertNotNull(b);
        assertTrue(a.startsWith("x=")); assertFalse(a.contains("${{"));   // marker gone
        assertNotEquals("each fire's nonce differs", a, b);
    }

    /**
     * DD-038 (@claude review): the salt must separate two driver PODS, not just two host processes.
     * The driver runs as a Kubernetes Job, and inside a container's own PID namespace every driver's
     * main process is renumbered from a small integer — commonly 1 — so two pods that start in the
     * same millisecond share both components of a millis+pid salt and re-emit an identical token
     * stream. That silently revives the saveText() no-op this whole feature exists to kill, and it
     * looks like a clean run. HOSTNAME is the pod name in Kubernetes (DD-013), so it carries the
     * separation; pid remains the fallback for a local run where HOSTNAME is unset.
     */
    @Test
    public void runSaltSeparatesTwoPodsThatCollideOnMillisAndPid() {
        String podA = LoadRun.buildRunSalt("basquin-driver-abc12", 1_700_000_000_000L, 1L);
        String podB = LoadRun.buildRunSalt("basquin-driver-xyz98", 1_700_000_000_000L, 1L);
        assertNotEquals("same millisecond + same namespaced PID 1 must still yield distinct salts",
                podA, podB);

        // Unset/blank HOSTNAME (a local run) falls back to the pid rather than emitting an empty slot.
        assertNotEquals(LoadRun.buildRunSalt(null, 1_700_000_000_000L, 4242L),
                LoadRun.buildRunSalt(null, 1_700_000_000_000L, 9999L));
        assertEquals(LoadRun.buildRunSalt("", 1_700_000_000_000L, 7L),
                LoadRun.buildRunSalt(null, 1_700_000_000_000L, 7L));

        // The salt is spliced verbatim into a URL-encoded body, so it must carry no unsafe char even
        // when HOSTNAME is something exotic (safeKey collapses the rest to '_').
        assertTrue("salt must stay URL-safe",
                LoadRun.buildRunSalt("pod/../x&y=1", 1L, 2L).matches("[A-Za-z0-9._x-]+"));
    }

    @Test
    public void nonceDoesNotMaskAnUnboundRealRef() {
        // @nonce fills, but an unbound ${{csrf}} still makes the step skip (null)
        assertNull(LoadRun.substitute("a=${{@nonce}}&t=${{csrf}}", new java.util.HashMap<>()));
    }

    /**
     * DD-038 task 2: end-to-end path substitution through the same shape the worker loop uses —
     * substitute the path unconditionally, substitute the body only when non-null. A path-only
     * nonce (empty body) must not NPE and must reach the server as a real token, never the literal
     * marker.
     */
    @Test
    public void pathOnlyNonceSubstitutesEndToEndWithoutNpe() throws IOException {
        final String[] seenPath = new String[1];
        server.createContext("/rec", (HttpExchange ex) -> {
            seenPath[0] = ex.getRequestURI().toString();
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        RequestLine step = RequestLine.parse("GET /rec?rev=${{@nonce}}");
        assertTrue("a path-only marker must be detected too", step.needsSubstitution());
        assertNull("this step has no body", step.body());

        Map<String, String> jar = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();

        // Mirrors the worker's null-safe substitution shape exactly.
        String p = LoadRun.substitute(step.path(), bindings);
        String b = (step.body() == null) ? null : LoadRun.substitute(step.body(), bindings);
        assertNotNull("path substitution must not return null for a self-filling @nonce", p);
        assertNull("a null body must stay null (no NPE), never get substitute()'d", b);

        RequestLine toFire = new RequestLine(step.method(), p, b, step.captures());
        int code = LoadRun.fire(base, toFire, jar, bindings);

        assertEquals(200, code);
        assertNotNull("server never received the request", seenPath[0]);
        assertFalse("literal marker must never reach the server: " + seenPath[0],
                seenPath[0].contains("${{"));
        assertTrue("rev= must carry a real non-empty token: " + seenPath[0],
                seenPath[0].matches("/rec\\?rev=[^&]+"));
    }

    /**
     * DD-038 task 2: the correlation-ordering lint must never false-warn on {@code @nonce} — it's
     * self-filling (Task 1), never a captured-value reference, so it has no "preceding capture" to
     * require. Scans BOTH path and body: this corpus line carries a nonce ref in each. Reflectively
     * resets the lint's log-once flag so the assertion isn't dependent on suite execution order.
     */
    @Test
    public void lintCorrelationOrderingIgnoresNonce() throws Exception {
        java.lang.reflect.Field warnedField = LoadRun.class.getDeclaredField("CORRELATION_LINT_WARNED");
        warnedField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean warned =
                (java.util.concurrent.atomic.AtomicBoolean) warnedField.get(null);
        boolean priorWarned = warned.get();
        warned.set(false);

        Path dir = Files.createTempDirectory("load-correlation-nonce-corpus");
        dir.toFile().deleteOnExit();
        Path corpusFile = dir.resolve("corpus.txt");
        // A nonce ref in the path (query string) AND one in the body, neither preceded by any
        // <<capture — exactly the shape that would trip the "will never bind" lint for a REAL ref.
        Files.write(corpusFile,
                "POST /rec?rev=${{@nonce}} body=${{@nonce}}\n".getBytes(StandardCharsets.UTF_8));
        corpusFile.toFile().deleteOnExit();

        java.io.PrintStream prevErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
            LoadRun.readCorpus(dir.toString());
        } finally {
            System.setErr(prevErr);
            warned.set(priorWarned);
        }

        String out = captured.toString("UTF-8");
        assertFalse("a nonce-only corpus must not trip the correlation lint's false "
                + "'will never bind' warning: " + out, out.contains("will never bind"));
    }
}
