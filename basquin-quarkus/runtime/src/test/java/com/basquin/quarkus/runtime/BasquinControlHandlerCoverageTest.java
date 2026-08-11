package com.basquin.quarkus.runtime;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BasquinControlHandler}'s {@code coverage} route (DD-043 PR-4, spec §6.4).
 *
 * <p>Only the RT-unavailable path is honestly testable here: a plain JUnit JVM has no JaCoCo agent
 * attached (no {@code -javaagent}, nothing offline-instrumented ever booted one), so {@code
 * org.jacoco.agent.rt.RT.getAgent()} is GUARANTEED to throw — verified directly against the
 * shipped {@code org.jacoco.agent:0.8.15:runtime} jar's {@code Agent.getInstance()} bytecode,
 * which throws exactly {@code new IllegalStateException("JaCoCo agent not started.")} when its
 * static singleton was never set. This mirrors the seam spike's own control-cell finding
 * ({@code .superpowers/sdd/dd043-pr4-seam-evidence/seam-build-ctl-noinstrument.log}): extension
 * present, nothing instrumented, agent never boots, and the route answers a distinct 503 —
 * exactly this unavailable case. The agent-PRESENT success path cannot be honestly unit-tested in
 * a plain JVM (no offline instrumentation exists here to boot a real agent) and is proven instead
 * by the seam spike (native, composed) and the PR-4 Task-4 2×2 (see spec §6.4/§7.1); faking it with
 * a stubbed {@code IAgent} would misrepresent the real {@code RT} call this route depends on.
 */
public class BasquinControlHandlerCoverageTest {

    // ---- The unavailable path: distinct non-2xx, never an empty-but-200 ----------------------

    @Test
    public void dispatchCoverageAnswersADistinctNon2xxWhenNoAgentIsPresent() {
        BasquinControlHandler.CoverageOutcome outcome = BasquinControlHandler.dispatchCoverage();

        assertNotEquals("an unavailable agent must never read as success (DD-040: an empty 200 "
                        + "would be indistinguishable from \"measured and genuinely zero coverage\")",
                200, outcome.statusCode());
        assertEquals(503, outcome.statusCode());
    }

    @Test
    public void dispatchCoverageBodyIsNeverEmptyOnTheUnavailablePath() {
        BasquinControlHandler.CoverageOutcome outcome = BasquinControlHandler.dispatchCoverage();

        assertTrue("the unavailable-path body must be non-empty and stable, never an empty-but-200 "
                        + "response masquerading as measured-and-clean data",
                outcome.body().length > 0);
        String body = new String(outcome.body(), StandardCharsets.UTF_8);
        assertTrue("body=" + body, body.startsWith("err:no-jacoco-agent"));
    }

    @Test
    public void dispatchCoverageBodyNamesTheExactUnderlyingFailure() {
        // Pinned against the shipped org.jacoco.agent:0.8.15:runtime jar's decompiled
        // Agent.getInstance() bytecode: when the static singleton was never set (no -javaagent,
        // nothing offline-instrumented booted it in this JVM), it throws exactly
        // `new IllegalStateException("JaCoCo agent not started.")`. If a future JaCoCo bump
        // changes this wording, this test is meant to catch that drift, not paper over it.
        BasquinControlHandler.CoverageOutcome outcome = BasquinControlHandler.dispatchCoverage();

        String body = new String(outcome.body(), StandardCharsets.UTF_8);
        assertEquals("err:no-jacoco-agent java.lang.IllegalStateException: JaCoCo agent not started.",
                body);
    }

    @Test
    public void dispatchCoverageErrorContentTypeIsText() {
        // The success body is binary (application/octet-stream, spec §6.4); the error body is a
        // human-readable diagnostic string, so it gets a distinct, honest content type.
        BasquinControlHandler.CoverageOutcome outcome = BasquinControlHandler.dispatchCoverage();

        assertEquals("text/plain", outcome.contentType());
    }

    // ---- Dispatch/content-type wiring: coverage bypasses the text switch entirely ------------

    @Test
    public void theTextSwitchDoesNotKnowAboutCoverage() {
        // Coverage is dispatched in handle(RoutingContext), AHEAD of the text/plain
        // handle(String, String) switch -- it must never be routed through the text switch, since
        // that returns a String (text/plain) and execution data is binary. This pins that the
        // pure text dispatcher itself was never taught a "coverage" case: if it had been, this
        // would wrongly return an empty String body under a text/plain header, a 200 default,
        // instead of falling through to the binary dispatch this test's sibling exercises below.
        assertEquals("err:unknown",
                BasquinControlHandler.handle(BasquinControlHandler.PREFIX + "coverage", null));
    }

    /**
     * Pins the WIRING (not just the already-tested pure {@link BasquinControlHandler#dispatchCoverage()}),
     * mirroring {@code BasquinControlHandlerTest#handleRoutingContextReadsTheNormalizedPathNotTheRawRequestPath}'s
     * own style: a hand-rolled {@link FakeInvocationHandler} (JDK dynamic proxy, no mocking
     * library, per this module's {@code junit:junit:4.13.2}-only rule) drives the real
     * {@code handle(RoutingContext)} entry point.
     *
     * <p>The ordinary text switch never calls {@code setStatusCode} for any path it recognises
     * (only the binary-dispatch branches -- defect routes, and now coverage -- do, since a
     * {@code text/plain} success answer relies on Vert.x's implicit 200 default). In this plain
     * JVM, {@code RT.getAgent()} is guaranteed to throw (see class javadoc), so a real invocation
     * through {@code handle(RoutingContext)} for the coverage path proves BOTH that the request
     * reached the new binary branch (never "err:unknown" from the text switch, which would never
     * call {@code setStatusCode}) and that the unavailable path answers its distinct non-2xx.
     */
    @Test
    public void handleRoutingContextRoutesCoverageThroughTheBinaryBranchNotTheTextSwitch() {
        FakeInvocationHandler requestHandler = new FakeInvocationHandler();
        requestHandler.stub("query", null);
        HttpServerRequest request = FakeInvocationHandler.proxy(HttpServerRequest.class, requestHandler);

        FakeInvocationHandler responseHandler = new FakeInvocationHandler();
        HttpServerResponse response = FakeInvocationHandler.proxy(HttpServerResponse.class, responseHandler);

        FakeInvocationHandler ctxHandler = new FakeInvocationHandler();
        ctxHandler.stub("normalizedPath", BasquinControlHandler.PREFIX + "coverage");
        ctxHandler.stub("request", request);
        ctxHandler.stub("response", response);
        RoutingContext ctx = FakeInvocationHandler.proxy(RoutingContext.class, ctxHandler);

        new BasquinControlHandler().handle(ctx);

        assertTrue("coverage must be dispatched through the binary branch (setStatusCode is only "
                        + "ever called there, never by the text switch's implicit-200 success path)",
                responseHandler.called("setStatusCode"));
        assertTrue("must write a body via end(), never leave the response hanging",
                responseHandler.called("end"));
    }
}
