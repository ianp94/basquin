package examples.targets;

import runner.api.IterationTarget;

/**
 * Allocates a configurable amount of memory during an iteration to trigger heap delta invariants.
 */
public class HeapBumpTarget implements IterationTarget {
    private byte[] hold;

    private static int bumpKb() {
        String v = System.getProperty("examples.heap.bumpKb");
        if (v != null) {
            try { return Math.max(1, Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
        }
        return 2048; // 2MB default
    }

    @Override
    public void executeIteration() {
        int kb = bumpKb();
        hold = new byte[kb * 1024];
        // brief work
        for (int i = 0; i < hold.length; i += 4096) {
            hold[i] = (byte) (i & 0xFF);
        }
        try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

