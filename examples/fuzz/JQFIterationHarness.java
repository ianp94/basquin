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
            // Save interesting input then rethrow for JQF to record
            FuzzIO.saveInteresting(data, t);
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        } finally {
            Agent.endIteration();
        }
    }
}
