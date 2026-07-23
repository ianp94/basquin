package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import runner.util.StatusReporter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-040, driver half: the explore driver stamps every request, recovers the measurements the
 * response header could not carry, and — the part that actually moves the campaign's number —
 * SAVES a recovered violation as a finding.
 *
 * <p>The defect being fixed: the boundary evaluated every invariant and could only report it by
 * attaching a response header, which is impossible once the response has committed. On Roller
 * 97.3% of responses had committed; one explore window evaluated 1,906 violations and reported 0.
 *
 * <p>The failure mode this test file exists to make unreachable: an implementation that polls,
 * parses {@code invariantCount=2}, feeds it to the cost model, and saves nothing. Every count-based
 * assertion would pass and the campaign would still report ~0 findings. So the finding tests assert
 * on the {@code Invariant-Remote} meta file and on {@code findInvariant} — the number Task 7
 * compares against the target pod's log — not on a parsed integer.
 */
public class ReportChannelTest {

    private HttpServer server;
    private String base;

    /** Fake of the target's ResultStore: the entry the next poll will get, REMOVED ON READ exactly
     *  as the real one — the driver's id is minted inside request(), so it cannot be pre-keyed here. */
    private volatile String pendingEntry;
    private final AtomicInteger pollHits = new AtomicInteger();
    private final List<String> pollIds = new ArrayList<>();
    private final List<String> pollStampHeaders = new ArrayList<>();
    private volatile String appSeenReqId;
    private volatile String appSeenQuery;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            pollHits.incrementAndGet();
            String q = ex.getRequestURI().getQuery();
            String id = q != null && q.startsWith("id=") ? q.substring(3) : null;
            pollIds.add(id);
            // A stamped poll would leave a stale id on a pooled connector thread and later publish
            // some probe's metrics under a driver id, so its absence is asserted, not assumed.
            pollStampHeaders.add(ex.getRequestHeaders().getFirst("X-Basquin-Req"));
            String body = id == null ? null : pendingEntry;
            pendingEntry = null;                                    // remove-on-read
            byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    /** The app half: a big response that has already committed, so it carries NO reporting header. */
    private void serveCommittedApp(String path) {
        server.createContext(path, (HttpExchange ex) -> {
            appSeenReqId = ex.getRequestHeaders().getFirst("X-Basquin-Req");
            appSeenQuery = ex.getRequestURI().getQuery();
            byte[] out = new byte[16384];
            java.util.Arrays.fill(out, (byte) 'x');
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
    }

    private void start() {
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // The motivating case: a response that committed, so no cost header — the driver must poll and
    // must recover the violation rather than recording a clean zero.
    @Test
    public void aCommittedResponseIsRecoveredByThePoll() throws Exception {
        serveCommittedApp("/big");
        start();
        long missesBefore = CoverageGuidedRun.reportMisses;
        pendingEntry = "719,120,0|2|latency: 719ms > 250ms|";

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/big");

        assertEquals("the poll must run when no cost header came back", 1, pollHits.get());
        assertTrue("a recovered sample is MEASURED", s.measured);
        assertEquals("the violation the header could not carry", 2, s.invariantCount);
        assertEquals("heap delta comes from the polled costCsv, in KB", 120, s.heapDeltaKb);
        assertEquals("no new miss", missesBefore, CoverageGuidedRun.reportMisses);
    }

    // The fast path is keyed on the COST header (always present on an explore exit), not the
    // invariant header (absent on every clean request) — otherwise the driver polls every request.
    @Test
    public void aResponseCarryingTheCostHeaderIsNotPolled() throws Exception {
        server.createContext("/clean", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().close();
        });
        start();

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/clean");

        assertEquals("a clean request carries no invariant header; keying on THAT would poll here",
                0, pollHits.get());
        assertTrue(s.measured);
        assertEquals(340, s.heapDeltaKb);
        assertEquals(0, s.invariantCount);
    }

    // Remove-on-read: counting the same violation from BOTH channels double-counts every violation
    // on an uncommitted response. The fast path and the reliable path are alternatives, not a sum.
    @Test
    public void aHeaderReportedViolationIsNotAlsoRecoveredByThePoll() throws Exception {
        server.createContext("/hdr", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
            ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "2");
            ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "latency: 719ms > 250ms");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().close();
        });
        start();
        // The target publishes to its store on EVERY explore exit, header or not; the entry is
        // sitting there. Taking it as well would file the same violation twice.
        pendingEntry = "12,340,0|2|latency: 719ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-report-dup");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/hdr");
            assertEquals("must not poll for an entry it already has", 0, pollHits.get());
            assertEquals(2, s.invariantCount);
            assertEquals("exactly one finding for one request", 1, countFindings(dir, "Invariant-Remote"));
        });
    }

    // A miss must NOT become a zero-filled CostSample. That reproduces the exact degeneration this
    // change exists to fix, with reportMisses ticking cheerfully beside it.
    @Test
    public void aMissYieldsUnmeasuredAndIncrementsReportMisses() throws Exception {
        serveCommittedApp("/big");
        start();
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/big");   // store is empty

        assertEquals(1, pollHits.get());
        assertFalse("a miss is UNMEASURED, never a measured zero", s.measured);
        assertEquals("one miss counted", before + 1, CoverageGuidedRun.reportMisses);
        assertFalse("an unmeasured sample must never be scored — scoring it means scoring zeros",
                CoverageGuidedRun.scoreable(true, s));
    }

    // 500s are the interesting requests; the poll must still happen when request() throws.
    @Test
    public void aServerErrorIsStillPolled() throws Exception {
        server.createContext("/boom", (HttpExchange ex) -> {
            appSeenReqId = ex.getRequestHeaders().getFirst("X-Basquin-Req");
            byte[] out = "<pre>java.lang.NullPointerException\n\tat app.Boom.go(Boom.java:1)</pre>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
        start();
        pendingEntry = "31,2,0|1|latency: 900ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-report-500");
        withResultsDir(dir, () -> {
            try {
                CoverageGuidedRun.request(base, "/boom");
                fail("a 500 must still throw the app's failure");
            } catch (Exception expected) {
                assertTrue("the app's 500, not a poll failure, must be what propagates",
                        String.valueOf(expected.getMessage()).contains("HTTP 500"));
            }
            assertEquals("the poll runs from the finally, or a 500's measurements rot in the store",
                    1, pollHits.get());
            assertNotNull("the request itself was stamped", appSeenReqId);
            assertEquals("the poll asked for exactly the id the request carried",
                    appSeenReqId, pollIds.get(0));
            assertNull("remove-on-read: the entry was consumed by that poll", pendingEntry);
            assertTrue("and a 500's recovered violation is still filed as a finding",
                    waitForFinding(dir, "Invariant-Remote").contains("count=1"));
        });
    }

    // An exception escaping the finally would REPLACE the in-flight serverError and lose the 500
    // finding. The poll's failures are swallowed inside it.
    @Test
    public void aFailingPollDoesNotReplaceTheServerError() throws Exception {
        server.createContext("/boom", (HttpExchange ex) -> {
            byte[] out = "boom".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
        server.removeContext("/__basquin/result");
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            pollHits.incrementAndGet();
            ex.sendResponseHeaders(200, 4096);      // promises 4 KiB...
            ex.getResponseBody().close();           // ...and delivers none: the client read fails
        });
        start();
        long before = CoverageGuidedRun.reportMisses;

        try {
            CoverageGuidedRun.request(base, "/boom");
            fail("the app's 500 must propagate");
        } catch (Exception expected) {
            assertTrue("a poll failure must never displace the app's exception",
                    String.valueOf(expected.getMessage()).contains("HTTP 500"));
        }
        assertEquals("a failed poll is a miss", before + 1, CoverageGuidedRun.reportMisses);
    }

    /** The same swallow, isolated: nothing listening at all must be a miss, not a thrown poll. */
    @Test
    public void anUnreachableTargetPollsToAMissWithoutThrowing() throws Exception {
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = dead.getAddress().getPort();
        dead.stop(0);   // nothing is listening on deadPort now
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s =
                CoverageGuidedRun.pollResult("http://127.0.0.1:" + deadPort, "salt-1", "/x");

        assertFalse(s.measured);
        assertEquals(before + 1, CoverageGuidedRun.reportMisses);
    }

    // THE test that makes the acceptance criterion reachable. Feeding the cost model is not
    // reporting: a violation becomes a FINDING only via FuzzIO.saveWithMeta -> recordSaved ->
    // findInvariant, which is the number Task 7 compares against the pod log. Without the save,
    // every other test here still passes and the campaign still reports ~0.
    @Test
    public void aRecoveredViolationIsSavedAsAFinding() throws Exception {
        serveCommittedApp("/thing");
        start();
        pendingEntry = "719,120,0|2|latency: 719ms > 250ms|";
        StatusReporter.enableTrackingForTest();
        long findsBefore = StatusReporter.findInvariantForTest();

        Path dir = Files.createTempDirectory("basquin-report-finding");
        // A ${{@nonce}} step: the wire carries the generated value, the finding must carry the RAW
        // recipe (DD-036) — a substituted request line would put a live token on disk.
        String rawStep = "/thing?n=${{@nonce}}";
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, rawStep);
            assertEquals(2, s.invariantCount);

            String text = waitForFinding(dir, "Invariant-Remote");
            assertTrue("the recovered violation must be persisted as an Invariant-Remote finding",
                    text.contains("classification=Invariant-Remote"));
            assertTrue("count must be recorded", text.contains("count=2"));
            assertTrue("detail must be recorded", text.contains("latency: 719ms > 250ms"));
            assertTrue("the RAW step label (DD-036), never the substituted request line",
                    text.contains("${{@nonce}}"));
            String sentNonce = appSeenQuery == null ? "" : appSeenQuery.substring("n=".length());
            assertFalse("the substituted value must not reach disk",
                    sentNonce.isEmpty() || text.contains(sentNonce));
        });
        assertEquals("recovering a count is not reporting it — findInvariant is the campaign's number",
                findsBefore + 1, StatusReporter.findInvariantForTest());
    }

    @Test
    public void aRecoveredLeakIsSavedAsAFinding() throws Exception {
        serveCommittedApp("/leaky");
        start();
        pendingEntry = "5,8,1|0||leak";

        Path dir = Files.createTempDirectory("basquin-report-leak");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.request(base, "/leaky");
            String text = waitForFinding(dir, "Leak-Remote");
            assertTrue(text.contains("classification=Leak-Remote"));
            assertTrue(text.contains("/leaky"));
            assertEquals("a leak entry with count=0 must not also file an invariant finding",
                    0, countFindings(dir, "Invariant-Remote"));
        });
    }

    /**
     * {@code detail} is app-derived. The store sanitises separators out of it, but a version-skewed
     * target could still emit one — so the driver parses with a 4-field limit, which bounds the
     * damage: the count and the cost are read from fields the app cannot reach, and the surplus text
     * lands in the last field rather than shifting every field along and being read as something
     * else. It must NOT, in particular, let app-controlled text forge a leak finding.
     */
    @Test
    public void anAppDerivedDetailContainingAPipeCannotShiftTheFields() throws Exception {
        serveCommittedApp("/pipe");
        start();
        pendingEntry = "9,1,0|1|heap: a|b|c exceeded";

        Path dir = Files.createTempDirectory("basquin-report-pipe");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/pipe");
            assertEquals("the count is unaffected by separators in the detail", 1, s.invariantCount);
            assertEquals("the cost is unaffected too", 1, s.heapDeltaKb);
            assertTrue(waitForFinding(dir, "Invariant-Remote").contains("heap: a"));
            assertEquals("app-derived text must not be able to forge a leak finding",
                    0, countFindings(dir, "Leak-Remote"));
        });
    }

    @Test
    public void theRequestIsStampedWithASaltedIdAndThePollIsNot() throws Exception {
        serveCommittedApp("/stamp");
        start();

        CoverageGuidedRun.request(base, "/stamp");

        assertNotNull("every explore request must be stamped", appSeenReqId);
        assertTrue("ids are salted with the run salt so a foreign/stale id misses honestly: "
                + appSeenReqId, appSeenReqId.startsWith(LoadRun.RUN_SALT + "-"));
        assertEquals(1, pollStampHeaders.size());
        assertNull("the poll must NEVER be stamped — a stale id on a pooled connector thread would "
                + "publish a later probe's metrics under a driver id", pollStampHeaders.get(0));
    }

    @Test
    public void targetViolationsAreReadFromTheControlSurfaceAndUnavailableIsNotZero() throws Exception {
        server.createContext("/__basquin/violations", (HttpExchange ex) -> {
            byte[] out = "1906".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
        start();

        assertEquals(1906L, CoverageGuidedRun.readTargetViolations(base));

        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = dead.getAddress().getPort();
        dead.stop(0);
        assertEquals("unavailable is -1, never a reassuring 0",
                -1L, CoverageGuidedRun.readTargetViolations("http://127.0.0.1:" + deadPort));
    }

    /** A run that could not see the majority of its own requests must not complete as "clean". */
    @Test
    public void aMissMajorityIsDetected() {
        assertFalse("no requests: nothing to judge", CoverageGuidedRun.missesAreTheMajority(0, 0));
        assertFalse(CoverageGuidedRun.missesAreTheMajority(10, 1));
        assertFalse("exactly half is not a majority", CoverageGuidedRun.missesAreTheMajority(5, 5));
        assertTrue(CoverageGuidedRun.missesAreTheMajority(4, 5));
        assertTrue("an uninstrumented target: reportMisses ~= iteration count",
                CoverageGuidedRun.missesAreTheMajority(0, 500));
    }

    /** "Lower bound" must be a field an operator and a dashboard can read, not prose in a log. */
    @Test
    public void theSummaryPublishesReportMissesAndTheLowerBoundFlag() throws Exception {
        StatusReporter.enableTrackingForTest();
        StatusReporter.recordReportMiss();
        StatusReporter.recordTargetViolations(1906L);

        String json = StatusReporter.snapshotJson();

        assertTrue("misses are published: " + json, json.contains("\"reportMisses\":"));
        assertTrue("a run with any miss cannot claim a complete finding count: " + json,
                json.contains("\"findingsLowerBound\":true"));
        assertTrue("the target-side number Task 7 compares against: " + json,
                json.contains("\"targetViolations\":1906"));
    }

    // --- helpers ---

    private interface Body { void run() throws Exception; }

    private static void withResultsDir(Path dir, Body b) throws Exception {
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            b.run();
        } finally {
            if (prior != null) System.setProperty("basquin.fuzz.resultsDir", prior);
            else System.clearProperty("basquin.fuzz.resultsDir");
        }
    }

    /** Findings are written asynchronously (TriageSink); wait briefly for one of a classification. */
    private static String waitForFinding(Path dir, String classification) throws Exception {
        for (int i = 0; i < 100; i++) {
            String text = readAll(dir);
            if (text.contains("classification=" + classification)) return text;
            Thread.sleep(30);
        }
        fail("no " + classification + " finding was ever saved — a recovered violation that is not "
                + "saved is not reported, which is the defect DD-040 exists to fix");
        return "";
    }

    private static int countFindings(Path dir, String classification) throws Exception {
        Thread.sleep(150);   // let the async writer settle before counting absences
        int n = 0;
        if (!Files.isDirectory(dir)) return 0;
        for (Path p : Files.newDirectoryStream(dir, "*.meta.txt")) {
            if (new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                    .contains("classification=" + classification)) n++;
        }
        return n;
    }

    private static String readAll(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (!Files.isDirectory(dir)) return "";
        for (Path p : Files.newDirectoryStream(dir)) {
            sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }
}
