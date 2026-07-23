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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-040 §A.6: the result poll must reach the pod that served the request.
 *
 * <p>The defect this file exists to make unreachable is subtle because it is invisible at one
 * replica. The result store is per-JVM. The driver's {@code baseURL} is a Service DNS name, which is
 * a single virtual IP load-balancing every new connection across all endpoints — so a poll through
 * it reaches the pod holding the entry with probability 1/N. It never returns another pod's data; it
 * returns {@code miss}. So the reporting channel does not break loudly when the target scales, it
 * quietly reports (N-1)/N of every campaign as unmeasured while every single-replica test still
 * passes. That is why "poll the VIP" is wrong rather than merely suboptimal.
 *
 * <p>Two real pods cannot be stood up in a unit test, so two independent target instances are
 * simulated exactly where independence matters: two {@link HttpServer}s, each with its <b>own</b>
 * id-keyed store, each removing on read. An entry written into one is unreachable from the other,
 * which is precisely the property the Service VIP violates.
 */
public class MultiReplicaPollTest {

    /** One simulated target JVM: its own HTTP endpoint and its own per-JVM result store. */
    private static final class Pod {
        final HttpServer server;
        final String base;
        final String hostPort;
        final Map<String, String> store = new ConcurrentHashMap<>();
        final AtomicInteger polls = new AtomicInteger();

        Pod() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(null);
            int port = server.getAddress().getPort();
            base = "http://127.0.0.1:" + port;
            hostPort = "127.0.0.1:" + port;
            server.createContext("/__basquin/result", (HttpExchange ex) -> {
                polls.incrementAndGet();
                String q = ex.getRequestURI().getQuery();
                String id = q != null && q.startsWith("id=") ? q.substring(3) : null;
                // Remove-on-read, per-JVM: this pod can only ever answer for ids IT served.
                String body = id == null ? null : store.remove(id);
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            });
            server.start();
        }

        /** The app half: a committed (header-less) response, whose measurements go to THIS store. */
        void serveCommitted(String path, String entry) {
            server.createContext(path, (HttpExchange ex) -> {
                String id = ex.getRequestHeaders().getFirst("X-Basquin-Req");
                if (id != null) store.put(id, entry);      // what the boundary does, in this JVM only
                byte[] out = new byte[16384];              // big enough that the response has committed
                java.util.Arrays.fill(out, (byte) 'x');
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            });
        }

        void stop() { server.stop(0); }
    }

    private Pod podA;
    private Pod podB;
    private String priorPodHost;
    private String priorJacoco;

    @Before
    public void setUp() throws IOException {
        podA = new Pod();
        podB = new Pod();
        priorPodHost = System.getProperty("basquin.report.podHost");
        priorJacoco = System.getProperty("basquin.coverage.jacoco");
        PodPollTargets.resetForTest();
    }

    @After
    public void tearDown() {
        if (podA != null) podA.stop();
        if (podB != null) podB.stop();
        restore("basquin.report.podHost", priorPodHost);
        restore("basquin.coverage.jacoco", priorJacoco);
        PodPollTargets.resetForTest();
    }

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }

    private void podHost(String value) {
        System.setProperty("basquin.report.podHost", value);
        PodPollTargets.resetForTest();
    }

    // ---------------------------------------------------------------- the core multi-replica case

    /**
     * THE test. A poll aimed at a pod that did not serve the request must miss — it must not, and
     * cannot, be answered out of another pod's store. This is what a Service-VIP poll does N-1 times
     * out of N, and it is counted as a miss rather than silently swallowed.
     */
    @Test
    public void aPollAimedAtTheWrongPodMissesAndIsCounted() {
        podB.store.put("salt-7", "719,120,0|2|latency: 719ms > 250ms|");   // only pod B has it
        podHost(podA.hostPort);                                            // ...and we ask pod A
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(podA.base, "salt-7", "/x");

        assertFalse("the wrong pod cannot answer for an entry it never stored", s.measured);
        assertEquals("and that is a counted miss, not a silent zero",
                before + 1, CoverageGuidedRun.reportMisses);
        assertEquals("pod A was asked", 1, podA.polls.get());
        assertEquals("pod B was never asked", 0, podB.polls.get());
        assertTrue("pod B still holds its entry", podB.store.containsKey("salt-7"));
    }

    /** The same poll aimed correctly succeeds — so the miss above is about WHICH pod, nothing else. */
    @Test
    public void aPollAimedAtTheServingPodSucceeds() {
        podB.store.put("salt-7", "719,120,0|2|latency: 719ms > 250ms|");
        podHost(podB.hostPort);
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(podB.base, "salt-7", "/x");

        assertTrue("the serving pod has the entry", s.measured);
        assertEquals(2, s.invariantCount);
        assertEquals(120, s.heapDeltaKb);
        assertEquals("no miss", before, CoverageGuidedRun.reportMisses);
    }

    /**
     * With both pods addressable the driver finds the entry wherever it lives, including behind a
     * pod that answers {@code miss} first. A run-salted id plus remove-on-read is what makes asking
     * several pods safe: at most one can hold it, so the fan-out cannot return the wrong data.
     */
    @Test
    public void theFanOutFindsTheEntryBehindAPodThatMisses() {
        podB.store.put("salt-9", "31,7,0|1|heap: 900KB > 500KB|");
        podHost(podA.hostPort + "," + podB.hostPort);      // A first, and A does not have it
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(podA.base, "salt-9", "/x");

        assertTrue("a miss from the first pod must not end the search", s.measured);
        assertEquals(1, s.invariantCount);
        assertEquals(before, CoverageGuidedRun.reportMisses);
        assertEquals(1, podA.polls.get());
        assertEquals(1, podB.polls.get());
    }

    /** End to end: the app request goes to one pod, and the recovered finding is attributed to it. */
    @Test
    public void aCommittedResponseIsRecoveredFromTheServingPodAndAttributedToIt() throws Exception {
        podA.serveCommitted("/big", "719,120,0|2|latency: 719ms > 250ms|");
        podB.serveCommitted("/big", "0,0,0|0||");     // pod B would answer 0 violations, if asked
        podHost(podB.hostPort + "," + podA.hostPort); // B first: the fan-out must get past it

        Path dir = Files.createTempDirectory("basquin-multireplica");
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(podA.base, "/big");

            assertTrue("the measurement must be recovered from the pod that served the request",
                    s.measured);
            assertEquals(2, s.invariantCount);
            String text = waitForFinding(dir, "Invariant-Remote");
            assertTrue("the recovered violation is filed as a finding", text.contains("count=2"));
            assertTrue("and attributed to the pod it came off, so a two-replica reconciliation "
                    + "against the pods' own logs is possible: " + text,
                    text.contains("pod=" + podA.base));
        } finally {
            restore("basquin.fuzz.resultsDir", prior);
        }
    }

    /**
     * The other half of §A.6's attribution: when the response DID carry headers, the serving pod's
     * own {@code X-Basquin-Pod} names it. That is the only path on which the header is ever visible
     * — a committed response carries no headers at all, which is exactly when the poll runs — so it
     * is recorded here and never routed on.
     */
    @Test
    public void aHeaderReportedViolationIsAttributedToTheHeaderPod() throws Exception {
        podA.server.createContext("/hdr", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
            ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "1");
            ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "latency: 719ms > 250ms");
            ex.getResponseHeaders().add("X-Basquin-Pod", "roller-7d9c4-abcde");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().close();
        });

        Path dir = Files.createTempDirectory("basquin-hdr-pod");
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            CoverageGuidedRun.request(podA.base, "/hdr");
            String text = waitForFinding(dir, "Invariant-Remote");
            assertTrue("the finding must name the pod that reported it: " + text,
                    text.contains("pod=roller-7d9c4-abcde"));
            assertEquals("a header-reported violation is never also polled for", 0, podA.polls.get());
        } finally {
            restore("basquin.fuzz.resultsDir", prior);
        }
    }

    // ---------------------------------------------------------------- resolution and degradation

    /**
     * §A.6's third clause: where the driver cannot resolve a pod address it degrades to a miss. It
     * must NOT fall back to the VIP — that would restore the 1-in-N lottery behind a poll that looks
     * like it worked.
     */
    @Test
    public void anUnresolvablePodSourceDegradesToACountedMiss() {
        podA.store.put("salt-1", "1,2,0|3||");     // reachable via the VIP, if we cheated
        podHost("no-such-host.invalid");
        long before = CoverageGuidedRun.reportMisses;

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(podA.base, "salt-1", "/x");

        assertFalse(s.measured);
        assertEquals(before + 1, CoverageGuidedRun.reportMisses);
        assertEquals("no pod was polled at all — including the VIP", 0, podA.polls.get());
        assertTrue(PodPollTargets.pollBases(podA.base).isEmpty());
    }

    /** The single-replica path — every other test in this repo — is left exactly as it was. */
    @Test
    public void aSingleInstanceTargetIsPolledThroughItsBaseUrlUnchanged() {
        System.clearProperty("basquin.report.podHost");
        System.clearProperty("basquin.coverage.jacoco");
        PodPollTargets.resetForTest();

        assertEquals("one instance: the base URL is returned untouched, no host rewrite",
                java.util.Collections.singletonList(podA.base),
                PodPollTargets.pollBases(podA.base));
    }

    /** A derived source that resolves to one address is a single instance; nothing is rewritten. */
    @Test
    public void aDerivedSourceResolvingToOneAddressIsNotRewritten() {
        System.clearProperty("basquin.report.podHost");
        System.setProperty("basquin.coverage.jacoco", "127.0.0.1:6300");
        PodPollTargets.resetForTest();

        assertEquals(java.util.Collections.singletonList(podA.base),
                PodPollTargets.pollBases(podA.base));
    }

    /** An explicit {@code off} keeps the VIP path for an operator who wants it. */
    @Test
    public void podAddressingCanBeTurnedOff() {
        podHost("off");
        assertEquals(java.util.Collections.singletonList(podA.base),
                PodPollTargets.pollBases(podA.base));
    }

    /** A rewritten poll target keeps scheme and context path; only the authority changes. */
    @Test
    public void rewritingKeepsTheContextPath() {
        assertEquals("http://10.42.0.7:8080/roller",
                PodPollTargets.withAddress("http://roller.demo.svc:8080/roller", "10.42.0.7", -1));
        assertEquals("an entry's own port wins, for a Service whose port differs from the container's",
                "http://10.42.0.7:9090/roller",
                PodPollTargets.withAddress("http://roller.demo.svc:8080/roller", "10.42.0.7", 9090));
        assertEquals("an IPv6 pod IP must be bracketed or the URL is unparseable",
                "http://[fd00::1]:8080/roller",
                PodPollTargets.withAddress("http://roller.demo.svc:8080/roller", "fd00::1", -1));
    }

    /** Two pods resolved from one source produce two distinct candidates, in order. */
    @Test
    public void twoPodAddressesProduceTwoCandidates() {
        podHost(podA.hostPort + "," + podB.hostPort);
        List<String> bases = new ArrayList<>(PodPollTargets.pollBases(podA.base));
        assertEquals(2, bases.size());
        assertEquals(podA.base, bases.get(0));
        assertEquals(podB.base, bases.get(1));
    }

    // --- helpers ---

    /** Findings are written asynchronously (TriageSink); wait briefly for one of a classification. */
    private static String waitForFinding(Path dir, String classification) throws Exception {
        for (int i = 0; i < 100; i++) {
            StringBuilder sb = new StringBuilder();
            if (Files.isDirectory(dir)) {
                for (Path p : Files.newDirectoryStream(dir)) {
                    sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
                }
            }
            if (sb.indexOf("classification=" + classification) >= 0) return sb.toString();
            Thread.sleep(30);
        }
        fail("no " + classification + " finding was saved");
        return "";
    }
}
