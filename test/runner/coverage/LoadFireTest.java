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
        // same page -> the PATH is the signal: a success echo keys by the one view route
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Main", "Main"));
        // different page -> the page name is the signal
        assertEquals("SessionExpired", LoadRun.normalizeLocation("/Wiki.jsp?page=SessionExpired", "Main"));
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?tab=view", null));
        assertEquals("X", LoadRun.normalizeLocation("http://h/Wiki.jsp?page=X", null));
        // degenerate same-page echo with an empty path segment -> the reserved "self"
        assertEquals("self", LoadRun.normalizeLocation("/?page=Main", "Main"));
    }

    /**
     * DD-038 fix, live-verified on 2.12.4: a REJECTED concurrent edit answers
     * {@code 302 /PageModified.jsp?page=<theVeryPageBeingSaved>} — the {@code page=} matches the
     * firing request's own page, so the original "page= wins, same page folds to self" rule filed a
     * genuine reject into the success bucket (over-folding, strictly worse than the crowd-out it
     * prevented). Same-page redirects must key by the path segment so the reject route stays visible
     * next to the success route.
     */
    @Test
    public void samePageRejectRouteKeepsItsOwnKeyInsteadOfFoldingIntoSuccesses() {
        String reqPage = LoadRun.paramValue("page=Page2&action=save&_editedtext=x", "page");
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Page2", reqPage));          // success echo
        assertEquals("PageModified.jsp", LoadRun.normalizeLocation("/PageModified.jsp?page=Page2", reqPage)); // conflict reject
        assertEquals("SessionExpired", LoadRun.normalizeLocation("/Wiki.jsp?page=SessionExpired", reqPage));  // spam-hash reject
        assertEquals("Forbidden.html", LoadRun.normalizeLocation("/error/Forbidden.html", reqPage));          // CSRF reject
    }

    /**
     * DD-038 N2: the {@code (^|[?&])page=} rule is the single load-bearing parse decision behind the
     * self-fold. The {@code ^} alternative exists because a fired step's own {@code page=} is often at
     * offset 0 of the BODY ({@code page=Main&action=save}, jspwiki's {@code edit_save}), where a
     * {@code [?&]page=} rule would miss it → {@code requestPage == null} → no self-fold → routine
     * SUCCESS 302s crowd the bounded map and evict the real rejects. It must still reject a param
     * whose name merely ENDS in {@code page} ({@code frompage=}).
     */
    @Test
    public void paramValueMatchesLeadingAndQueryPageButNotFrompage() {
        assertEquals("Main", LoadRun.paramValue("page=Main&action=save", "page"));   // body-leading (^)
        assertNull(LoadRun.paramValue("frompage=X&action=save", "page"));            // suffix name, no match
        assertEquals("Main", LoadRun.paramValue("/Edit.jsp?page=Main&x=1", "page")); // query form (?)
        assertNull(LoadRun.paramValue("/Edit.jsp?x=1", "page"));                     // absent
        assertEquals("Main", LoadRun.paramValue("/Edit.jsp?x=1&page=Main", "page")); // non-leading (&)
    }

    /**
     * DD-038: the same-page fold must work "from a body-leading {@code page=}" — that is the
     * composition the worker actually performs (extract the fired step's own page with
     * {@code paramValue}, then classify the Location against it), so pin the pair, not just the halves.
     */
    @Test
    public void samePageFoldFromABodyLeadingPageParam() {
        String reqPage = LoadRun.paramValue("page=Main&action=save&text=hi", "page");
        assertEquals("Main", reqPage);
        // a SUCCESS save 302s back to the page it just saved -> the shared view-route slot
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Main", reqPage));
        // a REJECTED save 302s to a different page -> its own slot, which is the whole point
        assertEquals("PageModified", LoadRun.normalizeLocation("/Wiki.jsp?page=PageModified", reqPage));
    }

    /**
     * DD-038 fix: JSPWiki canonicalizes a page name by capitalizing its FIRST letter
     * ({@code DefaultCommandResolver} → {@code MarkupParser.cleanLink} → {@code TextUtil.cleanString};
     * verified live on 2.12.4 — saving {@code page=ndws} answers {@code Location: /Wiki.jsp?page=Ndws},
     * {@code nDWS} answers {@code NDWS}, the rest of the name preserved). A byte-equality same-page
     * test misses that, filing every successful lowercase-page save under its own capitalized key
     * ({@code "Ndws": 1051} in the first c8 run) — with a 66-page corpus that fills the 12-slot map
     * with successes and evicts the real rejects, the exact crowd-out the fold exists to prevent.
     */
    @Test
    public void samePageFoldSurvivesFirstLetterCanonicalization() {
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Ndws", "ndws"));  // the c8-run miss
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=NDWS", "nDWS")); // rest preserved
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Main", "main")); // front page too
        // and composed with the worker's own paramValue extraction, as the worker performs it
        String reqPage = LoadRun.paramValue("page=ndws&action=save&_editedtext=x", "page");
        assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?page=Ndws", reqPage));
        // the canonicalized CONFLICT reject stays distinct from the canonicalized success
        assertEquals("PageModified.jsp", LoadRun.normalizeLocation("/PageModified.jsp?page=Ndws", reqPage));
    }

    /**
     * Over-folding guard for {@code samePage}: ONLY the first character compares case-insensitively.
     * Beyond it, case and length still distinguish genuinely different targets — a rejected save of a
     * page named {@code sessionexpired} redirects to {@code SessionExpired} (differs at the mid-word
     * {@code E}) and must keep its own page-name key, not merge into the success route's bucket.
     */
    @Test
    public void samePageDoesNotEquateNamesDifferingBeyondTheFirstLetter() {
        assertEquals("SessionExpired", LoadRun.normalizeLocation("/Wiki.jsp?page=SessionExpired", "sessionexpired"));
        assertEquals("PageModified", LoadRun.normalizeLocation("/Wiki.jsp?page=PageModified", "pagemodified"));
        assertEquals("Ndws2", LoadRun.normalizeLocation("/Wiki.jsp?page=Ndws2", "ndws"));  // length differs
    }

    /** DD-038: keys are truncated to 64 chars so one long/reflected Location can't eat the ~4 KB budget. */
    @Test
    public void normalizeLocationTruncatesLongKeysTo64Chars() {
        String longPage = "P".repeat(200);
        String key = LoadRun.normalizeLocation("/Wiki.jsp?page=" + longPage, null);
        assertEquals(64, key.length());
        assertEquals("P".repeat(64), key);
    }

    /**
     * DD-038: the admission cap is what stands between a reflected/fuzzed Location and an unbounded
     * map (and an oversized termination-message summary). A key ALREADY present must still be admitted
     * even when the map is full — diverting it to "other" would fragment its count.
     */
    @Test
    public void admitKeyCapsNewKeysButNeverDivertsAKnownOne() {
        Map<String, java.util.concurrent.atomic.LongAdder> targets = new HashMap<>();
        for (int i = 0; i < LoadRun.REDIRECT_TARGETS_CAP - 1; i++) {
            targets.put("k" + i, new java.util.concurrent.atomic.LongAdder());
        }
        assertEquals("fresh", LoadRun.admitKey(targets, "fresh"));   // room for the last slot
        targets.put("fresh", new java.util.concurrent.atomic.LongAdder());
        assertEquals(LoadRun.REDIRECT_TARGETS_CAP, targets.size());
        assertEquals("other", LoadRun.admitKey(targets, "overflow")); // full: a NEW key overflows
        assertEquals("fresh", LoadRun.admitKey(targets, "fresh"));    // full: a KNOWN key still admitted
        assertEquals("k0", LoadRun.admitKey(targets, "k0"));
    }
}
