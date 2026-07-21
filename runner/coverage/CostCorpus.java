package runner.coverage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The exploration corpus as cost-bearing entries (DD-031). Encapsulates: uniform parent selection,
 * expanded retention (coverage OR notably-expensive, gated by a cold-start minimum and an EMA baseline),
 * bounded size with coverage-preserving eviction, and cost-ranked replay ordering. ALL access is
 * synchronized on this instance — eviction can shrink the list, so unsynchronized index reads would
 * race (the whole reason this class exists rather than a bare List). When {@code enabled} is false it
 * behaves exactly like today's grow-only, coverage-only, insertion-ordered corpus.
 */
public final class CostCorpus {

    private final List<CorpusEntry> entries = new ArrayList<>();
    private final boolean enabled;
    private final int maxSize;
    private final double retainFactor;
    private final int minSamples;
    private final double emaAlpha;

    private double emaCost = 0.0;   // EMA baseline of observed cost (heavy-tail-robust vs a mean)
    private int samples = 0;

    private final boolean pheromone;
    private final double epsilon;
    private final double decay;
    private final double depositCap;
    private double totalPheromone = 0.0;   // running sum of in-corpus pheromone (O(1)-maintained)

    public CostCorpus(List<String> seeds, boolean enabled) { this(seeds, enabled, false); }

    public CostCorpus(List<String> seeds, boolean enabled, boolean pheromone) {
        this.enabled = enabled;
        this.pheromone = pheromone;
        this.maxSize = Integer.getInteger("basquin.corpus.max", 1000);
        this.retainFactor = dbl("basquin.cost.retainFactor", 3.0);
        this.minSamples = Integer.getInteger("basquin.cost.minSamples", 20);
        this.emaAlpha = dbl("basquin.cost.emaAlpha", 0.1);
        this.epsilon = dbl("basquin.pheromone.epsilon", 0.3);
        this.decay = dbl("basquin.pheromone.decay", 0.7);
        this.depositCap = dbl("basquin.pheromone.depositCap", 10.0);
        if (seeds != null) {
            for (String s : seeds) entries.add(new CorpusEntry(s, 0.0, 0, 0, 0, 0, true)); // pheromone 0 (ema=0 here)
        }
    }

    /**
     * ε-greedy parent selection (DD-032): with probability ε (or when pheromone is off / the total is
     * zero / cold start) draw UNIFORMLY — the coverage guardrail; otherwise roulette by pheromone.
     * Returns the ENTRY so the caller can reinforce it. Null only if the corpus is empty. Synchronized.
     * Single O(n) locate scan against the running total — no re-summing.
     */
    public synchronized CorpusEntry selectParent(Random rnd) {
        int n = entries.size();
        if (n == 0) return null;
        if (!pheromone || totalPheromone <= 0.0 || rnd.nextDouble() < epsilon) {
            return entries.get(rnd.nextInt(n));   // uniform (off / cold-start / ε-explore)
        }
        double r = rnd.nextDouble() * totalPheromone;
        double cum = 0.0;
        for (int i = 0; i < n; i++) {
            cum += entries.get(i).pheromone;
            if (cum >= r) return entries.get(i);
        }
        return entries.get(n - 1);   // float-rounding safety
    }

    /**
     * Deposit a fired child's cost onto the parent it was mutated from (DD-032 credit assignment),
     * capped at depositCap × EMA so one invariant-hit spike can't own the roulette. MUST be called
     * before the child's consider() (the only evictor), so parent is a live entry and the running total
     * stays exact — do not reorder. No contains-check needed for that reason. Synchronized.
     */
    public synchronized void reinforce(CorpusEntry parent, double childCost) {
        if (!pheromone || parent == null) return;
        double cap = depositCap * (emaCost > 0 ? emaCost : childCost);
        double deposit = Math.min(childCost, cap);
        parent.pheromone += deposit;
        totalPheromone += deposit;
    }

    /** Evaporate all pheromone by `decay` (DD-032). O(n), called every N iterations by the loop. */
    public synchronized void evaporate() {
        if (!pheromone) return;
        for (CorpusEntry e : entries) e.pheromone *= decay;
        totalPheromone *= decay;
    }

    /** Back-compat string accessor (DD-031). Uniform when pheromone is off. */
    public synchronized String randomParentInput(Random rnd) {
        CorpusEntry e = selectParent(rnd);
        return e == null ? "/" : e.input;
    }

    /**
     * Offer a just-fired input. Coverage finds are always retained. When enabled, a non-coverage input
     * is retained iff cost-retention is active (>= minSamples observed) and cost exceeds retainFactor ×
     * the EMA baseline. Every enabled observation trains the EMA. Over-cap triggers eviction.
     */
    public synchronized void consider(String input, double cost, long latencyMs, long heapDeltaKb,
                                      int threadDelta, int invariantCount, boolean coverageFind) {
        boolean retain = coverageFind;
        if (enabled && !coverageFind) {
            samples++;
            boolean active = samples > minSamples;      // cold-start guard
            if (active && emaCost > 0 && cost > retainFactor * emaCost) retain = true;
            // train the EMA AFTER the threshold check so an input isn't measured against itself
            emaCost = emaCost == 0.0 ? cost : emaAlpha * cost + (1 - emaAlpha) * emaCost;
        }
        if (!retain) return;
        CorpusEntry e = new CorpusEntry(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind);
        if (pheromone) { e.pheromone = emaCost + cost; totalPheromone += e.pheromone; }
        entries.add(e);
        if (enabled) evictIfOverCap();
    }

    /** Remove the worst NON-coverage entry when over the cap (pheromone-aware when on). Coverage finds are never evicted. */
    private void evictIfOverCap() {
        while (entries.size() > maxSize) {
            int victim = -1;
            double worst = Double.MAX_VALUE;
            for (int i = 0; i < entries.size(); i++) {
                CorpusEntry e = entries.get(i);
                if (e.coverageFind) continue;                 // coverage backbone never evicted
                double key = pheromone ? e.pheromone : e.cost; // pheromone-aware when on (DD-032)
                if (key < worst) { worst = key; victim = i; }
            }
            if (victim < 0) break;   // only coverage-finds remain — stop (the cap yields to coverage)
            totalPheromone -= entries.get(victim).pheromone;
            entries.remove(victim);
        }
    }

    /** A copy for replay emission: cost-descending when enabled, else insertion order (today's behavior). */
    public synchronized List<CorpusEntry> snapshotByCost() {
        List<CorpusEntry> copy = new ArrayList<>(entries);
        if (enabled) copy.sort(Comparator.comparingDouble((CorpusEntry e) -> e.cost).reversed());
        return copy;
    }

    public synchronized int size() { return entries.size(); }

    private static double dbl(String key, double def) {
        try { String v = System.getProperty(key); return v == null ? def : Double.parseDouble(v); }
        catch (RuntimeException e) { return def; }
    }
}
