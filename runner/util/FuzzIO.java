package runner.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;

public final class FuzzIO {
    private FuzzIO() {}

    public static void saveInteresting(byte[] data, Throwable cause) {
        String dirProp = System.getProperty("closurejvm.fuzz.resultsDir", "fuzz-results");
        boolean enabled = Boolean.parseBoolean(System.getProperty("closurejvm.fuzz.saveOnException", "true"));
        if (!enabled) return;
        try {
            Path dir = Paths.get(dirProp);
            Files.createDirectories(dir);
            long ts = Instant.now().toEpochMilli();
            String base = String.format(Locale.ROOT, "input-%d", ts);
            Path inputPath = dir.resolve(base + ".bin");
            Files.write(inputPath, data);
            Path meta = dir.resolve(base + ".meta.txt");
            StringBuilder m = new StringBuilder();
            m.append("classification=Crash\n");
            m.append("timestamp=").append(ts).append('\n');
            if (cause != null) {
                m.append("exception=").append(cause.getClass().getName()).append('\n');
                m.append("message=").append(String.valueOf(cause.getMessage())).append('\n');
                m.append("stack=\n");
                for (StackTraceElement e : cause.getStackTrace()) {
                    m.append("  at ").append(e.toString()).append('\n');
                }
            }
            // Also write a UTF-8 preview if printable
            try {
                String preview = new String(data, StandardCharsets.UTF_8);
                m.append("preview=").append(preview).append('\n');
            } catch (Exception ignored) {}
            Files.write(meta, m.toString().getBytes(StandardCharsets.UTF_8));
            System.err.println("[ClosureJVM][Fuzz] Saved interesting input to " + inputPath);
        } catch (IOException ioe) {
            System.err.println("[ClosureJVM][Fuzz] Failed to save input: " + ioe);
        }
    }

    public static void saveWithMeta(byte[] data, String classification, String details) {
        String dirProp = System.getProperty("closurejvm.fuzz.resultsDir", "fuzz-results");
        try {
            Path dir = Paths.get(dirProp);
            Files.createDirectories(dir);
            long ts = Instant.now().toEpochMilli();
            String base = String.format(Locale.ROOT, "input-%d", ts);
            Path inputPath = dir.resolve(base + ".bin");
            Files.write(inputPath, data);
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
    }
}
