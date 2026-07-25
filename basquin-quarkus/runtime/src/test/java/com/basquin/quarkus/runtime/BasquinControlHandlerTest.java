package com.basquin.quarkus.runtime;

import agent.ResultStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
