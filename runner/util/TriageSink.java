package runner.util;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * In-process bounded handoff queue decoupling triage I/O (saved inputs, meta files,
 * stack dumps) from the iteration loop. The iteration enqueues a fully-materialized
 * write task and moves on; a single daemon consumer thread performs the file I/O.
 *
 * Guarantees (see docs/DESIGN-DECISIONS.md DD-006):
 * - Findings are never dropped: if the queue is full or the sink is shutting down,
 *   the write runs synchronously in the caller (backpressure over loss).
 * - Bounded: capacity via -Dbasquin.triage.queueCapacity (default 256), so the
 *   harness that hunts leaks cannot itself grow without bound.
 * - Shutdown flush: a shutdown hook drains remaining tasks before JVM exit.
 *
 * Call {@link #ensureStarted()} once at harness startup (runners do this) so the
 * consumer thread is created before the first iteration baseline, not mid-iteration
 * where it would show up as a thread delta.
 */
public final class TriageSink {

    private static final int CAPACITY = Integer.getInteger("basquin.triage.queueCapacity", 256);
    private static final ArrayBlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static volatile boolean shutdown = false;
    private static final Thread CONSUMER;

    static {
        CONSUMER = new Thread(TriageSink::drainLoop, "Basquin-TriageSink");
        CONSUMER.setDaemon(true);
        CONSUMER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(TriageSink::flushRemaining, "Basquin-TriageSink-Flush"));
    }

    private TriageSink() {}

    /** Trigger class initialization (consumer thread start) at a controlled point. */
    public static void ensureStarted() {
        // Class init did the work; method exists so callers have an explicit hook.
    }

    /**
     * Hand a write task to the consumer. The task must own all its data (defensive
     * copies made by the caller) — it will run on another thread, later.
     */
    public static void submit(Runnable writeTask) {
        if (!shutdown && QUEUE.offer(writeTask)) {
            return;
        }
        // Queue full or JVM shutting down: never drop a finding — write synchronously.
        runSafely(writeTask);
    }

    private static void drainLoop() {
        while (true) {
            try {
                runSafely(QUEUE.take());
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void flushRemaining() {
        // No new tasks enter the queue once shutdown is set (submit() runs synchronously then).
        shutdown = true;
        Runnable task;
        while ((task = QUEUE.poll()) != null) {
            runSafely(task);
        }
        // The consumer may have take()n a task and be mid-write; interrupt it out of an idle
        // take() and join so any in-flight write completes before the JVM exits. This is what
        // makes "never drop a finding" hold for a finding already handed to the consumer.
        CONSUMER.interrupt();
        try {
            CONSUMER.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.println("[Basquin][Triage] Write task failed: " + t);
        }
    }
}
