package agent;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * DD-040 invariant 4: <b>the load path gains zero instructions.</b>
 *
 * <p>{@code RequestBoundaryIdTest.loadPassthroughNeverWritesTheStore} pins that a load passthrough
 * writes no {@link ResultStore} entry. That is a different claim: the first shipped version of
 * {@code onExit} ran {@code REQ_ID.get()} + {@code REQ_ID.remove()} <em>before</em> the phase check,
 * so it wrote no result and still charged every load request two ThreadLocalMap operations and one
 * allocation — {@code ThreadLocal.get()} on an unset variable does not merely probe, its
 * {@code setInitialValue()} inserts a null-valued {@code ThreadLocalMap.Entry}. The claim said zero;
 * the code said otherwise, and no test could tell the difference.
 *
 * <p>So this test looks at the thread's actual {@code ThreadLocalMap}: after a load passthrough (and
 * after a control request) there must be no entry keyed by the boundary's request-id variable at
 * all. Each case runs on a freshly started thread, because the assertion is about a connector thread
 * that has never served an explore request — the pooled-thread case the boundary really faces.
 *
 * <p>Reading {@code Thread.threadLocals} needs {@code --add-opens java.base/java.lang=ALL-UNNAMED},
 * which the {@code test} task passes for this file's sake. {@link #theProbeCanTellThePresentCase}
 * is what keeps the negative assertions from passing vacuously.
 */
public class RequestBoundaryLoadPathCostTest {

    /** True iff this thread's ThreadLocalMap holds an entry keyed by RequestBoundary.REQ_ID. */
    private static boolean hasRequestIdEntry() throws Exception {
        Field idField = RequestBoundary.class.getDeclaredField("REQ_ID");
        idField.setAccessible(true);
        Object reqIdKey = idField.get(null);

        Field mapField = Thread.class.getDeclaredField("threadLocals");
        mapField.setAccessible(true);
        Object map = mapField.get(Thread.currentThread());
        if (map == null) return false;    // no ThreadLocal of any kind has been touched

        Field tableField = map.getClass().getDeclaredField("table");
        tableField.setAccessible(true);
        for (Object entry : (Object[]) tableField.get(map)) {
            // ThreadLocalMap.Entry extends WeakReference<ThreadLocal<?>>; the referent is the key.
            if (entry != null && ((Reference<?>) entry).get() == reqIdKey) return true;
        }
        return false;
    }

    /** Runs the body on a brand-new thread and rethrows whatever it failed with. */
    private static void onAFreshThread(ThrowingRunnable body) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { body.run(); } catch (Throwable e) { failure.set(e); }
        }, "connector-thread-under-test");
        t.start();
        t.join(30_000L);
        assertFalse("the request under test never finished", t.isAlive());
        if (failure.get() != null) throw failure.get();
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    @Test public void aLoadPassthroughNeverTouchesTheRequestIdThreadLocal() throws Throwable {
        LoadMode.setLoad(60_000L);
        try {
            onAFreshThread(() -> {
                assertFalse("precondition: a fresh thread has no request-id entry", hasRequestIdEntry());
                RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
                assertEquals(RequestBoundary.Phase.LOAD_PASSTHROUGH, d.phase);
                RequestBoundary.onExit(null);
                assertFalse("a load passthrough must not insert a ThreadLocalMap entry for the "
                        + "request id — not even the null-valued one ThreadLocal.get() creates",
                        hasRequestIdEntry());
            });
        } finally {
            LoadMode.setExplore();
        }
    }

    @Test public void aControlRequestNeverTouchesTheRequestIdThreadLocal() throws Throwable {
        onAFreshThread(() -> {
            RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/drift", null);
            assertEquals(RequestBoundary.Phase.CONTROL_HANDLED, d.phase);
            RequestBoundary.onExit(null);
            assertFalse("a control request must not touch the request-id ThreadLocal either",
                    hasRequestIdEntry());
        });
    }

    /**
     * The probe is only worth anything if it can see an entry that IS there. This also pins the
     * safety argument the load-path fix rests on: the explore branch — the only path that can ever
     * write an id — still removes it before returning, so nothing is left behind for the next
     * request on a pooled connector thread.
     */
    @Test public void theProbeCanTellThePresentCase() throws Throwable {
        onAFreshThread(() -> {
            RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
            assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
            assertFalse("no entry before the glue stamps", hasRequestIdEntry());
            RequestBoundary.stampRequestId("salt-probe");
            assertTrue("the probe must see the entry the stamp creates", hasRequestIdEntry());
            RequestBoundary.onExit(null);
            assertFalse("the explore exit must remove it — a stale id must not outlive its request",
                    hasRequestIdEntry());
            assertFalse("and the explore path must still publish",
                    ResultStore.take("salt-probe").isEmpty());
        });
    }
}
