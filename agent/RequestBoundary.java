package agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The shared three-state request boundary (control / load / explore), extracted from BasquinValve so
 * the valve AND the agent's bytecode boundary (TomcatBoundaryAdvice) run identical logic
 * (DD-005/DD-010/DD-029).
 *
 * <p><b>Catalina-free by design.</b> Agent classes load on the boot loader (the operator injects
 * {@code -Xbootclasspath/a:basquin-agent.jar}), which cannot see {@code org.apache.catalina.*} — a
 * child loader. So this class names no Catalina type: it takes the request URI/query as strings and
 * returns a {@link Decision}/{@link ExitResult}; each caller performs its own Catalina response I/O.
 */
public final class RequestBoundary {

    public enum Phase { CONTROL_HANDLED, LOAD_PASSTHROUGH, EXPLORE_BEGAN }

    /** onEnter's result: the phase and, for control requests, the plaintext body to write. */
    public static final class Decision {
        public final Phase phase;
        public final String controlBody; // non-null iff phase == CONTROL_HANDLED
        Decision(Phase phase, String controlBody) { this.phase = phase; this.controlBody = controlBody; }
        /** True iff the app body must be skipped (a /__basquin control request handled here). */
        public boolean skipApp() { return phase == Phase.CONTROL_HANDLED; }
    }

    /** onExit's result: invariant + cost headers to set (never null; may be empty) + throwable to propagate. */
    public static final class ExitResult {
        public final Map<String, String> headers;
        public final Throwable toThrow; // null if clean
        ExitResult(Map<String, String> headers, Throwable toThrow) { this.headers = headers; this.toThrow = toThrow; }
    }

    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);
    private static final ThreadLocal<Phase> PHASE = new ThreadLocal<>();
    /** DD-040: the driver's salted request id for the explore iteration in flight on this thread.
     *  Set by the glue only on the EXPLORE_BEGAN branch; cleared in {@link #onExit} with the same
     *  discipline as {@link #PHASE}. */
    private static final ThreadLocal<String> REQ_ID = new ThreadLocal<>();
    private static final Map<String, String> NO_HEADERS = Collections.emptyMap();

    private RequestBoundary() { }

    /**
     * DD-040: associate the driver's salted request id with the explore iteration in flight, so
     * {@link #onExit} can publish this request's measurements to {@link ResultStore} — a channel
     * that survives a response the app already committed, which the reporting headers do not.
     *
     * <p>Call this <em>only</em> when {@code onEnter} returned {@link Phase#EXPLORE_BEGAN}. That is
     * why {@code onEnter} does not take the id itself: the caller cannot know the phase until
     * {@code onEnter} returns, so reading the header there would put work on every load-mode
     * request, and the load path must gain zero instructions.
     *
     * <p>Sets the thread-local <b>unconditionally</b> — a null argument overwrites. Connector
     * threads are pooled, and an id left behind by an earlier request would publish a later
     * kubelet probe's metrics under a driver id: stale data presented as fresh.
     */
    public static void stampRequestId(String reqId) {
        REQ_ID.set(reqId);
    }

    /** Before the wrapped invoke. Never throws. Stashes the phase in a thread-local for {@link #onExit}. */
    public static Decision onEnter(String uri, String query) {
        try {
            // Control surface: /__basquin/* handled here, never reaches the app.
            String control = LoadModeControl.handle(uri, query);
            if (control != null) {
                PHASE.set(Phase.CONTROL_HANDLED);
                return new Decision(Phase.CONTROL_HANDLED, control);
            }
            // Load mode (DD-029): passthrough — no lock, no begin, run concurrently.
            if (LoadMode.isLoad(System.currentTimeMillis())) {
                PHASE.set(Phase.LOAD_PASSTHROUGH);
                return new Decision(Phase.LOAD_PASSTHROUGH, null);
            }
        } catch (Throwable t) {
            // Never throw out of onEnter: an unexpected failure here degrades this one request to
            // passthrough rather than failing the app request (no lock is held at this point).
            PHASE.set(Phase.LOAD_PASSTHROUGH);
            return new Decision(Phase.LOAD_PASSTHROUGH, null);
        }
        // Explore (default): serialize + begin. Lock BEFORE begin; if begin throws, unlock and degrade
        // this one request to passthrough rather than stranding the fair lock forever.
        ITERATION_LOCK.lock();
        try {
            Agent.beginIteration();
        } catch (Throwable t) {
            ITERATION_LOCK.unlock();
            PHASE.set(Phase.LOAD_PASSTHROUGH);
            return new Decision(Phase.LOAD_PASSTHROUGH, null);
        }
        PHASE.set(Phase.EXPLORE_BEGAN);
        return new Decision(Phase.EXPLORE_BEGAN, null);
    }

    /**
     * After the wrapped invoke. {@code appError} is what the app threw (or null). Runs even when the app
     * was skipped. Never throws. Preserves the valve's exact exception semantics: the app's exception
     * wins and an endIteration error is attached as suppressed; an endIteration error on a clean request
     * surfaces as the returned throwable.
     */
    public static ExitResult onExit(Throwable appError) {
        Phase phase = PHASE.get();
        PHASE.remove();
        // Cleared for EVERY phase, not just explore: the id must never outlive the request that
        // carried it on a pooled connector thread (see stampRequestId).
        String reqId = REQ_ID.get();
        REQ_ID.remove();
        if (phase != Phase.EXPLORE_BEGAN) {
            return new ExitResult(NO_HEADERS, appError); // control / load: nothing to close
        }
        Throwable pending = appError;
        Map<String, String> headers = NO_HEADERS;
        // DD-040: capture the context BEFORE endIteration(), never from its outcome. endIteration()
        // throws on a hard-mode invariant violation and on a leak — exactly the iterations that
        // carry a finding — and its own finally does CURRENT.remove(), so a context read afterwards
        // is null precisely when there is something to publish. All the fields we need
        // (latency/heap/thread deltas, invariantViolations, leakDetected) are populated before any
        // of those throws, so the entry below is complete even on the throwing path.
        IterationContext ctx = Agent.currentContext();
        try {
            Agent.endIteration();
        } catch (Throwable endError) {
            if (pending != null) pending.addSuppressed(endError);
            else pending = endError;
        } finally {
            try {
                try {
                    headers = exitHeaders();
                } finally {
                    // Unconditional: whether or not endIteration() threw, and whether or not the
                    // headers could be built. This is the reliable channel.
                    publishResult(reqId, ctx);
                }
            } finally {
                ITERATION_LOCK.unlock();
            }
        }
        return new ExitResult(headers, pending);
    }

    /**
     * DD-040: publish this iteration's measurements under the driver's stamped id.
     *
     * <p>Built from the {@link IterationContext} — never from {@code Agent}'s process-global
     * {@code last*} statics — and written while ITERATION_LOCK is still held, so the numbers
     * provably belong to THIS request. Unstamped requests (load passthrough, kubelet probes, any
     * non-driver traffic) write nothing at all. Never throws: reporting must not fail an app
     * request.
     */
    private static void publishResult(String reqId, IterationContext ctx) {
        if (reqId == null || ctx == null) return;
        try {
            List<String> violations = ctx.invariantViolations;
            int count = (violations == null) ? 0 : violations.size();
            String detail = (count > 0) ? violations.get(0) : null;
            // Composed exactly as the X-Basquin-Cost header is (Agent.lastCostCsv): heap in KB.
            String costCsv = ctx.latencyMs + "," + (ctx.heapDeltaBytes / 1024L) + "," + ctx.threadDelta;
            ResultStore.put(reqId, new ResultStore.Entry(costCsv, count, detail, ctx.leakDetected));
        } catch (Throwable ignored) {
            // best-effort publication; never let it fail a request
        }
    }

    // Test hooks: quiescence is only assertable if a test can hold the same lock. Public (not
    // package-private) because the test that pins awaitQuiescence's wait-then-observe behavior
    // lives in test/LoadModeControlTest.java, an existing file this task must not fork — and that
    // file is not in package `agent`, so package-private access would make these two uncallable
    // from it. Nothing outside this task's own tests calls either method.
    public static void lockForTest() { ITERATION_LOCK.lock(); }
    public static void unlockForTest() { ITERATION_LOCK.unlock(); }

    /** DD-040: wait until no explore iteration is in flight, bounded. Used only by the control
     *  path so a result poll observes the entry the in-flight iteration is about to write. */
    public static boolean awaitQuiescence(long millis) {
        try {
            if (ITERATION_LOCK.tryLock(millis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                ITERATION_LOCK.unlock();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * DD-040 §A.6: this JVM's pod identity, resolved ONCE at class load — {@code HOSTNAME} is the pod
     * name in Kubernetes, the same source {@code runner/util/DashboardClient} uses for the dashboard
     * id (DD-013). Null outside Kubernetes, in which case the header is simply absent.
     *
     * <p>The environment read happens once, at class load; {@link #podId()} adds only a property
     * lookup, and only on the explore exit — the load path gains nothing at all.
     *
     * <p>It is deliberately <em>not</em> the mechanism that routes the result poll. This header rides
     * the same response as {@code X-Basquin-Cost}, so it is present precisely when the driver already
     * has its measurements from the headers, and absent precisely when the response committed and the
     * poll is needed. It exists for attribution and for the two-replica reconciliation in DD-040's
     * verification — "which pod produced this violation" — while addressing the right pod is the
     * driver's job (see {@code runner.coverage.PodPollTargets}).
     */
    private static final String POD_ENV = resolvePodEnv();

    private static String resolvePodEnv() {
        try {
            String h = System.getenv("HOSTNAME");   // the pod name, when running in Kubernetes
            return (h == null || h.isEmpty()) ? null : h;
        } catch (Throwable t) {
            return null;   // a SecurityManager forbidding env reads must not break the boundary
        }
    }

    /**
     * This JVM's pod identity, or null when it has none (outside Kubernetes) — in which case the
     * header is simply absent rather than a made-up value.
     *
     * <p>{@code -Dbasquin.pod.id} overrides {@code HOSTNAME}, mirroring how DD-013's dashboard id
     * resolves ({@code basquin.dashboard.id} first, then {@code HOSTNAME}). It is what a deployment
     * uses when the pod name arrives through the Downward API as a JVM flag rather than as the
     * container hostname, and it is how a test can assert the header at all — {@code HOSTNAME} is
     * not something a test can set for the JVM it is running in.
     */
    private static String podId() {
        try {
            String p = System.getProperty("basquin.pod.id");
            if (p != null && !p.isEmpty()) return p;
        } catch (Throwable ignored) {
            // property access denied: fall back to the environment
        }
        return POD_ENV;
    }

    /** Headers to set on the EXPLORE exit: invariant evidence (if any) + the always-present cost header
     *  + this pod's identity.
     *  Read here — before onExit releases ITERATION_LOCK — so the numbers belong to THIS request. */
    private static Map<String, String> exitHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        try {
            List<String> violations = Agent.getLastInvariantViolations();
            if (violations != null && !violations.isEmpty()) {
                h.put("X-Basquin-Invariant-Count", String.valueOf(violations.size()));
                String first = violations.get(0);
                if (first != null) {
                    if (first.length() > 200) first = first.substring(0, 200);
                    h.put("X-Basquin-Invariant-Detail", first);
                }
            }
        } catch (Throwable ignored) {
            // invariant reporting is best-effort
        }
        try {
            h.put("X-Basquin-Cost", Agent.lastCostCsv());
        } catch (Throwable ignored) {
            // cost reporting is best-effort
        }
        String pod = podId();
        if (pod != null) h.put("X-Basquin-Pod", pod);
        return h.isEmpty() ? NO_HEADERS : h;
    }
}
