# Bench A/B protocol — pheromone selection (DD-032)

Design: [DD-032 in DESIGN-DECISIONS.md](DESIGN-DECISIONS.md#dd-032-cost-biased-parent-selection-with-credit-assignment--opt-in-true-pheromone-2026-07-21) ·
spec: [`docs/superpowers/specs/2026-07-21-pheromone-selection-design.md`](superpowers/specs/2026-07-21-pheromone-selection-design.md).

`-Dbasquin.pheromone=on` (ε-greedy cost-biased parent selection + immediate-parent credit assignment +
evaporation) is opt-in and **default off**. It ships without operator/CRD support and without a CI
gate: the in-cluster e2e only smoke-tests the default-**off** path (does the plumbing still work?), it
never runs long enough or repeats enough seeds to say whether pheromone selection actually finds more
coverage or more expensive states than uniform selection. That question — "prove or kill" — is answered
here, against a bench target, not in CI. **Operator/CRD enablement is intentionally deferred until this
protocol's result favors pheromone.**

## What this protocol measures

Coverage-guided exploration against the same target, same duration, same seed, with pheromone `off`
then `on`, repeated across 3 seeds — comparing the *distributions* of coverage% and top discovered
costs across the two arms. Not a single before/after run: exploration is stochastic, so one A/B pair
proves nothing.

## Setup

Any bench target from [`deploy/bench/README.md`](../deploy/bench/README.md) works; JPetStore is the
simplest (in-memory HSQLDB, no external DB dependency to keep identical across runs). Stand it up with
coverage enabled — see [`docker-compose.coverage.yml`](../docker-compose.coverage.yml) or
[USAGE.md § Coverage-guided exploration](USAGE.md#coverage-guided-exploration-v010) for how to wire the
JaCoCo tcpserver port and extracted `.class` files. Build once:

```bash
./gradlew stageAgents jar
```

## Procedure

For each seed in `1 2 3`, run the **same** target instance through both arms back to back (restart /
reset the target between arms if it retains state that would bias one arm), with everything held
identical except `-Dbasquin.pheromone`:

```bash
for seed in 1 2 3; do
  for pheromone in off on; do
    ./gradlew runCoverageGuided \
      -Dexamples.http.baseUrl=http://localhost:8092 \
      -Dbasquin.coverage.jacoco=localhost:6300 \
      -Dbasquin.coverage.classes=/path/to/WEB-INF/classes \
      -Dbasquin.grammar=examples/grammar/jpetstore.grammar \
      -Dbasquin.run.duration=10m \
      -Dbasquin.pheromone=$pheromone \
      -Dbasquin.seed=$seed \
      2>&1 | tee "bench-pheromone-${pheromone}-seed${seed}.log"
  done
done
```

`-Dbasquin.run.duration` (identical across every run) is the control — never compare an `iterations`-
bounded run against a `duration`-bounded one, or a longer run against a shorter one. `-Dbasquin.seed`
seeds the RNG identically for both arms of a given seed number, so within a seed pair the only
deliberate difference is pheromone on/off (the two arms will still diverge in requests fired once
pheromone starts biasing selection — that divergence is the thing under test).

## Reading a run

Grep each log for two lines:

```bash
grep -E 'CoverageGuidedRun done:|replay cost-ranked' bench-pheromone-*-seed*.log
```

- **`CoverageGuidedRun done: corpus=<n> coverage=X/Y pheromone=on|off seed=S`** — the headline number.
  Confirm `pheromone=` and `seed=` match what you asked for before trusting the row (a typo'd `-D` flag
  fails silently to today's default).
- **`[Basquin] replay cost-ranked (top N): route=cost, route=cost, ...`** — the discovered corpus's most
  expensive entries, in order. Compare which routes appear and how concentrated the top costs are
  across the `on` runs vs. the `off` runs.

## Comparing arms — distributions, not points (n≥3 per arm)

Build two small tables (coverage% and top-cost lists) across the 3 seeds, one column per arm:

| seed | coverage off | coverage on | top cost off | top cost on |
|------|-------------|-------------|---------------|--------------|
| 1    | …           | …           | …             | …            |
| 2    | …           | …           | …             | …            |
| 3    | …           | …           | …             | …            |

Look at the **spread**, not any single seed: does `on` land consistently above `off` on coverage% (or
at least not consistently below it), and does `on` consistently surface higher/more concentrated top
costs than `off`? A single seed's win either direction is noise; the verdict is in whether the pattern
holds across all three.

## Two caveats — read before interpreting the numbers

These are recorded verbatim in DD-032 because a naive reading of the A/B will misattribute the effect
size or mistake the intended behavior for a bug:

1. **The `off` arm is not a pure-uniform strawman.** The loop already spends ~30% of iterations on
   fresh/random expansion before ε-selection even applies, so the `on` arm's roulette only actually
   governs ≈ `0.7 × (1 − ε)` of iterations — with a typical ε, ≈49%, not 100%. The `off` arm is the
   *same* loop structure with the roulette branch removed, not a differently-shaped baseline. Don't
   attribute the whole run's difference to "pheromone vs. uniform" — attribute it to that ~49% share.
2. **Winner-take-all inside the roulette is intended, not a bug.** A parent that consistently spawns
   expensive children has its pheromone converge toward `deposit-per-cycle / (1 − decay)` — tens of EMA
   multiples with the shipped defaults (`decay=0.7`, deposit cap `10×EMA`) — bounded only by ε's uniform
   share. If the `on` arm's cost-ranked top is dominated by one or two routes, that is the design
   working as intended, not a stuck loop. Don't discard an `on` run for looking "too concentrated."

## What happens with the result

- **Favors pheromone** (consistently higher coverage or consistently more/pricier expensive-state finds
  across all 3 seeds, without a coverage regression): the next step is a driver-flag passthrough on the
  operator's `BasquinCampaign.spec.driver` (no CRD schema change needed beyond a new optional field) —
  tracked separately, not part of this protocol.
- **Doesn't favor pheromone, or is a wash:** ship stays default-off; the feature remains available via
  `-Dbasquin.pheromone=on` for further bench iteration (tuning ε / decay / deposit cap) rather than
  being removed outright, since the kill-switch already makes it free to leave in place.
