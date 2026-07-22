package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Task 3: {@link LoadRun#fire} must replay a {@link RequestLine} faithfully — method, body (with the
 * form content type), and a session cookie carried across steps via a per-worker jar. Uses a real JDK
 * {@link HttpServer} on an ephemeral port rather than mocking {@code HttpURLConnection}.
 */
public class LoadFireTest {

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
    public void firePostsMethodAndBodyWithFormContentType() throws IOException {
        final String[] seenMethod = new String[1];
        final String[] seenContentType = new String[1];
        final String[] seenBody = new String[1];

        server.createContext("/signon", (HttpExchange ex) -> {
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

        RequestLine step = RequestLine.parse("POST /signon u=j2ee&p=j2ee");
        int code = LoadRun.fire(base, step, new HashMap<>());

        assertEquals(200, code);
        assertEquals("POST", seenMethod[0]);
        assertEquals("application/x-www-form-urlencoded", seenContentType[0]);
        assertEquals("u=j2ee&p=j2ee", seenBody[0]);
    }

    @Test
    public void fireCarriesSessionCookieAcrossStepsInTheSameJar() throws IOException {
        final String[] seenCookie = new String[1];

        server.createContext("/login", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=abc123; Path=/; HttpOnly");
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.createContext("/account", (HttpExchange ex) -> {
            seenCookie[0] = ex.getRequestHeaders().getFirst("Cookie");
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, String> jar = new HashMap<>();
        RequestLine step1 = RequestLine.parse("GET /login");
        RequestLine step2 = RequestLine.parse("GET /account");

        int code1 = LoadRun.fire(base, step1, jar);
        int code2 = LoadRun.fire(base, step2, jar);

        assertEquals(200, code1);
        assertEquals(200, code2);
        assertEquals("JSESSIONID=abc123", seenCookie[0]);
    }
}
