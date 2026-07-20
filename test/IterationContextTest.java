package test;

import agent.Agent;
import agent.IterationContext;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Contract test for the v0.6 explicit context API. begin() must hand out an independent
 * context per call with a distinct, increasing iteration number — the property that lets
 * concurrent begin/end pairs stop stomping each other's baselines. end() behavior is
 * exercised end-to-end by the forked runner tests (leak/invariant/reset).
 */
public class IterationContextTest {

    @Test
    public void beginReturnsIndependentContextsWithIncreasingNumbers() {
        IterationContext a = Agent.begin();
        IterationContext b = Agent.begin();

        assertNotNull("begin() must return a context", a);
        assertNotNull(b);
        assertNotSame("each begin() is a fresh context", a, b);
        assertTrue("iteration numbers increase", b.iterationNumber() > a.iterationNumber());

        // Fresh contexts carry no results until end() runs.
        assertFalse(a.leakDetected());
        assertTrue(a.invariantViolations().isEmpty());
        assertEquals(0L, a.latencyMs());
    }

    @Test
    public void contextResultAccessorsAreDefensiveCopies() {
        IterationContext ctx = Agent.begin();
        // Mutating the returned list must not affect the context's internal state.
        ctx.invariantViolations().add("bogus");
        assertTrue("accessor returns a copy", ctx.invariantViolations().isEmpty());
    }
}
