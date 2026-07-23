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

    @Test public void exploreExitEmitsCostHeader() {
        LoadMode.setExplore();
        RequestBoundary.onEnter("/app/page", null);           // EXPLORE_BEGAN
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
        String cost = r.headers.get("X-Basquin-Cost");
        Assert.assertNotNull("explore exit must emit X-Basquin-Cost", cost);
        Assert.assertTrue("cost is latencyMs,heapDeltaKb,threadDelta", cost.matches("^-?\\d+,-?\\d+,-?\\d+$"));
    }

    @Test public void loadAndControlEmitNoCostHeader() {
        LoadMode.setLoad(60_000L);
        RequestBoundary.onEnter("/app", null);                 // LOAD_PASSTHROUGH
        Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Cost"));
        LoadMode.setExplore();
        RequestBoundary.onEnter("/__basquin/drift", null);     // CONTROL_HANDLED
        Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Cost"));
    }

    /**
     * DD-040 §A.6: an explore exit carries this JVM's pod identity ({@code HOSTNAME}, which is the
     * pod name in Kubernetes — the same source DD-013's dashboard id uses). It is a hint for
     * attribution and for the two-replica reconciliation, never the poll's routing mechanism: it
     * rides the same response headers as the cost header, so it is absent on exactly the committed
     * responses that need the poll.
     */
    @Test public void exploreExitEmitsThePodIdentityHeader() {
        String prior = System.getProperty("basquin.pod.id");
        System.setProperty("basquin.pod.id", "roller-7d9c4-abcde");
        try {
            LoadMode.setExplore();
            RequestBoundary.onEnter("/app/page", null);        // EXPLORE_BEGAN
            Assert.assertEquals("the serving pod's identity, verbatim",
                    "roller-7d9c4-abcde",
                    RequestBoundary.onExit(null).headers.get("X-Basquin-Pod"));
        } finally {
            restore("basquin.pod.id", prior);
        }
    }

    /** No identity to report is reported as no header, never as a made-up or blank one. */
    @Test public void noPodIdentityMeansNoHeader() {
        String prior = System.getProperty("basquin.pod.id");
        System.setProperty("basquin.pod.id", "");              // explicitly empty
        try {
            LoadMode.setExplore();
            RequestBoundary.onEnter("/app/page", null);
            String pod = RequestBoundary.onExit(null).headers.get("X-Basquin-Pod");
            String hostname = System.getenv("HOSTNAME");       // the fallback, absent off-cluster
            Assert.assertEquals(hostname == null || hostname.isEmpty() ? null : hostname, pod);
        } finally {
            restore("basquin.pod.id", prior);
        }
    }

    /** The load path gains nothing at all — including no pod header. */
    @Test public void loadAndControlEmitNoPodHeader() {
        String prior = System.getProperty("basquin.pod.id");
        System.setProperty("basquin.pod.id", "roller-7d9c4-abcde");
        try {
            LoadMode.setLoad(60_000L);
            RequestBoundary.onEnter("/app", null);             // LOAD_PASSTHROUGH
            Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Pod"));
            LoadMode.setExplore();
            RequestBoundary.onEnter("/__basquin/drift", null); // CONTROL_HANDLED
            Assert.assertNull(RequestBoundary.onExit(null).headers.get("X-Basquin-Pod"));
        } finally {
            restore("basquin.pod.id", prior);
        }
    }

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }
}
