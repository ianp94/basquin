package agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Core agent for ClosureJVM - a persistent execution harness for JVM web applications
 * that uses coverage-guided exploration to discover availability, performance, and
 * correctness failures.
 */
public class Agent {

    private static volatile Set<Long> baselineNonDaemonThreadIds = Collections.emptySet();
    private static volatile int iteration = 0;
    // Keep weak references so tracking doesn't retain executors/timers long-term
    private static final Set<java.lang.ref.WeakReference<ExecutorService>> trackedExecutorRefs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<java.lang.ref.WeakReference<Timer>> trackedTimerRefs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile Set<Integer> baselineActiveExecutorIdentities = Collections.emptySet();
    private static volatile Set<Integer> baselineActiveTimerIdentities = Collections.emptySet();
    // Simple metrics baselines captured at iteration start
    private static volatile long iterationStartNanos = 0L;
    private static volatile long baselineHeapUsedBytes = 0L;
    private static volatile int baselineThreadCount = 0;
    private static volatile java.util.List<String> lastInvariantViolations = java.util.Collections.emptyList();

    /**
     * Entry point for the JVM agent
     * @param agentArgs arguments passed to the agent
     * @param instrumentation instrumentation object for the JVM
     */
    public static void premain(String agentArgs, java.lang.instrument.Instrumentation instrumentation) {
        System.out.println("ClosureJVM Agent initialized");
        // Agent initialization logic would go here
    }

    /**
     * Begin a new iteration boundary
     * This method should be called at the start of each iteration
     */
    public static void beginIteration() {
        iteration++;
        System.out.println("Beginning iteration");
        // Metrics baseline
        iterationStartNanos = System.nanoTime();
        baselineHeapUsedBytes = usedHeapBytes();
        baselineThreadCount = snapshotTotalThreadCount();
        lastInvariantViolations = java.util.Collections.emptyList();
        baselineNonDaemonThreadIds = snapshotNonDaemonThreadIds();
        baselineActiveExecutorIdentities = snapshotActiveExecutorIdentities();
        baselineActiveTimerIdentities = snapshotActiveTimerIdentities();
    }

    /**
     * End current iteration boundary
     * This method should be called at the end of each iteration
     */
    public static void endIteration() {
        // Small grace period: allow short-lived tasks to finish
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simple metrics snapshot and print-only signal
        final long elapsedMs = Math.max(0L, (System.nanoTime() - iterationStartNanos) / 1_000_000L);
        final long heapNow = usedHeapBytes();
        final long heapDeltaBytes = heapNow - baselineHeapUsedBytes;
        final int threadsNow = snapshotTotalThreadCount();
        final int threadsDelta = threadsNow - baselineThreadCount;
        System.out.println(String.format(
                "[ClosureJVM] Iteration %d metrics: latency=%dms, heapDelta=%+d KB, threads=%d (%+d)",
                iteration, elapsedMs, heapDeltaBytes / 1024, threadsNow, threadsDelta));

        // Configurable invariants (v0.2): thresholds checked here. Defaults disabled unless props set.
        try {
            Invariants.evaluateAndMaybeFail(iteration, elapsedMs, heapDeltaBytes, threadsNow, threadsDelta);
        } catch (IllegalStateException e) {
            // If configured to fail-hard, rethrow to stop the iteration loop fast
            if (Boolean.getBoolean("closurejvm.forceExitOnLeak")) {
                System.err.println("[ClosureJVM] Forcing process exit due to invariant violation (closurejvm.forceExitOnLeak=true)");
                System.exit(2);
            }
            throw e;
        }

        Map<Long, Thread> currentNonDaemon = snapshotNonDaemonThreads();
        Set<Long> currentIds = currentNonDaemon.keySet();

        // New non-daemon threads that were not present at iteration start
        Set<Long> newNonDaemon = new HashSet<>(currentIds);
        newNonDaemon.removeAll(baselineNonDaemonThreadIds);

        boolean leakDetected = false;
        if (!newNonDaemon.isEmpty()) {
            System.err.println("[ClosureJVM] Thread leak detected after iteration " + iteration + ": " + newNonDaemon.size() + " new non-daemon thread(s) remain");
            for (Long id : newNonDaemon) {
                Thread t = currentNonDaemon.get(id);
                if (t == null) {
                    continue;
                }
                System.err.println("  - Thread '" + t.getName() + "' (id=" + t.getId() + ", state=" + t.getState() + ")");
                StackTraceElement[] st = t.getStackTrace();
                int limit = Math.min(10, st.length);
                for (int i = 0; i < limit; i++) {
                    System.err.println("      at " + st[i]);
                }
                if (st.length > limit) {
                    System.err.println("      ..." + (st.length - limit) + " more");
                }
            }
            leakDetected = true;
        }

        // Best-effort executor leak detection: executors created during this iteration that are not shutdown
        Set<ExecutorService> newActiveExecutors = new HashSet<>();
        for (ExecutorService ex : liveTrackedExecutors()) {
            if (ex == null) continue;
            int id = System.identityHashCode(ex);
            if (!baselineActiveExecutorIdentities.contains(id) && !ex.isShutdown()) {
                newActiveExecutors.add(ex);
            }
        }
        if (!newActiveExecutors.isEmpty()) {
            System.err.println("[ClosureJVM] Executor leak detected after iteration " + iteration + ": " + newActiveExecutors.size() + " executor(s) not shutdown");
            for (ExecutorService ex : newActiveExecutors) {
                String type = ex.getClass().getName();
                String extra = "";
                if (ex instanceof ScheduledExecutorService) {
                    ThreadPoolExecutor tpe = ex instanceof ThreadPoolExecutor ? (ThreadPoolExecutor) ex : null;
                    int q = -1;
                    if (tpe != null && tpe.getQueue() != null) q = tpe.getQueue().size();
                    extra = String.format(" [scheduledExecutor, poolSize=%s, active=%s, queued=%d, isTerminated=%s]",
                            tpe != null ? tpe.getPoolSize() : -1,
                            tpe != null ? tpe.getActiveCount() : -1,
                            q,
                            ex.isTerminated());
                } else if (ex instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor tpe = (ThreadPoolExecutor) ex;
                    extra = String.format(" [poolSize=%d, active=%d, queued=%d, isTerminated=%s]",
                            tpe.getPoolSize(), tpe.getActiveCount(), tpe.getQueue() != null ? tpe.getQueue().size() : -1, ex.isTerminated());
                }
                System.err.println("  - Executor " + type + "@" + Integer.toHexString(System.identityHashCode(ex)) + extra);
            }
            leakDetected = true;
        }

        // Best-effort timer leak detection: timers created during this iteration that are not cancelled
        Set<Timer> newActiveTimers = new HashSet<>();
        for (Timer t : liveTrackedTimers()) {
            if (t == null) continue;
            int id = System.identityHashCode(t);
            // Timer has no isCancelled API; we can only infer via purge()/cancel usage. Best-effort: assume live if thread exists.
            if (!baselineActiveTimerIdentities.contains(id)) {
                newActiveTimers.add(t);
            }
        }
        if (!newActiveTimers.isEmpty()) {
            System.err.println("[ClosureJVM] Timer leak (best-effort) after iteration " + iteration + ": " + newActiveTimers.size() + " timer(s) may be active");
            for (Timer t : newActiveTimers) {
                System.err.println("  - Timer " + t.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(t)) + " (daemon=" + isTimerDaemon(t) + ")");
            }
            leakDetected = true;
        }

        if (leakDetected) {
            // Optional hard-exit for demos/CI so leaked non-daemon threads don't keep JVM alive
            if (Boolean.getBoolean("closurejvm.forceExitOnLeak")) {
                System.err.println("[ClosureJVM] Forcing process exit due to leak detection (closurejvm.forceExitOnLeak=true)");
                System.exit(2);
            }
            // Fail fast for v0.1 to make leaks obvious
            throw new IllegalStateException("Leak(s) detected after iteration " + iteration);
        }

        System.out.println("Ending iteration");
    }

    private static Set<Long> snapshotNonDaemonThreadIds() {
        return new HashSet<>(snapshotNonDaemonThreads().keySet());
    }

    private static Map<Long, Thread> snapshotNonDaemonThreads() {
        // getAllStackTraces() gives us all live threads and avoids missing threads not in current ThreadGroup
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        Map<Long, Thread> result = new HashMap<>();
        for (Thread t : all.keySet()) {
            if (t != null && t.isAlive() && !t.isDaemon()) {
                result.put(t.getId(), t);
            }
        }
        return result;
    }

    private static int snapshotTotalThreadCount() {
        // Total live threads (daemon + non-daemon), best-effort via all stack traces
        return Thread.getAllStackTraces().keySet().size();
    }

    public static <T extends ExecutorService> T trackExecutor(T executor) {
        if (executor != null) {
            trackedExecutorRefs.add(new java.lang.ref.WeakReference<>(executor));
        }
        return executor;
    }

    public static Timer trackTimer(Timer timer) {
        if (timer != null) {
            trackedTimerRefs.add(new java.lang.ref.WeakReference<>(timer));
        }
        return timer;
    }

    private static Set<Integer> snapshotActiveExecutorIdentities() {
        Set<Integer> ids = new HashSet<>();
        for (ExecutorService ex : liveTrackedExecutors()) {
            if (ex != null && !ex.isShutdown()) {
                ids.add(System.identityHashCode(ex));
            }
        }
        return ids;
    }

    private static Set<Integer> snapshotActiveTimerIdentities() {
        Set<Integer> ids = new HashSet<>();
        for (Timer t : liveTrackedTimers()) {
            if (t != null) {
                ids.add(System.identityHashCode(t));
            }
        }
        return ids;
    }

    private static boolean isTimerDaemon(Timer t) {
        // Timer has a private thread; exposing daemon status would require reflection. Best-effort: infer from name scan.
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            if (th != null && th.isAlive() && th.getName() != null && th.getName().startsWith("Timer-")) {
                return th.isDaemon();
            }
        }
        return false;
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static void setLastInvariantViolations(java.util.List<Invariants.Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            lastInvariantViolations = java.util.Collections.emptyList();
            return;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Invariants.Violation v : violations) {
            out.add(v.name + ": " + v.detail);
        }
        lastInvariantViolations = out;
    }

    public static java.util.List<String> getLastInvariantViolations() {
        return new java.util.ArrayList<>(lastInvariantViolations);
    }

    private static Set<ExecutorService> liveTrackedExecutors() {
        Set<ExecutorService> live = new HashSet<>();
        trackedExecutorRefs.removeIf(ref -> ref == null || ref.get() == null);
        for (java.lang.ref.WeakReference<ExecutorService> ref : trackedExecutorRefs) {
            ExecutorService ex = ref.get();
            if (ex != null) live.add(ex);
        }
        return live;
    }

    private static Set<Timer> liveTrackedTimers() {
        Set<Timer> live = new HashSet<>();
        trackedTimerRefs.removeIf(ref -> ref == null || ref.get() == null);
        for (java.lang.ref.WeakReference<Timer> ref : trackedTimerRefs) {
            Timer t = ref.get();
            if (t != null) live.add(t);
        }
        return live;
    }
}
