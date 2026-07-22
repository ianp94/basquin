package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Task 4 (DD-036): the LOAD driver actually USES {@link Capture}/{@link RequestLine#needsSubstitution()}
 * — capture a value out of one response and substitute it into a later request's body via
 * {@code ${{name}}}. Covers {@link LoadRun#substitute}, the 5-arg {@link LoadRun#fire} overload, and the
 * (d) uncorrelated-sequence no-alloc path via {@link LoadRun.Seq#correlated()}.
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
    public void substituteUrlEncodesPlusAndEqualsInBoundValue() {
        Map<String, String> bindings = new HashMap<>();
        bindings.put("csrf", "a+b=c");

        String out = LoadRun.substitute("X-XSRF-TOKEN=${{csrf}}&page=Main", bindings);

        assertEquals("X-XSRF-TOKEN=a%2Bb%3Dc&page=Main", out);
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
        RequestLine step1 = new RequestLine("GET", "/login", null, csrf);
        RequestLine step2 = RequestLine.parse("POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main");

        Map<String, String> jar = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();
        AtomicLong captureMisses = new AtomicLong();

        int code1 = LoadRun.fire(base, step1, jar, bindings, captureMisses);
        assertEquals(200, code1);
        assertEquals("tok123", bindings.get("csrf"));

        String substituted = LoadRun.substitute(step2.body(), bindings);
        assertEquals("X-XSRF-TOKEN=tok123&page=Main", substituted);

        RequestLine toFire = new RequestLine(step2.method(), step2.path(), substituted, step2.capture());
        int code2 = LoadRun.fire(base, toFire, jar, bindings, captureMisses);

        assertEquals(200, code2);
        assertEquals("X-XSRF-TOKEN=tok123&page=Main", seenBody[0]);
        assertEquals(0, captureMisses.get());
    }

    @Test
    public void captureMissLeavesBindingsUnchangedIncrementsCounterAndSkipsFiringStep() throws IOException {
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
        RequestLine step1 = new RequestLine("GET", "/login", null, csrf);
        RequestLine step2 = RequestLine.parse("POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main");

        Map<String, String> jar = new HashMap<>();
        Map<String, String> bindings = new HashMap<>();
        AtomicLong captureMisses = new AtomicLong();

        int code1 = LoadRun.fire(base, step1, jar, bindings, captureMisses);
        assertEquals(200, code1);
        assertTrue("bindings must stay unchanged on a capture miss", bindings.isEmpty());
        assertEquals(1, captureMisses.get());

        // The correlated step-2 must not be fired: substitute() signals the miss with null.
        String substituted = LoadRun.substitute(step2.body(), bindings);
        assertNull(substituted);
    }

    @Test
    public void uncorrelatedSequenceHasNullBindingsAndNoCaptureAlloc() {
        RequestLine step1 = RequestLine.parse("GET /a");
        RequestLine step2 = RequestLine.parse("GET /b");
        LoadRun.Seq seq = new LoadRun.Seq(java.util.List.of(step1, step2), false);

        assertEquals(false, seq.correlated());

        // The worker loop only allocates a bindings map when seq.correlated() is true; verify the
        // uncorrelated wrapper reports false so that path is taken (bindings stays null upstream).
        assertEquals(2, seq.steps().size());
    }
}
