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

    /** onExit's result: invariant headers to set (never null; may be empty) + throwable to propagate. */
    public static final class ExitResult {
        public final Map<String, String> headers;
        public final Throwable toThrow; // null if clean
        ExitResult(Map<String, String> headers, Throwable toThrow) { this.headers = headers; this.toThrow = toThrow; }
    }

    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);
    private static final ThreadLocal<Phase> PHASE = new ThreadLocal<>();
    private static final Map<String, String> NO_HEADERS = Collections.emptyMap();

    private RequestBoundary() { }

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
        if (phase != Phase.EXPLORE_BEGAN) {
            return new ExitResult(NO_HEADERS, appError); // control / load: nothing to close
        }
        Throwable pending = appError;
        Map<String, String> headers = NO_HEADERS;
        try {
            Agent.endIteration();
        } catch (Throwable endError) {
            if (pending != null) pending.addSuppressed(endError);
            else pending = endError;
        } finally {
            try {
                headers = exitHeaders();
            } finally {
                ITERATION_LOCK.unlock();
            }
        }
        return new ExitResult(headers, pending);
    }

    /** Headers to set on the EXPLORE exit: invariant evidence (if any) + the always-present cost header.
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
        return h.isEmpty() ? NO_HEADERS : h;
    }
}
