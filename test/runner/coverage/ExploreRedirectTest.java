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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-039: explore follows redirects itself.
 *
 * <p>Three defects, one line ({@code setInstanceFollowRedirects(true)}): a {@code Set-Cookie} on the
 * intermediate 3xx is unreachable (the JDK discards the response headers before re-issuing); the
 * {@code Cookie} REQUEST header is dropped on every method-rewritten hop, so landing pages rendered
 * anonymously; and the intermediate hop's {@code X-Basquin-*} headers are discarded.
 *
 * <p>The measurement half is what closes DD-040's 189-violation gap, and the test that pins it —
 * {@link #aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce} — deliberately makes its second
 * hop a COMMITTED response with no reporting headers at all, the 97.3% case DD-040 measured. Every
 * assertion in it is unreachable through the header path.
 */
public class ExploreRedirectTest {

    /** One request the fake server saw. */
    record Seen(String method, String path, String cookie, String reqId,
                String contentType, String body) { }

    private HttpServer server;
    private String base;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pollHits = new AtomicInteger();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private volatile String pendingBody;
    private String priorPodHost;

    @Before
    public void setUp() throws IOException {
        CoverageGuidedRun.resetSession();          // the suite shares one JVM; a seeded cookie leaks
        priorPodHost = System.getProperty("basquin.report.podHost");
        System.setProperty("basquin.report.podHost", "off");
        PodPollTargets.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            try {
                pollHits.incrementAndGet();
                String body = pendingBody;
                pendingBody = null;                                    // remove-on-read
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        CoverageGuidedRun.resetSession();
        if (priorPodHost != null) System.setProperty("basquin.report.podHost", priorPodHost);
        else System.clearProperty("basquin.report.podHost");
        PodPollTargets.resetForTest();
        assertNull("a test server handler threw — a truncated response must not read as a pass",
                handlerFailure.get());
    }

    private void start() {
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void record(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("Cookie"),
                ex.getRequestHeaders().getFirst("X-Basquin-Req"),
                ex.getRequestHeaders().getFirst("Content-Type"),
                new String(b, StandardCharsets.UTF_8)));
    }

    /** A 3xx to {@code target}, optionally issuing a rotated session, with a boilerplate body that
     *  MUST be drained (Tomcat's sendRedirect emits one). HEAD-aware: com.sun.net.httpserver forbids
     *  a body on a HEAD response (write() throws "stream closed"), and a real container sends none. */
    private void redirect(String path, int code, String target, String setCookie) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                if (setCookie != null) ex.getResponseHeaders().add("Set-Cookie", setCookie);
                ex.getResponseHeaders().add("Location", target);
                byte[] out = "<html><body>Moved</body></html>".getBytes(StandardCharsets.UTF_8);
                if ("HEAD".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(code, -1);          // -1: headers only, no body (spec: HEAD)
                } else {
                    ex.sendResponseHeaders(code, out.length);
                    ex.getResponseBody().write(out);
                }
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    /** An ordinary 200 carrying {@code body}. HEAD-aware (see {@link #redirect}). */
    private void page(String path, String body) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                if ("HEAD".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(200, -1);           // -1: headers only, no body (spec: HEAD)
                } else {
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                }
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    /** A response that has ALREADY COMMITTED: 16 KB, no reporting headers. Ported from
     *  ReportChannelTest.serveCommittedApp — the case that CANNOT report through a header. */
    private void committed(String path) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                byte[] out = new byte[16384];
                java.util.Arrays.fill(out, (byte) 'x');
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    // ------------------------------------------------------------------ the motivating cookie cases

    /** Spec Verification #1. Spring Security answers POST /login with a 302 and issues the ROTATED
     *  JSESSIONID ON THAT 302 — a deliberate session-fixation defence. Under auto-follow the JDK
     *  discards those headers before re-issuing, so the driver kept the pre-login session forever. */
    @Test
    public void aSessionRotatedOnThe302ReachesTheNextHop() throws Exception {
        redirect("/login", 302, "/landing", "JSESSIONID=ROTATED; Path=/; HttpOnly");
        page("/landing", "ok");
        start();

        CoverageGuidedRun.request(base, "POST /login u=a&p=b");

        assertEquals(2, seen.size());
        assertEquals("JSESSIONID=ROTATED", seen.get(1).cookie());
        assertEquals("a 302 after a POST rewrites to GET (spec §3, JDK Evidence TEST A)",
                "GET", seen.get(1).method());
    }

    /** Spec Verification #2, and the one that DISCRIMINATES: a 302 that sets NO cookie. The target
     *  must still receive the pre-existing one. The JDK issues a method-rewritten hop with no Cookie
     *  header at all, so this failed before DD-039 even for a session that never rotated — every
     *  redirecting explore step had been scoring coverage against a logged-out page. */
    @Test
    public void aPreExistingCookieIsCarriedOntoAHopThatSetsNoCookie() throws Exception {
        page("/seed-page", "seed");
        redirect("/go", 302, "/dest", null);
        page("/dest", "ok");
        server.createContext("/seed", (HttpExchange ex) -> {
            try {
                ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=PREEXISTING; Path=/");
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.request(base, "/seed");     // establishes the session
        seen.clear();                                  // so the static field's prior value is never asserted on

        CoverageGuidedRun.request(base, "/go");

        assertEquals(2, seen.size());
        assertEquals("hop 0 carries the session", "JSESSIONID=PREEXISTING", seen.get(0).cookie());
        assertEquals("and so must the hop the JDK used to send anonymously",
                "JSESSIONID=PREEXISTING", seen.get(1).cookie());
    }

    /** Spec Verification #3: a three-hop chain, each hop rotating, ends with the last value. */
    @Test
    public void aThreeHopChainEndsWithTheLastRotatedSession() throws Exception {
        redirect("/a", 302, "/b", "JSESSIONID=ONE; Path=/");
        redirect("/b", 302, "/c", "JSESSIONID=TWO; Path=/");
        page("/c", "done");
        start();

        CoverageGuidedRun.request(base, "/a");

        assertEquals(3, seen.size());
        assertNull("hop 0 had no session yet", seen.get(0).cookie());
        assertEquals("JSESSIONID=ONE", seen.get(1).cookie());
        assertEquals("JSESSIONID=TWO", seen.get(2).cookie());
    }

    // ------------------------------------------------------------------------ method and body rules

    /** Spec Verification #8, HEAD half. A grammar can express HEAD; promoting it to GET pulls a body
     *  the caller never asked for, inflating the input's measured latency and heap. Regression guard:
     *  the JDK already preserved HEAD, so this is red only if the rewrite rule is written wrong. */
    @Test
    public void headStaysHeadAcrossAFollow() throws Exception {
        redirect("/h", 302, "/h2", null);
        page("/h2", "");
        start();

        CoverageGuidedRun.request(base, "HEAD /h");

        assertEquals(2, seen.size());
        assertEquals("HEAD", seen.get(1).method());
    }

    /** Spec Verification #8, body half — the riskiest line in the change. 307 exists to preserve the
     *  method AND the body, and 303-after-POST must drop the body AND its Content-Type together
     *  (carrying application/x-www-form-urlencoded onto a bodyless GET makes some filters attempt
     *  form parsing on an empty stream). */
    @Test
    public void a307PreservesMethodAndBodyAndA303AfterPostDropsBoth() throws Exception {
        redirect("/p307", 307, "/sink307", null);
        page("/sink307", "");
        redirect("/p303", 303, "/sink303", null);
        page("/sink303", "");
        start();

        CoverageGuidedRun.request(base, "POST /p307 u=a&p=b");
        assertEquals("POST", seen.get(1).method());
        assertEquals("u=a&p=b", seen.get(1).body());
        assertEquals("application/x-www-form-urlencoded", seen.get(1).contentType());

        seen.clear();
        CoverageGuidedRun.request(base, "POST /p303 u=a&p=b");
        assertEquals("GET", seen.get(1).method());
        assertEquals("", seen.get(1).body());
        assertNull("the Content-Type must be dropped WITH the body, not left behind",
                seen.get(1).contentType());
    }

    // ----------------------------------------------------------------- stamping and reconciliation

    /**
     * THE stamping test. DD-040's acceptance run lost 189 violations because the JDK does not carry a
     * {@code setRequestProperty} value onto a method-rewritten hop, so hop 2 arrived unstamped and
     * published nothing at all. Delete either {@code setRequestProperty} from the loop body and this
     * fails on hop 1.
     */
    @Test
    public void everyHopCarriesTheSameStampedIdAndTheSessionCookie() throws Exception {
        redirect("/login", 302, "/landing", "JSESSIONID=ROTATED; Path=/");
        committed("/landing");
        start();

        CoverageGuidedRun.request(base, "POST /login u=a&p=b");

        assertEquals(2, seen.size());
        assertNull(seen.get(0).cookie());
        assertEquals("JSESSIONID=ROTATED", seen.get(1).cookie());
        assertFalse("hop 0 must be stamped", seen.get(0).reqId() == null);
        assertEquals("hop 1 must carry the SAME id — the store accumulates under one id (§4b), and "
                + "an unstamped hop is a hop whose violation is evaluated, logged, and never counted",
                seen.get(0).reqId(), seen.get(1).reqId());
        assertTrue("ids are run-salted so a foreign or stale id misses honestly",
                seen.get(0).reqId().startsWith(LoadRun.RUN_SALT + "-"));
    }

    /**
     * THE reconciliation test, and the one that would have caught DD-040's failure before an
     * acceptance run. Hop 0 is a 302 that DID report through headers (small, uncommitted). Hop 1 is a
     * COMMITTED 200 with no reporting headers at all — the 97.3% case. The store holds both.
     *
     * <p>It fails, distinguishably, on each of the three ways this can go wrong:
     * <ul>
     *   <li>header save not suppressed → <b>3</b> records (hop 0 filed twice)</li>
     *   <li>{@code fetchResult} drops '\n', or {@code hops > 1} does not force a poll → <b>1</b>
     *       record carrying hop 0's count only, and {@code invariantCount == 2} instead of 5</li>
     *   <li>a second poll path added → {@code pollHits != 1}</li>
     * </ul>
     */
    @Test
    public void aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce() throws Exception {
        server.createContext("/login", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "2");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "latency: 300ms > 250ms");
                ex.getResponseHeaders().add("Location", "/dashboard");
                byte[] out = "<html>Moved</html>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(302, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        committed("/dashboard");
        start();
        // What the target's ResultStore holds after BOTH hops published under the one id.
        pendingBody = "12,340,0|2|latency: 300ms > 250ms|\n900,4096,1|3|latency: 900ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-dd039-reconcile");
        long measuredBefore = CoverageGuidedRun.reportMeasured;
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "POST /login u=a&p=b");

            assertTrue("the chain was measured", s.measured);
            assertEquals("BOTH hops' violations — this is the 189 that used to be lost",
                    5, s.invariantCount);
            assertEquals("heap summed across hops (spec §4)", 340L + 4096L, s.heapDeltaKb);
            assertEquals("exactly one poll for the whole input, never one per hop", 1, pollHits.get());

            List<String> metas = AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 2);
            assertEquals("one record per breaching hop, and NOT also one from hop 0's header — "
                    + "clearing a flag cannot un-save a file, so the header save had to be deleted "
                    + "from the header-read site", 2, metas.size());
            assertTrue(String.join("\n", metas).contains("hop=0"));
            assertTrue(String.join("\n", metas).contains("hop=1"));
            assertTrue("the committed hop's own detail is what makes the record triageable",
                    String.join("\n", metas).contains("latency: 900ms > 250ms"));
            assertTrue("labelled with the RAW step (DD-036)",
                    String.join("\n", metas).contains("route=POST /login u=a&p=b"));
        });
        assertEquals("one input, one measured request", measuredBefore + 1,
                CoverageGuidedRun.reportMeasured);
    }

    /**
     * The fallback that stops the fix from failing runs it used to pass. A multi-hop chain whose poll
     * MISSES (entry evicted, pod unreachable, DNS unable to answer) must fall back to the header
     * reading rather than record a miss — otherwise, on a redirect-heavy target, forced polling
     * pushes {@code missesAreTheMajority} past its threshold and {@code System.exit(3)} fails a run
     * DD-040 was reporting fine.
     */
    @Test
    public void aMultiHopPollThatMissesFallsBackToTheHeaderRatherThanCountingAMiss() throws Exception {
        redirect("/a", 302, "/b", null);
        server.createContext("/b", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "1");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "heap: 900KB > 500KB");
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();
        pendingBody = null;                    // the store has nothing: the poll will miss
        long missesBefore = CoverageGuidedRun.reportMisses;

        Path dir = Files.createTempDirectory("basquin-dd039-fallback");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/a");
            assertEquals("the poll ran, because hops > 1", 1, pollHits.get());
            assertTrue("...and its miss must not discard a perfectly good header reading", s.measured);
            assertEquals(1, s.invariantCount);
            assertEquals("the header record is emitted HERE, and only here, because the poll took "
                    + "nothing", 1, AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 1).size());
        });
        assertEquals("a measured request must not be counted as a miss",
                missesBefore, CoverageGuidedRun.reportMisses);
    }

    // ------------------------------------------------------------------------- unchanged behaviours

    /** Spec Verification #11. A non-redirect response must behave exactly as it did before. */
    @Test
    public void aNonRedirectResponseIsUnchangedAndIsNotPolledWhenItReportedAHeader() throws Exception {
        server.createContext("/plain", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                byte[] out = "hello".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/plain");

        assertEquals(1, seen.size());
        assertTrue(s.measured);
        assertEquals(340L, s.heapDeltaKb);
        assertEquals("a single hop with a cost header must NOT poll — remove-on-read would take the "
                + "store's copy of the same violation", 0, pollHits.get());
    }

    /** Spec Verification #10. The intermediate hop is drained; the FINAL hop's body is not, so a
     *  DD-036 capture still has something to read after a redirect. Drain the final hop by mistake
     *  and this fails with an unresolved correlation ref. */
    @Test
    public void theFinalHopsBodySurvivesTheDrainSoACaptureStillWorks() throws Exception {
        redirect("/form", 302, "/realform", null);
        page("/realform", "<input name=\"X-XSRF-TOKEN\" value=\"tok999\">");
        final String[] posted = new String[1];
        server.createContext("/save", (HttpExchange ex) -> {
            try {
                posted[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.runSequence(base, List.of(
                "/form <<csrf=input:X-XSRF-TOKEN",
                "POST /save X-XSRF-TOKEN=${{csrf}}"));

        assertEquals("the capture ran against the FINAL hop's body, per spec §5",
                "X-XSRF-TOKEN=tok999", posted[0]);
    }

    /** An unsupported method is not sent, and a request that never went out is not a miss — the
     *  pre-DD-039 early return sat before the try/finally and counted nothing.
     *
     *  <p>PATCH, not TRACE: the method must be one {@link RequestLine#parse} recognizes (so the path
     *  stays valid) yet {@code HttpURLConnection.setRequestMethod} rejects — only PATCH is both. TRACE
     *  is absent from RequestLine's method set, so "TRACE /x" parses as GET with path "TRACE", whose
     *  malformed URL would throw before the ProtocolException branch this test exercises. */
    @Test
    public void anUnsupportedMethodIsNotSentAndIsNotCountedAsAMiss() throws Exception {
        start();
        long missesBefore = CoverageGuidedRun.reportMisses;
        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "PATCH /x");
        assertFalse(s.measured);
        assertEquals(0, seen.size());
        assertEquals(0, pollHits.get());
        assertEquals(missesBefore, CoverageGuidedRun.reportMisses);
    }

    // ---------------------------------------------------------------- Task 5: redirect policy + loop

    /** Spec Verification #6. A fuzzed input inducing an open redirect must not turn the explorer
     *  into a client for another server, or attribute another host's cost to the app. */
    @Test
    public void aCrossOriginLocationIsNotFollowedAndIsCounted() throws Exception {
        redirect("/out", 302, "http://evil.example.invalid/steal", null);
        start();
        long before = CoverageGuidedRun.crossOriginRedirects;

        CoverageGuidedRun.request(base, "/out");

        assertEquals("only hop 0 was issued", 1, seen.size());
        assertEquals("the refusal must be COUNTED — a configured absolute base URL makes every "
                + "redirect cross-origin and degrades DD-039 to its pre-DD-039 behaviour, and a "
                + "non-zero counter beside flat coverage is the only thing that makes that visible",
                before + 1, CoverageGuidedRun.crossOriginRedirects);
    }

    /** Spec Verification #7. A relative Location resolves, and http://svc/x matches http://svc:80. */
    @Test
    public void aRelativeLocationResolvesAndIsFollowed() throws Exception {
        redirect("/dir/one", 302, "two", null);
        page("/dir/two", "ok");
        start();

        CoverageGuidedRun.request(base, "/dir/one");

        assertEquals(2, seen.size());
        assertEquals("/dir/two", seen.get(1).path());
    }

    /** Spec Verification #5. URI throws on unencoded space, {}, | and ^ — the bytes a fuzzer
     *  reflects. request(...) is `throws Exception` and its caller files a CRASH finding against the
     *  app on any Throwable, so an unguarded parse would blame the app for a driver bug. */
    @Test
    public void anUnparseableLocationEndsTheChainWithoutFilingACrash() throws Exception {
        redirect("/bad", 302, "/next?q=a b|c^{}", null);
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-badloc");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/bad");     // must not throw
            assertEquals("the 3xx becomes the final response", 1, seen.size());
            assertEquals("no crash finding may be filed against the app",
                    0, AccumulatedPollTest.readMetasFor(dir, "Crash").size());
        });
    }

    /** Spec Verification #4, revisit half. /protected -> /login -> /protected is a real loop that
     *  never reaches five hops, which is why the cap alone is a poor detector. */
    @Test
    public void aRevisitedUrlEndsTheChainAndFilesARedirectLoopFinding() throws Exception {
        redirect("/protected", 302, "/login", null);
        redirect("/login", 302, "/protected", null);
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-loop");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/protected");

            // The loop detects a revisited URL BEFORE re-issuing it (look-ahead), so a self-loop
            // is exactly 2 requests: the original and the one hop that reveals the repeat. "A
            // revisited URL ends the chain" (spec §2) does not require wastefully re-fetching it.
            assertEquals("stopped at the revisit (look-ahead), not at the cap", 2, seen.size());
            List<String> metas = AccumulatedPollTest.waitForMetas(dir, "Redirect-Loop", 1);
            assertEquals(1, metas.size());
            assertTrue("labelled with the RAW step, never a resolved hop URL (DD-036)",
                    metas.get(0).contains("route=/protected"));
            assertTrue("the ordered hops are the evidence", metas.get(0).contains("/login"));
        });
    }

    /** Spec Verification #4, cap half. Five REQUESTS total, not six. */
    @Test
    public void theHopCapStopsAtFiveRequestsAndFilesTheFinding() throws Exception {
        for (int i = 0; i < 9; i++) redirect("/n" + i, 302, "/n" + (i + 1), null);
        page("/n9", "end");
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-cap");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/n0");
            assertEquals("MAX_HOPS counts REQUESTS: five, not 1 + 5", 5, seen.size());
            assertEquals(1, AccumulatedPollTest.waitForMetas(dir, "Redirect-Loop", 1).size());
        });
    }

    /** An ordinary non-redirect response must NOT file a Redirect-Loop finding — the failure mode of
     *  fusing the two terminal conditions into one branch. */
    @Test
    public void anOrdinaryResponseFilesNoRedirectLoopFinding() throws Exception {
        page("/plainer", "hello");
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-noloop");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/plainer");
            Thread.sleep(200);
            assertEquals("fusing !isRedirect with the cap check makes EVERY response a loop finding",
                    0, AccumulatedPollTest.readMetasFor(dir, "Redirect-Loop").size());
        });
    }

    /** DD-036, the half the existing guard test cannot see: it puts its token in the BODY. Hop 0's
     *  URL is built from the SUBSTITUTED path, so an unstripped hop URL writes a live token to disk. */
    @Test
    public void aTokenSubstitutedIntoThePathNeverReachesDisk() throws Exception {
        page("/edit", "<input name=\"X-XSRF-TOKEN\" value=\"SECRET-PATH-TOKEN-42\">");
        redirect("/save", 302, "/save", null);      // an immediate self-loop: forces the loop finding
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-pathtoken");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.runSequence(base, List.of(
                    "/edit <<csrf=input:X-XSRF-TOKEN",
                    "/save?csrf=${{csrf}}"));

            String all = String.join("\n", AccumulatedPollTest.readMetasFor(dir, "Redirect-Loop"));
            assertFalse("a token substituted into the PATH must never reach a hop URL on disk: " + all,
                    all.contains("SECRET-PATH-TOKEN-42"));
            assertTrue("the safe recipe is what is recorded", all.contains("${{csrf}}"));
        });
    }
}
