package runner.coverage;

import com.sun.net.httpserver.HttpServer;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-043 PR-4 Task 3: the runner's HTTP coverage transport. {@code JacocoCoverageProvider} grows a
 * second transport alongside the tcpserver protocol -- a plain GET whose 200 body IS raw
 * exec-format bytes, read with the base {@code ExecutionDataReader} -- and this test drives it with
 * REAL exec data (instrument {@link TinyProbe}, run it, collect from a live {@link RuntimeData}),
 * not a hand-typed byte array, so a wire-format regression would actually be caught.
 *
 * <p>The canned bytes come from a local {@code com.sun.net.httpserver.HttpServer} stub -- there is
 * no live JaCoCo agent involved, matching how this transport will actually be driven against
 * {@code basquin-quarkus}'s {@code /__basquin/coverage} route (server-side wiring is a different
 * task; this exercises the client/parse side of the same wire shape, per the Task-0 seam-spike's
 * documented {@code 01 c0 c0 10} exec magic).
 *
 * <p><b>The seam-spike's central finding, reproduced deterministically.</b> {@code RuntimeData}
 * assigns each dump its own session timestamp, so two dumps of IDENTICAL probe data serialize to
 * DIFFERENT bytes -- exactly the trap the spike hit ("t2 is byte-identical in size to t1, differs in
 * sha only through the exec session-info timestamps"). {@link #identicalCoverageAcrossTwoByteDifferentButLogicallyIdenticalDumps()}
 * proves {@code sample()} is immune: it parses entries (class ids + probe arrays), never diffs raw
 * response bytes, so two responses that differ only in session metadata analyze to the SAME
 * covered/total.
 */
public class JacocoHttpTransportTest {

    /** Two branch-bearing methods so partial coverage (one called, one not) is meaningful. */
    public static final class TinyProbe {
        public static int methodA(int x) {
            if (x > 0) {
                return x + 1;
            }
            return x - 1;
        }

        public static int methodB(int x) {
            if (x > 0) {
                return x * 2;
            }
            return x * -2;
        }
    }

    /** Loads instrumented bytes under the SAME binary name as the original (JaCoCo examples' pattern). */
    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();

        MemoryClassLoader(ClassLoader parent) {
            super(parent);
        }

        void put(String name, byte[] bytes) {
            defs.put(name, bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = defs.get(name);
            if (bytes == null) {
                return super.loadClass(name, resolve);
            }
            Class<?> c = defineClass(name, bytes, 0, bytes.length);
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private HttpServer server;
    private Path classesDir;
    private final AtomicReference<byte[]> stubBody = new AtomicReference<>();
    private final AtomicInteger stubStatus = new AtomicInteger(200);

    @Before
    public void setUp() throws Exception {
        classesDir = Files.createTempDirectory("basquin-http-cov-classes");
        Files.write(classesDir.resolve("TinyProbe.class"), originalClassBytes());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coverage", exchange -> {
            byte[] body = stubBody.get();
            int status = stubStatus.get();
            if (body == null || body.length == 0) {
                exchange.sendResponseHeaders(status, -1);   // -1 = no body at all, never chunked-empty
            } else {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
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

    private static byte[] originalClassBytes() throws IOException {
        String resource = "/" + TinyProbe.class.getName().replace('.', '/') + ".class";
        try (InputStream in = TinyProbe.class.getResourceAsStream(resource)) {
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

    /**
     * One live instrument+run+collect cycle: instruments {@link TinyProbe}'s real bytecode, loads
     * the instrumented copy under a fresh classloader, calls the requested methods, then serializes
     * whatever {@link RuntimeData} collected as exec-format bytes -- the same shape a real agent's
     * {@code getExecutionData(false)} would hand back over {@code /__basquin/coverage}.
     */
    private static byte[] realExecBytes(boolean callA, boolean callB, String sessionId) throws Exception {
        byte[] original = originalClassBytes();
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, "TinyProbe");

        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            MemoryClassLoader loader = new MemoryClassLoader(JacocoHttpTransportTest.class.getClassLoader());
            String binaryName = TinyProbe.class.getName();
            loader.put(binaryName, instrumented);
            Class<?> loaded = loader.loadClass(binaryName);
            if (callA) {
                loaded.getMethod("methodA", int.class).invoke(null, 5);
            }
            if (callB) {
                loaded.getMethod("methodB", int.class).invoke(null, 5);
            }
            return collect(data, sessionId);
        } finally {
            runtime.shutdown();
        }
    }

    /** Collects the runtime's CURRENT accumulated state (no reset) and serializes it to exec bytes. */
    private static byte[] collect(RuntimeData data, String sessionId) throws IOException {
        data.setSessionId(sessionId);
        ExecutionDataStore execStore = new ExecutionDataStore();
        SessionInfoStore sessionStore = new SessionInfoStore();
        data.collect(execStore, sessionStore, false);   // false = don't reset -- cumulative, like RT.getAgent().getExecutionData(false)

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecutionDataWriter writer = new ExecutionDataWriter(out);
        sessionStore.accept(writer);
        execStore.accept(writer);
        writer.flush();
        return out.toByteArray();
    }

    @Test
    public void parsesRealExecBytesOverHttpAndAnalyzesPartialCoverage() throws Exception {
        stubBody.set(realExecBytes(true, false, "session-A"));   // only methodA ran

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        JacocoCoverageProvider.Coverage c = provider.sample();

        assertEquals(1, c.sourcesResponded);
        assertEquals(1, c.sourcesTotal);
        assertEquals(1, c.classesAnalyzed);
        assertEquals(0, c.classesSkipped);
        assertTrue("methodA ran, so some instructions must be covered: " + c.covered, c.covered > 0);
        assertTrue("methodB never ran, so coverage must be PARTIAL, not full: "
                + c.covered + "/" + c.total, c.covered < c.total);
    }

    @Test
    public void identicalCoverageAcrossTwoByteDifferentButLogicallyIdenticalDumps() throws Exception {
        // Two dumps of the SAME underlying probe state (methodA ran once, nothing changes between
        // them) but with different session ids -- RuntimeData timestamps each collect() call
        // independently, so these two byte blobs are provably NOT identical, reproducing exactly
        // the seam-spike's "t1/t2 differ only in session-info timestamps" trap.
        byte[] dumpA;
        byte[] dumpB;
        {
            byte[] original = originalClassBytes();
            IRuntime runtime = new LoggerRuntime();
            Instrumenter instrumenter = new Instrumenter(runtime);
            byte[] instrumented = instrumenter.instrument(original, "TinyProbe");
            RuntimeData data = new RuntimeData();
            runtime.startup(data);
            try {
                MemoryClassLoader loader = new MemoryClassLoader(JacocoHttpTransportTest.class.getClassLoader());
                String binaryName = TinyProbe.class.getName();
                loader.put(binaryName, instrumented);
                loader.loadClass(binaryName).getMethod("methodA", int.class).invoke(null, 5);
                dumpA = collect(data, "session-A");
                dumpB = collect(data, "session-B");   // no new calls in between
            } finally {
                runtime.shutdown();
            }
        }
        assertTrue("the two dumps must be byte-DIFFERENT (session id/timestamp) to reproduce the "
                + "spike's trap -- otherwise this test proves nothing", !java.util.Arrays.equals(dumpA, dumpB));

        stubBody.set(dumpA);
        JacocoCoverageProvider providerA = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        JacocoCoverageProvider.Coverage covA = providerA.sample();

        stubBody.set(dumpB);
        JacocoCoverageProvider providerB = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        JacocoCoverageProvider.Coverage covB = providerB.sample();

        // The whole point: byte-different responses, but the driver must see NO spurious growth --
        // it parses class ids + probe arrays, never diffs raw bytes.
        assertEquals("byte-different session metadata must not change parsed coverage",
                covA.covered, covB.covered);
        assertEquals(covA.total, covB.total);
    }

    @Test
    public void coverageGrowsWhenAGenuinelyNewMethodIsCovered() throws Exception {
        byte[] original = originalClassBytes();
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, "TinyProbe");
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        byte[] dumpBefore;
        byte[] dumpAfter;
        try {
            MemoryClassLoader loader = new MemoryClassLoader(JacocoHttpTransportTest.class.getClassLoader());
            String binaryName = TinyProbe.class.getName();
            loader.put(binaryName, instrumented);
            Class<?> loaded = loader.loadClass(binaryName);
            loaded.getMethod("methodA", int.class).invoke(null, 5);
            dumpBefore = collect(data, "session-before");
            loaded.getMethod("methodB", int.class).invoke(null, 5);   // genuinely new coverage
            dumpAfter = collect(data, "session-after");
        } finally {
            runtime.shutdown();
        }

        stubBody.set(dumpBefore);
        JacocoCoverageProvider providerBefore = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        long coveredBefore = providerBefore.sample().covered;

        stubBody.set(dumpAfter);
        JacocoCoverageProvider providerAfter = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        long coveredAfter = providerAfter.sample().covered;

        assertTrue("methodB genuinely ran between the two dumps -- covered must strictly grow: "
                + coveredBefore + " -> " + coveredAfter, coveredAfter > coveredBefore);
    }

    @Test
    public void nonTwoXxStatusIsAnErrorNeverTreatedAsData() throws Exception {
        stubStatus.set(503);
        stubBody.set("err:no-jacoco-agent".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        try {
            provider.sample();
            fail("a 503 must never be silently accepted as a coverage sample");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("503"));
        }
    }

    @Test
    public void anEmptyButTwoHundredBodyIsAnErrorNeverAManufacturedZero() throws Exception {
        stubStatus.set(200);
        stubBody.set(new byte[0]);

        JacocoCoverageProvider provider = new JacocoCoverageProvider(
                JacocoCoverageProvider.parseEndpoints(coverageUrl()), classesDir);
        try {
            provider.sample();
            fail("an empty 200 body must never be silently accepted as zero coverage");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("empty"));
        }
    }
}
