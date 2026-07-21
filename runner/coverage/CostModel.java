package runner.coverage;

/**
 * Turns a request's observed cost components into one comparable number (DD-031). Higher = more
 * "expensive" = more worth hammering under load. Coefficients are -D overridable; defaults weight the
 * rare, high-signal events (invariant hits, leaked threads) far above ordinary latency, and heap growth
 * — the availability thesis — above latency too. Pure and stateless.
 */
public final class CostModel {

    private static final double C_LAT    = dbl("basquin.cost.latencyWeight", 1.0);
    private static final double C_HEAP   = dbl("basquin.cost.heapWeight", 0.0625);   // per KB (16KB => 1)
    private static final double C_THREAD = dbl("basquin.cost.threadWeight", 500.0);  // per leaked thread
    private static final double C_INV    = dbl("basquin.cost.invariantWeight", 1000.0);

    private CostModel() {}

    /** Negative deltas (freed heap / dropped threads) contribute 0, never a discount. */
    public static double score(double latencyMs, double heapDeltaKb, int threadDelta, int invariantCount) {
        return C_LAT    * Math.max(0.0, latencyMs)
             + C_HEAP   * Math.max(0.0, heapDeltaKb)
             + C_THREAD * Math.max(0,   threadDelta)
             + C_INV    * Math.max(0,   invariantCount);
    }

    private static double dbl(String key, double def) {
        try { String v = System.getProperty(key); return v == null ? def : Double.parseDouble(v); }
        catch (RuntimeException e) { return def; }
    }
}
