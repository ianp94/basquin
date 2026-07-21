package agent;

import net.bytebuddy.asm.Advice;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import java.util.Map;

/**
 * ByteBuddy advice inlined into {@code org.apache.catalina.core.StandardHostValve.invoke(Request,Response)}
 * by {@link Agent#premain} when {@code -Dbasquin.boundary=agent}. This is the ONLY Basquin class that
 * names Catalina connector types. It is never loaded/linked as a class — its body is copied into
 * StandardHostValve (where Catalina is visible), so its Catalina refs never trip the boot-loader
 * visibility limit that keeps {@link RequestBoundary} Catalina-free. All logic lives in RequestBoundary;
 * this is pure Catalina glue, mirroring {@code BasquinValve.invoke}.
 */
public final class TomcatBoundaryAdvice {

    private TomcatBoundaryAdvice() { }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) Request request,
                         @Advice.Argument(1) Response response) {
        RequestBoundary.Decision d =
                RequestBoundary.onEnter(request.getRequestURI(), request.getQueryString());
        if (d.skipApp()) {
            try {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getWriter().print(d.controlBody);
            } catch (Throwable ignored) {
                // best-effort control write; never fail the request
            }
        }
        return d.skipApp(); // non-default (true) → skip the StandardHostValve.invoke body
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(1) Response response,
                     @Advice.Thrown(readOnly = false) Throwable thrown) {
        RequestBoundary.ExitResult r = RequestBoundary.onExit(thrown);
        try {
            if (!r.headers.isEmpty() && !response.isCommitted()) {
                for (Map.Entry<String, String> h : r.headers.entrySet()) {
                    response.setHeader(h.getKey(), h.getValue());
                }
            }
        } catch (Throwable ignored) {
            // best-effort header write
        }
        thrown = r.toThrow; // app exception wins; endIteration error suppressed under it
    }
}
