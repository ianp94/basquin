package agent;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Core agent for Basquin - a persistent execution harness for JVM web applications
 * that uses coverage-guided exploration to discover availability, performance, and
 * correctness failures.
 *
 * Per-iteration state lives on an {@link IterationContext} (v0.6): {@link #begin()} returns
 * one and {@link #end(IterationContext)} consumes it. The legacy {@link #beginIteration()} /
 * {@link #endIteration()} pair is retained as thread-local-backed wrappers so existing
 * callers are unchanged; because the context is per-thread, concurrent begin/end pairs no
 * longer stomp each other's latency baseline or leak set. See docs/DESIGN-DECISIONS.md
 * DD-005 / DD-010.
 */
public class Agent {

    private static final AtomicInteger ITERATION = new AtomicInteger(0);

    // Global registries and infrastructure (not per-iteration).
    // Keep weak references so tracking doesn't retain executors/timers long-term.
    private static final Set<java.lang.ref.WeakReference<ExecutorService>> trackedExecutorRefs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<java.lang.ref.WeakReference<Timer>> trackedTimerRefs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Timer invariantSampler = new Timer("Basquin-InvariantSampler", true);
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    // "Last completed iteration" evidence, exposed to servlet integrations (filter/valve,
    // status servlet). Under serialized execution this reflects the just-finished iteration.
    private static volatile List<String> lastInvariantViolations = Collections.emptyList();
    private static volatile String lastInvariantStack = "";

    // Backs the legacy beginIteration()/endIteration() wrappers: the context for the
    // iteration in progress on this thread.
    private static final ThreadLocal<IterationContext> CURRENT = new ThreadLocal<>();

    /**
     * Entry point for the JVM agent
     * @param agentArgs arguments passed to the agent
     * @param instrumentation instrumentation object for the JVM
     */
    public static void premain(String agentArgs, java.lang.instrument.Instrumentation instrumentation) {
        System.out.println("Basquin Agent initialized");
        // Opt-in, default OFF: the bench path runs the valve AND this agent, and two boundaries would
        // double-count. The operator sets -Dbasquin.boundary=agent (it mounts no valve); the bench path
        // leaves it unset, so the valve stays the sole boundary there.
        if (!"agent".equals(System.getProperty("basquin.boundary"))) {
            return;
        }
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges() // advice-only; no class-schema changes
                    .type(ElementMatchers.named("org.apache.catalina.core.StandardHostValve"))
                    .transform((builder, type, cl, module, pd) -> builder.visit(
                            Advice.to(TomcatBoundaryAdvice.class).on(ElementMatchers.named("invoke"))))
                    .installOn(instrumentation);
            System.out.println("[Basquin] agent boundary installed on StandardHostValve");
        } catch (Throwable t) {
            // Degrade, don't break: an app whose Tomcat internals differ just runs uninstrumented.
            System.err.println("[Basquin] agent boundary NOT installed: " + t);
        }
    }

    // --- Explicit context API (preferred) ---

    /** Begin an iteration, returning its context. Pass the result to {@link #end(IterationContext)}. */
    public static IterationContext begin() {
        IterationContext ctx = new IterationContext(ITERATION.incrementAndGet());
        if (!runner.util.StatusReporter.isEnabled()) {
            System.out.println("Beginning iteration");
        }
        // Keep baseline and end-of-iteration heap readings symmetric (see end());
        // GC runs before the latency clock starts so it never counts against the iteration.
        if (Boolean.getBoolean("basquin.heap.gcBeforeMeasure")) {
            System.gc();
        }
        ctx.startNanos = System.nanoTime();
        ctx.baselineHeapBytes = usedHeapBytes();
        ctx.baselineThreadCount = snapshotTotalThreadCount();
        ctx.monitoredThread = Thread.currentThread();
        scheduleLatencySampleIfConfigured(ctx);
        ctx.baselineNonDaemonThreadIds = snapshotNonDaemonThreadIds();
        ctx.baselineActiveExecutorIdentities = snapshotActiveExecutorIdentities();
        ctx.baselineActiveTimerIdentities = snapshotActiveTimerIdentities();
        return ctx;
    }

    /** End the iteration described by {@code ctx}, running metrics, invariants, and leak checks. */
    public static void end(IterationContext ctx) {
        // Latency is measured at entry, before the grace sleep below, so the
        // grace period never counts against the iteration's latency invariant.
        final long elapsedMs = Math.max(0L, (System.nanoTime() - ctx.startNanos) / 1_000_000L);

        // Small grace period: allow short-lived tasks to finish before the leak snapshot
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simple metrics snapshot and print-only signal
        // Opt-in GC so heap delta reflects retention rather than allocation noise.
        // Off by default: System.gc() is slow and distorts iteration throughput.
        if (Boolean.getBoolean("basquin.heap.gcBeforeMeasure")) {
            System.gc();
        }
        final long heapNow = usedHeapBytes();
        final long heapDeltaBytes = heapNow - ctx.baselineHeapBytes;
        final int threadsNow = snapshotTotalThreadCount();
        final int threadsDelta = threadsNow - ctx.baselineThreadCount;
        ctx.latencyMs = elapsedMs;
        ctx.heapDeltaBytes = heapDeltaBytes;
        ctx.threadCount = threadsNow;
        ctx.threadDelta = threadsDelta;
        if (!runner.util.StatusReporter.isEnabled()) {
            System.out.println(String.format(
                    "[Basquin] Iteration %d metrics: latency=%dms, heapDelta=%+d KB, threads=%d (%+d)",
                    ctx.iterationNumber, elapsedMs, heapDeltaBytes / 1024, threadsNow, threadsDelta));
        }

        // Configurable invariants (v0.2): thresholds checked here. Defaults disabled unless props set.
        try {
            Invariants.evaluateAndMaybeFail(ctx, elapsedMs, heapDeltaBytes, threadsNow, threadsDelta);
        } catch (IllegalStateException e) {
            // Publish evidence before the throw propagates so servlet integrations can read it.
            publishInvariantEvidence(ctx);
            cancelLatencySample(ctx);
            recordStatus(ctx);
            // If configured to fail-hard, rethrow to stop the iteration loop fast
            if (Boolean.getBoolean("basquin.forceExitOnLeak")) {
                System.err.println("[Basquin] Forcing process exit due to invariant violation (basquin.forceExitOnLeak=true)");
                System.exit(2);
            }
            throw e;
        }
        publishInvariantEvidence(ctx);
        cancelLatencySample(ctx);

        Map<Long, Thread> currentNonDaemon = snapshotNonDaemonThreads();
        Set<Long> currentIds = currentNonDaemon.keySet();

        // New non-daemon threads that were not present at iteration start
        Set<Long> newNonDaemon = new HashSet<>(currentIds);
        newNonDaemon.removeAll(ctx.baselineNonDaemonThreadIds);

        boolean leakDetected = false;
        if (!newNonDaemon.isEmpty()) {
            System.err.println("[Basquin] Thread leak detected after iteration " + ctx.iterationNumber + ": " + newNonDaemon.size() + " new non-daemon thread(s) remain");
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
            if (!ctx.baselineActiveExecutorIdentities.contains(id) && !ex.isShutdown()) {
                newActiveExecutors.add(ex);
            }
        }
        if (!newActiveExecutors.isEmpty()) {
            System.err.println("[Basquin] Executor leak detected after iteration " + ctx.iterationNumber + ": " + newActiveExecutors.size() + " executor(s) not shutdown");
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
            if (!ctx.baselineActiveTimerIdentities.contains(id)) {
                newActiveTimers.add(t);
            }
        }
        if (!newActiveTimers.isEmpty()) {
            System.err.println("[Basquin] Timer leak (best-effort) after iteration " + ctx.iterationNumber + ": " + newActiveTimers.size() + " timer(s) may be active");
            for (Timer t : newActiveTimers) {
                System.err.println("  - Timer " + t.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(t)) + " (daemon=" + timerDaemonStatus(t) + ")");
            }
            leakDetected = true;
        }

        ctx.leakDetected = leakDetected;
        recordStatus(ctx);
        if (leakDetected) {
            // Optional hard-exit for demos/CI so leaked non-daemon threads don't keep JVM alive
            if (Boolean.getBoolean("basquin.forceExitOnLeak")) {
                System.err.println("[Basquin] Forcing process exit due to leak detection (basquin.forceExitOnLeak=true)");
                System.exit(2);
            }
            // Fail fast for v0.1 to make leaks obvious
            throw new IllegalStateException("Leak(s) detected after iteration " + ctx.iterationNumber);
        }

        if (!runner.util.StatusReporter.isEnabled()) {
            System.out.println("Ending iteration");
        }
    }

    private static void recordStatus(IterationContext ctx) {
        runner.util.StatusReporter.recordIteration(
                ctx.latencyMs, ctx.heapDeltaBytes / 1024L, ctx.threadCount, ctx.leakDetected, ctx.invariantViolations);
    }

    // --- Legacy API (thread-local-backed wrappers over the context API) ---

    /**
     * Begin a new iteration boundary on the current thread.
     * Prefer {@link #begin()} in new code; this stores the context in a thread-local.
     */
    public static void beginIteration() {
        CURRENT.set(begin());
    }

    /**
     * End the current thread's iteration boundary.
     * Prefer {@link #end(IterationContext)} in new code.
     */
    public static void endIteration() {
        IterationContext ctx = CURRENT.get();
        if (ctx == null) {
            // Defensive: end without a matching begin. Synthesize a context so we still run the
            // checks rather than NPE — but its metrics measure ~nothing, so warn loudly that
            // this is a caller-ordering bug rather than reporting plausible all-zero numbers.
            System.err.println("[Basquin] endIteration() called without a matching beginIteration() "
                    + "on this thread; this iteration's metrics are not meaningful (caller-ordering bug).");
            ctx = begin();
        }
        try {
            end(ctx);
        } finally {
            CURRENT.remove();
        }
    }

    private static Set<Long> snapshotNonDaemonThreadIds() {
        return new HashSet<>(snapshotNonDaemonThreads().keySet());
    }

    private static Map<Long, Thread> snapshotNonDaemonThreads() {
        // Preferred source: the JVMTI native agent's event-maintained leak set — real Thread
        // objects tracked from ThreadStart/ThreadEnd, so no enumeration happens at all.
        // Fallback: enumerate via the root ThreadGroup, which returns Thread objects without
        // walking any stacks (the previous Thread.getAllStackTraces() forced a safepoint stack
        // walk of every thread per iteration only to discard the stacks). Either way, stacks
        // are captured lazily below, only for threads that actually leaked.
        Map<Long, Thread> result = new HashMap<>();
        // Native path returns null on an internal error (e.g. allocation failure); fall back to
        // enumeration rather than treating it as "no leaked threads".
        Thread[] threads = NativeThreadTracker.isActive() ? NativeThreadTracker.nonDaemonThreads() : null;
        if (threads == null) {
            threads = enumerateAllThreads();
        }
        for (Thread t : threads) {
            if (t != null && t.isAlive() && !t.isDaemon()) {
                result.put(t.getId(), t);
            }
        }
        return result;
    }

    private static int snapshotTotalThreadCount() {
        // Prefer the JVMTI native agent's event-maintained count when it is loaded: no JMX call,
        // no stack walk. Falls back to the management bean's count otherwise. Both are used only
        // via deltas across an iteration, so the small absolute offset between the two sources
        // (JVMTI sees threads JMX does not) does not affect the thread-delta signal.
        if (NativeThreadTracker.isActive()) {
            return NativeThreadTracker.liveThreadCount();
        }
        return THREAD_MX.getThreadCount();
    }

    private static Thread[] enumerateAllThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        if (root == null) {
            return new Thread[0];
        }
        for (ThreadGroup parent = root.getParent(); parent != null; parent = root.getParent()) {
            root = parent;
        }
        // activeCount() is an estimate; oversize and retry until the array is not filled exactly,
        // which guarantees we captured every thread rather than truncating.
        int size = root.activeCount() + 16;
        Thread[] threads;
        int n;
        while (true) {
            threads = new Thread[size];
            n = root.enumerate(threads, true);
            if (n < size) {
                break;
            }
            size *= 2;
        }
        return java.util.Arrays.copyOf(threads, n);
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

    private static String timerDaemonStatus(Timer t) {
        // Timer's thread is private; reflection only works with --add-opens java.base/java.util.
        // Report "unknown" rather than guessing from an unrelated Timer-* thread.
        try {
            java.lang.reflect.Field f = Timer.class.getDeclaredField("thread");
            f.setAccessible(true);
            Thread th = (Thread) f.get(t);
            return th != null ? String.valueOf(th.isDaemon()) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void publishInvariantEvidence(IterationContext ctx) {
        lastInvariantViolations = ctx.invariantViolations;
        lastInvariantStack = ctx.invariantStack;
    }

    public static java.util.List<String> getLastInvariantViolations() {
        return new ArrayList<>(lastInvariantViolations);
    }

    public static String getLastInvariantStack() {
        return lastInvariantStack;
    }

    /** Called by {@link Invariants} during evaluation to record evidence onto the context. */
    static void recordInvariantEvidence(IterationContext ctx, List<Invariants.Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            ctx.invariantViolations = Collections.emptyList();
        } else {
            List<String> out = new ArrayList<>();
            for (Invariants.Violation v : violations) {
                out.add(v.name + ": " + v.detail);
            }
            ctx.invariantViolations = out;
        }
        ctx.invariantStack = buildStackSnapshot(ctx);
    }

    private static String buildStackSnapshot(IterationContext ctx) {
        String mode = System.getProperty("basquin.invariant.stack", "current");
        StringBuilder sb = new StringBuilder();
        String sampled = ctx.sampledExecStack;
        if (sampled != null && !sampled.isEmpty()) {
            sb.append("executionThread(sampled)=\n").append(sampled);
        }
        if ("off".equalsIgnoreCase(mode)) {
            return "";
        }
        if ("all".equalsIgnoreCase(mode)) {
            int maxFrames = Integer.getInteger("basquin.invariant.stack.maxFrames", 10);
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t == null || !t.isAlive()) continue;
                sb.append("Thread '").append(t.getName()).append("' (id=").append(t.getId())
                  .append(", state=").append(t.getState()).append(")\n");
                StackTraceElement[] st = e.getValue();
                int limit = Math.min(maxFrames, st != null ? st.length : 0);
                for (int i = 0; i < limit; i++) {
                    sb.append("  at ").append(st[i]).append('\n');
                }
                if (st != null && st.length > limit) sb.append("  ...").append(st.length - limit).append(" more\n");
            }
            return sb.toString();
        }
        // default: current thread only
        Thread t = Thread.currentThread();
        sb.append("Thread '").append(t.getName()).append("' (id=").append(t.getId())
          .append(", state=").append(t.getState()).append(")\n");
        StackTraceElement[] st = t.getStackTrace();
        int limit = Math.min(Integer.getInteger("basquin.invariant.stack.maxFrames", 15), st.length);
        for (int i = 0; i < limit; i++) sb.append("  at ").append(st[i]).append('\n');
        if (st.length > limit) sb.append("  ...").append(st.length - limit).append(" more\n");
        return sb.toString();
    }

    private static void scheduleLatencySampleIfConfigured(IterationContext ctx) {
        if (!Boolean.getBoolean("basquin.invariant.latency.sample")) return;
        Long latencyMax = null;
        try { latencyMax = Long.getLong("basquin.invariant.latency.maxMs"); } catch (Exception ignored) {}
        if (latencyMax == null || latencyMax <= 0) return;
        final Thread threadToSample = ctx.monitoredThread;
        if (threadToSample == null) return;
        TimerTask task = new TimerTask() {
            @Override public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Thread '").append(threadToSample.getName()).append("' (id=")
                      .append(threadToSample.getId()).append(", state=")
                      .append(threadToSample.getState()).append(")\n");
                    StackTraceElement[] st = threadToSample.getStackTrace();
                    int limit = Math.min(Integer.getInteger("basquin.invariant.stack.maxFrames", 15), st.length);
                    for (int i = 0; i < limit; i++) sb.append("  at ").append(st[i]).append('\n');
                    if (st.length > limit) sb.append("  ...").append(st.length - limit).append(" more\n");
                    ctx.sampledExecStack = sb.toString();
                } catch (Throwable ignored) {}
            }
        };
        ctx.samplingTask = task;
        try {
            invariantSampler.schedule(task, Math.max(1L, latencyMax));
        } catch (IllegalStateException ignored) {}
    }

    private static void cancelLatencySample(IterationContext ctx) {
        try {
            if (ctx.samplingTask != null) ctx.samplingTask.cancel();
        } catch (Throwable ignored) {}
        ctx.samplingTask = null;
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
