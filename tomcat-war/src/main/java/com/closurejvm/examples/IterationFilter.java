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
            chain.doFilter(request, response);
        } finally {
            try {
                Agent.endIteration();
            } finally {
                try {
                    if (response instanceof HttpServletResponse) {
                        List<String> v = Agent.getLastInvariantViolations();
                        if (v != null && !v.isEmpty()) {
                            HttpServletResponse resp = (HttpServletResponse) response;
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

