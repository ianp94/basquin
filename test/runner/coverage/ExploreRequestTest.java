package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Task 4: {@link CoverageGuidedRun#request} must issue a corpus entry's method + body (Task 3's
 * {@code RequestLine}), not hardcode GET — a POST form handler must actually see a POST with a
 * form body, not a GET the harness invented. Uses a real JDK {@link HttpServer} on an ephemeral
 * port rather than mocking {@code HttpURLConnection}.
 */
public class ExploreRequestTest {

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
    public void requestPostsMethodAndBodyWithFormContentType() throws Exception {
        final String[] seenMethod = new String[1];
        final String[] seenContentType = new String[1];
        final String[] seenBody = new String[1];

        server.createContext("/submit", (HttpExchange ex) -> {
            seenMethod[0] = ex.getRequestMethod();
            seenContentType[0] = ex.getRequestHeaders().getFirst("Content-Type");
            byte[] body = ex.getRequestBody().readAllBytes();
            seenBody[0] = new String(body, StandardCharsets.UTF_8);
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        CoverageGuidedRun.request(base, "POST /submit a=1&b=2");

        assertEquals("POST", seenMethod[0]);
        assertEquals("application/x-www-form-urlencoded", seenContentType[0]);
        assertEquals("a=1&b=2", seenBody[0]);
    }
}
