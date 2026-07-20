package agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;

/**
 * Per-iteration state for one begin/end cycle. Replaces the static per-iteration fields
 * that previously lived on {@link Agent}, so overlapping iterations (e.g. concurrent
 * requests) cannot corrupt each other's latency baseline or leak set. See
 * docs/DESIGN-DECISIONS.md DD-005 / DD-010.
 *
 * Latency and the non-daemon leak set scope cleanly to a context. Heap and thread
 * deltas remain process-global (another concurrent iteration's allocations/threads land
 * in this context's delta), so those signals are only trustworthy under serialized or
 * single-flight execution — which is why the servlet integrations still serialize.
 *
 * Obtain one from {@link Agent#begin()} and pass it to {@link Agent#end(IterationContext)}.
 * The result fields (latency, deltas, violations) are populated by {@code end} and are
 * intended to feed a triage payload (DD-006) without reaching back into global state.
 */
public final class IterationContext {

    final int iterationNumber;

    // Baselines captured at begin().
    long startNanos;
    long baselineHeapBytes;
    int baselineThreadCount;
    Set<Long> baselineNonDaemonThreadIds = Collections.emptySet();
    Set<Integer> baselineActiveExecutorIdentities = Collections.emptySet();
    Set<Integer> baselineActiveTimerIdentities = Collections.emptySet();

    // Latency sampling for this iteration.
    Thread monitoredThread;
    volatile String sampledExecStack = "";
    TimerTask samplingTask;

    // Results, populated by end().
    long latencyMs;
    long heapDeltaBytes;
    int threadCount;
    int threadDelta;
    List<String> invariantViolations = Collections.emptyList();
    String invariantStack = "";
    boolean leakDetected;

    IterationContext(int iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public int iterationNumber() { return iterationNumber; }
    public long latencyMs() { return latencyMs; }
    public long heapDeltaBytes() { return heapDeltaBytes; }
    public int threadCount() { return threadCount; }
    public int threadDelta() { return threadDelta; }
    public boolean leakDetected() { return leakDetected; }
    public String invariantStack() { return invariantStack; }

    public List<String> invariantViolations() {
        return new ArrayList<>(invariantViolations);
    }
}
