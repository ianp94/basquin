package com.basquin.quarkus.runtime;

import agent.ResultStore;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.Collections;
import java.util.List;

/**
 * The control surface at {@code /__basquin/*} — the prefix the driver actually calls (DD-043
 * §4.4a). Installed via a {@code RouteBuildItem} as a blocking route (see {@code
 * BasquinProcessor}): {@link #pollResult} can block its worker thread for up to
 * {@value #DEFAULT_RESULT_POLL_TIMEOUT_MS}ms, which must never happen on the event loop.
 *
 * <h2>Share the wire format, not the handler</h2>
 * This is deliberately NOT {@code agent.LoadModeControl.handle(path, query)}. That method
 * references {@code agent.LoadMode} (three times) and {@code agent.RequestBoundary.awaitQuiescence}
 * (once), both of which stay in {@code agent/} — {@code awaitQuiescence} is
 * {@code ITERATION_LOCK.tryLock(...)}, lock-based machinery with no meaning on this lock-free
 * reactive path, and depending on {@code agent/} from this extension would drag the Tomcat/JVMTI
 * surface into a native-targeted artifact. What genuinely IS shared — and is the part that
 * matters, since it is where the two paths could disagree — is the wire format:
 * {@link ResultStore#format} / {@link ResultStore#take}, reused verbatim.
 *
 * <p>{@code PREFIX} and the query-parameter parser mirror {@code agent.LoadModeControl}'s
 * (byte-for-byte, deliberately) but are NOT extracted into {@code basquin-core} by this increment:
 * this PR may only touch {@code basquin-core} to add tests, so the extension carries its own copy
 * of these two trivial, pure functions instead of refactoring a shipped control path.
 *
 * <p>{@code mode} and {@code drift} are out of scope here: both are {@code LoadMode}, the DD-029
 * valve strategy flag, and load mode against native/reactive targets is a DD-043 §2 non-goal
 * (DD-042's business). Any path under {@code PREFIX} other than {@code result}/{@code violations}
 * — including {@code mode}/{@code drift} — answers {@code err:unknown} and never reaches the app.
 */
public final class BasquinControlHandler implements Handler<RoutingContext> {

    /** Mirrors {@code agent.LoadModeControl.PREFIX}. Collision-unlikely with a real app route. */
    public static final String PREFIX = "/__basquin/";

    /**
     * Bounded wait for an in-flight explore iteration's {@code addEndHandler} to publish (spec
     * §4.4/§4.4a). There is no {@code awaitQuiescence} on this path (no lock to wait on), so the
     * poll waits on the store itself instead, in short increments, up to this bound. A timeout
     * returns whatever {@link ResultStore#format} gives for an empty list — {@code "miss"} —
     * never a zero.
     *
     * <p>Overridable via the {@code basquin.quarkus.report.pollTimeoutMs} system property, set
     * before this class loads — mirroring {@code agent.LoadModeControl.QUIESCENCE_WAIT_MS}, which
     * is {@code static final} for the same reason: a value read once at class-load, not re-read
     * per call, so it is retuned at JVM startup, not mid-run.
     */
    static final long DEFAULT_RESULT_POLL_TIMEOUT_MS = 2_000L;
    private static final long RESULT_POLL_TIMEOUT_MS =
            Long.getLong("basquin.quarkus.report.pollTimeoutMs", DEFAULT_RESULT_POLL_TIMEOUT_MS);
    private static final long RESULT_POLL_INTERVAL_MS = 20L;

    @Override
    public void handle(RoutingContext ctx) {
        String path = ctx.request().path();
        String query = ctx.request().query();
        String body = handle(path, query);
        ctx.response().putHeader("Content-Type", "text/plain").end(body);
    }

    /** Package-visible for tests: the pure dispatch logic, isolated from Vert.x wiring. */
    static String handle(String path, String query) {
        if (path == null || !path.startsWith(PREFIX)) {
            return "err:unknown";
        }
        String sub = path.substring(PREFIX.length());
        switch (sub) {
            case "result":
                return pollResult(param(query, "id"));
            case "violations":
                return Long.toString(ResultStore.totalViolations());
            default:
                // Includes mode/drift (out of scope here, see class javadoc) and anything else:
                // never reaches the app.
                return "err:unknown";
        }
    }

    private static String pollResult(String id) {
        if (id == null) {
            return ResultStore.format(Collections.emptyList());
        }
        long deadlineNanos = System.nanoTime() + RESULT_POLL_TIMEOUT_MS * 1_000_000L;
        List<ResultStore.Entry> hops = ResultStore.take(id);
        while (hops.isEmpty() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(RESULT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            hops = ResultStore.take(id);
        }
        // A timeout falls straight through to format(empty) = "miss" — never a zero.
        return ResultStore.format(hops);
    }

    /** Minimal query-param read; mirrors {@code agent.LoadModeControl.param} (see class javadoc). */
    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }
}
