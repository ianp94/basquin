package runner.coverage;

import com.sun.net.httpserver.HttpServer;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * F1 (approver finding on DD-043 PR-4): {@code JacocoCoverageProvider#sample()} already fails
 * loudly on an all-skip via {@link JacocoCoverageProvider.CoverageUnmeasurableException} (D1, see
 * {@link JacocoLoudSkipTest}), but the consumer, {@code CoverageGuidedRun#sampleCoverage}, used to
 * catch {@code Throwable} broadly and silently return 0 with no log — the exact silent-zero D1
 * exists to prevent, recurring one layer up: a run would print a clean {@code coverage=0/0} with
 * nothing in the logs to explain it.
 *
 * <p>These tests exercise the consumer directly, against a REAL {@link JacocoCoverageProvider}
 * (the same all-skip fixture as {@code JacocoLoudSkipTest}: a genuinely valid class file with only
 * its major-version field corrupted) — not a mock — so what's proven is the actual wiring between
 * the provider's exception type and the consumer's catch, not a stand-in for it.
 */
public class CoverageGuidedRunSampleCoverageTest {

    /** A trivial, genuinely valid, genuinely analyzable fixture class (mirrors JacocoLoudSkipTest). */
    public static final class GoodClass {
        public static int identity(int x) {
            return x;
        }
    }

    private HttpServer server;

    @Before
    public void setUp() throws IOException {
        byte[] headerOnly = ExecutionDataWriter.getFileHeader();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coverage", exchange -> {
            exchange.sendResponseHeaders(200, headerOnly.length);
            exchange.getResponseBody().write(headerOnly);
            exchange.close();
        });
        server.start();
        // coverageUnmeasurable is static/permanent-once-true by design (see its javadoc) -- reset
        // before AND after so no other test in the suite's shared JVM observes a flag this test set
        // (the same caveat CoverageGuidedRun#resetSession's javadoc documents for sessionCookie).
        CoverageGuidedRun.coverageUnmeasurable = false;
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        CoverageGuidedRun.coverageUnmeasurable = false;
    }

    private String coverageUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/coverage";
    }

    private static byte[] classBytesOf(Class<?> c) throws IOException {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            assertTrue("fixture class resource must be found: " + resource, in != null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Same corruption technique as JacocoLoudSkipTest: only the major-version field, offset 6-7. */
    private static byte[] corruptMajorVersion(byte[] good) {
        byte[] bad = good.clone();
        bad[6] = (byte) 0x27;
        bad[7] = (byte) 0x0F;   // major version 9999
        return bad;
    }

    @Test
    public void persistentAllSkipSurfacesInsteadOfSilentlyReadingAsNoNewCoverage() throws Exception {
        Path classesDir = Files.createTempDirectory("basquin-consumer-allbad");
        Files.write(classesDir.resolve("GoodClass.class"), corruptMajorVersion(classBytesOf(GoodClass.class)));
        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);

        assertFalse(CoverageGuidedRun.coverageUnmeasurable);
        long covered = CoverageGuidedRun.sampleCoverage(provider);

        assertEquals("an unmeasurable sample must still read as 0 covered (never negative/exceptional)",
                0, covered);
        assertTrue("the consumer must flip its unmeasurable flag rather than swallow the D1 "
                + "all-skip the way a plain agent blip is swallowed", CoverageGuidedRun.coverageUnmeasurable);

        // And it STAYS surfaced: a second call must not quietly clear the flag -- the provider's
        // class bytes never change across a run, so the underlying condition cannot resolve itself.
        long secondCovered = CoverageGuidedRun.sampleCoverage(provider);
        assertEquals(0, secondCovered);
        assertTrue(CoverageGuidedRun.coverageUnmeasurable);
    }

    @Test
    public void aSingleTransientSampleFailureIsToleratedAndDoesNotFlipUnmeasurable() throws Exception {
        // A provider pointed at an endpoint nothing is listening on: sample() throws a plain
        // IOException ("no JaCoCo agent responded") -- the shape of a restarting pod or a one-off
        // connection error, NOT the D1 all-skip. This must still read as "no new coverage" and must
        // NOT trip the unmeasurable flag, so one blip cannot end a campaign.
        Path classesDir = Files.createTempDirectory("basquin-consumer-blip");
        Files.write(classesDir.resolve("GoodClass.class"), classBytesOf(GoodClass.class));
        JacocoCoverageProvider blipped = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints("http://127.0.0.1:1/coverage"), classesDir);

        assertFalse(CoverageGuidedRun.coverageUnmeasurable);
        long covered = CoverageGuidedRun.sampleCoverage(blipped);

        assertEquals(0, covered);
        assertFalse("a transient blip must not be mistaken for the deterministic D1 all-skip",
                CoverageGuidedRun.coverageUnmeasurable);

        // The campaign continues: the SAME kind of call against a real, healthy agent still works,
        // proving the earlier blip left no latched bad state behind (only a genuine all-skip should).
        JacocoCoverageProvider healthy = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        long recoveredCovered = CoverageGuidedRun.sampleCoverage(healthy);
        assertEquals(0, recoveredCovered); // header-only exec body -> nothing executed, but no throw
        assertFalse(CoverageGuidedRun.coverageUnmeasurable);
    }
}
