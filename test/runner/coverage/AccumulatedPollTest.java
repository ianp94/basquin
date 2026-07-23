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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-039 §4b, driver half: the store now returns N hops under one id, and the driver must READ all
 * of them.
 *
 * <p>The defect this file makes unreachable is the one that would have made DD-039 close none of
 * DD-040's 189-violation gap while every other test passed: {@code fetchResult} appended each line
 * without its terminator, so an N-hop body arrived as one concatenated string and
 * {@code split("\\|", 4)} read hop 0's count and silently discarded hops 1..N-1.
 */
public class AccumulatedPollTest {

    private HttpServer server;
    private String base;
    private volatile String pendingBody;
    private final AtomicInteger pollHits = new AtomicInteger();
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
                pollHits.incrementAndGet();
                String body = pendingBody;
                pendingBody = null;                                  // remove-on-read
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) {
                // NEVER swallow: a handler that dies mid-response would let a truncated read pass.
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

    /**
     * THE test. Three hops accumulated under one id must produce THREE Invariant-Remote records with
     * distinct hop numbers and a summed count — not one record carrying hop 0's number.
     *
     * <p>Remove the '\n' from fetchResult, or the per-line parse from pollResult, and this fails with
     * "expected:<3> but was:<1>" and a CostSample of 2 instead of 6. That single assertion is the
     * difference between closing DD-040's 189-violation gap and closing none of it.
     */
    @Test
    public void aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers() throws Exception {
        pendingBody = "12,340,0|2|latency: 300ms > 250ms|\n"
                    + "5,10,0|1|heap: 900KB > 500KB|\n"
                    + "900,4096,2|3|latency: 900ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-accum-3hop");
        withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-3hop", "/login", 3);

            assertTrue(s.measured);
            assertEquals("the input's TOTAL violations across the chain, per spec §4", 6, s.invariantCount);
            assertEquals("heap deltas are SUMMED across hops", 340L + 10L + 4096L, s.heapDeltaKb);
            assertEquals("thread deltas are summed too", 0 + 0 + 2, s.threadDelta);

            List<String> metas = waitForMetas(dir, "Invariant-Remote", 3);
            assertEquals("one record per breaching hop — a single summed record reads "
                    + "'6 violations on POST /login' when 3 were the dashboard render", 3, metas.size());
            assertTrue(joined(metas).contains("hop=0"));
            assertTrue(joined(metas).contains("hop=1"));
            assertTrue(joined(metas).contains("hop=2"));
            assertTrue("each record carries ITS OWN hop's detail",
                    joined(metas).contains("heap: 900KB > 500KB"));
            assertTrue("labelled with the raw step, never a hop URL (DD-036)",
                    joined(metas).contains("route=/login"));
        });
    }

    /** A clean hop in the middle of a chain contributes cost but no finding. */
    @Test
    public void onlyBreachingHopsProduceRecords() throws Exception {
        pendingBody = "12,340,0|0||\n5,10,0|2|boom|";

        Path dir = Files.createTempDirectory("basquin-accum-clean");
        withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-mixed", "/x", 2);
            assertEquals(2, s.invariantCount);
            assertEquals(350L, s.heapDeltaKb);
            List<String> metas = waitForMetas(dir, "Invariant-Remote", 1);
            assertEquals("a count=0 hop must not file a finding", 1, metas.size());
            assertTrue("and the record must name the hop that actually breached",
                    metas.get(0).contains("hop=1"));
        });
    }

    /** A leak on ANY hop of the chain is a leak for the input. */
    @Test
    public void aLeakOnAnyHopIsRecovered() throws Exception {
        pendingBody = "12,340,0|0||\n5,10,1|0||leak";
        Path dir = Files.createTempDirectory("basquin-accum-leak");
        withResultsDirFor(dir, () -> {
            CoverageGuidedRun.pollResult(base, "salt-leak", "/leaky", 2);
            assertEquals(1, waitForMetas(dir, "Leak-Remote", 1).size());
        });
    }

    /** Regression guard: the single-hop path is byte-identical to DD-040's. */
    @Test
    public void aSingleHopBodyBehavesExactlyAsBefore() throws Exception {
        pendingBody = "719,120,0|2|latency: 719ms > 250ms|";
        Path dir = Files.createTempDirectory("basquin-accum-1hop");
        withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-1hop", "/big");
            assertTrue(s.measured);
            assertEquals(2, s.invariantCount);
            assertEquals(120L, s.heapDeltaKb);
            assertEquals(1, waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    /** An unparseable body is a miss, never a measured zero — the degeneration DD-040 exists to stop. */
    @Test
    public void anUnparseableBodyIsAMissNotAMeasuredZero() {
        pendingBody = "not|a|number|at-all";     // f[1] is not an int on any line
        long before = CoverageGuidedRun.reportMisses;
        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-bad", "/x", 2);
        assertFalse(s.measured);
        assertEquals(before + 1, CoverageGuidedRun.reportMisses);
    }

    /** A truncated tail line must be discarded by its own parse, not shift the fields of a good one. */
    @Test
    public void aTruncatedTailLineIsDiscardedNotMisparsed() throws Exception {
        pendingBody = "12,340,0|2|ok|\n5,10,0";     // second line has 2 '|'-free fields → f.length < 2
        Path dir = Files.createTempDirectory("basquin-accum-trunc");
        withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-trunc", "/x", 2);
            assertTrue(s.measured);
            assertEquals("only the well-formed hop counts", 2, s.invariantCount);
            assertEquals(1, waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    // --- helpers (no production code, no sleeps beyond a bounded poll) ---

    /** Package-private (was private): ExploreRedirectTest (DD-039 Task 4) and Task 5 drive a server
     *  body through this same helper, so the results-dir plumbing is written once, not copied. */
    interface Body { void run() throws Exception; }

    static void withResultsDirFor(Path dir, Body b) throws Exception {
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try { b.run(); } finally {
            if (prior != null) System.setProperty("basquin.fuzz.resultsDir", prior);
            else System.clearProperty("basquin.fuzz.resultsDir");
        }
    }

    /**
     * Poll the results dir until EXACTLY-OR-MORE-THAN n .meta.txt files of a classification exist,
     * then settle briefly and return them.
     *
     * <p>Written locally rather than reusing ExploreCorrelationTest.waitAndReadAll (which is
     * {@code private static} at {@code :210} and returns as soon as ONE file appears — right for its
     * own "no token anywhere" assertion, wrong for a count) and rather than draining TriageSink
     * (whose public surface is {@code ensureStarted()}/{@code submit()} only, and whose consumer
     * {@code take()}s a task off the queue BEFORE running it, so the queue can be empty while a write
     * is still in flight).
     */
    static List<String> waitForMetas(Path dir, String classification, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            found = readMetas(dir, classification);
            if (found.size() >= n) break;
            Thread.sleep(25);
        }
        if (found.size() < n) {
            fail("expected " + n + " " + classification + " record(s) in " + dir + ", saw "
                    + found.size() + " — a recovered violation that is not SAVED is not reported, "
                    + "which is the defect DD-040 exists to fix");
        }
        Thread.sleep(150);          // settle, so an EXTRA record (a double-save) is caught too
        return readMetas(dir, classification);
    }

    private static List<String> readMetas(Path dir, String classification) throws Exception {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        for (Path p : Files.newDirectoryStream(dir, "*.meta.txt")) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (text.contains("classification=" + classification)) out.add(text);
        }
        return out;
    }

    private static String joined(List<String> metas) { return String.join("\n", metas); }
}
