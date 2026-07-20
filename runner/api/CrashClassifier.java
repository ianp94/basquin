package runner.api;

/**
 * Optional interface a target implements to declare which exceptions are <em>expected</em>
 * rejections of invalid input rather than genuine crashes. Without it, every exception thrown
 * by {@code executeIteration()} is treated as a crash — which floods the crash count with a
 * parser's normal "bad input" errors (a classic fuzzing false positive).
 *
 * Expected exceptions are counted as rejected/invalid inputs and are not saved to the crash
 * corpus; only unexpected exceptions (NPE, AssertionError, an empty-stack NoSuchElementException,
 * etc.) count as crashes and get saved for triage. In the JQF path an expected exception is
 * turned into an assumption violation so the fuzzer discards the input instead of minimizing it.
 */
public interface CrashClassifier {

    /**
     * @return true if {@code t} is an expected rejection of invalid input (not a crash).
     */
    boolean isExpected(Throwable t);
}
