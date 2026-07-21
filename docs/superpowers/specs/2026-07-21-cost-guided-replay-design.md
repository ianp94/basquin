# DD-031 — Cost-ranked replay corpus (cost-guided exploration, phase 1) — Design

**Status:** accepted (2026-07-21), pending implementation. Builds on coverage-guided exploration
(DD-026 load mode, the `CoverageGuidedRun` loop), the shared boundary (DD-030 `RequestBoundary` /
agent advice, and the valve), and lock-free load (DD-029).

## Context — exploration finds diverse states, but load hammers them blindly

Today the coverage-guided runner keeps an input if it **increased coverage** and mutates parents by
**uniform random** draw. The **replay corpus** that load mode consumes is emitted in *insertion
order* and truncated by a byte budget — so which states get load-tested is an accident of discovery
order, not of how expensive they are. Meanwhile no per-input **cost** (latency / heap growth / thread
leak) is computed or stored anywhere: the corpus is a bare `List<String>`.

The thesis (`docs/LOCKFREE-LOAD-DESIGN.md` synergy note) is that a load test should **concentrate
real concurrent load on the states that hurt**. With DD-029 (lock-free load) and DD-030 (the operator
now measures the server) both merged, the missing piece is a per-input cost signal and a way to steer
load with it.

## Decision — measure cost during explore, rank the replay corpus by it

Steer **load**, not exploration. Exploration stays coverage-driven (its job is diverse discovery;
cost-biasing selection risks trading away coverage). We measure each input's cost during the explore
run and hand load the most expensive states.

**Why not "true pheromone" (evaporation + reinforcement) now.** In the current loop each corpus entry
is fired exactly once (seeds in the first pass; a coverage/cost find when it is added) — entries are
never re-fired, their *mutated children* are, as new entries. So an entry carries a single measured
cost; there is nothing to evaporate or reinforce. Real pheromone dynamics need **credit assignment**
(a child's cost flowing back along the mutation lineage to bias parent *selection*) — a genuine future
feature, valuable only once selection is cost-biased. This phase deliberately stops at cost-ranked
replay; the richer pheromone stays a documented roadmap extension.

## Architecture & components

### New per-request cost channel — `X-Basquin-Cost` header

`RequestBoundary.onExit` already writes `X-Basquin-Invariant-Count`/`-Detail` on the `EXPLORE_BEGAN`
path (explore serializes under `ITERATION_LOCK`, so per-request heap/thread deltas are attributable —
DD-010). Add a sibling header carrying the target-side cost the `IterationContext` already holds:

```
X-Basquin-Cost: <latencyMs>,<heapDeltaKb>,<threadDelta>
```

- **Agent** exposes the just-ended iteration's cost via statics that mirror `lastInvariantViolations`
  (set at `Agent.java:404` from `ctx`): add `lastLatencyMs`, `lastHeapDeltaKb`, `lastThreadDelta`
  (from `ctx.latencyMs`, `ctx.heapDeltaBytes/1024`, `ctx.threadDelta`) and a getter.
- **RequestBoundary** reads them in `onExit` (only for `EXPLORE_BEGAN`) and adds the header alongside
  the invariant header, through the same best-effort `Map<String,String>` the callers already write.
- Written by the shared boundary, so it works on **both** the valve (bench) and the agent advice
  (operator, DD-030) — the operator path gets real target heap/thread cost for the first time.
- **Best-effort delivery** (DD-029 noted some apps flush early and drop late headers). The driver's own
  round-trip latency is always available as the cost floor; missing header → heap/thread contribute 0.

### New: `runner/coverage/CostModel.java` — pure cost scoring

```java
public final class CostModel {
    // Coefficients (‑D overridable). Defaults weight rare/high-signal events heavily; heap is the thesis.
    // basquin.cost.latencyWeight (1.0), .heapWeight (0.0625 = per-16KB), .threadWeight (500), .invariantWeight (1000)
    public static double score(double latencyMs, double heapDeltaKb, int threadDelta, int invariantCount) {
        return c_lat*latencyMs + c_heap*heapDeltaKb + c_thread*Math.max(0,threadDelta) + c_inv*invariantCount;
    }
}
```

Coefficients read once from system properties (defaults above). Pure, unit-testable, no I/O.

### New: `runner/coverage/CorpusEntry.java` — a corpus entry with its cost

Replaces the bare `String`. Carries the input, its computed cost, the raw components (for transparency
in the summary), and whether it earned its place by coverage:

```java
public final class CorpusEntry {
    public final String input;
    public final double cost;
    public final long latencyMs; public final long heapDeltaKb; public final int threadDelta; public final int invariantCount;
    public final boolean coverageFind;   // true = retained for new coverage; false = retained purely for cost
}
```

### Modified: `runner/coverage/CoverageGuidedRun.java`

1. **Corpus type:** `List<String>` → `List<CorpusEntry>`. Parent selection stays **uniform** (unchanged
   behavior) but reads `entry.input`. `lastCorpus` and the sequence branch updated in lockstep.
2. **Capture cost per fired input:** `request()` already brackets the call with the driver's
   `Agent.beginIteration/endIteration` (real round-trip `latencyMs`) and reads
   `X-Basquin-Invariant-Count`. Additionally read `X-Basquin-Cost` (heap/thread), compute
   `cost = CostModel.score(latencyMs, heapDeltaKb, threadDelta, invariantCount)`, and return it to the
   loop (today `request()` does not surface a per-input value; it returns the cost via a small result
   object or out-field so retention can use it).
3. **Expanded retention:** add a `CorpusEntry` when **coverage increased** (`coverageFind=true`, as
   today) **OR** the input is notably expensive — `cost > retainFactor × runningMeanCost`
   (`basquin.cost.retainFactor`, default 3.0), `coverageFind=false`. The running mean is maintained
   over all measured costs.
4. **Bounded corpus, coverage-preserving eviction:** cap at `basquin.corpus.max` (default 1000). When
   over cap, evict the **lowest-cost `coverageFind=false`** entry; never evict a `coverageFind` entry
   (they are the coverage backbone that keeps exploration diverse). If only coverage-finds remain, stop
   adding cost-finds. This bounds memory and keeps the pool focused without harming coverage behavior.
5. **Cost-ranked replay emission:** `replayCorpusJson`/`writeSummary` sorts entries by `cost` **desc**
   (was `LinkedHashSet` insertion order), dedups by input, and appends within the existing
   `REPLAY_CORPUS_MAX_BYTES` budget — so the most expensive states survive truncation instead of the
   earliest-discovered. The emitted ConfigMap stays **plain route lines** (no format change → zero
   operator/LoadRun change). Load then replays the expensive set.
6. **Cost in the summary (observability):** the summary JSON gains a compact `"topCost"` list (route +
   cost + components) for the top-K entries, so a campaign can *show* what was expensive (supports the
   benchmarking/comparison goal) and so the e2e/dashboard can surface it.

### Unchanged

`LoadRun`, the operator, the corpus ConfigMap format, and exploration's uniform parent selection.
The only exploration-behavior change is retention (a bounded set of expensive inputs now also enters
the mutate pool — a mild, intentional "explore around expensive states" effect, not weighted selection).

## Config (all `-D`, sensible defaults)

| Property | Default | Meaning |
|---|---|---|
| `basquin.cost.enabled` | `true` | master switch; `false` → legacy insertion-order replay, no cost headers read (A/B baseline) |
| `basquin.cost.latencyWeight` | `1.0` | cost coefficient per ms |
| `basquin.cost.heapWeight` | `0.0625` | per KB (≈ per 16 KB = 1) |
| `basquin.cost.threadWeight` | `500` | per leaked thread |
| `basquin.cost.invariantWeight` | `1000` | per invariant violation |
| `basquin.cost.retainFactor` | `3.0` | retain a non-coverage input if `cost > factor × meanCost` |
| `basquin.corpus.max` | `1000` | corpus size cap (evict cheapest cost-find) |

`basquin.cost.enabled=false` gives a clean baseline for measuring the feature's effect.

## Testing

- **Unit — `CostModel`:** score monotonic in each component; coefficients honored; a heap-growing input
  outscores a fast clean one; negative/zero deltas floor to 0 contribution.
- **Unit — retention/eviction/ranking** (extract the pool logic so it's testable without HTTP — a small
  `PheromoneCorpus`/`CostCorpus` helper, or package-private methods on `CoverageGuidedRun`): retain on
  coverage OR cost>threshold; never evict a coverage-find; over-cap evicts the cheapest cost-find;
  replay ordering is cost-desc; byte-budget truncation keeps the top costs.
- **Unit — `RequestBoundary`:** `EXPLORE_BEGAN` exit emits an `X-Basquin-Cost` header of the right
  shape; `LOAD_PASSTHROUGH`/`CONTROL_HANDLED` emit none (extend the existing `RequestBoundaryTest`).
- **Integration — e2e:** assert (a) the target serves the `X-Basquin-Cost` header in-cluster (proof the
  cost channel is live via the DD-030 boundary), and (b) the driver reports a cost-ranked replay
  (a log line / summary field showing a non-empty top-cost with the corpus ordered by cost).

## Non-goals / deferred (the roadmap the pheromone framing points to)

- **Cost-biased exploration selection + credit assignment** — the "true pheromone" (a child's cost
  reinforces its parent's selection weight, with evaporation). Requires lineage tracking; only pays off
  once *selection* is biased. This phase intentionally stops at replay.
- **Weighted replay inside `LoadRun`** — fire expensive routes proportionally more. Needs per-entry
  weights in the ConfigMap (a format change). Cost-ordered top-N already concentrates load on the
  expensive set; weighting within it is a follow-up.
- **Per-edge coverage attribution** — impossible today (JaCoCo global-monotonic total); unrelated but
  would enable combined coverage+cost fitness.

## Global constraints (carried into the plan)

- Exploration's parent **selection stays uniform** — this phase changes retention + replay ordering, not
  selection weighting.
- The `X-Basquin-Cost` header is **best-effort** and written only on the `EXPLORE_BEGAN` boundary path;
  it must never fail a request, and the driver must tolerate its absence (latency-only cost).
- The replay **ConfigMap format is unchanged** (plain route lines) — no operator/`LoadRun` change.
- `basquin.cost.enabled=false` fully restores today's behavior (insertion-order replay, no cost header
  read) — the A/B baseline.
- Coverage-find corpus entries are **never evicted** — coverage diversity must not regress.
- Works on both the valve (bench) and the agent boundary (operator) since the header lives in the
  shared `RequestBoundary`.
