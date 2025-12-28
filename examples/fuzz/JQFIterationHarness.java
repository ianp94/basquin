package examples.fuzz;

import agent.Agent;
import runner.util.FuzzIO;
import edu.berkeley.cs.jqf.fuzz.Fuzz;
import edu.berkeley.cs.jqf.fuzz.JQF;
import org.junit.runner.RunWith;
import runner.api.IterationTarget;
import runner.api.InputReceiver;

/**
 * JQF fuzz harness that feeds byte[] inputs into a configured IterationTarget.
 * Specify the target with -Dclosurejvm.target=<FQCN> implementing IterationTarget,
 * and optionally InputReceiver to consume fuzz inputs.
 */
@RunWith(JQF.class)
public class JQFIterationHarness {

    private static volatile boolean initialized = false;
    private static IterationTarget target;

    private static void ensureTarget() {
        if (initialized) return;
        synchronized (JQFIterationHarness.class) {
            if (initialized) return;
            String cls = System.getProperty("closurejvm.target");
            if (cls == null || cls.isBlank()) {
                throw new IllegalStateException("closurejvm.target not set for JQF harness");
            }
            try {
                Class<?> c = Class.forName(cls);
                Object o = c.getDeclaredConstructor().newInstance();
                if (!(o instanceof IterationTarget)) {
                    throw new IllegalArgumentException("Target does not implement IterationTarget: " + cls);
                }
                target = (IterationTarget) o;
                target.initialize();
                // Optional: pre-seed from a corpus directory deterministically before fuzzing
                String preseed = System.getProperty("closurejvm.fuzz.preseedDir");
                if (preseed != null && !preseed.isBlank()) {
                    java.nio.file.Path dir = java.nio.file.Paths.get(preseed);
                    if (java.nio.file.Files.exists(dir)) {
                        int count = 0;
                        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                            for (java.nio.file.Path p : ds) {
                                if (!java.nio.file.Files.isRegularFile(p)) continue;
                                byte[] data = java.nio.file.Files.readAllBytes(p);
                                Agent.beginIteration();
                                try {
                                    if (target instanceof InputReceiver) {
                                        ((InputReceiver) target).accept(data);
                                    }
                                    target.executeIteration();
                                } catch (Throwable t) {
                                    FuzzIO.saveInteresting(data, t);
                                } finally {
                                    Agent.endIteration();
                                }
                                count++;
                            }
                        }
                        System.out.println("[ClosureJVM][Fuzz] Pre-seeded " + count + " input(s) from " + preseed);
                    }
                }
                initialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize target for JQF", e);
            }
        }
    }

    @Fuzz
    public void fuzz(byte[] data) throws Exception {
        ensureTarget();
        Agent.beginIteration();
        try {
            if (target instanceof InputReceiver) {
                ((InputReceiver) target).accept(data);
            }
            target.executeIteration();
        } catch (Throwable t) {
            // Save crashing input then rethrow for JQF to record
            FuzzIO.saveInteresting(data, t);
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        } finally {
            Agent.endIteration();
            // Save invariant violations even in soft mode (non-crashing)
            java.util.List<String> v = agent.Agent.getLastInvariantViolations();
            if (v != null && !v.isEmpty()) {
                StringBuilder details = new StringBuilder(String.join("\n", v));
                String stack = agent.Agent.getLastInvariantStack();
                if (stack != null && !stack.isEmpty()) {
                    details.append("\nstack=\n").append(stack);
                }
                FuzzIO.saveWithMeta(data, "Invariant", details.toString());
            }
        }
    }
}
