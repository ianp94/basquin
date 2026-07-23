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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * DD-039 Task 6: the residual accounting the spec's Consequences promised and the plan left inert
 * until the driver actually threaded it.
 *
 * <p>Two mechanisms, both silent no-ops if the wiring is missing:
 * <ul>
 *   <li><b>{@code latMs} excludes the forced poll.</b> {@code reconcile} runs inside {@code request()}'s
 *       own {@code finally}, so a {@code hops > 1} input always polls INSIDE the wall-clock window the
 *       caller measures at {@code :302}. That poll can cost up to {@code POLL_READ_TIMEOUT_MS}
 *       (4000 ms), and {@code latMs} feeds {@link CostModel#score} — so without the subtraction every
 *       redirecting input out-ranks every non-redirecting one purely for being polled, the exact
 *       ranking distortion DD-040 set out to repair.</li>
 *   <li><b>The corpus entry records its hop count.</b> {@code CostSample.hops} rides through
 *       {@code CostCorpus.consider} onto {@code CorpusEntry.hops}, so a cost-ranked corpus records that
 *       a multi-hop cost is an explore-side measurement (load replays the same input with follow OFF
 *       and fires one hop, DD-038). Every construction site defaults {@code hops = 1}; unless
 *       {@code request()} threads its REAL hop count into the sample it returns, {@code sample.hops} is
 *       always 1 and this is inert — which is why the 3-hop assertion below drives the real request
 *       path, not a hand-built sample.</li>
 * </ul>
 */
public class HopAccountingTest {

    private HttpServer server;
    private String base;
    private volatile String pendingBody;
    private String priorPodHost;

    // -------------------------------------------------------------- wireLatency: pure, no server

    /** The subtraction, and its floor. A poll longer than the whole wall-clock (a clock hiccup, or a
     *  measurement taken across a GC pause) must not produce a NEGATIVE latency fed to the cost model. */
    @Test
    public void wireLatencyIsElapsedMinusPollFlooredAtZero() {
        assertEquals("wire time is elapsed minus the poll window", 70L,
                CoverageGuidedRun.wireLatency(100L, 30L));
        assertEquals("a single-hop input polls nothing, so latMs is unchanged", 50L,
                CoverageGuidedRun.wireLatency(50L, 0L));
        assertEquals("the poll cannot exceed the wall-clock in practice, but the floor must hold", 0L,
                CoverageGuidedRun.wireLatency(30L, 100L));
        assertEquals(0L, CoverageGuidedRun.wireLatency(0L, 0L));
    }

    // -------------------------------------------------------------------- lastPollMs, on the wire

    @Before
    public void setUp() throws IOException {
        CoverageGuidedRun.resetSession();
        priorPodHost = System.getProperty("basquin.report.podHost");
        System.setProperty("basquin.report.podHost", "off");
        PodPollTargets.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            String body = pendingBody;
            pendingBody = null;                                 // remove-on-read
            try { Thread.sleep(4); } catch (InterruptedException ignored) { }  // guarantee lastPollMs > 0
            byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        CoverageGuidedRun.resetSession();
        if (priorPodHost != null) System.setProperty("basquin.report.podHost", priorPodHost);
        else System.clearProperty("basquin.report.podHost");
        PodPollTargets.resetForTest();
    }

    private void start() {
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A 3xx to {@code target}. */
    private void redirect(String path, int code, String target) {
        server.createContext(path, (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Location", target);
            byte[] out = "<html>Moved</html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
    }

    /** An ordinary 200. */
    private void page(String path, String body) {
        server.createContext(path, (HttpExchange ex) -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
    }

    /** A single hop that reported through the COST header — the fast path. It must NOT poll, so no
     *  poll time is recorded and {@code latMs} is the raw wall-clock. */
    @Test
    public void aSingleHopHeaderFastPathRecordsNoPollTime() throws Exception {
        server.createContext("/plain", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
            byte[] out = "hello".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.getResponseBody().close();
        });
        start();

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/plain");

        assertTrue(s.measured);
        assertEquals("a single hop with a cost header never polls, so nothing is subtracted from latMs",
                0L, CoverageGuidedRun.lastPollMs);
    }

    /** A redirecting input ALWAYS polls ({@code hops > 1}), and that poll's wall-time is captured so
     *  the caller can subtract it. The result handler sleeps 4 ms, so a lost subtraction is the
     *  difference between latMs and latMs+4 on every redirecting input — enough to reorder the corpus. */
    @Test
    public void aMultiHopPollRecordsItsWallTime() throws Exception {
        redirect("/x", 302, "/y");
        page("/y", "landing");
        start();
        pendingBody = "10,100,0|0||\n10,100,0|0||";       // two well-formed, non-breaching hops

        Path dir = Files.createTempDirectory("basquin-dd039-lastpoll");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/x");
            assertTrue("the chain was measured off the poll", s.measured);
            assertTrue("the forced multi-hop poll's time must be captured for subtraction, was "
                    + CoverageGuidedRun.lastPollMs + " ms", CoverageGuidedRun.lastPollMs > 0L);
        });
    }

    // ------------------------------------------------------------------ hops onto the CorpusEntry

    /** The whole point of Step 2: a 3-hop input records {@code hops == 3} on its {@code CorpusEntry}.
     *  Driven through the REAL request path — {@code request()} counts three requests, stamps them
     *  under one id, polls once, and returns a sample carrying {@code hops = 3}; {@code consider}
     *  threads that onto the entry. If any link defaults to 1 this reads {@code 1}, which is the spec
     *  Consequence going silently unimplemented. */
    @Test
    public void aThreeHopInputRecordsItsHopCountOnTheCorpusEntry() throws Exception {
        redirect("/a", 302, "/b");
        redirect("/b", 302, "/c");
        page("/c", "done");
        start();
        pendingBody = "10,100,0|0||\n10,100,0|0||\n10,100,0|0||";   // three well-formed hops

        Path dir = Files.createTempDirectory("basquin-dd039-hops");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/a");
            assertTrue(s.measured);
            assertEquals("request() must thread its real hop count into the sample it returns",
                    3, s.hops);

            CostCorpus corpus = new CostCorpus(List.of(), true);
            corpus.consider("/a", 5.0, 12L, s.heapDeltaKb, s.threadDelta, s.invariantCount, true, s.hops);
            List<CorpusEntry> ranked = corpus.snapshotByCost();
            assertEquals(1, ranked.size());
            assertEquals("the multi-hop count rides onto the retained corpus entry", 3, ranked.get(0).hops);
        });
    }

    /** The default the ~20 existing construction sites rely on: an ordinary input is one hop, and the
     *  7-arg {@code consider} still records {@code hops == 1}. */
    @Test
    public void anOrdinaryInputIsOneHop() {
        CostCorpus corpus = new CostCorpus(List.of(), true);
        corpus.consider("/plain", 1.0, 5L, 0L, 0, 0, true);
        assertEquals(1, corpus.snapshotByCost().get(0).hops);
    }
}
