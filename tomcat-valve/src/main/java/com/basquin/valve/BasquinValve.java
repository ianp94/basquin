package com.basquin.valve;

import agent.RequestBoundary;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.util.Map;

/**
 * Tomcat Valve that wraps every request in a Basquin iteration boundary, for ANY web
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
 * <pre>{@code <Valve className="com.basquin.valve.BasquinValve"/>}</pre>
 * The valve jar goes in Tomcat's lib/, and the agent jar is injected via CATALINA_OPTS
 * (-javaagent + classpath).
 *
 * <p>Iterations are SERIALIZED (a fair lock): the Agent's heap/thread deltas are process-global,
 * so they are only meaningful when one request owns the iteration window. The harness measures
 * iteration cleanliness, not throughput under load (DD-005). The boundary itself now lives in
 * {@link RequestBoundary}, shared with the agent's own bytecode-installed boundary
 * (TomcatBoundaryAdvice); this valve is only Catalina glue over it.
 */
public class BasquinValve extends ValveBase {

    public BasquinValve() {
        // Valve participates in async request processing.
        super(true);
    }

    // Narrowed throws (no ServletException): a legal override that keeps the servlet namespace out of
    // this method's signature and bytecode (DD-011). All boundary logic lives in RequestBoundary, shared
    // with the agent-installed boundary (TomcatBoundaryAdvice); this method is only Catalina glue.
    @Override
    public void invoke(Request request, Response response) throws IOException {
        RequestBoundary.Decision decision =
                RequestBoundary.onEnter(request.getRequestURI(), request.getQueryString());
        if (decision.skipApp()) {
            // /__basquin control request: write the plaintext result, never reach the app.
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().print(decision.controlBody);
            return;
        }
        Throwable appError = null;
        try {
            getNext().invoke(request, response);
        } catch (Throwable t) {
            appError = t;
        }
        RequestBoundary.ExitResult r = RequestBoundary.onExit(appError);
        try {
            if (!r.headers.isEmpty() && !response.isCommitted()) {
                for (Map.Entry<String, String> h : r.headers.entrySet()) {
                    response.setHeader(h.getKey(), h.getValue());
                }
            }
        } catch (Throwable ignored) {
            // header reporting is best-effort; never let it fail a request
        }
        if (r.toThrow != null) {
            if (r.toThrow instanceof IOException) throw (IOException) r.toThrow;
            if (r.toThrow instanceof RuntimeException) throw (RuntimeException) r.toThrow;
            if (r.toThrow instanceof Error) throw (Error) r.toThrow;
            // Checked ServletException (javax or jakarta) — re-raise without naming the type.
            sneakyThrow(r.toThrow);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
