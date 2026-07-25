package com.basquin.quarkus.runtime;

import agent.ResultStore;
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
}
