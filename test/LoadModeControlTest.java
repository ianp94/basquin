import org.junit.After;
import org.junit.Test;
import agent.LoadMode;
import agent.LoadModeControl;

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
}
