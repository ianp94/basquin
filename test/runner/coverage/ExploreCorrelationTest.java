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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    private static final String SECRET = "SECRETTOKEN12345";

    /**
     * DD-036 security invariant: the real captured token must NEVER reach disk. Only the capture
     * *recipe* (the raw {@code ${{name}}}-bearing step text) may be persisted. This drives a
     * correlated sequence whose second step trips the {@code X-Basquin-Invariant-Count} save path
     * and asserts every byte written under the results dir is free of the live token.
     */
    @Test
    public void correlatedInvariantFindingDoesNotPersistToken() throws Exception {
        AtomicBoolean sawSubstitutedBody = new AtomicBoolean(false);

        server.createContext("/edit", (HttpExchange ex) -> {
            String resp = "<input name=\"X-XSRF-TOKEN\" value=\"" + SECRET + "\">";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        server.createContext("/save", (HttpExchange ex) -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String bodyStr = new String(reqBody, StandardCharsets.UTF_8);
            if (bodyStr.contains(SECRET)) {
                sawSubstitutedBody.set(true);
            }
            ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "1");
            ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "boom");
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Path dir = Files.createTempDirectory("basquin-fuzz-results");
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            CoverageGuidedRun.runSequence(base, List.of(
                    "/edit <<csrf=input:X-XSRF-TOKEN",
                    "POST /save X-XSRF-TOKEN=${{csrf}}&page=Main"));

            assertTrue("server never received the substituted (real-token) body — capture/substitution "
                    + "didn't run, so this test would prove nothing", sawSubstitutedBody.get());

            String allText = waitAndReadAll(dir);
            assertFalse("the live captured token leaked to disk in a finding file", allText.contains(SECRET));
            assertTrue("the safe recipe ('${{csrf}}') should have been persisted instead of the token",
                    allText.contains("${{csrf}}"));
        } finally {
            if (prior != null) {
                System.setProperty("basquin.fuzz.resultsDir", prior);
            } else {
                System.clearProperty("basquin.fuzz.resultsDir");
            }
        }
    }

    /**
     * Same DD-036 invariant on the other persistence path: a correlated step that gets a 5xx back
     * throws inside {@code request}, and {@code runSequence}'s catch persists it via
     * {@code FuzzIO.saveInteresting} — whose meta file embeds the exception message. That message
     * must be built from the raw recipe, never from the substituted (real-token) request text.
     */
    @Test
    public void correlated5xxFindingDoesNotPersistToken() throws Exception {
        AtomicBoolean sawSubstitutedBody = new AtomicBoolean(false);

        server.createContext("/edit", (HttpExchange ex) -> {
            String resp = "<input name=\"X-XSRF-TOKEN\" value=\"" + SECRET + "\">";
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        server.createContext("/save", (HttpExchange ex) -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String bodyStr = new String(reqBody, StandardCharsets.UTF_8);
            if (bodyStr.contains(SECRET)) {
                sawSubstitutedBody.set(true);
            }
            byte[] resp = "boom".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, resp.length);
            ex.getResponseBody().write(resp);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Path dir = Files.createTempDirectory("basquin-fuzz-results");
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            CoverageGuidedRun.runSequence(base, List.of(
                    "/edit <<csrf=input:X-XSRF-TOKEN",
                    "POST /save X-XSRF-TOKEN=${{csrf}}&page=Main"));

            assertTrue("server never received the substituted (real-token) body — capture/substitution "
                    + "didn't run, so this test would prove nothing", sawSubstitutedBody.get());

            String allText = waitAndReadAll(dir);
            assertFalse("the live captured token leaked to disk in a finding file", allText.contains(SECRET));
            assertTrue("the safe recipe should have been persisted (either '${{csrf}}' or the raw step text)",
                    allText.contains("${{csrf}}"));
        } finally {
            if (prior != null) {
                System.setProperty("basquin.fuzz.resultsDir", prior);
            } else {
                System.clearProperty("basquin.fuzz.resultsDir");
            }
        }
    }

    /**
     * Findings are written asynchronously (runner.util.TriageSink). Poll briefly for at least one
     * file to land, then concatenate every file's bytes as UTF-8 text.
     */
    private static String waitAndReadAll(Path dir) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        java.io.File[] files;
        do {
            files = dir.toFile().listFiles();
            if (files != null && files.length > 0) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);

        assertNotNull("results dir listing was null", files);
        assertTrue("expected at least one finding file to be written to " + dir, files.length > 0);

        StringBuilder all = new StringBuilder();
        for (java.io.File f : files) {
            all.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).append('\n');
        }
        return all.toString();
    }
}
