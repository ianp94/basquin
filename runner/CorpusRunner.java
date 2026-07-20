package runner;

import agent.Agent;
import runner.api.InputReceiver;
import runner.api.IterationTarget;
import runner.util.FuzzIO;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple corpus replayer: feeds each file's bytes as an input into an IterationTarget
 * that optionally implements InputReceiver, one iteration per file.
 *
 * Usage:
 *   java -cp <cp> runner.CorpusRunner <targetClass> <corpusDir>
 * or set -Dclosurejvm.target and -Dclosurejvm.corpusDir and pass no args.
 */
public final class CorpusRunner {

    public static void main(String[] args) throws Exception {
        runner.util.TriageSink.ensureStarted();
        String targetClass = (args.length > 0) ? args[0] : System.getProperty("closurejvm.target");
        String corpusDir = (args.length > 1) ? args[1] : System.getProperty("closurejvm.corpusDir", "corpus");
        if (targetClass == null || targetClass.isBlank()) {
            throw new IllegalArgumentException("Target class not specified. Pass arg or -Dclosurejvm.target");
        }

        IterationTarget target = instantiate(targetClass);
        target.initialize();

        List<Path> inputs = listInputs(Paths.get(corpusDir));
        System.out.println("CorpusRunner: running " + inputs.size() + " inputs from " + corpusDir + " against target " + targetClass);

        int failures = 0;
        int violations = 0;
        boolean stopOnFailure = Boolean.getBoolean("closurejvm.corpus.stopOnFailure");
        boolean failOnAny = Boolean.getBoolean("closurejvm.corpus.failOnAny");

        for (int i = 0; i < inputs.size(); i++) {
            Path p = inputs.get(i);
            byte[] data = Files.readAllBytes(p);
            System.out.println("Input " + (i + 1) + "/" + inputs.size() + ": " + p.getFileName());
            Agent.beginIteration();
            Throwable failure = null;
            try {
                if (target instanceof InputReceiver) {
                    ((InputReceiver) target).accept(data);
                }
                target.executeIteration();
            } catch (Throwable t) {
                failure = t;
            }
            try {
                Agent.endIteration();
            } catch (Throwable t2) {
                if (failure == null) failure = t2;
            }

            if (failure != null) {
                failures++;
                System.err.println("[Corpus] FAIL: " + p.getFileName() + " — " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                FuzzIO.saveInteresting(data, failure);
                if (stopOnFailure) {
                    break;
                }
            } else {
                // Check invariant violations in soft mode
                java.util.List<String> v = agent.Agent.getLastInvariantViolations();
                if (v != null && !v.isEmpty()) {
                    violations++;
                    StringBuilder details = new StringBuilder(String.join("\n", v));
                    String stack = agent.Agent.getLastInvariantStack();
                    if (stack != null && !stack.isEmpty()) {
                        details.append("\nstack=\n").append(stack);
                    }
                    System.err.println("[Corpus] VIOLATION: " + p.getFileName());
                    FuzzIO.saveWithMeta(data, "Invariant", details.toString());
                    if (stopOnFailure) {
                        break;
                    }
                } else {
                    System.out.println("[Corpus] PASS: " + p.getFileName());
                }
            }
        }

        target.close();
        System.out.println("CorpusRunner complete. Failures=" + failures + ", Violations=" + violations + ", Total=" + inputs.size());
        if (failOnAny && (failures > 0 || violations > 0)) {
            System.exit(2);
        }
    }

    private static IterationTarget instantiate(String cls) {
        try {
            Class<?> c = Class.forName(cls);
            Object o = c.getDeclaredConstructor().newInstance();
            if (!(o instanceof IterationTarget)) {
                throw new IllegalArgumentException("Target does not implement IterationTarget: " + cls);
            }
            return (IterationTarget) o;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate target: " + cls, e);
        }
    }

    private static List<Path> listInputs(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(dir)) {
            return files;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
        }
        files.sort(null);
        return files;
    }
}
