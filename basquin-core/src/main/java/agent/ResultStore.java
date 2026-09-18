package agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>DD-039 §4b: a value is a LIST of hops, not a single entry. Explore follows redirects itself and
 * stamps every hop of a chain with one id, so {@code put} must ACCUMULATE — DD-040's
 * {@code MAP.put(id, e)} had each hop overwrite the last, which is why a POST → 302 → GET chain
 * recovered exactly one hop and 189 of a measured 1,602 violations were never reported.
 *
 * <p>Order is publish order within THIS JVM, which is hop order because the driver issues hops
 * sequentially. That is what makes the driver's {@code hop=<n>} meaningful. A chain whose hops were
 * served by different replicas is recovered per-pod, so the driver's index is a position in the
 * recovered sequence rather than a proof of hop order — see {@code CoverageGuidedRun.pollResult}.
 *
 * <p>Retention bound: {@value #CAPACITY} ids × {@value #MAX_HOPS_PER_ID} hops × a 200-char detail
 * ≈ 256 KB worst case, in the JVM whose heap deltas this tool reports.
 */
public final class ResultStore {

    public static final int CAPACITY = 256;

    /**
     * Hops retained per id. The SAME number as the driver's follow cap
     * ({@code CoverageGuidedRun.MAX_HOPS} = 5 requests total), so a conforming driver never
     * overflows and this is a defensive bound on a version-skewed producer. Two caps that disagree
     * would silently drop a hop, and a silently dropped hop is a lost violation — the defect class
     * this whole change exists to remove.
     */
    public static final int MAX_HOPS_PER_ID = 5;

    private static final int DETAIL_MAX = 200;   // same cap the header path applies
    public static final String MISS = "miss";

    /**
     * DD-043 PR-5 (D1): the disposition of a fully-measured sample. The Tomcat path publishes this
     * on every entry — its iterations are serialized under ITERATION_LOCK, so every published
     * sample is attributable by construction. The reactive path's other dispositions
     * ({@code UNMEASURED}, {@code disconnected}, spec §6/§6.1) land with their producers.
     */
    public static final String DISPOSITION_MEASURED = "measured";

    /**
     * {@code disposition} (DD-043 PR-5, D1): how this sample may be used — {@code "measured"} for a
     * sample whose window is attributable to this request. Producer-controlled vocabulary, never
     * app-derived. {@code null} formats as an empty field, which a reader must treat as ABSENT
     * (a pre-PR-5 producer), never as measured-by-default.
     */
    public record Entry(String costCsv, int invariantCount, String detail, boolean leakDetected,
                        String disposition) {
        public Entry {
            if (detail != null && detail.length() > DETAIL_MAX) detail = detail.substring(0, DETAIL_MAX);
        }
    }

    private static final AtomicLong TOTAL_VIOLATIONS = new AtomicLong();
    private static final AtomicLong OVERFLOWED_HOPS = new AtomicLong();

    private static final Map<String, List<Entry>> MAP =
            Collections.synchronizedMap(new LinkedHashMap<String, List<Entry>>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Entry>> eldest) {
                    return size() > CAPACITY;   // CAPACITY IDS, not entries — size() means what it meant
                }
            });

    private ResultStore() {}

    /**
     * APPEND this hop's measurements to the id's list.
     *
     * <p>Both the lookup and the append run inside {@code synchronized (MAP)} — the monitor for a
     * {@code Collections.synchronizedMap} is the wrapper itself. The natural one-liner,
     * {@code MAP.computeIfAbsent(id, k -> new ArrayList<>()).add(e)}, performs the {@code add}
     * OUTSIDE that monitor, so a concurrent {@code take} can hand a connector thread a list that is
     * mid-append. The reader holds no ITERATION_LOCK (see the class javadoc), so that is a live race.
     */
    public static void put(String id, Entry e) {
        if (id == null || e == null) return;
        TOTAL_VIOLATIONS.addAndGet(e.invariantCount());
        synchronized (MAP) {
            List<Entry> hops = MAP.get(id);
            if (hops == null) {
                hops = new ArrayList<>(2);
                MAP.put(id, hops);          // the only put: removeEldestEntry evicts whole IDS
            }
            hops.add(e);
            while (hops.size() > MAX_HOPS_PER_ID) {
                // Drop the OLDEST. The newest hop of a redirect chain is the landing page — the
                // committed render that is the highest-value measurement and the entire reason this
                // feature exists. The oldest is a boilerplate 302.
                hops.remove(0);
                OVERFLOWED_HOPS.incrementAndGet();
            }
        }
    }

    /**
     * Returns ALL hops recorded under this id, in publish order, and REMOVES the id. Empty list on a
     * miss — never null.
     *
     * <p>Still exactly ONE remove-on-read, so a duplicate poll honestly misses. Note that this alone
     * does NOT give the driver header/poll exclusivity: that is a property of the driver reconciling
     * at one point (see {@code CoverageGuidedRun.reconcile}), because the header path saves the
     * moment it reads the header and no flag can un-save a file.
     *
     * <p>The copy is taken inside the monitor: the caller must never hold a list a concurrent
     * {@code put} can append to.
     */
    public static List<Entry> take(String id) {
        if (id == null) return Collections.emptyList();
        synchronized (MAP) {
            List<Entry> hops = MAP.remove(id);
            if (hops == null || hops.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(hops));
        }
    }

    /** IDS currently held, not hops — unchanged meaning, so the capacity assertions still hold. */
    public static int size() { return MAP.size(); }

    public static long totalViolations() { return TOTAL_VIOLATIONS.get(); }

    /** Hops dropped because a chain exceeded {@link #MAX_HOPS_PER_ID}. Counted because a silently
     *  dropped hop is a lost violation, which is exactly the defect class DD-039 removes. */
    public static long overflowedHops() { return OVERFLOWED_HOPS.get(); }

    /**
     * Wire format: ONE LINE PER HOP, {@code '\n'}-separated, each line
     * {@code costCsv|invariantCount|detail|leak|disposition} — five fields, plaintext (the fifth is
     * DD-043 PR-5's D1 widening). {@code detail} is app-derived, so BOTH {@code '|'} and newlines
     * are sanitised here: a pipe would shift the field boundaries, and a newline would forge an
     * extra hop line and have the driver count a violation the app invented. {@code disposition} is
     * producer-controlled vocabulary, but it is sanitised identically — defense in depth costs one
     * call and a compromised producer string must not be able to forge a hop either. The driver
     * splits on {@code '\n'} and parses each line with a 5-field limit.
     *
     * <p><b>Version skew (D1), pinned honestly rather than claimed impossible:</b> a pre-PR-5
     * driver parses these lines with {@code split("\\|", 4)}, so on a five-field line its
     * {@code f[3]} reads {@code "leak|<disposition>"} and its {@code "leak".equals(f[3])} check is
     * a silent leak FALSE-NEGATIVE if negotiation is bypassed (pinned by {@code ResultWireSkewTest}).
     * Endpoints therefore emit this format only for {@code wire=2}; legacy serialized clients
     * receive {@link #formatLegacy}, and reactive targets reject legacy polling.
     *
     * <p>Null or empty is {@link #MISS}, so {@code LoadModeControl}'s caller and the driver's
     * {@code POLL_MISS.equals(body)} check are unchanged by accumulation.
     */
    public static String format(List<Entry> hops) {
        if (hops == null || hops.isEmpty()) return MISS;
        StringBuilder sb = new StringBuilder();
        for (Entry e : hops) {
            if (e == null) continue;
            if (sb.length() > 0) sb.append('\n');
            String d = sanitize(e.detail());
            sb.append(e.costCsv() == null ? "" : e.costCsv()).append('|')
              .append(e.invariantCount()).append('|').append(d).append('|')
              .append(e.leakDetected() ? "leak" : "").append('|')
              .append(sanitize(e.disposition()));
        }
        return sb.length() == 0 ? MISS : sb.toString();
    }

    /** Original wire for serialized targets serving old runners. Never append a fifth field. */
    public static String formatLegacy(List<Entry> hops) {
        String body = format(hops);
        if (MISS.equals(body)) return body;
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            if (out.length() > 0) out.append('\n');
            out.append(line, 0, line.lastIndexOf('|'));
        }
        return out.toString();
    }

    public static final String SERIALIZED_WIRE = "basquin-result-v2:serialized";
    public static final String REACTIVE_WIRE = "basquin-result-v2:reactive";
    public static final String UPGRADE_REQUIRED = "err:result-wire-upgrade-required";

    private static String sanitize(String field) {
        return field == null ? ""
                : field.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    public static void clearForTest() {
        MAP.clear();
        TOTAL_VIOLATIONS.set(0);
        OVERFLOWED_HOPS.set(0);
    }
}
