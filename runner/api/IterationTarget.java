package runner.api;

/**
 * Minimal interface for plugging custom iteration code into the harness.
 *
 * Lifecycle:
 * - initialize(): called once before the first iteration (optional)
 * - executeIteration(): called for each iteration
 * - close(): called once after all iterations (optional)
 */
public interface IterationTarget extends AutoCloseable {
    default void initialize() throws Exception {}
    void executeIteration() throws Exception;
    @Override
    default void close() throws Exception {}
}

