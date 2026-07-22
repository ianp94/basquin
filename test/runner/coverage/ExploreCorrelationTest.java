package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Task 5 (DD-036): the EXPLORE (fuzzing) driver's transaction runner wires the same
 * capture+substitute machinery as {@link LoadRun} — {@link RequestLine#capture()},
 * {@link RequestLine#needsSubstitution()}, and {@link LoadRun#substitute} — into
 * {@link CoverageGuidedRun#runSequence} / {@link CoverageGuidedRun#request}, so a grammar
 * {@code @sequence} can GET a CSRF form, capture the token, and POST it.
 */
public class ExploreCorrelationTest {

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
    public void runSequenceCapturesTokenAndSubstitutesIntoLaterStep() throws Exception {
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

        String getStep = "GET /login <<csrf=input:X-XSRF-TOKEN";
        String postStep = "POST /submit X-XSRF-TOKEN=${{csrf}}&page=Main";

        CoverageGuidedRun.runSequence(base, List.of(getStep, postStep));

        assertEquals("X-XSRF-TOKEN=tok123&page=Main", seenBody[0]);
    }

    @Test
    public void twoArgRequestIsInertOnACaptureStep() throws Exception {
        server.createContext("/login", (HttpExchange ex) -> {
            String resp = "<input name=\"X-XSRF-TOKEN\" value=\"tok123\">";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        String stepWithCapture = "GET /login <<csrf=input:X-XSRF-TOKEN";

        CoverageGuidedRun.CostSample sample = CoverageGuidedRun.request(base, stepWithCapture);

        assertNotNull(sample);
    }
}
