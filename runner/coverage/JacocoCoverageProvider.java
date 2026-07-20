package runner.coverage;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads coverage from one or more target JVMs running the JaCoCo agent in tcpserver mode
 * ({@code -javaagent:jacocoagent.jar=output=tcpserver,address=0.0.0.0,port=6300}) and analyzes it
 * against the app's class files to compute covered/total probes. This is the app-under-test
 * coverage signal for coverage-guided-over-HTTP (v0.10): the agent lives in the app JVM, the
 * client (this) pulls the numbers over the wire — no in-harness instrumentation.
 *
 * <p><b>Multiple replicas (DD-023).</b> When one driver drives N replicas behind a Service, a
 * single JaCoCo connection lands on one pod while requests load-balance across all of them, so the
 * coverage it reports is only that pod's ~1/N slice. This provider instead dumps <em>every</em>
 * endpoint into one {@link ExecutionDataStore}: JaCoCo keys execution data by a CRC64 of the class
 * bytes, so identical replicas share class ids and the store merges their probe arrays (a boolean
 * OR) automatically — giving true union coverage across the fleet. Endpoints come from a
 * comma-separated {@code host:port} list, and each host is resolved with {@code getAllByName}, so a
 * headless Service name transparently expands to all of its pod IPs.
 *
 * <p>The class source is a directory of {@code .class} files (e.g. a WAR's WEB-INF/classes extracted).
 */
public final class JacocoCoverageProvider {

    /** One JaCoCo tcpserver endpoint. {@code host} may resolve to several addresses (headless svc). */
    public static final class Endpoint {
        final String host;
        final int port;
        public Endpoint(String host, int port) { this.host = host; this.port = port; }
    }

    private final List<Endpoint> endpoints;
    /**
     * Class bytes are read ONCE at construction. sample() is called per iteration, and the class
     * files cannot change during a run, so re-reading and re-parsing hundreds of files per HTTP
     * request was pure overhead on the hot path.
     */
    private final List<byte[]> classBytes = new ArrayList<>();

    public JacocoCoverageProvider(List<Endpoint> endpoints, Path classesDir) throws IOException {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one JaCoCo endpoint is required");
        }
        this.endpoints = new ArrayList<>(endpoints);
        try (Stream<Path> s = Files.walk(classesDir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).collect(java.util.stream.Collectors.toList())) {
                try {
                    classBytes.add(Files.readAllBytes(p));
                } catch (IOException ignored) {
                    // skip unreadable class; analysis just omits it
                }
            }
        }
    }

    /** Convenience for the single-endpoint case (tests, one target). */
    public JacocoCoverageProvider(String host, int port, Path classesDir) throws IOException {
        this(java.util.Collections.singletonList(new Endpoint(host, port)), classesDir);
    }

    /** Parse {@code host:port[,host:port...]} into endpoints. Throws on a malformed entry. */
    public static List<Endpoint> parseEndpoints(String spec) {
        List<Endpoint> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int colon = p.lastIndexOf(':');
            if (colon <= 0 || colon == p.length() - 1) {
                throw new IllegalArgumentException("coverage endpoint must be host:port (got \"" + p + "\")");
            }
            int port;
            try {
                port = Integer.parseInt(p.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("coverage endpoint port is not a number in \"" + p + "\"");
            }
            out.add(new Endpoint(p.substring(0, colon), port));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no coverage endpoints in \"" + spec + "\"");
        }
        return out;
    }

    /** Covered and total instruction probes, plus how many agents actually responded this sample. */
    public static final class Coverage {
        public final long covered;
        public final long total;
        public final int sourcesResponded;
        public final int sourcesTotal;
        Coverage(long covered, long total, int sourcesResponded, int sourcesTotal) {
            this.covered = covered;
            this.total = total;
            this.sourcesResponded = sourcesResponded;
            this.sourcesTotal = sourcesTotal;
        }
    }

    /**
     * Dump current execution data from every endpoint (expanding each host to all resolved
     * addresses) into one merged store, then analyze it. A single unreachable pod is skipped
     * rather than failing the whole sample — a restarting replica must not zero the campaign's
     * coverage — but if nothing responds at all, this throws.
     */
    public Coverage sample() throws IOException {
        ExecutionDataStore execStore = new ExecutionDataStore();
        SessionInfoStore sessionStore = new SessionInfoStore();

        int total = 0, responded = 0;
        IOException lastError = null;
        for (Endpoint ep : endpoints) {
            InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(ep.host);
            } catch (IOException e) {
                lastError = e;
                total++;   // count the endpoint even though we couldn't resolve it
                continue;
            }
            // Collapse the loopback set to a single address. "localhost" resolves to BOTH
            // 127.0.0.1 and ::1 on a dual-stack host, which on a healthy single-target run would
            // otherwise show [1/2 pods] (the family the agent isn't bound to fails) or dump the
            // same agent twice ([2/2 pods]) -- either way implying replicas that don't exist. A
            // distinct-pod headless Service never returns loopbacks, so the fleet case is untouched.
            List<InetAddress> addrs = new ArrayList<>();
            boolean loopbackKept = false;
            for (InetAddress a : resolved) {
                if (a.isLoopbackAddress()) {
                    if (loopbackKept) continue;
                    loopbackKept = true;
                }
                addrs.add(a);
            }
            for (InetAddress addr : addrs) {
                total++;
                try {
                    dumpInto(addr, ep.port, execStore, sessionStore);
                    responded++;
                } catch (Exception e) {
                    lastError = e instanceof IOException ? (IOException) e : new IOException(e);
                    // skip this pod; others may still answer
                }
            }
        }
        if (responded == 0) {
            throw lastError != null ? lastError : new IOException("no JaCoCo agent responded");
        }

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(execStore, builder);
        for (byte[] bytes : classBytes) {
            try {
                analyzer.analyzeClass(bytes, "");
            } catch (Exception ignored) {
                // skip classes JaCoCo can't analyze (e.g. version mismatch)
            }
        }
        long covered = 0, totalProbes = 0;
        for (IClassCoverage c : builder.getClasses()) {
            covered += c.getInstructionCounter().getCoveredCount();
            totalProbes += c.getInstructionCounter().getTotalCount();
        }
        return new Coverage(covered, totalProbes, responded, total);
    }

    /** Dump one agent's accumulated data into the shared store (JaCoCo OR-merges by class id). */
    private static void dumpInto(InetAddress addr, int port,
                                 ExecutionDataStore execStore, SessionInfoStore sessionStore) throws IOException {
        // Explicit timeouts, NOT the OS defaults. In Kubernetes a deleted pod's IP black-holes
        // (drops SYNs), and the stale-DNS window on a headless Service hands getAllByName exactly
        // those IPs. A default-timeout connect to one dead IP would stall the whole sample() for
        // ~2 minutes -- and CoverageGuidedRun calls sample() synchronously in its loop, so the
        // campaign would freeze. Fail fast instead: a dead pod is skipped, the live ones still
        // merge. (These could become system properties later; conservative fixed values for now.)
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(addr, port), 2_000);
            socket.setSoTimeout(5_000);
            RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
            RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());
            reader.setSessionInfoVisitor(sessionStore);
            reader.setExecutionDataVisitor(execStore);   // same store across pods -> merged
            // Dump without resetting, so coverage accumulates across the campaign.
            writer.visitDumpCommand(true, false);
            if (!reader.read()) {
                throw new IOException("no dump response from JaCoCo agent at " + addr + ":" + port);
            }
        }
    }
}
