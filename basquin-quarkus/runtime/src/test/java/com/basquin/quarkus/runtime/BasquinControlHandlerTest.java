package com.basquin.quarkus.runtime;

import agent.ResultStore;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BasquinControlHandler}'s pure dispatch logic (DD-043 PR-2, spec §4.4a).
 * Exercises the package-visible {@code handle(path, query)} directly — the same method the Vert.x
 * {@code handle} override delegates to — so no live {@code RoutingContext} is needed. This test
 * lives in the same package deliberately, to call it without reflection.
 */
public class BasquinControlHandlerTest {

    @Before
    public void clearStore() {
        ResultStore.clearForTest();
    }

    @After
    public void tearDown() {
        ResultStore.clearForTest();
    }

    @Test
    public void resultReturnsTheFormattedEntryOncePublished() {
        ResultStore.put("probe-1", new ResultStore.Entry("42,2,3", 0, null, false));

        assertEquals("42,2,3|0||", BasquinControlHandler.handle("/__basquin/result", "id=probe-1"));
    }

    /**
     * take() is destructive: an id never published, or already polled, is a legitimate miss. A
     * timeout returns at the bound rather than hanging or failing instantly — mirroring
     * {@code LoadModeControlTest}'s style for the analogous Tomcat-path assertion. The poll
     * timeout is read via reflection (like that test reads {@code QUIESCENCE_WAIT_MS}) because it
     * is {@code static final} and computed once at class-load, so it cannot be shrunk per-test.
     */
    @Test
    public void resultMissesForAnUnpublishedId() throws Exception {
        long boundMs = resultPollTimeoutMs();
        long start = System.nanoTime();

        assertEquals("miss", BasquinControlHandler.handle("/__basquin/result", "id=never-published"));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("must return at the bound, not hang (took " + elapsedMs + "ms, bound " + boundMs + "ms)",
                elapsedMs < boundMs + 600L);
    }

    private static long resultPollTimeoutMs() throws Exception {
        java.lang.reflect.Field f = BasquinControlHandler.class.getDeclaredField("RESULT_POLL_TIMEOUT_MS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    @Test
    public void violationsReturnsTheRunningTotal() {
        ResultStore.put("x", new ResultStore.Entry("1,0,0", 2, "detail", false));
        ResultStore.take("x"); // consume, but totalViolations is a separate running counter

        assertEquals("2", BasquinControlHandler.handle("/__basquin/violations", null));
    }

    @Test
    public void modeAndDriftAreOutOfScopeAndAnswerUnknown() {
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/mode", "to=load"));
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/drift", null));
    }

    @Test
    public void anyOtherSubPathAnswersUnknown() {
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/nonsense", null));
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/", null));
    }

    @Test
    public void aPathOutsideThePrefixAnswersUnknown() {
        // Defensive: the route is registered only under /__basquin/*, so this should never
        // actually happen, but the dispatcher must not silently pass such a path through.
        assertEquals("err:unknown", BasquinControlHandler.handle("/not-basquin/result", "id=x"));
    }

    /**
     * Fix 2 (DD-043 approver finding), mirrored for the control surface
     * (`BasquinControlHandler.handle(RoutingContext)`, which also switched to
     * {@code ctx.normalizedPath()}): pins the WIRING, not just the already-tested pure dispatcher
     * above. Built with a hand-rolled {@link FakeInvocationHandler} (JDK dynamic proxy, no mocking
     * library), per this module's {@code junit:junit:4.13.2}-only rule.
     *
     * <p>Crafts a case where the raw request path and the normalized path disagree about whether
     * this is a defect route: the raw path looks like {@code control/defect/slow}, but the
     * normalized path is the ordinary {@code violations} endpoint. Defect routes are disabled by
     * default (see {@link BasquinControlHandlerDefectRoutesTest}), so the defect branch is the only
     * one that ever calls {@code response.setStatusCode(...)} — if the raw path were consulted
     * instead of the normalized one, this request would wrongly fall into that branch and get
     * refused with 403 instead of being answered by the ordinary dispatch.
     */
    @Test
    public void handleRoutingContextReadsTheNormalizedPathNotTheRawRequestPath() {
        System.clearProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY);

        FakeInvocationHandler requestHandler = new FakeInvocationHandler();
        // What ctx.request().path() (the RAW, pre-normalization path) would say for this request.
        requestHandler.stub("path", BasquinControlHandler.DEFECT_PREFIX + "slow");
        requestHandler.stub("query", null);
        HttpServerRequest request = FakeInvocationHandler.proxy(HttpServerRequest.class, requestHandler);

        FakeInvocationHandler responseHandler = new FakeInvocationHandler();
        HttpServerResponse response = FakeInvocationHandler.proxy(HttpServerResponse.class, responseHandler);

        FakeInvocationHandler ctxHandler = new FakeInvocationHandler();
        // What ctx.normalizedPath() (what Vert.x-Web actually routed on) says: the ordinary
        // /__basquin/violations endpoint, nowhere near the defect sub-tree.
        ctxHandler.stub("normalizedPath", "/__basquin/violations");
        ctxHandler.stub("request", request);
        ctxHandler.stub("response", response);
        RoutingContext ctx = FakeInvocationHandler.proxy(RoutingContext.class, ctxHandler);

        new BasquinControlHandler().handle(ctx);

        assertFalse("must NOT take the defect-dispatch branch (setStatusCode is only ever called "
                        + "there) -- this only holds if the routing decision used normalizedPath(), "
                        + "not the raw (pre-normalization) request path",
                responseHandler.called("setStatusCode"));
    }
}
