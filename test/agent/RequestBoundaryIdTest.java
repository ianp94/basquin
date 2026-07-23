package agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * DD-040 Task 2: the boundary publishes each explore iteration's measurements into
 * {@link ResultStore} under the driver's stamped request id, so a finding survives a response
 * that already committed (the header path's failure mode).
 */
public class RequestBoundaryIdTest {

    @Before public void reset() { ResultStore.clearForTest(); }

    // The whole point: an explore request that carries an id leaves its measurements retrievable
    // even though this test never looks at a response header.
    @Test public void exploreIterationPublishesItsResultUnderTheGivenId() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
        RequestBoundary.stampRequestId("salt-7");           // glue stamps only after EXPLORE_BEGAN
        RequestBoundary.onExit(null);

        java.util.List<ResultStore.Entry> hops = ResultStore.take("salt-7");
        assertEquals("the boundary must publish a result for a stamped explore request", 1, hops.size());
        assertNotNull("cost is always available on an explore exit", hops.get(0).costCsv());
    }

    @Test public void anUnstampedRequestPublishesNothingAndStillWorks() {
        RequestBoundary.onEnter("/app/page", null);          // never stamped
        RequestBoundary.onExit(null);
        assertEquals(0, ResultStore.size());
    }

    // THE case a naive implementation loses. endIteration() THROWS on a hard-mode violation and on
    // a leak, so an entry built from its return value is skipped for exactly the violating
    // iterations -- making the "reliable" channel weaker than the header it replaces.
    @Test public void aThrowingIterationStillPublishesItsResult() {
        String prior = System.getProperty("basquin.invariant.latency.maxMs");
        System.setProperty("basquin.invariant.latency.maxMs", "0");   // hard mode, always violated
        try {
            RequestBoundary.onEnter("/app/slow", null);
            RequestBoundary.stampRequestId("salt-throw");
            // Guarantee elapsedMs >= 1 so "> 0ms" is a violation on every machine, not a race.
            try { Thread.sleep(5); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
            assertNotNull("onExit must surface the violation", r.toThrow);
            java.util.List<ResultStore.Entry> hops = ResultStore.take("salt-throw");
            assertEquals("...and STILL publish the entry", 1, hops.size());
            ResultStore.Entry e = hops.get(0);
            assertTrue("the published entry must carry the violation, not an empty shell",
                    e.invariantCount() > 0);
            assertNotNull("and its detail", e.detail());
        } finally {
            if (prior == null) System.clearProperty("basquin.invariant.latency.maxMs");
            else System.setProperty("basquin.invariant.latency.maxMs", prior);
        }
    }

    // The load path must gain zero instructions: no store write at all.
    @Test public void loadPassthroughNeverWritesTheStore() {
        LoadMode.setLoad(60_000L);
        try {
            RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
            assertEquals(RequestBoundary.Phase.LOAD_PASSTHROUGH, d.phase);
            RequestBoundary.onExit(null);
            assertEquals(0, ResultStore.size());
        } finally {
            LoadMode.setExplore();
        }
    }

    // A control request must not begin an iteration or publish anything.
    @Test public void controlRequestPublishesNothing() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/drift", null);
        assertEquals(RequestBoundary.Phase.CONTROL_HANDLED, d.phase);
        assertTrue(d.skipApp());
        assertEquals(0, ResultStore.size());
        RequestBoundary.onExit(null);
    }

    // A stale id must never outlive its request: a pooled connector thread that served a stamped
    // explore request and is then reused for an unstamped one must publish nothing the second time.
    // Otherwise a later kubelet probe's metrics get filed under a driver id -- stale data as fresh.
    @Test public void theStampDoesNotLeakToTheNextRequestOnTheSameThread() {
        RequestBoundary.onEnter("/app/page", null);
        RequestBoundary.stampRequestId("salt-first");
        RequestBoundary.onExit(null);
        assertFalse("the explore path must publish at all — this is the property the whole DD-040 "
                + "channel rests on, and assertNotNull on a never-null List asserts nothing",
                ResultStore.take("salt-first").isEmpty());

        RequestBoundary.onEnter("/app/other", null);          // same thread, never stamped
        RequestBoundary.onExit(null);
        assertEquals("a stale id must not republish under the previous request's id", 0, ResultStore.size());
    }
}
