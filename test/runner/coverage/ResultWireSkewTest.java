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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-043 PR-5 Task 1 (D1): the result wire gained a fifth {@code |}-field — the disposition — and
 * this file pins BOTH directions of the resulting version skew, honestly, rather than claiming
 * either is impossible.
 *
 * <ul>
 *   <li><b>Old producer → new driver:</b> a four-field line parses exactly as it always did — the
 *       leak flag is still {@code f[3]}, and the disposition is ABSENT, never assumed.</li>
 *   <li><b>New producer → old driver:</b> the frozen pre-PR-5 parse was {@code split("\\|", 4)}
 *       with {@code "leak".equals(f[3])}; on a five-field line its {@code f[3]} reads
 *       {@code "leak|<disposition>"}, so the old driver silently records a leak FALSE-NEGATIVE.
 *       That is a documented, tested consequence of widening the wire — the alternative (a version
 *       handshake) was considered and not taken for one added field. This test is the
 *       documentation.</li>
 * </ul>
 */
public class ResultWireSkewTest {

    private HttpServer server;
    private String base;
    private volatile String pendingBody;
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private String priorPodHost;

    @Before
    public void startServer() throws IOException {
        priorPodHost = System.getProperty("basquin.report.podHost");
        System.setProperty("basquin.report.podHost", "off");   // single-target: no fan-out
        PodPollTargets.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            try {
                String body = pendingBody;
                pendingBody = null;                                  // remove-on-read
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) {
                handlerFailure.compareAndSet(null, t);
                throw t;
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
        if (priorPodHost != null) System.setProperty("basquin.report.podHost", priorPodHost);
        else System.clearProperty("basquin.report.podHost");
        PodPollTargets.resetForTest();
        assertNull("a test server handler threw", handlerFailure.get());
    }

    /** A NEW-format body (fifth field present) parses fully: counts, cost, and the leak flag —
     *  the disposition field must not displace or swallow any of them. */
    @Test
    public void aFiveFieldLineParsesWithLeakAndCostIntact() throws Exception {
        pendingBody = "12,340,0|2|latency: 300ms > 250ms||measured\n"
                    + "5,10,1|0||leak|measured";

        Path dir = Files.createTempDirectory("basquin-skew-new");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-new", "/x", 2);
            assertTrue(s.measured);
            assertEquals("counts must survive the widened parse", 2, s.invariantCount);
            assertEquals("heap deltas must survive too", 350L, s.heapDeltaKb);
            assertEquals("a leak on a five-field line must still be recovered",
                    1, AccumulatedPollTest.waitForMetas(dir, "Leak-Remote", 1).size());
            assertEquals(1, AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    /** OLD producer → NEW driver: a four-field line (no disposition) parses exactly as before —
     *  the disposition is absent, and absence must not turn a real leak into a clean sample. */
    @Test
    public void aFourFieldLineFromAnOldProducerStillParsesLeakAndCost() throws Exception {
        pendingBody = "12,340,0|1|heap: 900KB > 500KB|\n"
                    + "5,10,1|0||leak";

        Path dir = Files.createTempDirectory("basquin-skew-old");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-old", "/x", 2);
            assertTrue(s.measured);
            assertEquals(1, s.invariantCount);
            assertEquals(350L, s.heapDeltaKb);
            assertEquals("the pre-PR-5 wire must keep working against a PR-5 driver",
                    1, AccumulatedPollTest.waitForMetas(dir, "Leak-Remote", 1).size());
        });
    }

    /** A redirect chain can cross pods upgraded at different times. Exercise the real formatter
     * alongside a legacy line, including an empty fifth field, through the HTTP polling path. */
    @Test
    public void mixedVersionHopsPreserveFindingsAndCosts() throws Exception {
        pendingBody = agent.ResultStore.format(java.util.List.of(
                new agent.ResultStore.Entry("12,340,0", 2, "latency", false,
                        agent.ResultStore.DISPOSITION_MEASURED),
                new agent.ResultStore.Entry("5,10,1", 0, null, true, null)))
                + "\n7,20,0|1|legacy heap|";

        Path dir = Files.createTempDirectory("basquin-skew-mixed");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s =
                    CoverageGuidedRun.pollResult(base, "salt-mixed", "/redirect", 3);
            assertTrue("all three hop records were recovered", s.measured);
            assertEquals(3, s.invariantCount);
            assertEquals(370L, s.heapDeltaKb);
            assertEquals(1, AccumulatedPollTest.waitForMetas(dir, "Leak-Remote", 1).size());
            assertEquals(2, AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 2).size());
        });
    }

    @Test
    public void unmeasuredHeapDoesNotEraseARealLatencyFinding() throws Exception {
        pendingBody = "12,99999,0|1|latency: slow||UNMEASURED\n"
                + "5,10,0|0|||measured";
        Path dir = Files.createTempDirectory("basquin-unmeasured-heap");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s =
                    CoverageGuidedRun.pollResult(base, "salt-unmeasured", "/x", 2);
            assertTrue("the report was recovered", s.measured);
            assertFalse("the chain's heap total is incomplete", s.heapMeasured);
            assertFalse("partial heap must not be ranked as a complete cost",
                    CoverageGuidedRun.scoreable(true, s));
            assertEquals("exclude the contaminated hop", 10L, s.heapDeltaKb);
            assertEquals(1, s.invariantCount);
            assertEquals(1, AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    @Test
    public void unknownAndDisconnectedDispositionsCannotEnterCostRanking() {
        for (String disposition : new String[]{"disconnected", "future-disposition"}) {
            pendingBody = "12,99999,1|0|||" + disposition;
            CoverageGuidedRun.CostSample s =
                    CoverageGuidedRun.pollResult(base, "salt-" + disposition, "/x", 1);
            assertTrue(s.measured);
            assertFalse(s.heapMeasured);
            assertEquals(0L, s.heapDeltaKb);
            assertFalse(CoverageGuidedRun.scoreable(true, s));
        }
    }

    /**
     * NEW producer → OLD driver, the accepted loss: this replicates the frozen pre-PR-5 parse
     * verbatim ({@code CoverageGuidedRun}'s former {@code split("\\|", 4)} +
     * {@code "leak".equals(f[3].trim())}) against a five-field line and asserts the OUTCOME —
     * a silent leak false-negative. If this test ever fails, the wire format changed in a way
     * that alters the documented skew semantics, and D1's decision record must be revisited.
     */
    @Test
    public void theFrozenOldParseReadsAFiveFieldLeakLineAsClean() {
        String newFormatLine = "5,10,1|0||leak|measured";

        String[] f = newFormatLine.split("\\|", 4);   // the frozen old limit
        assertEquals("the old driver's f[3] swallows the new field", "leak|measured", f[3].trim());
        assertFalse("DOCUMENTED CONSEQUENCE (D1): an old driver reading a new producer drops the "
                        + "leak flag — a false-negative, deliberately accepted over a version "
                        + "handshake for one added field",
                "leak".equals(f[3].trim()));

        // The rest of the old parse is UNAFFECTED by the widening: count and cost still read.
        assertEquals(0, Integer.parseInt(f[1].trim()));
        assertEquals("5,10,1", f[0]);
    }
}
