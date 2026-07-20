package runner.coverage;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads coverage from a target JVM running the JaCoCo agent in tcpserver mode
 * ({@code -javaagent:jacocoagent.jar=output=tcpserver,address=0.0.0.0,port=6300}) and analyzes it
 * against the app's class files to compute covered/total probes. This is the app-under-test
 * coverage signal for coverage-guided-over-HTTP (v0.10): the agent lives in the app JVM, the
 * client (this) pulls the numbers over the wire — no in-harness instrumentation.
 *
 * The class source is a directory of {@code .class} files (e.g. a WAR's WEB-INF/classes extracted).
 */
public final class JacocoCoverageProvider {

    private final String host;
    private final int port;
    private final List<File> classFiles;

    public JacocoCoverageProvider(String host, int port, Path classesDir) throws IOException {
        this.host = host;
        this.port = port;
        this.classFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(classesDir)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(p -> classFiles.add(p.toFile()));
        }
    }

    /** Covered and total instruction probes across the analyzed classes. */
    public static final class Coverage {
        public final long covered;
        public final long total;
        Coverage(long covered, long total) { this.covered = covered; this.total = total; }
    }

    /** Dump current execution data from the agent and analyze it against the class files. */
    public Coverage sample() throws IOException {
        ExecutionDataStore execStore = new ExecutionDataStore();
        SessionInfoStore sessionStore = new SessionInfoStore();
        try (Socket socket = new Socket(host, port)) {
            RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
            RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());
            reader.setSessionInfoVisitor(sessionStore);
            reader.setExecutionDataVisitor(execStore);
            // Request a dump without resetting, so coverage accumulates across the campaign.
            writer.visitDumpCommand(true, false);
            if (!reader.read()) {
                throw new IOException("no dump response from JaCoCo agent at " + host + ":" + port);
            }
        }

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(execStore, builder);
        for (File f : classFiles) {
            try (InputStream in = Files.newInputStream(f.toPath())) {
                analyzer.analyzeClass(in, f.getPath());
            } catch (Exception ignored) {
                // skip classes JaCoCo can't analyze (e.g. version mismatch)
            }
        }
        long covered = 0, total = 0;
        for (IClassCoverage c : builder.getClasses()) {
            covered += c.getInstructionCounter().getCoveredCount();
            total += c.getInstructionCounter().getTotalCount();
        }
        return new Coverage(covered, total);
    }
}
