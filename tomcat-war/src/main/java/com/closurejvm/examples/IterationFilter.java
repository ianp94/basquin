package com.closurejvm.examples;

import agent.Agent;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps every request in an iteration boundary and exposes invariant info via headers.
 * Headers:
 * - X-ClosureJVM-Invariant-Count: number of violations (if any)
 * - X-ClosureJVM-Invariant-Detail: first violation detail (truncated)
 *
 * Iterations are SERIALIZED: exactly one request is inside an iteration at a time;
 * concurrent requests queue on the lock. The Agent's per-iteration baselines are
 * process-global, and heap/thread deltas are only meaningful when a single request
 * owns the iteration window. The harness measures iteration cleanliness, not
 * throughput under load. See docs/DESIGN-DECISIONS.md DD-005.
 */
public class IterationFilter implements Filter {
    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        ITERATION_LOCK.lock();
        try {
            // beginIteration is inside the try so that if it throws, the lock is still
            // released by the outer finally — otherwise every later request would queue
            // on the fair lock forever.
            Agent.beginIteration();
            Throwable pending = null;
            try {
                chain.doFilter(request, response);
            } catch (Throwable t) {
                // Count crashes (5xx-producing exceptions)
                ClosureJVMMetrics.incCrashes();
                pending = t;
            }
            try {
                Agent.endIteration();
            } catch (Throwable endError) {
                // A leak/invariant thrown by endIteration must not mask the application's
                // own exception; attach it as suppressed instead of replacing it.
                if (pending != null) {
                    pending.addSuppressed(endError);
                } else {
                    pending = endError;
                }
            } finally {
                // Headers read Agent's last-violation state, so they must be written before
                // the lock is released — otherwise the next request's begin clears it.
                writeInvariantHeaders(response);
            }
            if (pending != null) {
                if (pending instanceof IOException) throw (IOException) pending;
                if (pending instanceof ServletException) throw (ServletException) pending;
                if (pending instanceof RuntimeException) throw (RuntimeException) pending;
                if (pending instanceof Error) throw (Error) pending;
                throw new ServletException(pending);
            }
        } finally {
            ITERATION_LOCK.unlock();
        }
    }

    private static void writeInvariantHeaders(ServletResponse response) {
        try {
            ClosureJVMMetrics.incRequests();
            if (response instanceof HttpServletResponse) {
                HttpServletResponse resp = (HttpServletResponse) response;
                List<String> v = Agent.getLastInvariantViolations();
                if (v != null && !v.isEmpty()) {
                    ClosureJVMMetrics.addInvariantCount(v.size());
                    resp.setHeader("X-ClosureJVM-Invariant-Count", String.valueOf(v.size()));
                    String first = v.get(0);
                    if (first != null && first.length() > 200) first = first.substring(0, 200);
                    resp.setHeader("X-ClosureJVM-Invariant-Detail", first);
                }
            }
        } catch (Throwable ignored) {
            // Header reporting is best-effort; never let it fail a request.
        }
    }
}
