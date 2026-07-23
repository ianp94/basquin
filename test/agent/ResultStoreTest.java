package agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResultStoreTest {

    @Before public void reset() { ResultStore.clearForTest(); }

    @Test public void putThenTakeReturnsTheEntryExactlyOnce() {
        ResultStore.put("salt-1", new ResultStore.Entry("12,340,0", 2, "latency: 719ms > 250ms", false));
        ResultStore.Entry e = ResultStore.take("salt-1");
        assertNotNull(e);
        assertEquals(2, e.invariantCount());
        assertNull("remove-on-read: a second take must miss", ResultStore.take("salt-1"));
    }

    // DD-040: a foreign or stale id must MISS, never return another run's entry. An unsalted
    // counter collides across two drivers or two campaigns against one long-lived target, and
    // returning stale data as fresh is worse than returning nothing.
    @Test public void unknownIdMisses() {
        assertNull(ResultStore.take("other-salt-1"));
    }

    @Test public void evictsOldestBeyondCapacityAndStaysBounded() {
        for (int i = 0; i < ResultStore.CAPACITY + 50; i++) {
            ResultStore.put("s-" + i, new ResultStore.Entry("1,2,0", 0, null, false));
        }
        assertEquals(ResultStore.CAPACITY, ResultStore.size());
        assertNull("oldest evicted", ResultStore.take("s-0"));
        assertNotNull("newest retained", ResultStore.take("s-" + (ResultStore.CAPACITY + 49)));
    }

    // The store lives inside the JVM whose heap deltas this tool reports, so its footprint is
    // part of the measurement. detail is capped like the header path already caps it.
    @Test public void detailIsCappedSoRetentionIsBounded() {
        String huge = "x".repeat(5000);
        ResultStore.put("s-cap", new ResultStore.Entry("1,2,0", 1, huge, false));
        assertTrue(ResultStore.take("s-cap").detail().length() <= 200);
    }

    // DD-040: the poll runs on a connector thread that never holds ITERATION_LOCK, while the
    // boundary write comes from a different thread under that lock -- so the map itself, not the
    // lock, is what has to be safe. A prior version of this test used a writer thread putting
    // "c-0".."c-1999" and a reader thread taking the same 2000 keys, then asserted only
    // size() <= CAPACITY; that proves the eviction cap holds, not that the map survived concurrent
    // structural mutation. Two independent, non-lockstepped loops racing through 2000 keys mostly
    // end up touching *different* keys at any given instant (one loop typically runs ahead of the
    // other), so real head-to-head contention on the same node was rare -- and CAPACITY=256 with
    // only 2000 total puts meant most of the run was past capacity anyway, but with only two
    // threads total, still not enough concurrent churn to be reliable: removing
    // Collections.synchronizedMap(...) entirely still let that version pass 85-95% of the time
    // (measured in review). This version instead:
    //   - draws every put/take from a small shared key pool (just over CAPACITY) so multiple
    //     writer AND reader threads are constantly hitting the exact same keys at the same time;
    //   - keeps the pool only slightly larger than CAPACITY so the map sits at its eviction
    //     threshold for virtually the whole run -- every put() is racing removeEldestEntry's own
    //     unlink against take()'s MAP.remove() unlink, which is the actual production race
    //     (LinkedHashMap's internal doubly-linked eviction order is a single shared structure that
    //     every put/remove touches, not just per-key state);
    //   - uses several writer AND reader threads (not just one of each) with a high per-thread
    //     iteration count, to raise the odds of a genuine same-instant collision from "occasional"
    //     to "essentially certain";
    //   - captures any Throwable from the worker threads into an AtomicReference, because a
    //     Thread's uncaught exception (e.g. ConcurrentModificationException, or an
    //     ArrayIndexOutOfBoundsException from a torn resize) is otherwise swallowed by the JVM's
    //     default handler and invisible to join();
    //   - bounds each join() with a timeout and asserts the thread actually finished, because the
    //     other real corruption failure mode is a hang (a torn circular link in the eviction
    //     list), not an exception; and
    //   - checks every entry actually taken carries an invariantCount from the pool's range, so a
    //     structurally-corrupted map handing back an aliased/wrong Entry would be caught even if
    //     it never throws or hangs.
    @Test public void concurrentPutAndTakeDoNotCorruptTheStore() throws Exception {
        final int POOL = ResultStore.CAPACITY + 50; // just over capacity: eviction fires on
                                                      // nearly every put, maximizing structural churn
        final int OPS_PER_THREAD = 300_000;
        final int WRITERS = 4;
        final int READERS = 4;
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        Runnable writer = () -> {
            try {
                java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int k = rnd.nextInt(POOL);
                    ResultStore.put("c-" + k, new ResultStore.Entry("1,2,0", k, null, false));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
        Runnable reader = () -> {
            try {
                java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int k = rnd.nextInt(POOL);
                    ResultStore.Entry e = ResultStore.take("c-" + k);
                    if (e != null && (e.invariantCount() < 0 || e.invariantCount() >= POOL)) {
                        throw new AssertionError("took a corrupted/aliased entry: " + e);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };

        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < WRITERS; i++) threads.add(new Thread(writer, "writer-" + i));
        for (int i = 0; i < READERS; i++) threads.add(new Thread(reader, "reader-" + i));
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(30_000);
        for (Thread t : threads) {
            assertFalse(t.getName() + " hung -- likely a corrupted internal link from "
                    + "unsynchronized concurrent structural mutation", t.isAlive());
        }
        assertNull("a worker thread threw: " + failure.get(), failure.get());
        // Check BOTH bounds, not just the upper one: a corrupted (unsynchronized) HashMap's
        // internal size counter can go negative under concurrent structural mutation, and a
        // negative number satisfies "<= CAPACITY" trivially -- that's exactly the blind spot the
        // original version of this test had.
        int size = ResultStore.size();
        assertTrue("store size left in an impossible state (corrupted internal bookkeeping): " + size,
                size >= 0 && size <= ResultStore.CAPACITY);
    }

    // DD-040: detail is app-derived, so an app that logs a literal '|' in an invariant message
    // must not be able to shift the wire format's field boundaries. format() sanitizes '|' -> '/'
    // in detail before joining; without that, this detail would produce a 5th field and push
    // "leak" out of the 4th (last) position, and a naive driver-side split('|') would silently
    // parse the leak flag as part of detail instead.
    @Test public void formatSanitizesPipesInDetailSoTheWireFormatStaysFourFields() {
        ResultStore.Entry e = new ResultStore.Entry("12,340,0", 2, "a|b|c", true);
        String body = ResultStore.format(e);
        String[] fields = body.split("\\|", -1);
        assertEquals("detail containing '|' must not add wire fields: " + body, 4, fields.length);
        assertEquals("12,340,0", fields[0]);
        assertEquals("2", fields[1]);
        assertEquals("a/b/c", fields[2]);
        assertEquals("leak flag must stay in the 4th field, not get pushed out by an unescaped '|'",
                "leak", fields[3]);
    }

    @Test public void violationsTotalAccumulatesAcrossEntries() {
        ResultStore.put("v-1", new ResultStore.Entry("1,2,0", 3, null, false));
        ResultStore.put("v-2", new ResultStore.Entry("1,2,0", 4, null, false));
        assertEquals(7, ResultStore.totalViolations());
    }
}
