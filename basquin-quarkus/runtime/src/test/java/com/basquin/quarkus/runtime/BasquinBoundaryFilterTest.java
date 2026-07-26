package com.basquin.quarkus.runtime;

import agent.ResultStore;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BasquinBoundaryFilter#publish}, the measurement + invariants + store-write
 * step isolated from Vert.x wiring (DD-043 PR-2, spec §4.3/§4.4). No live {@code RoutingContext}
 * is needed: {@code publish} is exactly what {@code onEnd} calls once it has decided the
 * disposition is NOT {@code disconnected}.
 */
public class BasquinBoundaryFilterTest {

    @Before
    public void clearStore() {
        ResultStore.clearForTest();
    }

    @After
    public void tearDown() {
        ResultStore.clearForTest();
        System.clearProperty("basquin.invariant.latency.maxMs");
        System.clearProperty("basquin.invariant.latency.mode");
        System.clearProperty("basquin.invariant.mode");
    }

    /** Store shape mirrors agent/RequestBoundary.java:177-178 exactly. */
    @Test
    public void publishesCostCsvInLatencyHeapKbThreadDeltaOrder() {
        BasquinBoundaryFilter.publish("req-1", 42L, 2048L, 7, 3);

        List<ResultStore.Entry> hops = ResultStore.take("req-1");
        assertEquals(1, hops.size());
        ResultStore.Entry e = hops.get(0);
        assertEquals("42,2,3", e.costCsv());
        assertEquals(0, e.invariantCount());
        assertNull(e.detail());
        assertFalse(e.leakDetected());
    }

    /** A second poll for the same id legitimately misses: take() is destructive. */
    @Test
    public void secondPollAfterPublishMisses() {
        BasquinBoundaryFilter.publish("req-2", 1L, 0L, 1, 0);

        assertFalse(ResultStore.take("req-2").isEmpty());
        assertTrue("take() removes on read", ResultStore.take("req-2").isEmpty());
    }

    /**
     * Never throws, even with a violated threshold under the DEFAULT global mode (hard) — this is
     * "soft by structure" (spec §6): publish() never reads hardFailureMessage, let alone throws
     * it, because it runs where the response is already fully written.
     */
    @Test
    public void neverThrowsEvenWhenAViolationWouldBeHardElsewhere() {
        System.setProperty("basquin.invariant.latency.maxMs", "1");
        // basquin.invariant.mode left at its default ("hard") on purpose: Invariants.isHard(null)
        // defaults to hard, and this test exists to prove publish() ignores that entirely.

        BasquinBoundaryFilter.publish("req-3", 5000L, 0L, 1, 0);

        List<ResultStore.Entry> hops = ResultStore.take("req-3");
        assertEquals(1, hops.size());
        ResultStore.Entry e = hops.get(0);
        assertEquals("a violated threshold is still recorded", 1, e.invariantCount());
        assertTrue(e.detail() != null && e.detail().contains("ms"));
    }

    @Test
    public void leakDetectedIsAlwaysFalse() {
        // No leak-snapshot grace period exists at this boundary (spec §4.1): the reactive
        // equivalent is "decided, not inherited". publish() must never invent a leak signal.
        BasquinBoundaryFilter.publish("req-4", 1L, 0L, 1, 0);
        assertFalse(ResultStore.take("req-4").get(0).leakDetected());
    }

    // ---- Fix 1 (DD-043 approver finding): detail must be "name: detail", matching the Tomcat
    // path (Agent.java:475's `v.name + ": " + v.detail`), not a bare violated-threshold message.
    // A driver reading a bare detail cannot recover WHICH invariant fired, since ResultStore.Entry
    // stores the field opaquely (agent.ResultStore.Entry). -------------------------------------

    @Test
    public void publishFormatsDetailAsNameColonDetailMatchingTomcat() {
        // Deterministic: Invariants.evaluateAndMaybeFail formats the latency detail as
        // "%dms > %dms" (elapsedMs, maxMs) — see agent.Invariants:74. With maxMs=1 and
        // elapsedMs=100 that is exactly "100ms > 1ms", so the full published detail must be
        // exactly "latency: 100ms > 1ms" — the SAME "name: detail" shape Agent.java:475 publishes
        // on the Tomcat path. A bare "100ms > 1ms" (the pre-fix behaviour) loses which invariant
        // fired and must fail this assertion.
        System.setProperty("basquin.invariant.latency.maxMs", "1");

        BasquinBoundaryFilter.publish("req-detail-format", 100L, 0L, 1, 0);

        List<ResultStore.Entry> hops = ResultStore.take("req-detail-format");
        assertEquals(1, hops.size());
        ResultStore.Entry e = hops.get(0);
        assertEquals(1, e.invariantCount());
        assertTrue("detail must name which invariant fired: " + e.detail(),
                e.detail() != null && e.detail().startsWith("latency: "));
        assertEquals("latency: 100ms > 1ms", e.detail());
    }

    @Test
    public void publishWithNoViolationsLeavesDetailARealNullNotNullColonNull() {
        // No invariant thresholds configured (cleared in tearDown) => zero violations. detail must
        // stay a genuine null — not the literal string "null: null" a naive unconditional
        // `name + ": " + detail` concatenation would produce if it ran regardless of
        // invariantCount instead of being guarded by the `invariantCount > 0` check.
        BasquinBoundaryFilter.publish("req-no-violation-detail", 1L, 0L, 1, 0);

        List<ResultStore.Entry> hops = ResultStore.take("req-no-violation-detail");
        assertEquals(1, hops.size());
        ResultStore.Entry e = hops.get(0);
        assertEquals(0, e.invariantCount());
        assertNull(e.detail());
    }

    // ---- Fix 2 (DD-043 approver finding): the control-surface bypass must classify off the
    // NORMALIZED path, not the raw ctx.request().path() — Vert.x-Web itself routes on the
    // normalized path, so branching on the raw one lets a path-traversal-shaped URL like
    // `/__basquin/../api/x` slip an explore request through unmeasured. Two layers: the pure
    // classification RULE (isUninstrumentedControlPath), and the WIRING (that handle() actually
    // feeds it ctx.normalizedPath()). Only the second one can catch a regression back to
    // ctx.request().path() — the rule test alone cannot, since the predicate has no way to know
    // which path string it was handed. --------------------------------------------------------

    @Test
    public void isUninstrumentedControlPathTrueForControlSurfaceOutsideDefectPrefix() {
        assertTrue(BasquinBoundaryFilter.isUninstrumentedControlPath("/__basquin/result"));
        assertTrue(BasquinBoundaryFilter.isUninstrumentedControlPath("/__basquin/violations"));
        assertTrue(BasquinBoundaryFilter.isUninstrumentedControlPath("/__basquin/"));
    }

    @Test
    public void isUninstrumentedControlPathFalseForDefectRoutes() {
        // The negative-control defect routes (spec §7.3) are the deliberate exception: they exist
        // specifically TO be measured, so they must fall through to instrumentation rather than
        // being treated as control traffic.
        assertFalse(BasquinBoundaryFilter.isUninstrumentedControlPath(
                BasquinControlHandler.DEFECT_PREFIX + "slow"));
        assertFalse(BasquinBoundaryFilter.isUninstrumentedControlPath(
                BasquinControlHandler.DEFECT_PREFIX + "alloc"));
    }

    @Test
    public void isUninstrumentedControlPathFalseForOrdinaryAppPathsAndNull() {
        assertFalse(BasquinBoundaryFilter.isUninstrumentedControlPath("/api/orders"));
        assertFalse(BasquinBoundaryFilter.isUninstrumentedControlPath("/"));
        assertFalse(BasquinBoundaryFilter.isUninstrumentedControlPath(null));
    }

    /**
     * Pins the WIRING, not just the rule: {@link BasquinBoundaryFilter#handle} must classify off
     * {@code ctx.normalizedPath()}, never {@code ctx.request().path()}. Built with a hand-rolled
     * {@link FakeInvocationHandler} (JDK dynamic proxy, no mocking library) rather than a live
     * {@code RoutingContext}, per this module's {@code junit:junit:4.13.2}-only rule.
     *
     * <p>Reproduces the concrete failure literally: a raw, un-normalized request path that LOOKS
     * like control-surface traffic (a {@code ..} segment folding back under {@code /__basquin/})
     * but that Vert.x-Web itself normalizes to an ordinary app route. If {@code handle} ever read
     * the raw request path again instead of the normalized one, this request would be classified
     * as control traffic and skip instrumentation entirely — reaching the app with a driver
     * request id attached but UNMEASURED, DD-040's exact failure mode from the other direction.
     */
    @Test
    public void handleReadsTheNormalizedPathNotTheRawRequestPath() {
        FakeInvocationHandler requestHandler = new FakeInvocationHandler();
        // What ctx.request().path() (the RAW, pre-normalization path) would say for this request.
        requestHandler.stub("path", "/__basquin/../api/orders");
        requestHandler.stub("getHeader", "req-wiring-1"); // X-Basquin-Req present
        HttpServerRequest request = FakeInvocationHandler.proxy(HttpServerRequest.class, requestHandler);

        FakeInvocationHandler ctxHandler = new FakeInvocationHandler();
        // What ctx.normalizedPath() (what Vert.x-Web actually routed on) says: an ordinary app path.
        ctxHandler.stub("normalizedPath", "/api/orders");
        ctxHandler.stub("request", request);
        ctxHandler.stub("addEndHandler", 1);
        RoutingContext ctx = FakeInvocationHandler.proxy(RoutingContext.class, ctxHandler);

        new BasquinBoundaryFilter().handle(ctx);

        assertTrue("must reach the header read on the actual request => took the INSTRUMENTED "
                        + "branch, which only happens if the routing decision used "
                        + "normalizedPath(), not the raw (pre-normalization) request path",
                requestHandler.called("getHeader"));
        assertTrue("must register the end-of-response hook -- only happens on the instrumented "
                        + "branch, never on the control-surface bypass",
                ctxHandler.called("addEndHandler"));
    }
}
