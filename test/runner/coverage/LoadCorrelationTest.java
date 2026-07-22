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
}
