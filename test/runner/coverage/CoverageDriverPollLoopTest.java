package runner.coverage;

import com.sun.net.httpserver.HttpServer;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * F1 (approver finding on DD-043 PR-4): {@code CoverageDriver}'s background poll loop used to catch
 * {@code Throwable} broadly and say nothing — a persistent (D1) all-skip would spin forever,
 * silently re-attempting an analysis that can never succeed (the provider's class bytes never
 * change across a run), with the live status panel stuck at a coverage that looks like a real,
 * flat zero rather than a defect.
 *
 * <p>{@code pollLoop} now stops (rather than sleeping {@code intervalMs} and trying again) the
 * moment it sees {@code JacocoCoverageProvider.CoverageUnmeasurableException}, and logs once before
 * doing so. This test proves BOTH halves: it passes a deliberately huge {@code intervalMs} so a
 * loop that failed to distinguish the all-skip from an ordinary blip would sleep for that whole
 * interval before ever getting a second chance to do anything — the JUnit test timeout below would
 * then fail the test — and it captures stderr to confirm the stop is logged, not silent.
 */
public class CoverageDriverPollLoopTest {

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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] corruptMajorVersion(byte[] good) {
        byte[] bad = good.clone();
        bad[6] = (byte) 0x27;
        bad[7] = (byte) 0x0F;   // major version 9999
        return bad;
    }

    @Test(timeout = 10_000)
    public void allSkipStopsThePollLoopAndLogsInsteadOfSpinningSilentlyForever() throws Exception {
        Path classesDir = Files.createTempDirectory("basquin-driver-allbad");
        Files.write(classesDir.resolve("GoodClass.class"), corruptMajorVersion(classBytesOf(GoodClass.class)));
        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);

        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            // A huge interval: if pollLoop failed to distinguish the all-skip from an ordinary blip
            // it would fall through to Thread.sleep(1_000_000L) before ever looping again, and the
            // 10s @Test timeout above would fail this test -- returning promptly is itself the
            // assertion that the loop stopped rather than silently spinning.
            CoverageDriver.pollLoop(provider, 1_000_000L);
        } finally {
            System.setErr(realErr);
        }

        String logged = captured.toString("UTF-8").toLowerCase(Locale.ROOT);
        assertTrue("the all-skip must be logged once, not swallowed silently: " + logged,
                logged.contains("unmeasurable") || logged.contains("silent-zero"));
    }
}
