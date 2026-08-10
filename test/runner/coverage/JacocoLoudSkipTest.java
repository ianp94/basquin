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
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-043 PR-4 decision D1: {@code JacocoCoverageProvider#sample()}'s {@code analyzeClass} used to
 * catch its exception and skip the class silently -- a class-file bytecode version JaCoCo's ASM
 * predates (0.8.12 vs. the targets' Java-25/major-69 classfiles) made EVERY supplied class
 * unanalyzable, and the silently-empty result reported a "clean" 0/0 exactly as if the app had no
 * code at all. This is the cardinal defect the 0.8.15 bump exists to prevent.
 *
 * <p>The fixture below reproduces the real failure mode, not a stand-in for it: it takes a
 * genuinely valid, compiled class file and corrupts ONLY its major-version field (offset 6-7, the
 * classfile spec's version field), which sends it through the exact ASM code path a too-old JaCoCo
 * hits on a too-new classfile ({@code IllegalArgumentException: Unsupported class file major
 * version <n>}, wrapped by {@code Analyzer#analyzeClass} as an {@code IOException}) -- verified by
 * hand against this repo's real JaCoCo 0.8.15 jar before this test was written.
 */
public class JacocoLoudSkipTest {

    /** A trivial, genuinely valid, genuinely analyzable fixture class. */
    public static final class GoodClass {
        public static int identity(int x) {
            return x;
        }
    }

    private HttpServer server;

    @Before
    public void setUp() throws IOException {
        // Minimal but wire-valid exec body: just the file header (magic + format version), no
        // session-info/execution-data blocks. Real and parseable -- ExecutionDataReader accepts it
        // and visits zero blocks -- so dumpHttpInto() succeeds ("this endpoint responded") without
        // needing a live agent; what these tests exercise is the ANALYZE side (classBytes), not the
        // dump side (that is JacocoHttpTransportTest's job).
        byte[] headerOnly = ExecutionDataWriter.getFileHeader();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coverage", exchange -> {
            exchange.sendResponseHeaders(200, headerOnly.length);
            exchange.getResponseBody().write(headerOnly);
            exchange.close();
        });
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
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

    /** {@code good}'s bytes with the major-version field (offset 6-7, big-endian) set past anything
     *  any real JaCoCo release supports -- ASM throws {@code IllegalArgumentException}, which
     *  {@code Analyzer#analyzeClass} wraps as {@code IOException}; {@code sample()}'s catch is
     *  {@code catch (Exception e)}, so either shape is caught the same way this defect always was. */
    private static byte[] corruptMajorVersion(byte[] good) {
        byte[] bad = good.clone();
        bad[6] = (byte) 0x27;
        bad[7] = (byte) 0x0F;   // major version 9999
        return bad;
    }

    @Test
    public void allClassesUnanalyzableFailsLoudlyInsteadOfReportingAShrunkDenominator() throws Exception {
        Path classesDir = Files.createTempDirectory("basquin-loudskip-allbad");
        Files.write(classesDir.resolve("GoodClass.class"), corruptMajorVersion(classBytesOf(GoodClass.class)));

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);

        try {
            provider.sample();
            fail("every supplied class file was unanalyzable -- sample() must throw, not silently "
                    + "report a 0/0 \"clean\" result (the exact silent-zero D1 exists to prevent)");
        } catch (IOException expected) {
            String msg = expected.getMessage().toLowerCase(Locale.ROOT);
            assertTrue("exception should name the silent-zero it refuses to report: " + expected.getMessage(),
                    msg.contains("silent-zero") || msg.contains("skipped"));
        }
    }

    @Test
    public void aPartialSkipStillReportsRealNumbersAndDoesNotThrow() throws Exception {
        Path classesDir = Files.createTempDirectory("basquin-loudskip-partial");
        Files.write(classesDir.resolve("Good.class"), classBytesOf(GoodClass.class));
        Files.write(classesDir.resolve("Bad.class"), corruptMajorVersion(classBytesOf(GoodClass.class)));

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        JacocoCoverageProvider.Coverage c = provider.sample();

        assertEquals("one class analyzed, one skipped -- a PARTIAL skip must not throw", 1, c.classesAnalyzed);
        assertEquals(1, c.classesSkipped);
    }

    @Test
    public void noSuppliedClassFilesAtAllDoesNotTripTheAllSkipGuard() throws Exception {
        // A directory with zero .class files is a pre-existing, different condition (nothing to
        // skip -- the D1 guard is specifically about classes that WERE supplied but couldn't be
        // analyzed) and must not be swept into the new loud-fail path.
        Path classesDir = Files.createTempDirectory("basquin-loudskip-empty");

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        JacocoCoverageProvider.Coverage c = provider.sample();

        assertEquals(0, c.classesAnalyzed);
        assertEquals(0, c.classesSkipped);
        assertFalse("no class files supplied is not the D1 all-skip condition", c.classesAnalyzed == 0 && c.classesSkipped > 0);
    }
}
