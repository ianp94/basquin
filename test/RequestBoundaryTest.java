package test;

import agent.LoadMode;
import agent.RequestBoundary;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class RequestBoundaryTest {

    @After
    public void reset() {
        LoadMode.setExplore(); // leave the shared flag clean for the next test
    }

    @Test
    public void controlToggleToLoadIsHandledAndSkipsApp() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/mode", "to=load");
        Assert.assertEquals(RequestBoundary.Phase.CONTROL_HANDLED, d.phase);
        Assert.assertTrue(d.skipApp());
        Assert.assertEquals("ok:load", d.controlBody);
        Assert.assertTrue(LoadMode.isLoad(System.currentTimeMillis()));
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null); // no-op close for a skipped request
        Assert.assertTrue(r.headers.isEmpty());
        Assert.assertNull(r.toThrow);
    }

    @Test
    public void driftReturnsThreeFieldCsv() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/drift", null);
        Assert.assertTrue(d.skipApp());
        Assert.assertTrue("drift body was: " + d.controlBody, d.controlBody.matches("^\\d+,\\d+,\\d+$"));
        RequestBoundary.onExit(null);
    }

    @Test
    public void loadModePassesThroughWithoutBoundary() {
        LoadMode.setLoad(60_000L);
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        Assert.assertEquals(RequestBoundary.Phase.LOAD_PASSTHROUGH, d.phase);
        Assert.assertFalse(d.skipApp());
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
        Assert.assertTrue(r.headers.isEmpty());
        Assert.assertNull(r.toThrow);
    }

    @Test
    public void exploreBeginsAndExitReleasesLock() {
        LoadMode.setExplore();
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        Assert.assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
        Assert.assertFalse(d.skipApp());
        Assert.assertNull(RequestBoundary.onExit(null).toThrow);

        // The lock must be released: a full cycle on ANOTHER thread must not deadlock.
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            RequestBoundary.Decision d2 = RequestBoundary.onEnter("/again", null);
            RequestBoundary.onExit(null);
            ok[0] = d2.phase == RequestBoundary.Phase.EXPLORE_BEGAN;
        });
        t.start();
        try { t.join(5_000); } catch (InterruptedException ignored) { }
        Assert.assertTrue("second explore cycle on another thread must not deadlock", ok[0]);
    }

    @Test
    public void appErrorPropagatesThroughExit() {
        LoadMode.setExplore();
        RequestBoundary.onEnter("/app", null);
        RuntimeException boom = new RuntimeException("app blew up");
        Assert.assertSame(boom, RequestBoundary.onExit(boom).toThrow);
    }
}
