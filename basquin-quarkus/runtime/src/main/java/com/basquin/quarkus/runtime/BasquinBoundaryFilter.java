package com.basquin.quarkus.runtime;

import agent.Invariants;
import agent.ResultStore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * The Vert.x-level request boundary for {@code basquin-quarkus} (DD-043 §4.3/§4.4).
 *
 * <p>Installed via a {@code FilterBuildItem} (see {@code BasquinProcessor}), never a JAX-RS
 * {@code @ServerRequestFilter}/{@code @ServerResponseFilter}: those sit inside JAX-RS, so they
 * miss non-JAX-RS traffic, and a response filter can run before the body is written. A
 * router-level filter sits below everything and gives access to {@link RoutingContext}'s end
 * hooks (spec §4.3).
 *
 * <h2>The request id is INBOUND — this filter reads it, never mints one</h2>
 * The driver generates {@code <RUN_SALT>-<n>} and sends it as the <b>request</b> header
 * {@code X-Basquin-Req} ({@code runner/coverage/CoverageGuidedRun.java:1065,1090}). Both existing
 * boundaries only read it ({@code tomcat-valve/.../BasquinValve.java:62},
 * {@code agent/TomcatBoundaryAdvice.java:30}); this filter does the same. Minting an id here
 * would publish results under ids the driver never sent, so every poll would miss — DD-040's
 * exact failure mode.
 *
 * <h2>No header, no measurement</h2>
 * Requests without {@code X-Basquin-Req} participate only in process-wide overlap tracking.
 * They publish no result, but their lifetime can taint a driver's heap window.
 *
 * <h2>Soft by structure</h2>
 * The measurement runs in {@code addEndHandler}, which fires only after the response is fully
 * written (spec §6) — throwing there can fail nothing already delivered. So
 * {@code Invariants.Result#hardFailureMessage} is deliberately never thrown here; it is a real,
 * documented semantic difference from the Tomcat path, which defaults to hard failure.
 *
 * <h2>Every completion releases the process-wide window</h2>
 * {@code addEndHandler} fires on success, error responses, redirects <b>and</b> client disconnect
 * (spike S3). Disconnects publish only a disposition, without latency or heap values.
 * Successful responses publish heap only when the window is attributable.
 *
 * <h2>No {@code ThreadLocal}</h2>
 * Requests interleave on the event loop, so per-request state rides the {@link RoutingContext}
 * (spec §4.1/§4.4) rather than a static {@code ThreadLocal}.
 *
 * <h2>The control surface is exempt from measurement — except the defect routes, which ARE the
 * measurement (spec §7.3)</h2>
 * {@code /__basquin/result} and {@code /__basquin/violations} are meta-queries about the
 * measurement system itself, not requests to measure: wrapping the result poll in its own
 * measurement window would be nonsensical (it can block for up to 2s waiting on itself) and
 * republishing under the SAME driver-issued id it is busy answering makes no sense either. But
 * {@code /__basquin/control/defect/*} is the opposite case — those routes exist specifically TO
 * be measured, since that is how a negative control demonstrates an invariant can fire. So this
 * filter reads {@link BasquinControlHandler#DEFECT_PREFIX} through, only skipping the narrower
 * {@link BasquinControlHandler#PREFIX} for everything else under it — "driving a control route
 * with an inbound {@code X-Basquin-Req} header produces a measurable iteration" is exactly this
 * distinction.
 */
public final class BasquinBoundaryFilter implements Handler<RoutingContext> {

    /** The driver's inbound request id header (DD-040). Read-only from this side of the wire. */
    public static final String REQ_ID_HEADER = "X-Basquin-Req";

    private static final String CTX_REQ_ID = "basquin.reqId";
    private static final String CTX_START_NANOS = "basquin.startNanos";
    private static final String CTX_BASELINE_HEAP = "basquin.baselineHeapBytes";
    private static final String CTX_BASELINE_THREADS = "basquin.baselineThreadCount";

    private static final String CTX_GC = "basquin.gcCount";
    private static final ReactiveWindows WINDOWS = new ReactiveWindows();

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    @Override
    public void handle(RoutingContext ctx) {
        // Control traffic (/__basquin/*) is not explore traffic; never wrap it in a measurement
        // window. Mirrors RequestBoundary.onEnter checking the control surface before any
        // explore-branch stamping happens on the Tomcat path. The negative-control defect routes
        // (spec §7.3) are the deliberate exception — see class javadoc — so they fall through to
        // the same instrumentation as an ordinary app route instead of returning here.
        //
        // ctx.normalizedPath(), NEVER ctx.request().path(): Vert.x-Web itself routes on the
        // NORMALIZED path (io.vertx.ext.web.RoutingContext#normalizedPath), so classifying against
        // the raw path would let a path-traversal-shaped URL like `/__basquin/../api/x` — which
        // Vert.x-Web resolves to an ordinary app route — read as control-surface traffic here and
        // skip instrumentation, reaching the app with a driver request id attached but UNMEASURED.
        // Pinned by BasquinBoundaryFilterTest#handleReadsTheNormalizedPathNotTheRawRequestPath.
        String path = ctx.normalizedPath();
        if (isUninstrumentedControlPath(path)) {
            ctx.next();
            return;
        }

        String reqId = ctx.request().getHeader(REQ_ID_HEADER);
        ReactiveWindows.Window window = WINDOWS.begin();
        try {
            // Track non-driver traffic too: it contaminates another request's heap window.
            if (reqId != null && !reqId.isEmpty()) {
                if (Boolean.getBoolean("basquin.heap.gcBeforeMeasure")) System.gc();
                ctx.put(CTX_GC, collectionCount());
                ctx.put(CTX_REQ_ID, reqId);
                ctx.put(CTX_START_NANOS, System.nanoTime());
                ctx.put(CTX_BASELINE_HEAP, usedHeapBytes());
                ctx.put(CTX_BASELINE_THREADS, THREAD_MX.getThreadCount());
            }
            ctx.addEndHandler(ar -> onEnd(ctx, ar, window));
        } catch (Throwable failure) {
            WINDOWS.end(window);
            throw failure;
        }
        ctx.next();
    }

    private static void onEnd(RoutingContext ctx, AsyncResult<Void> ar, ReactiveWindows.Window window) {
        String reqId = null;
        boolean ended = false;
        try {
            reqId = ctx.get(CTX_REQ_ID);
            if (reqId == null) return;
            if (!ar.succeeded()) {
                // Publish the disposition only: no fabricated latency, heap, or crash finding.
                ResultStore.put(reqId, new ResultStore.Entry(null, 0, null, false, "disconnected"));
                return;
            }

            long startNanos = ctx.get(CTX_START_NANOS);
            long baselineHeapBytes = ctx.get(CTX_BASELINE_HEAP);
            int baselineThreadCount = ctx.get(CTX_BASELINE_THREADS);

            long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            long heapDeltaBytes = usedHeapBytes() - baselineHeapBytes;
            int threadsNow = THREAD_MX.getThreadCount();
            int threadsDelta = threadsNow - baselineThreadCount;

            long gcAfter = collectionCount();
            long gcBefore = ctx.get(CTX_GC);
            boolean overlap = WINDOWS.end(window);
            ended = true;
            publish(reqId, elapsedMs, heapDeltaBytes, threadsNow, threadsDelta,
                    heapAvailable(heapDeltaBytes, overlap, gcBefore, gcAfter));
        } catch (Throwable t) {
            // Never let boundary bookkeeping fail a request whose response is already written.
            System.err.println("[Basquin] boundary end-handler failed for id=" + reqId + ": " + t);
        } finally {
            if (!ended) WINDOWS.end(window);
        }
    }

    static boolean heapAvailable(long bytes, boolean overlap, long gcBefore, long gcAfter) {
        return !overlap && bytes >= 1_048_576L && gcBefore >= 0 && gcAfter == gcBefore;
    }

    static long collectionCount() {
        long sum = 0;
        java.util.List<java.lang.management.GarbageCollectorMXBean> beans =
                ManagementFactory.getGarbageCollectorMXBeans();
        if (beans.isEmpty()) return -1;
        for (java.lang.management.GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count < 0) return -1;
            sum += count;
        }
        return sum;
    }

    /**
     * Pure classification for {@link #handle}'s control-surface bypass: true for anything under
     * {@link BasquinControlHandler#PREFIX} EXCEPT the negative-control defect routes (spec §7.3),
     * which fall through to the same instrumentation as an ordinary app route instead of being
     * treated as control traffic. Extracted out of {@link #handle} so the rule itself is testable
     * without a live {@link RoutingContext} — see {@code BasquinBoundaryFilterTest}. Package-visible
     * for tests.
     *
     * <p>Callers MUST pass the NORMALIZED path — see the comment at {@link #handle}'s call site for
     * why. This method has no way to enforce that itself, since it only ever sees whatever string
     * it is given; the wiring (which path {@link #handle} actually reads) is pinned separately, by
     * a test that exercises {@link #handle} itself.
     */
    static boolean isUninstrumentedControlPath(String path) {
        return path != null
                && path.startsWith(BasquinControlHandler.PREFIX)
                && !path.startsWith(BasquinControlHandler.DEFECT_PREFIX);
    }

    /**
     * The measurement + invariants + store-write, isolated from Vert.x wiring so it is testable
     * without a live {@link RoutingContext}. Package-visible for tests.
     *
     * <p>{@code iterationNumber} is passed as {@code 0}: there is no serialized iteration sequence
     * on this lock-free path (spec §4.1), so no meaningful iteration number exists.
     * {@code Invariants.evaluateAndMaybeFail} only uses it in its own log line, never as a
     * threshold input.
     *
     * <p>{@code leakDetected} is always {@code false}: the Tomcat path's leak check depends on a
     * 25ms grace-period snapshot that is deliberately NOT transplanted here (spec §4.1 — "the
     * reactive equivalent of the leak-snapshot grace period is decided, not inherited: there is
     * none at the boundary").
     */
    static void publish(String reqId, long elapsedMs, long heapDeltaBytes, int threadsNow, int threadsDelta) {
        publish(reqId, elapsedMs, heapDeltaBytes, threadsNow, threadsDelta,
                heapAvailable(heapDeltaBytes, false, 0, 0));
    }

    static void publish(String reqId, long elapsedMs, long heapDeltaBytes, int threadsNow,
                        int threadsDelta, boolean heapAvailable) {
        Invariants.Result r = Invariants.evaluateAvailable(0, elapsedMs, heapDeltaBytes,
                threadsNow, threadsDelta, heapAvailable, false);
        List<Invariants.Violation> violations = r.violations;
        int invariantCount = violations.size();
        // Format must match the Tomcat path (Agent.java:475 publishes `name + ": " + detail`).
        // Sharing ResultStore.format only guarantees the WIRE shape; what goes INTO the Entry is
        // per-boundary, so the two can still diverge — and did: publishing a bare detail loses which
        // invariant fired, since the driver stores this field opaquely.
        String detail = invariantCount > 0
                ? violations.get(0).name + ": " + violations.get(0).detail
                : null;
        // r.hardFailureMessage is deliberately never thrown: soft by structure (see class javadoc).

        // Mirrors agent/RequestBoundary.java's costCsv composition exactly.
        String costCsv = elapsedMs + "," + (heapAvailable ? Long.toString(heapDeltaBytes / 1024L) : "") + ",0";
        // An unavailable heap window stays empty on the wire; latency findings remain valid.
        ResultStore.put(reqId, new ResultStore.Entry(costCsv, invariantCount, detail, false,
                heapAvailable ? ResultStore.DISPOSITION_MEASURED : "UNMEASURED"));
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
