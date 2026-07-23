import org.junit.After;
import org.junit.Test;
import agent.LoadMode;
import agent.LoadModeControl;
import agent.RequestBoundary;
import agent.ResultStore;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure control-request logic the valve delegates to (DD-029). The valve calls
 * {@link LoadModeControl#handle} at the top of invoke(); a non-null return means "this was a
 * /__basquin control request — write this body and don't touch the app or the lock."
 */
public class LoadModeControlTest {

    @After public void reset() { LoadMode.setExplore(); }

    @Test public void nonControlPathReturnsNull() {
        assertNull(LoadModeControl.handle("/Wiki.jsp?page=Main", "page=Main"));
        assertNull(LoadModeControl.handle("/", null));
        assertNull(LoadModeControl.handle(null, null));
    }

    @Test public void modeLoadEntersLoadWithTtl() {
        assertEquals("ok:load", LoadModeControl.handle("/__basquin/mode", "to=load&ttlMs=5000"));
        assertTrue(LoadMode.isLoad(LoadMode.enteredAt() + 100));
        assertFalse(LoadMode.isLoad(LoadMode.enteredAt() + 6000)); // ttl honored
    }

    @Test public void modeExploreLeavesLoad() {
        LoadMode.setLoad(10_000);
        assertEquals("ok:explore", LoadModeControl.handle("/__basquin/mode", "to=explore"));
        assertFalse(LoadMode.isLoad(LoadMode.enteredAt()));
    }

    @Test public void modeMissingTtlUsesADefault() {
        assertEquals("ok:load", LoadModeControl.handle("/__basquin/mode", "to=load"));
        assertTrue("default ttl keeps it load briefly", LoadMode.isLoad(LoadMode.enteredAt() + 100));
    }

    @Test public void driftReturnsTheSnapshotCsv() {
        String body = LoadModeControl.handle("/__basquin/drift", null);
        assertNotNull(body);
        assertEquals(3, body.split(",").length);
    }

    @Test public void unknownControlPathIsHandledNotPassedThrough() {
        // Must NOT return null (that would pass an odd /__basquin/* path to the app); returns an error body.
        assertNotNull(LoadModeControl.handle("/__basquin/bogus", null));
    }

    @Test public void resultEndpointReturnsTheEntryThenMisses() {
        ResultStore.clearForTest();
        ResultStore.put("s-9", new ResultStore.Entry("5,10,0", 1, "latency: 300ms > 250ms", false));
        assertTrue(LoadModeControl.handle("/__basquin/result", "id=s-9").contains("5,10,0"));
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", "id=s-9"));
    }

    @Test public void resultWithNoIdIsAMissNotAnError() {
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", null));
    }

    // A writer holds ITERATION_LOCK and publishes LATE, exactly as onExit does after the 25ms
    // grace sleep. The handler must WAIT for it. Delete awaitQuiescence and this test fails.
    @Test public void resultWaitsForAnInFlightIterationToPublish() throws Exception {
        ResultStore.clearForTest();
        Thread writer = new Thread(() -> {
            RequestBoundary.lockForTest();
            try { Thread.sleep(100); ResultStore.put("s-w", new ResultStore.Entry("1,2,0", 1, "x", false)); }
            catch (InterruptedException ignored) { }
            finally { RequestBoundary.unlockForTest(); }
        });
        writer.start();
        Thread.sleep(10);                        // let the writer take the lock first
        String body = LoadModeControl.handle("/__basquin/result", "id=s-w");
        writer.join();
        assertTrue("the poll must observe the late write, not race it", body.contains("1,2,0"));
    }

    @Test public void resultTimesOutToAMissRatherThanHangingTheDriver() throws Exception {
        ResultStore.clearForTest();
        Thread holder = new Thread(() -> {
            RequestBoundary.lockForTest();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            finally { RequestBoundary.unlockForTest(); }
        });
        holder.start();
        Thread.sleep(10);
        long t0 = System.nanoTime();
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", "id=nope"));
        assertTrue("must return within the bound", (System.nanoTime() - t0) / 1_000_000 < 2600);
        holder.interrupt(); holder.join();
    }
}
