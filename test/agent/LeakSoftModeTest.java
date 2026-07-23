package agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * DD-040 Task 5: a leak must never alter the application's response in soft mode — and must still
 * be RECORDED where the driver can retrieve it.
 *
 * <p>Both halves are load-bearing. Before this task {@code Agent.end()} threw unconditionally on a
 * leak, ignoring {@code basquin.invariant.mode=soft}: observed live, a Roller publish wrote its
 * database row and the client got an empty {@code 500}, because the throw propagated out of
 * {@code onExit} <em>after</em> the app had produced a correct response. Gating that throw alone,
 * however, would trade a false 500 for a silently lost finding: leak evidence is not in
 * {@code lastInvariantViolations} (only {@link Invariants} violations reach there), so it is not in
 * any response header, and {@code StatusReporter.recordIteration} is a no-op in a target JVM
 * without {@code -Dbasquin.status}. It is stderr only. The {@link ResultStore} entry is therefore
 * the only channel that carries it, and these tests pin that it does.
 */
public class LeakSoftModeTest {

    private String priorGlobal;
    private String priorLeak;

    @Before public void setUp() {
        priorGlobal = System.getProperty("basquin.invariant.mode");
        priorLeak = System.getProperty("basquin.invariant.leak.mode");
        System.clearProperty("basquin.invariant.mode");
        System.clearProperty("basquin.invariant.leak.mode");
        ResultStore.clearForTest();
    }

    @After public void tearDown() {
        restore("basquin.invariant.mode", priorGlobal);
        restore("basquin.invariant.leak.mode", priorLeak);
        ResultStore.clearForTest();
    }

    private static void restore(String key, String prior) {
        if (prior == null) System.clearProperty(key);
        else System.setProperty(key, prior);
    }

    // --- Half 1: soft mode must not alter the response ---------------------------------------

    @Test public void softModeDoesNotThrowSoTheAppsResponseSurvives() {
        System.setProperty("basquin.invariant.mode", "soft");
        RequestBoundary.ExitResult r = leakingRequest("salt-soft");
        assertNull("soft mode must not propagate a leak out of onExit — the app already answered; "
                + "a throw here becomes an empty 500 over a correct response", r.toThrow);
        // ...and the boundary's own reporting still happened: the response is untouched apart from
        // the reporting header it always carries on an explore exit.
        assertNotNull(r.headers);
        assertNotNull("cost is always reported on an explore exit", r.headers.get("X-Basquin-Cost"));
        assertNull("a leak is not an Invariants violation; it must not forge an invariant header",
                r.headers.get("X-Basquin-Invariant-Count"));
    }

    // --- Half 2: the leak must actually be RECORDED where the driver can read it ---------------

    @Test public void softModeStillRecordsTheLeakForTheDriver() {
        System.setProperty("basquin.invariant.mode", "soft");
        leakingRequest("salt-soft-record");

        ResultStore.Entry e = ResultStore.take("salt-soft-record");
        assertNotNull("a soft-mode leak must still publish an entry — otherwise soft mode loses the "
                + "finding entirely (stderr is not a channel the driver reads)", e);
        assertTrue("and that entry must carry the leak flag", e.leakDetected());
    }

    /**
     * Closes the seam between the two halves of the end-to-end path: this asserts the agent
     * produces exactly the wire form the driver parses. {@code CoverageGuidedRun.request} splits
     * the polled body with a 4-field limit and treats {@code f[3].trim().equals("leak")} as the
     * flag that saves a {@code Leak-Remote} finding (covered from the driver side by
     * {@code ReportChannelTest.aRecoveredLeakIsSavedAsAFinding}). If the agent's format and the
     * driver's parse ever drift, the leak silently becomes a clean measurement.
     */
    @Test public void theRecordedLeakIsOnTheWireInTheFormTheDriverParses() {
        System.setProperty("basquin.invariant.mode", "soft");
        leakingRequest("salt-soft-wire");

        String body = ResultStore.format(ResultStore.take("salt-soft-wire"));
        String[] f = body.split("\\|", 4);
        assertEquals("four fields: costCsv|invariantCount|detail|leak — " + body, 4, f.length);
        assertEquals("the driver reads a leak off field 4 only — " + body, "leak", f[3].trim());
        assertEquals("a leak is not an invariant violation and must not inflate that count", "0", f[1].trim());
    }

    // --- The safety property: hard mode must keep failing loudly ------------------------------

    @Test public void hardModeIsTheDefaultAndStillThrows() {
        // Both properties cleared in setUp: this is the out-of-the-box configuration, and the
        // gate must not have quietly disabled it (Invariants.java defaults the global mode to hard).
        RequestBoundary.ExitResult r = leakingRequest("salt-hard");
        assertNotNull("the default configuration must still fail loudly on a leak", r.toThrow);
        assertTrue("...with the leak message the demo and CI assert on: " + r.toThrow,
                String.valueOf(r.toThrow.getMessage()).contains("Leak(s) detected"));

        ResultStore.Entry e = ResultStore.take("salt-hard");
        assertNotNull("even the throwing path must publish its entry (Task 2)", e);
        assertTrue(e.leakDetected());
    }

    // --- The documented mode resolution: per-invariant key, else global ------------------------

    @Test public void aPerInvariantLeakModeSoftensLeakAlone() {
        System.setProperty("basquin.invariant.mode", "hard");
        System.setProperty("basquin.invariant.leak.mode", "soft");
        RequestBoundary.ExitResult r = leakingRequest("salt-leak-soft");
        assertNull("basquin.invariant.leak.mode=soft must win over a hard global mode", r.toThrow);
        ResultStore.Entry e = ResultStore.take("salt-leak-soft");
        assertNotNull(e);
        assertTrue(e.leakDetected());
    }

    @Test public void aPerInvariantLeakModeCanHardenLeakUnderASoftGlobal() {
        System.setProperty("basquin.invariant.mode", "soft");
        System.setProperty("basquin.invariant.leak.mode", "hard");
        RequestBoundary.ExitResult r = leakingRequest("salt-leak-hard");
        assertNotNull("basquin.invariant.leak.mode=hard must win over a soft global mode", r.toThrow);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Runs one stamped explore request that leaks a real non-daemon thread while the iteration is
     * open — the same thing {@code Agent.end()} detects in a target JVM. The thread is released and
     * joined before returning, so the leak never escapes into another test.
     */
    private RequestBoundary.ExitResult leakingRequest(String reqId) {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        Thread leaked = new Thread(() -> {
            started.countDown();
            try { stop.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "basquin-test-leaked-" + reqId);
        leaked.setDaemon(false);   // a daemon thread is not a leak by this definition

        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/leaky", null);
        assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
        RequestBoundary.stampRequestId(reqId);
        try {
            leaked.start();   // started AFTER begin(), so it is absent from the baseline set
            assertTrue("leak thread failed to start", await(started));
            return RequestBoundary.onExit(null);
        } finally {
            stop.countDown();
            try { leaked.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static boolean await(CountDownLatch l) {
        try { return l.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
