package runner.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * Post-process JQF output directory to create ClosureJVM triage metadata files
 * for coverage-interesting inputs that did not crash.
 */
public final class PostprocessJQF {
    public static void main(String[] args) throws IOException {
        Path jqfDir = Paths.get(System.getProperty("closurejvm.jqf.dir", "fuzz-results"));
        Path outDir = Paths.get(System.getProperty("closurejvm.fuzz.resultsDir", jqfDir.toString()));
        Files.createDirectories(outDir);
        int processed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(jqfDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                // Skip our own .bin/.meta artifacts
                String name = p.getFileName().toString();
                if (name.endsWith(".bin") || name.endsWith(".meta.txt")) continue;
                byte[] data = Files.readAllBytes(p);
                String base = "cov-" + UUID.randomUUID();
                Path bin = outDir.resolve(base + ".bin");
                Files.write(bin, data);
                Path meta = outDir.resolve(base + ".meta.txt");
                String metaText = "classification=Coverage\nsource=" + p.toAbsolutePath() + "\n";
                Files.write(meta, metaText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                processed++;
            }
        }
        System.err.println("[ClosureJVM][Fuzz] Post-processed JQF inputs: " + processed + " → " + outDir);
    }
}

