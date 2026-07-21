package agent;

/**
 * Load-mode flag (DD-029). The Tomcat valve reads this to pick its request-handling strategy:
 * <ul>
 *   <li><b>explore</b> (default): serialize every request under {@code ITERATION_LOCK} and capture
 *       per-request heap/thread deltas (the deltas are process-global and need serialization to be
 *       trustworthy — DD-005/DD-010).</li>
 *   <li><b>load</b>: run lock-free/passthrough so the app can be driven concurrently; per-request
 *       deltas are given up, and the app's heap/thread <em>drift</em> is read absolutely instead
 *       (see {@link #driftSnapshotCsv()}).</li>
 * </ul>
 *
 * <p>Load mode is entered with a TTL and <b>auto-reverts</b> to explore once it expires, so a driver
 * that crashes mid-run can't leave a target stuck lock-free forever. The driver refreshes the TTL by
 * re-toggling. This is a whole-target flag for a campaign's duration, never per-request — the lock is
 * all-or-nothing (a request skipping it corrupts a concurrent locked request's delta).
 */
public final class LoadMode {

    private static volatile boolean load = false;
    private static volatile long enteredAt = 0L;
    private static volatile long expiresAt = 0L;

    private LoadMode() {}

    /** Enter load mode until {@code now + ttlMillis}. Re-call to refresh the TTL (a keep-alive). */
    public static void setLoad(long ttlMillis) {
        long now = System.currentTimeMillis();
        enteredAt = now;
        expiresAt = now + ttlMillis;
        load = true;
    }

    /** Leave load mode immediately (explicit revert at a clean end-of-campaign). */
    public static void setExplore() {
        load = false;
    }

    /** True iff load mode is active and not past its TTL at {@code nowMillis} (auto-revert). */
    public static boolean isLoad(long nowMillis) {
        return load && nowMillis < expiresAt;
    }

    /** When load mode was last entered (epoch millis); for TTL/drift-window reasoning. */
    public static long enteredAt() {
        return enteredAt;
    }

    /**
     * One absolute, lock-free snapshot of the app JVM for drift tracking: {@code
     * "<usedHeapKb>,<liveThreads>,<epochMillis>"}. The driver polls this and assembles the series —
     * these are absolute {@code Runtime} reads, NOT the per-iteration {@code ctx.heapDeltaBytes}
     * delta (which needs the lock; DD-029 / #63). Comma-free numeric fields keep it CSV-safe.
     */
    public static String driftSnapshotCsv() {
        Runtime rt = Runtime.getRuntime();
        long usedHeapKb = (rt.totalMemory() - rt.freeMemory()) / 1024L;
        int threads = Thread.activeCount();
        return usedHeapKb + "," + threads + "," + System.currentTimeMillis();
    }
}
