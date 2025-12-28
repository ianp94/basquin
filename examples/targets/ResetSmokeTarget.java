package examples.targets;

import runner.api.IterationTarget;

/**
 * A target that deliberately fails on the first iteration by
 * incrementing a counter stored in a non-reloaded class (parent loader),
 * then succeeds on subsequent iterations after a classloader reset.
 */
public class ResetSmokeTarget implements IterationTarget {
    @Override
    public void initialize() {
        // no-op
    }

    @Override
    public void executeIteration() {
        int n = ++examples.shared.ResetCounter.attempts;
        if (n == 1) {
            throw new RuntimeException("Intentional failure to trigger reset (attempt=" + n + ")");
        }
        // Success path: do a tiny bit of work
        try { Thread.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        // no-op
    }
}

