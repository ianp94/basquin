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
    /** DD-032 selection weight. Package-private: written ONLY by CostCorpus's synchronized methods. */
    double pheromone;

    public CorpusEntry(String input, double cost, long latencyMs, long heapDeltaKb,
                       int threadDelta, int invariantCount, boolean coverageFind) {
        this.input = input;
        this.cost = cost;
        this.latencyMs = latencyMs;
        this.heapDeltaKb = heapDeltaKb;
        this.threadDelta = threadDelta;
        this.invariantCount = invariantCount;
        this.coverageFind = coverageFind;
    }

    /** Read-only accessor for the pheromone weight (writes stay inside CostCorpus). */
    public double pheromone() { return pheromone; }
}
