package runner.coverage;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads coverage from one or more target JVMs running a JaCoCo agent and analyzes it against the
 * app's class files to compute covered/total probes. This is the app-under-test coverage signal
 * for coverage-guided-over-HTTP (v0.10): the agent lives in the app JVM, the client (this) pulls
 * the numbers over the wire — no in-harness instrumentation.
 *
 * <p><b>Two transports, one merged store (DD-043 PR-4).</b> An endpoint is either:
 * <ul>
 *   <li>{@code host:port} — the original tcpserver agent
 *       ({@code -javaagent:jacocoagent.jar=output=tcpserver,address=0.0.0.0,port=6300}), read via
 *       the {@code RemoteControlWriter}/{@code RemoteControlReader} dump-command handshake; or</li>
 *   <li>a URL (e.g. {@code http://host:port/__basquin/coverage}) — a build-time-injected target
 *       whose {@code basquin-quarkus} extension serves {@code RT.getAgent().getExecutionData(false)}
 *       as a plain GET whose 200 response body IS raw exec-format bytes (header block, session-info
 *       block(s), execution-data block(s)), read with the base {@link ExecutionDataReader} — no
 *       dump-command framing, because there is no persistent socket to command.</li>
 * </ul>
 * Both transports read cumulative (non-resetting) data —
 * {@code RT.getAgent().getExecutionData(false)} on the HTTP side is the same "don't reset" semantics
 * as {@code visitDumpCommand(true, false)} on the tcpserver side — and both feed the SAME
 * {@link ExecutionDataStore}, so a fleet can mix transports and still union-merge correctly.
 *
 * <p><b>Multiple replicas (DD-023).</b> When one driver drives N replicas behind a Service, a
 * single JaCoCo connection lands on one pod while requests load-balance across all of them, so the
 * coverage it reports is only that pod's ~1/N slice. This provider instead dumps <em>every</em>
 * endpoint into one {@link ExecutionDataStore}: JaCoCo keys execution data by a CRC64 of the class
 * bytes, so identical replicas share class ids and the store merges their probe arrays (a boolean
 * OR) automatically — giving true union coverage across the fleet. tcpserver endpoints come from a
 * comma-separated {@code host:port} list, and each host is resolved with {@code getAllByName}, so a
 * headless Service name transparently expands to all of its pod IPs. (The HTTP transport addresses
 * one URL per endpoint; fanning an HTTP endpoint out across a headless Service's pod IPs the same
 * way is a follow-on — PR-4 has no operator-side HTTP-coverage Service to drive it against.)
 *
 * <p>The class source is a directory of {@code .class} files (e.g. a WAR's WEB-INF/classes extracted).
 *
 * <p><b>D1 — the loud-skip guard.</b> {@code Analyzer#analyzeClass} throws when it cannot parse a
 * class file (e.g. a bytecode major version its ASM predates); a version skewed enough to make
 * every supplied class unanalyzable used to be swallowed silently, reporting a "clean" 0/0 instead
 * of failing — a silent-zero denominator indistinguishable from "the app truly has no code". {@link
 * #sample()} now counts skips, surfaces the count via {@link Coverage#classesSkipped}, and throws a
 * {@link CoverageUnmeasurableException} — a type distinct from a plain transport {@code
 * IOException} on purpose — when EVERY supplied class file failed to analyze. That distinction
 * matters one layer up: {@code CoverageGuidedRun}/{@code CoverageDriver} must tolerate a plain
 * {@code IOException} (a blipped agent, a restarting pod) without ending the campaign, but must
 * NOT tolerate a {@code CoverageUnmeasurableException} the same way — {@link #classBytes} never
 * changes across a run, so this is a deterministic, structural failure that will recur on every
 * future {@link #sample()} call too, not a one-off to ride out.
 *
 * <p><b>F3 — the empty/unreadable classes dir guard.</b> The constructor throws when a classes
 * dir IS supplied but yields zero readable {@code .class} files (a typo'd path, an extraction that
 * silently produced nothing, a permissions problem) — there is no legitimate reason to poll
 * coverage against zero supplied classes, and letting construction succeed used to let {@link
 * #sample()} report a clean {@code Coverage(0, 0)} with no error at all, the same silent-zero
 * shape as D1 one layer earlier.
 */
public final class JacocoCoverageProvider {

    /**
     * One coverage endpoint: either a tcpserver {@code host:port} ({@code url == null}, may resolve
     * to several addresses via a headless Service) or an HTTP URL ({@code url != null}).
     */
    public static final class Endpoint {
        final String host;
        final int port;
        final URL url;

        public Endpoint(String host, int port) {
            this(host, port, null);
        }

        private Endpoint(String host, int port, URL url) {
            this.host = host;
            this.port = port;
            this.url = url;
        }

        private static Endpoint ofUrl(URL url) {
            int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
            return new Endpoint(url.getHost(), port, url);
        }

        boolean isHttp() {
            return url != null;
        }
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
                    // skip unreadable class; analysis just omits it -- but see the check below:
                    // an all-unreadable dir must not silently fall through to zero supplied classes.
                }
            }
        }
        // F3: a classes dir WAS supplied (this constructor requires a Path; the genuinely-optional
        // "no coverage classes at all" mode lives outside this class -- CoverageDriver simply never
        // constructs a provider when -Dbasquin.coverage.classes is unset), so ending up with zero
        // readable class files here is never legitimate: a typo'd path, an extraction that produced
        // nothing, or every file unreadable. Without this check, sample() would go on to report a
        // clean Coverage(0, 0) with no error -- the same silent-zero shape D1 guards against one
        // layer later, just reached a different way.
        if (classBytes.isEmpty()) {
            throw new IOException("supplied coverage classes dir " + classesDir + " yielded zero "
                    + "readable .class file(s) -- there is no legitimate reason to poll coverage "
                    + "against zero supplied classes. Check -Dbasquin.coverage.classes.");
        }
    }

    /** Convenience for the single-endpoint case (tests, one target). */
    public JacocoCoverageProvider(String host, int port, Path classesDir) throws IOException {
        this(java.util.Collections.singletonList(new Endpoint(host, port)), classesDir);
    }

    /**
     * Parse {@code host:port[,host:port...]} and/or {@code URL[,URL...]} into endpoints (the two
     * forms may be mixed in one comma-separated spec). An entry is a URL when it contains
     * {@code "://"}; a bare hostname never does, so {@code "localhost"} (no port) still falls
     * through to the host:port branch and still rejects with the same message as before the HTTP
     * transport existed. Throws on a malformed entry either way.
     */
    public static List<Endpoint> parseEndpoints(String spec) {
        List<Endpoint> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("://")) {
                try {
                    out.add(Endpoint.ofUrl(new URL(p)));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(
                            "coverage endpoint URL is malformed: \"" + p + "\" (" + e.getMessage() + ")", e);
                }
                continue;
            }
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

    /**
     * Covered and total instruction probes, how many agents actually responded this sample, and
     * how many of the supplied class files JaCoCo could/couldn't analyze (D1: a nonzero
     * {@code classesSkipped} is visible even on an otherwise-healthy sample — {@link #sample()}
     * only throws on the all-skip case, an all-PARTIAL skip still reports real numbers here).
     */
    public static final class Coverage {
        public final long covered;
        public final long total;
        public final int sourcesResponded;
        public final int sourcesTotal;
        public final int classesAnalyzed;
        public final int classesSkipped;
        Coverage(long covered, long total, int sourcesResponded, int sourcesTotal,
                 int classesAnalyzed, int classesSkipped) {
            this.covered = covered;
            this.total = total;
            this.sourcesResponded = sourcesResponded;
            this.sourcesTotal = sourcesTotal;
            this.classesAnalyzed = classesAnalyzed;
            this.classesSkipped = classesSkipped;
        }
    }

    /**
     * D1/F1: thrown by {@link #sample()} when EVERY supplied class file failed to analyze — a
     * deterministic, structural incompatibility between the pinned JaCoCo/ASM version and the
     * target's class-file bytecode version (e.g. a future target's classfile major version passing
     * this JaCoCo release's ASM ceiling), never a transient condition. {@link #classBytes} is read
     * once at construction and never changes across a run, so a sample that hits this once WILL hit
     * it on every future {@link #sample()} call too. A distinct type (rather than a plain {@code
     * IOException}, which also covers ordinary transport blips — an unreachable pod, a dropped
     * connection) lets a consumer tell the two apart: an approver-found defect was consumers
     * ({@code CoverageGuidedRun#sampleCoverage}, {@code CoverageDriver}'s poll loop) catching {@code
     * Throwable} broadly and silently returning/reporting as if this were just another blip, which
     * let a persistent all-skip publish a "clean" {@code coverage=0/0} with no diagnostics — the
     * exact silent-zero D1 was written to prevent, recurring one layer up. Consumers must surface
     * this (abort, or at minimum log once and report "unmeasured" rather than a numerator/
     * denominator) while still tolerating a plain {@code IOException} as a blip.
     */
    public static final class CoverageUnmeasurableException extends IOException {
        CoverageUnmeasurableException(String message) {
            super(message);
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
            if (ep.isHttp()) {
                total++;
                try {
                    dumpHttpInto(ep.url, execStore, sessionStore);
                    responded++;
                } catch (Exception e) {
                    lastError = e instanceof IOException ? (IOException) e : new IOException(e);
                    // skip this endpoint; others may still answer
                }
                continue;
            }
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
        int analyzed = 0, skipped = 0;
        for (byte[] bytes : classBytes) {
            try {
                analyzer.analyzeClass(bytes, "");
                analyzed++;
            } catch (Exception e) {
                skipped++;   // e.g. a class-file bytecode version this JaCoCo predates (D1)
            }
        }
        // D1: 0.8.12's ASM could not parse the targets' Java-25 classfiles and analyzeClass's
        // caught exception silently zeroed the coverage denominator -- indistinguishable from "the
        // app has no code". A partial skip still reports real, if incomplete, numbers (visible via
        // Coverage#classesSkipped below); an ALL-skip means the analyzer is unusable against this
        // class set and MUST fail loudly rather than report a "clean" 0/0 as if it were real zero
        // coverage.
        if (!classBytes.isEmpty() && analyzed == 0) {
            throw new CoverageUnmeasurableException("JaCoCo analyzed 0 of " + classBytes.size()
                    + " supplied class file(s) -- all " + skipped + " were skipped (unanalyzable). "
                    + "This is the silent-zero denominator D1 guards against: refusing to report it "
                    + "as coverage. Check the JaCoCo version against the target's class-file bytecode "
                    + "version.");
        }
        long covered = 0, totalProbes = 0;
        for (IClassCoverage c : builder.getClasses()) {
            covered += c.getInstructionCounter().getCoveredCount();
            totalProbes += c.getInstructionCounter().getTotalCount();
        }
        return new Coverage(covered, totalProbes, responded, total, analyzed, skipped);
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

    /**
     * Dump one HTTP coverage endpoint into the shared store. There is no dump-command handshake
     * here (that is a tcpserver-protocol concept) — the endpoint is a plain GET whose 200 response
     * body IS the exec bytes end to end (the seam-spike's {@code 01 c0 c0 10} magic first, then
     * whatever session-info/execution-data blocks follow), so the base {@link ExecutionDataReader}
     * reads it directly. A non-2xx status is always an error (never treated as data); an empty 2xx
     * body is ALSO an error — the plan's server-side contract promises "RT-unavailable is a distinct
     * non-2xx, never an empty-but-200 body", and this defends the client side of that contract too,
     * since an empty-but-200 response would otherwise parse as zero blocks and silently look like a
     * legitimate (if empty) sample rather than the manufactured zero it would actually be.
     */
    private static void dumpHttpInto(URL url, ExecutionDataStore execStore, SessionInfoStore sessionStore)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(5_000);
        conn.setRequestMethod("GET");
        byte[] body;
        int status;
        try {
            status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("coverage endpoint " + url + " returned HTTP " + status
                        + errorBodySuffix(conn));
            }
            try (InputStream in = conn.getInputStream()) {
                body = readAll(in);
            }
        } finally {
            conn.disconnect();
        }
        if (body.length == 0) {
            throw new IOException("coverage endpoint " + url + " returned an empty HTTP " + status
                    + " body -- an empty-but-200 response is a manufactured zero, never real coverage");
        }
        ExecutionDataReader reader = new ExecutionDataReader(new ByteArrayInputStream(body));
        reader.setSessionInfoVisitor(sessionStore);
        reader.setExecutionDataVisitor(execStore);   // same store across endpoints -> merged
        reader.read();
    }

    /** {@code ": <body>"} for an error response, or {@code ""} if there was no readable body. */
    private static String errorBodySuffix(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return "";
            String text = new String(readAll(err), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "" : ": " + text;
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
