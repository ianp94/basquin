package agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DD-040: per-request measurement results, keyed by the driver's salted request id.
 *
 * <p>Exists because the response header is not a reliable channel: the boundary can only attach
 * headers if the response has not committed, and on a real app most responses have (>8KB, an
 * explicit flush, or an error-page forward). The violations were always evaluated — they were
 * being thrown away at the last hop.
 *
 * <p>Lives inside the JVM whose heap deltas this tool reports, so it is deliberately tiny and
 * bounded: {@value #CAPACITY} entries, each capped, removed on read.
 *
 * <p>Thread-safety: written by the boundary thread (under ITERATION_LOCK, which gives the write
 * its attribution) and read by a connector thread serving a control request, which holds no such
 * lock. The map is therefore synchronized here — the iteration lock is NOT the reader's guard.
 */
public final class ResultStore {

    public static final int CAPACITY = 256;
    private static final int DETAIL_MAX = 200;   // same cap the header path applies
    public static final String MISS = "miss";

    public record Entry(String costCsv, int invariantCount, String detail, boolean leakDetected) {
        public Entry {
            if (detail != null && detail.length() > DETAIL_MAX) detail = detail.substring(0, DETAIL_MAX);
        }
    }

    private static final AtomicLong TOTAL_VIOLATIONS = new AtomicLong();

    private static final Map<String, Entry> MAP =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > CAPACITY;
                }
            });

    private ResultStore() {}

    public static void put(String id, Entry e) {
        if (id == null || e == null) return;
        TOTAL_VIOLATIONS.addAndGet(e.invariantCount());
        MAP.put(id, e);
    }

    /** Returns and REMOVES the entry, or null on a miss. Remove-on-read bounds retention for a
     *  driver that polls every request, and makes a duplicate poll honestly miss. */
    public static Entry take(String id) { return id == null ? null : MAP.remove(id); }

    public static int size() { return MAP.size(); }
    public static long totalViolations() { return TOTAL_VIOLATIONS.get(); }

    /** Wire format: {@code costCsv|invariantCount|detail|leak} — FOUR fields, plaintext. {@code
     *  detail} is app-derived and may contain '|', so it is sanitised here; the driver still parses
     *  with a 4-field limit rather than a naive split. */
    public static String format(Entry e) {
        if (e == null) return MISS;
        String d = e.detail() == null ? "" : e.detail().replace('|', '/');
        return (e.costCsv() == null ? "" : e.costCsv()) + "|" + e.invariantCount() + "|" + d
                + "|" + (e.leakDetected() ? "leak" : "");
    }

    public static void clearForTest() { MAP.clear(); TOTAL_VIOLATIONS.set(0); }
}
