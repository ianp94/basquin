package com.closurejvm.examples;

import agent.Agent;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Wraps every request in an iteration boundary and exposes invariant info via headers.
 * Headers:
 * - X-ClosureJVM-Invariant-Count: number of violations (if any)
 * - X-ClosureJVM-Invariant-Detail: first violation detail (truncated)
 */
public class IterationFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Agent.beginIteration();
        try {
            try {
                chain.doFilter(request, response);
            } catch (Throwable t) {
                // Count crashes (5xx-producing exceptions)
                ClosureJVMMetrics.incCrashes();
                throw t;
            }
        } finally {
            try {
                Agent.endIteration();
            } finally {
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
                } catch (Throwable ignored) {}
            }
        }
    }
}
