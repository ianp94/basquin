package com.closurejvm.valve;

import agent.Agent;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.ServletException;

/**
 * Tomcat Valve that wraps every request in a ClosureJVM iteration boundary, for ANY
 * web application deployed on the container — without modifying that application's WAR
 * or web.xml. This is the third-party-app integration path (see DESIGN-DECISIONS.md DD-009);
 * the in-WAR {@code IterationFilter} remains for our own demo WAR.
 *
 * Register it in Tomcat's server.xml (Engine, Host, or Context level), e.g.:
 * <pre>{@code
 *   <Host name="localhost" ...>
 *     <Valve className="com.closurejvm.valve.ClosureJVMValve"/>
 *   </Host>
 * }</pre>
 * The valve JAR goes in Tomcat's lib/, and the agent JAR is injected via CATALINA_OPTS
 * (-javaagent + classpath) exactly as for the demo WAR.
 *
 * Iterations are SERIALIZED (a fair lock): the Agent's per-iteration baselines are
 * process-global, so heap/thread deltas are only meaningful when one request owns the
 * iteration window. The harness measures iteration cleanliness, not throughput under
 * load (DD-005).
 */
public class ClosureJVMValve extends ValveBase {

    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);

    public ClosureJVMValve() {
        // Valve participates in async request processing.
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        ITERATION_LOCK.lock();
        Agent.beginIteration();
        try {
            getNext().invoke(request, response);
        } finally {
            try {
                try {
                    Agent.endIteration();
                } finally {
                    // Expose invariant evidence as response headers before releasing the lock,
                    // since the next iteration's begin clears the Agent's last-violation state.
                    writeInvariantHeaders(response);
                }
            } finally {
                ITERATION_LOCK.unlock();
            }
        }
    }

    private static void writeInvariantHeaders(HttpServletResponse response) {
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
}
