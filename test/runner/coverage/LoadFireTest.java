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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

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

    @Test
    public void firePatchFailsLoudWithoutSilentlyDowngradingToGet() throws IOException {
        final AtomicBoolean handlerWasInvoked = new AtomicBoolean(false);

        server.createContext("/x", (HttpExchange ex) -> {
            handlerWasInvoked.set(true);
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        RequestLine step = RequestLine.parse("PATCH /x");
        int code = LoadRun.fire(base, step, new HashMap<>());

        assertEquals(-1, code);
        assertFalse("Handler should not have been invoked for unsupported PATCH method", handlerWasInvoked.get());
    }

    // --- Task 3 (DD-038): fireR does not auto-follow redirects and reports the raw Location so the
    // worker loop can classify a rejected write (e.g. JSPWiki's SessionExpired/PageModified) instead of
    // it vanishing into a followed 200. ---

    @Test
    public void fireRDoesNotFollowAndReportsLocation() throws IOException {
        server.createContext("/Edit.jsp", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Location", "/Wiki.jsp?page=SessionExpired");
            ex.sendResponseHeaders(302, 0);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        LoadRun.FireResult r = LoadRun.fireR(base, new RequestLine("POST", "/Edit.jsp", "page=X&action=save"),
                new HashMap<>(), null);

        assertEquals(302, r.code());
        assertEquals("/Wiki.jsp?page=SessionExpired", r.location());
    }

    @Test
    public void fireRHasNullLocationForA200() throws IOException {
        server.createContext("/ok", (HttpExchange ex) -> {
            byte[] resp = new byte[0];
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        LoadRun.FireResult r = LoadRun.fireR(base, new RequestLine("GET", "/ok", null), new HashMap<>(), null);

        assertEquals(200, r.code());
        assertNull(r.location());
    }

    @Test
    public void a302SetCookieStillPopulatesTheJar() throws IOException {
        server.createContext("/login", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Location", "/Wiki.jsp?page=Main");
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=abc; Path=/; HttpOnly");
            ex.sendResponseHeaders(302, 0);
            ex.getResponseBody().close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, String> jar = new HashMap<>();
        LoadRun.fireR(base, new RequestLine("GET", "/login", null), jar, null);

        assertEquals("abc", jar.get("JSESSIONID"));
    }

    @Test
    public void normalizeLocationClassifies() {
        assertEquals("self", LoadRun.normalizeLocation("/Wiki.jsp?page=Main", "Main"));
        assertEquals("SessionExpired", LoadRun.normalizeLocation("/Wiki.jsp?page=SessionExpired", "Main"));
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?tab=view", null));
        assertEquals("X", LoadRun.normalizeLocation("http://h/Wiki.jsp?page=X", null));
    }
}
