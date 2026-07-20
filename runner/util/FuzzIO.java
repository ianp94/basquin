package runner.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class FuzzIO {
    private FuzzIO() {}

    // Sequence suffix so two findings in the same millisecond never overwrite
    // each other's files (timestamp-only names collided).
    private static final AtomicLong SEQ = new AtomicLong();

    public static void saveInteresting(byte[] data, Throwable cause) {
        boolean enabled = Boolean.parseBoolean(System.getProperty("closurejvm.fuzz.saveOnException", "true"));
        if (!enabled) return;
        String dirProp = System.getProperty("closurejvm.fuzz.resultsDir", "fuzz-results");
        // Snapshot everything the async write needs NOW: the fuzzer may reuse/mutate
        // the input buffer after we return, and system properties may change.
        final byte[] input = data != null ? data.clone() : new byte[0];
        final long ts = Instant.now().toEpochMilli();
        final long seq = SEQ.incrementAndGet();
        final StackTraceElement[] stack = cause != null ? cause.getStackTrace() : null;
        final String exClass = cause != null ? cause.getClass().getName() : null;
        final String exMsg = cause != null ? String.valueOf(cause.getMessage()) : null;
        TriageSink.submit(() -> {
            try {
                Path dir = Paths.get(dirProp);
                Files.createDirectories(dir);
                String base = String.format(Locale.ROOT, "input-%d-%d", ts, seq);
                Path inputPath = dir.resolve(base + ".bin");
                Files.write(inputPath, input);
                Path meta = dir.resolve(base + ".meta.txt");
                StringBuilder m = new StringBuilder();
                m.append("classification=Crash\n");
                m.append("timestamp=").append(ts).append('\n');
                if (exClass != null) {
                    m.append("exception=").append(exClass).append('\n');
                    m.append("message=").append(exMsg).append('\n');
                    m.append("stack=\n");
                    for (StackTraceElement e : stack) {
                        m.append("  at ").append(e.toString()).append('\n');
                    }
                }
                // Also write a UTF-8 preview if printable
                try {
                    String preview = new String(input, StandardCharsets.UTF_8);
                    m.append("preview=").append(preview).append('\n');
                } catch (Exception ignored) {}
                Files.write(meta, m.toString().getBytes(StandardCharsets.UTF_8));
                System.err.println("[ClosureJVM][Fuzz] Saved interesting input to " + inputPath);
            } catch (IOException ioe) {
                System.err.println("[ClosureJVM][Fuzz] Failed to save input: " + ioe);
            }
        });
    }

    public static void saveWithMeta(byte[] data, String classification, String details) {
        String dirProp = System.getProperty("closurejvm.fuzz.resultsDir", "fuzz-results");
        final byte[] input = data != null ? data.clone() : new byte[0];
        final long ts = Instant.now().toEpochMilli();
        final long seq = SEQ.incrementAndGet();
        TriageSink.submit(() -> {
            try {
                Path dir = Paths.get(dirProp);
                Files.createDirectories(dir);
                String base = String.format(Locale.ROOT, "input-%d-%d", ts, seq);
                Path inputPath = dir.resolve(base + ".bin");
                Files.write(inputPath, input);
                Path meta = dir.resolve(base + ".meta.txt");
                StringBuilder m = new StringBuilder();
                m.append("classification=").append(classification).append('\n');
                m.append("timestamp=").append(ts).append('\n');
                if (details != null && !details.isEmpty()) {
                    m.append("details=\n").append(details).append('\n');
                }
                Files.write(meta, m.toString().getBytes(StandardCharsets.UTF_8));
                System.err.println("[ClosureJVM][Fuzz] Saved input (" + classification + ") to " + inputPath);
            } catch (IOException ioe) {
                System.err.println("[ClosureJVM][Fuzz] Failed to save input: " + ioe);
            }
        });
    }
}
