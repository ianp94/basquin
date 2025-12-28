package runner;

import agent.Agent;
import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Naive byte-level delta-debugging minimizer for a crashing input.
 *
 * Usage:
 *   java -cp <cp> runner.MinimizeRunner <targetClass> <inputFile> <outputFile>
 */
public final class MinimizeRunner {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: runner.MinimizeRunner <targetClass> <inputFile> <outputFile>");
        }
        String targetClass = args[0];
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        byte[] data = Files.readAllBytes(in);

        IterationTarget target = instantiate(targetClass);
        System.out.println("Minimizing input of length " + data.length + " for target " + targetClass);
        byte[] minimized = ddmin(target, data);
        Files.createDirectories(out.getParent() != null ? out.getParent() : Paths.get("."));
        Files.write(out, minimized);
        System.out.println("Minimized length: " + minimized.length + ", written to " + out);
    }

    private static byte[] ddmin(IterationTarget target, byte[] data) {
        byte[] best = Arrays.copyOf(data, data.length);
        int n = 2; // granularity
        while (best.length >= 2) {
            boolean reduced = false;
            int chunk = Math.max(1, best.length / n);
            for (int start = 0; start < best.length; start += chunk) {
                int end = Math.min(best.length, start + chunk);
                byte[] candidate = new byte[best.length - (end - start)];
                System.arraycopy(best, 0, candidate, 0, start);
                System.arraycopy(best, end, candidate, start, best.length - end);
                if (isInteresting(target, candidate)) {
                    best = candidate;
                    reduced = true;
                    // restart with same granularity
                    break;
                }
            }
            if (!reduced) {
                if (n >= best.length) break;
                n = Math.min(best.length, n * 2);
            }
        }
        return best;
    }

    private static boolean isInteresting(IterationTarget target, byte[] data) {
        try {
            if (target instanceof InputReceiver) {
                ((InputReceiver) target).accept(data);
            }
            Agent.beginIteration();
            try {
                target.executeIteration();
            } finally {
                Agent.endIteration();
            }
            return false; // no exception -> not interesting
        } catch (Throwable t) {
            return true; // still crashes -> interesting
        }
    }

    private static IterationTarget instantiate(String cls) {
        try {
            Class<?> c = Class.forName(cls);
            Object o = c.getDeclaredConstructor().newInstance();
            if (!(o instanceof IterationTarget)) {
                throw new IllegalArgumentException("Target does not implement IterationTarget: " + cls);
            }
            ((IterationTarget) o).initialize();
            return (IterationTarget) o;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate target: " + cls, e);
        }
    }
}

