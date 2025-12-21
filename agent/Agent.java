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
    private static final Set<ExecutorService> trackedExecutors = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Timer> trackedTimers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile Set<Integer> baselineActiveExecutorIdentities = Collections.emptySet();
    private static volatile Set<Integer> baselineActiveTimerIdentities = Collections.emptySet();

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
        for (ExecutorService ex : trackedExecutors) {
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
                if (ex instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor tpe = (ThreadPoolExecutor) ex;
                    extra = String.format(" [poolSize=%d, active=%d, queued=%d, isTerminated=%s]",
                            tpe.getPoolSize(), tpe.getActiveCount(), tpe.getQueue() != null ? tpe.getQueue().size() : -1, ex.isTerminated());
                } else if (ex instanceof ScheduledExecutorService) {
                    extra = String.format(" [scheduledExecutor, isTerminated=%s]", ex.isTerminated());
                }
                System.err.println("  - Executor " + type + "@" + Integer.toHexString(System.identityHashCode(ex)) + extra);
            }
            leakDetected = true;
        }

        // Best-effort timer leak detection: timers created during this iteration that are not cancelled
        Set<Timer> newActiveTimers = new HashSet<>();
        for (Timer t : trackedTimers) {
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

    public static <T extends ExecutorService> T trackExecutor(T executor) {
        if (executor != null) {
            trackedExecutors.add(executor);
        }
        return executor;
    }

    public static Timer trackTimer(Timer timer) {
        if (timer != null) {
            trackedTimers.add(timer);
        }
        return timer;
    }

    private static Set<Integer> snapshotActiveExecutorIdentities() {
        Set<Integer> ids = new HashSet<>();
        for (ExecutorService ex : trackedExecutors) {
            if (ex != null && !ex.isShutdown()) {
                ids.add(System.identityHashCode(ex));
            }
        }
        return ids;
    }

    private static Set<Integer> snapshotActiveTimerIdentities() {
        Set<Integer> ids = new HashSet<>();
        for (Timer t : trackedTimers) {
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
}
