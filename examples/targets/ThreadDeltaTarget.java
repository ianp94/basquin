package examples.targets;

import runner.api.IterationTarget;

/**
 * Spawns a daemon thread that sleeps briefly to increase total thread count
 * without triggering non-daemon thread leak detection. Used to trip threadDelta invariant.
 */
public class ThreadDeltaTarget implements IterationTarget {
    @Override
    public void executeIteration() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }, "thread-delta-demo");
        t.setDaemon(true);
        t.start();
        try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

