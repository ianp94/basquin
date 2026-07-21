# DD-032 — Cost-biased selection with credit assignment ("true pheromone") — Design

**Status:** accepted (2026-07-21), pending implementation. Extends DD-031 (cost-ranked replay,
`CostCorpus`/`CorpusEntry`/`CostModel`) and the coverage-guided loop.

## Context — DD-031 steered load; this steers exploration (carefully)

DD-031 measured per-input cost and cost-ranked the *replay* corpus, but left exploration parent
selection **uniform** — deliberately, because cost-biasing selection can trade away coverage (fixate
on an expensive region, stop finding new code, which is exploration's job). This DD adds the deferred
piece — a real pheromone loop that biases *selection* toward parents that spawn expensive children —
**opt-in and instrumented**, because its value is genuinely uncertain and must be proven, not assumed.

## Decision — ε-greedy weighted selection + immediate-parent credit + evaporation

Enabled only under `-Dbasquin.pheromone=on` (default **off** = today's exact behavior). When on:

1. **ε-greedy selection.** With probability ε draw a parent **uniformly** (pure exploration);
   otherwise **roulette by pheromone**. `ε` is literally the kill-switch: `ε=1.0` ≡ uniform. **ε is the
   coverage guardrail** (see below).
2. **Immediate-parent credit assignment.** After a mutated child is fired and its cost computed,
   deposit that cost onto the *one* parent it was mutated from: `parent.pheromone += deposit`. A parent
   that keeps spawning expensive children becomes attractive to mutate.
3. **Evaporation.** Every `evaporateEvery` iterations, `pheromone *= decay` for all entries — stale
   spikes fade, consistently-productive parents persist.

Multi-hop lineage propagation (the full ant trail) is deferred; immediate-parent is the correct v1.

### What actually protects coverage — ε, not BASE (review point 1)

Real costs run in the hundreds-to-thousands (one invariant hit = 1000, a leaked thread = 500). In the
roulette branch, a cheap coverage-find cannot compete with entries at 800+ — it is *selectable* but not
*competitive*. **The ε-uniform share is the coverage guardrail; the initial pheromone is a tie-breaker
among cheap entries, not a coverage protector.** The spec is explicit about this so nobody mistakes a
constant floor for coverage safety.

**Initial pheromone is tied to the EMA cost baseline, not a constant.** A new entry starts at
`pheromone = emaCost + cost` (the EMA `CostCorpus` already maintains from DD-031). A constant `1.0`
would be meaningless for an app whose costs are ~10 and swamped for an app whose costs are ~5000; the
EMA makes the starting attractiveness scale-relative and cross-app meaningful. Cold start: if the EMA
isn't warm yet (or total weight is 0), `selectParent` falls back to uniform.

### Sticky-spike prevention — the evaporation math (review point 2)

Deposits are raw child costs, so without care a single 5000-cost spike (one invariant hit) with
`decay=0.9` every 50 iters needs ~74 cycles ≈ **3,700 iterations** to fade near baseline — longer than
most bench runs, so one early spike would own the roulette for the whole run (indistinguishable from
"pheromone got stuck"). Two defaults fix this:

- **`decay=0.7`** (not 0.9): a capped 10×EMA spike fades to ~1×EMA in `0.7^k ≈ 0.1 → k≈6.5` cycles ≈
  **~325 iterations** — recoverable within a run.
- **Deposit cap** `deposit = min(childCost, depositCap × emaCost)` (`depositCap` default 10): a single
  invariant hit can't inject a 5000 spike into a run whose typical cost is ~50. Bounds the tallest
  spike to a known multiple of "typical."

The defaults are what the A/B runs, so they must bias toward *recoverable* pheromone, not sticky spikes.

### Eviction becomes pheromone-aware (review point 3)

DD-031's `evictIfOverCap` evicts the cheapest-by-own-cost non-coverage entry. Under DD-032 that fights
reinforcement: an entry with cheap own-cost but high pheromone is a *proven productive parent* — the
thing to keep. So **when pheromone is on, evict the min-`pheromone` non-coverage entry**; when off,
keep DD-031's cost-based eviction unchanged. Coverage-finds are still never evicted. Reinforcement runs
**before** the child's `consider` (which may evict), so a freshly-reinforced parent is protected from
eviction by its own child.

### The `cost.enabled=false` + `pheromone=on` collision (review point 4)

Reinforcement needs a measured cost. **`pheromone=on` forces cost measurement on**, overriding
`basquin.cost.enabled=false`, and **logs that it did** (`[Basquin] pheromone=on forces cost
measurement on`). No silent partial state — a half-configured run would poison the A/B.

### A/B rigor — seeds and repeats (review point 5)

Coverage-guided runs are stochastic; a single on-vs-off comparison misleads. So:

- The RNG seed becomes **`-Dbasquin.seed`** (default 1; today it is hardcoded `new Random(1)`), and is
  **logged in the end-of-run line**.
- The end-of-run line reports `pheromone=on|off seed=S coverage=X/Y` + top-cost, so the two arms are
  directly comparable.
- The documented **protocol**: n≥3 runs per arm, varied seeds (e.g. 1/2/3), identical duration/target;
  compare the *distributions* of coverage% and expensive-states-found, not single points.

## Architecture & components

### `runner/coverage/CorpusEntry.java` — one mutable field, structurally guarded (note)

Add `double pheromone` as a **package-private, non-final** field, mutated ONLY by `CostCorpus`'s
synchronized methods (same package). Update the class doc: it is no longer "Immutable" — the cost
fields are final; `pheromone` is monitor-guarded state owned by `CostCorpus`.

### `runner/coverage/CostCorpus.java` — selection/credit/evaporation

- `CorpusEntry selectParent(Random rnd)` — ε-greedy: ε-uniform else roulette by pheromone; returns the
  **entry** (so the loop can reinforce it). Off / cold-start / zero-total → uniform. Synchronized.
- `void reinforce(CorpusEntry parent, double childCost)` — `parent.pheromone += min(childCost,
  depositCap × emaCost)`, and the deposit is also added to the running `totalPheromone`. **Correctness
  depends on call ordering, not a `contains` check:** `reinforce` MUST run before the child's `consider`
  (the only evictor), so the parent is still a live entry and `totalPheromone` stays equal to the sum of
  live pheromone. Reinforcing an *already-evicted* parent is **not** harmless — it would drift
  `totalPheromone` above the live sum and silently corrupt subsequent roulette draws; the ordering is
  what prevents that (do **not** add a `contains` check as a substitute, and do **not** reorder
  `reinforce` after `consider`). Synchronized.
- `void evaporate()` — `pheromone *= decay` for all entries; also scales the running total. Synchronized.
- Retention sets a new entry's `pheromone = emaCost + cost`.
- `evictIfOverCap` evicts min-`pheromone` (pheromone on) or min-`cost` (off) non-coverage entry.
- The DD-031 API (`randomParentInput`, `consider`, `snapshotByCost`, `size`) is unchanged; `selectParent`
  is additive. `randomParentInput` may be reimplemented as `selectParent(...).input` for the off path.

### `runner/coverage/CoverageGuidedRun.java` — loop wiring

- Seed: `new Random(Long.getLong("basquin.seed", 1L))`.
- Selection: `CorpusEntry parent = corpus.selectParent(rnd); input = grammar.mutate(parent.input);`
  (and the no-grammar `mutate(parent.input, rnd)`). Track `parent` (nullable — only the mutate branch
  sets it; fresh/seed branches leave it null).
- After cost is computed: `if (pheromoneOn && parent != null && measured) corpus.reinforce(parent, cost);`
  **before** the `consider` call.
- Evaporation: `if (pheromoneOn && i % evaporateEvery == 0) corpus.evaporate();`
- `pheromoneOn` forces `costEnabled=true` (with the logged notice).
- End-of-run line extended with `pheromone=`, `seed=`.

### Scope — bench-first; operator/CRD deferred

v1 is driver-only, enabled via `-Dbasquin.pheromone=on`, A/B-measured in the docker-compose bench
path. **No operator/CRD surface** — a CRD field is a compat commitment awkward to walk back if the A/B
says "kill it," and a `buildAgentArgs`-style change re-injects every target on upgrade (DD-030). The
driver-flag passthrough costs the same to add later, *after* the numbers earn it. The e2e/CI is a
~15-minute smoke test that can only confirm plumbing, never that pheromone beats uniform — so it is not
where this feature is validated.

## Performance (honoring the low-latency practice, with analysis)

The pheromone ops run in the **driver's exploration loop**, which is **serialized and HTTP-round-trip-
bound** (one request at a time, milliseconds each). Against that, in-memory work over a corpus of
≤`corpus.max` (1000) entries is microseconds — dominated by the request. So the honest high-performance
decision is **not** to pre-build an O(log n) structure, but to keep the per-iteration path lean:

- **Running total weight** maintained incrementally (O(1) on add/reinforce/evict) so `selectParent`
  never re-sums the corpus — a single O(n) cumulative scan to locate the drawn entry, no recomputation.
- **Evaporation is O(n) but only every `evaporateEvery` (50) iterations**, and scales the running total
  in the same pass — negligible amortized.
- **No per-iteration heap allocation** in the selection path (reuse the entries list; primitive
  accumulators).
- Documented upgrade path *if a future non-serialized/high-throughput selection ever needs it*: a
  Fenwick/BIT over pheromones (O(log n) draw + point update) with **lazy-scale evaporation** (represent
  deposits relative to a growing global scale so evaporation is O(1), with periodic renormalization to
  avoid overflow). Not built now — profiling of the serialized loop would not justify it.

The genuinely latency-critical component — the target-side `RequestBoundary` (every app request) — is
untouched here; DD-031's `X-Basquin-Cost` path stays a single string-concat + map put, no new work.

## Testing

- **`selectParent`:** ε=0 + one high-pheromone entry → it dominates a large weighted-draw sample; ε=1 →
  uniform (roughly flat distribution); pheromone off → uniform; zero-total/cold-start → uniform, no
  crash.
- **`reinforce`:** parent pheromone rises by the (capped) child cost; a deposit above `depositCap×EMA`
  is clamped; a negative test pins that reinforcing an *evicted* parent drifts the running total
  (corrupting selection) — proving why reinforce-before-consider ordering is load-bearing, not optional.
- **`evaporate`:** pheromone × decay; running total stays consistent with the sum after evaporation;
  the sticky-spike scenario (one capped spike) returns near baseline within the computed ~cycle count.
- **eviction:** with pheromone on, the min-*pheromone* (not min-cost) non-coverage entry is evicted; a
  cheap-cost/high-pheromone entry survives; coverage-finds never evicted.
- **kill-switch:** `pheromone=off` → `selectParent`≡uniform, no pheromone mutation, end-to-end behavior
  == DD-031/today; `pheromone=on` with `cost.enabled=false` forces cost on (asserted via the log/notice).
- **loop-level:** the parent is reinforced before `consider`; the end-of-run line carries
  `pheromone=`/`seed=`.

## Non-goals / deferred

- **Multi-hop lineage propagation** (reinforce grandparent… with per-hop decay) — needs parent links;
  immediate-parent first.
- **Operator/CRD enablement** — a driver-flag passthrough, gated on a winning bench A/B.
- **Fenwick/lazy-scale selection** — unjustified for the serialized HTTP-bound loop; documented upgrade.

## Global constraints (carried into the plan)

- **Default off.** `-Dbasquin.pheromone=off` (the default) is byte-for-byte today's behavior: uniform
  selection, no reinforce/evaporate, DD-031 cost-based eviction, `emaCost` untouched by pheromone.
- **ε is the coverage guardrail** — state it; initial pheromone (`emaCost + cost`) is a tie-breaker.
- **Sticky-spike defaults:** `decay=0.7`, `depositCap=10×EMA` — chosen so a capped spike recovers
  within a run; these are the A/B defaults.
- **Pheromone-aware eviction** when on (min-pheromone), DD-031 eviction when off; coverage-finds never
  evicted; reinforce runs before `consider`.
- **`pheromone=on` forces cost measurement on** and logs it — no silent partial state.
- **`-Dbasquin.seed`** (default 1) drives the RNG and is logged for A/B reproducibility.
- **`pheromone` is package-private, mutated only via `CostCorpus`'s synchronized methods.**
- **Low-latency:** per-iteration selection path allocation-free with an incrementally-maintained running
  total; evaporation O(n) only every N iters; the target boundary untouched.
