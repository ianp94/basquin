package com.closurejvm.valve;

import agent.Agent;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tomcat Valve that wraps every request in a ClosureJVM iteration boundary, for ANY web
 * application deployed on the container — without modifying that application's WAR or
 * web.xml. This is the third-party-app integration path (see DESIGN-DECISIONS.md DD-009);
 * the in-WAR {@code IterationFilter} remains for our own demo WAR, and the two are mutually
 * exclusive (never run both on the same app).
 *
 * <p><b>Namespace-free by design (DD-011).</b> This valve's compiled bytecode references no
 * {@code javax.servlet} or {@code jakarta.servlet} class, so a single jar runs on both
 * Tomcat 9 (javax) and Tomcat 10+ (jakarta). It achieves that by (1) narrowing the
 * {@code invoke} override to {@code throws IOException} only, (2) writing headers through the
 * Catalina {@link Response} (whose {@code setHeader}/{@code isCommitted} are concrete methods,
 * not the servlet interface), and (3) re-raising the checked exception from the next valve via
 * {@link #sneakyThrow} without naming {@code ServletException}.
 *
 * <p>Register it in Tomcat's server.xml (Engine/Host/Context level) or a global context.xml:
 * <pre>{@code <Valve className="com.closurejvm.valve.ClosureJVMValve"/>}</pre>
 * The valve jar goes in Tomcat's lib/, and the agent jar is injected via CATALINA_OPTS
 * (-javaagent + classpath).
 *
 * <p>Iterations are SERIALIZED (a fair lock): the Agent's heap/thread deltas are process-global,
 * so they are only meaningful when one request owns the iteration window. The harness measures
 * iteration cleanliness, not throughput under load (DD-005).
 */
public class ClosureJVMValve extends ValveBase {

    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);

    public ClosureJVMValve() {
        // Valve participates in async request processing.
        super(true);
    }

    // Narrowed throws (no ServletException): a legal override, and it keeps the servlet
    // namespace out of this method's signature and bytecode.
    @Override
    public void invoke(Request request, Response response) throws IOException {
        ITERATION_LOCK.lock();
        Agent.beginIteration();
        try {
            try {
                getNext().invoke(request, response);
            } catch (IOException | RuntimeException | Error e) {
                throw e;
            } catch (Throwable servletException) {
                // ServletException (javax or jakarta) — re-raise without referencing the type.
                sneakyThrow(servletException);
            }
        } finally {
            try {
                try {
                    Agent.endIteration();
                } finally {
                    // Write headers before releasing the lock: the next iteration's begin
                    // clears the Agent's last-violation state.
                    writeInvariantHeaders(response);
                }
            } finally {
                ITERATION_LOCK.unlock();
            }
        }
    }

    private static void writeInvariantHeaders(Response response) {
        try {
            List<String> violations = Agent.getLastInvariantViolations();
            if (violations != null && !violations.isEmpty() && !response.isCommitted()) {
                response.setHeader("X-ClosureJVM-Invariant-Count", String.valueOf(violations.size()));
                String first = violations.get(0);
                if (first != null && first.length() > 200) {
                    first = first.substring(0, 200);
                }
                response.setHeader("X-ClosureJVM-Invariant-Detail", first);
            }
        } catch (Throwable ignored) {
            // Header reporting is best-effort; never let it fail a request.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
