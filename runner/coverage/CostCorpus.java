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

    public CostCorpus(List<String> seeds, boolean enabled) {
        this.enabled = enabled;
        this.maxSize = Integer.getInteger("basquin.corpus.max", 1000);
        this.retainFactor = dbl("basquin.cost.retainFactor", 3.0);
        this.minSamples = Integer.getInteger("basquin.cost.minSamples", 20);
        this.emaAlpha = dbl("basquin.cost.emaAlpha", 0.1);
        if (seeds != null) {
            for (String s : seeds) entries.add(new CorpusEntry(s, 0.0, 0, 0, 0, 0, true));
        }
    }

    /** Uniform parent draw (selection is NOT cost-biased in this phase). Synchronized with mutation. */
    public synchronized String randomParentInput(Random rnd) {
        if (entries.isEmpty()) return "/";
        return entries.get(rnd.nextInt(entries.size())).input;
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
        entries.add(new CorpusEntry(input, cost, latencyMs, heapDeltaKb, threadDelta, invariantCount, coverageFind));
        if (enabled) evictIfOverCap();
    }

    /** Remove the cheapest NON-coverage entry when over the cap. Coverage finds are never evicted. */
    private void evictIfOverCap() {
        while (entries.size() > maxSize) {
            int victim = -1;
            double cheapest = Double.MAX_VALUE;
            for (int i = 0; i < entries.size(); i++) {
                CorpusEntry e = entries.get(i);
                if (!e.coverageFind && e.cost < cheapest) { cheapest = e.cost; victim = i; }
            }
            if (victim < 0) break;   // only coverage-finds remain — stop (the cap yields to coverage)
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
