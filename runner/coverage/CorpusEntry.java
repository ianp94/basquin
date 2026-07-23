package runner.coverage;

/** An input in the exploration corpus plus the cost it produced when fired (DD-031). The cost fields
 *  are immutable; {@code pheromone} (DD-032) is monitor-guarded mutable state owned by CostCorpus. */
public final class CorpusEntry {
    public final String input;
    public final double cost;
    public final long latencyMs;
    public final long heapDeltaKb;
    public final int threadDelta;
    public final int invariantCount;
    /** true = retained because it hit new coverage (never evicted); false = retained purely for cost. */
    public final boolean coverageFind;
    /** DD-039: how many HTTP requests explore fired to produce this entry — 1 for an ordinary input,
     *  &gt;1 when it followed a redirect chain. The cost fields are the SUMMED multi-hop cost, but load
     *  replays the same input with follow OFF (DD-038) and fires exactly one hop, so a login POST
     *  ranked expensive on its dashboard hop is a cheap 302 under load. Recorded so the next reader of
     *  a cost-ranked corpus knows the number is an explore-side measurement. */
    public final int hops;
    /** DD-032 selection weight. Package-private: written ONLY by CostCorpus's synchronized methods. */
    double pheromone;

    public CorpusEntry(String input, double cost, long latencyMs, long heapDeltaKb,
                       int threadDelta, int invariantCount, boolean coverageFind) {
        this(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind, 1);
    }

    /** DD-039: the widened form threading the explore-side hop count. The 7-arg constructor delegates
     *  here with {@code hops = 1}, so the ~20 existing construction sites compile unchanged. */
    public CorpusEntry(String input, double cost, long latencyMs, long heapDeltaKb,
                       int threadDelta, int invariantCount, boolean coverageFind, int hops) {
        this.input = input;
        this.cost = cost;
        this.latencyMs = latencyMs;
        this.heapDeltaKb = heapDeltaKb;
        this.threadDelta = threadDelta;
        this.invariantCount = invariantCount;
        this.coverageFind = coverageFind;
        this.hops = hops;
    }
}
