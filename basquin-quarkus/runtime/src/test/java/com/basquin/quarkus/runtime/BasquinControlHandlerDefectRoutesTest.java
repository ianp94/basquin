package com.basquin.quarkus.runtime;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BasquinControlHandler}'s negative-control defect routes (DD-043 PR-2,
 * spec §7.3): {@code control/defect/{slow,alloc,error5xx,block-loop}}. Exercises the
 * package-visible {@code dispatchDefect(defectName, query)} directly — the same pure logic the
 * Vert.x {@code handle} override delegates to — so no live {@code RoutingContext} is needed,
 * mirroring {@link BasquinControlHandlerTest}'s own style for {@code result}/{@code violations}.
 *
 * <p>{@code block-loop}'s actual property under test — that it runs on a genuine Vert.x
 * event-loop thread, not a worker thread — is a build-time ROUTING guarantee ({@code
 * BasquinProcessor#blockLoopRoute()} mounts it as {@code HandlerType.NORMAL}), not something this
 * method arranges, so it is proved end-to-end against the running fixture (see
 * bench-results/dd043-pr2-controls-2026-07-25/), not by a unit test here.
 */
public class BasquinControlHandlerDefectRoutesTest {

    @Before
    public void setUp() {
        System.clearProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY);
        BasquinControlHandler.clearRetainedForTest();
    }

    @After
    public void tearDown() {
        System.clearProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY);
        BasquinControlHandler.clearRetainedForTest();
    }

    // ---- The guard that matters: disabled by default -----------------------------------------

    @Test
    public void defectRoutesAreDisabledByDefault() {
        assertTrue("BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY must be unset by default "
                        + "in a plain test JVM for this test to mean anything",
                System.getProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY) == null);

        assertEquals(false, BasquinControlHandler.defectRoutesEnabled());
    }

    @Test
    public void allFourDefectRoutesRefuseWhenThePropertyIsUnset() {
        for (String defect : new String[] {"slow", "alloc", "error5xx", "block-loop"}) {
            BasquinControlHandler.DefectOutcome outcome =
                    BasquinControlHandler.dispatchDefect(defect, null);
            assertEquals("defect=" + defect + " must refuse, not 404 or silently succeed",
                    403, outcome.statusCode());
            assertEquals("err:defect-routes-disabled", outcome.body());
        }
    }

    @Test
    public void anUnknownDefectNameAlsoRefusesWhenDisabled_ratherThanLeakingErrUnknown() {
        // Disabled must gate the WHOLE control/defect/* sub-tree, not just recognised names —
        // otherwise probing subpaths would distinguish "known but off" from "unknown" and leak
        // information about which defects exist.
        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("nonsense", null);
        assertEquals(403, outcome.statusCode());
        assertEquals("err:defect-routes-disabled", outcome.body());
    }

    @Test
    public void enablingThePropertyMakesDefectRoutesReachable() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");
        assertTrue(BasquinControlHandler.defectRoutesEnabled());

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("error5xx", null);
        assertEquals(500, outcome.statusCode());
    }

    // ---- slow: the latency invariant's control ------------------------------------------------

    @Test
    public void slowDefectSleepsAtLeastTheRequestedMsAndReportsIt() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        long start = System.nanoTime();
        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("slow", "ms=60");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(200, outcome.statusCode());
        assertEquals("defect:slow ms=60", outcome.body());
        assertTrue("must actually sleep ~60ms, only took " + elapsedMs + "ms", elapsedMs >= 55L);
    }

    @Test
    public void slowDefectDefaultsWhenNoMsGiven() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("slow", "ms=notanumber");

        // A malformed ms falls back to the documented default rather than failing the request.
        assertEquals(200, outcome.statusCode());
        assertTrue(outcome.body().startsWith("defect:slow ms="));
    }

    // ---- alloc: the heap invariant's control, both sides of the quantum -----------------------

    @Test
    public void allocDefectRequiresBytes() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        BasquinControlHandler.DefectOutcome missing =
                BasquinControlHandler.dispatchDefect("alloc", null);
        assertEquals(200, missing.statusCode());
        assertTrue("missing bytes must be refused, not defaulted: " + missing.body(),
                missing.body().startsWith("err:bytes"));

        BasquinControlHandler.DefectOutcome malformed =
                BasquinControlHandler.dispatchDefect("alloc", "bytes=notanumber");
        assertTrue(malformed.body().startsWith("err:bytes"));
    }

    @Test
    public void allocDefectAboveTheQuantumIsRetainedAndReportedExactly() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");
        // 4 MiB — comfortably above §6.1's 524,288 B quantum, the size S2 used to clear the floor.
        long bytes = 4L * 1024 * 1024;

        int before = BasquinControlHandler.retainedAllocationCountForTest();
        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("alloc", "bytes=" + bytes);

        assertEquals(200, outcome.statusCode());
        assertEquals("defect:alloc bytes=" + bytes, outcome.body());
        assertEquals("the allocation must still be reachable (retained) after the call returns, "
                        + "or a boundary reading heap after response-end would see nothing",
                before + 1, BasquinControlHandler.retainedAllocationCountForTest());
    }

    @Test
    public void allocDefectBelowTheQuantumIsHonouredJustAsFaithfully() {
        // §7.3: alloc must be drivable BOTH far above AND deliberately below the 524,288 B
        // quantum. This route's own job is only to honour `bytes` faithfully and retain it —
        // whether the boundary reports a number or UNMEASURED for a sub-quantum delta is a
        // property of the boundary/instrument, not of this route, and is exercised end-to-end
        // against the running fixture (see the PR report for what was actually observed).
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");
        long bytes = 100L; // far below 524,288 B

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("alloc", "bytes=" + bytes);

        assertEquals(200, outcome.statusCode());
        assertEquals("defect:alloc bytes=" + bytes, outcome.body());
    }

    @Test
    public void allocDefectRejectsAnAbsurdlyLargeRequestRatherThanRiskingOom() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("alloc", "bytes=999999999999999");

        assertTrue(outcome.body().startsWith("err:bytes"));
    }

    // ---- error5xx: the 5xx/crash control -------------------------------------------------------

    @Test
    public void error5xxReturns500WithARecognisableBody() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("error5xx", null);

        assertEquals(500, outcome.statusCode());
        assertEquals("defect:error5xx", outcome.body());
    }

    // ---- block-loop: the event-loop-blocking control (no detector until PR-5) -----------------

    @Test
    public void blockLoopDefectSleepsAtLeastTheRequestedMsAndReportsItsThread() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");
        String thisThread = Thread.currentThread().getName();

        long start = System.nanoTime();
        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("block-loop", "ms=60");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(200, outcome.statusCode());
        assertEquals("defect:block-loop ms=60 thread=" + thisThread, outcome.body());
        assertTrue("must actually sleep ~60ms, only took " + elapsedMs + "ms", elapsedMs >= 55L);
    }

    // ---- unknown subpath, enabled ---------------------------------------------------------------

    @Test
    public void anUnknownDefectNameAnswersUnknownOnceEnabled() {
        System.setProperty(BasquinControlHandler.DEFECT_ROUTES_ENABLED_PROPERTY, "true");

        BasquinControlHandler.DefectOutcome outcome =
                BasquinControlHandler.dispatchDefect("nonsense", null);

        assertEquals(200, outcome.statusCode());
        assertEquals("err:unknown", outcome.body());
    }

    // ---- the boundary's own gate: /__basquin/* stays walled off from the app -------------------

    @Test
    public void theHandleRoutingContextEntryPointStillDelegatesNonDefectPathsToThePureDispatcher() {
        // result/violations/anything-else must still resolve exactly as BasquinControlHandlerTest
        // already asserts via handle(path, query) — this test only pins that dispatchDefect is
        // reached exclusively via the DEFECT_PREFIX sub-tree, not via PREFIX generally.
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/control", null));
        assertEquals("err:unknown", BasquinControlHandler.handle("/__basquin/control/defect", null));
    }
}
