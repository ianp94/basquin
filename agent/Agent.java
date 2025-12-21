package agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Core agent for ClosureJVM - a persistent execution harness for JVM web applications
 * that uses coverage-guided exploration to discover availability, performance, and
 * correctness failures.
 */
public class Agent {

    private static volatile Set<Long> baselineNonDaemonThreadIds = Collections.emptySet();
    private static volatile int iteration = 0;

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

            // Fail fast for v0.1 to make leaks obvious
            throw new IllegalStateException("Thread leak(s) detected: " + newNonDaemon.size());
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
}
