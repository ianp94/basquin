package agent.reset;

/**
 * Reset strategy interface for v0.2+.
 * Implementations provide a way to recover iteration cleanliness when invariants/leaks are detected.
 */
public interface ResetStrategy {
    /** Prepare/reset before a new iteration if needed. */
    default void beforeIteration(int iteration) {}

    /** Attempt to reset state after an iteration. Returns true if reset was performed. */
    default boolean afterIteration(int iteration) { return false; }
}

