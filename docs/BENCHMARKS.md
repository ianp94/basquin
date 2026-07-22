# Benchmarks (v0.3.0)

How Basquin's load engine compares to purpose-built load tools, what its replay corpus does to a
real app, and how it captures server-side heap drift. All runs are **in-cluster** (kind), against an
**unmodified** [JPetStore-6](https://github.com/mybatis/jpetstore-6) pod instrumented by the
operator — the same path a user drives.

> **Read these as indicative, not absolute.** A single JPetStore pod is the shared bottleneck, so
> the load generators *converge* on the target's capacity rather than showing their own ceilings.
> Numbers are specific to this config (Tomcat 9 · HSQLDB · JaCoCo + JVMTI + agent boundary all
> loaded · `-Xmx2g` · kind on a developer box). The point is the *comparison* and the *findings*,
> not the absolute rps.

## Setup

| | |
|---|---|
| Target | Unmodified JPetStore-6 on Tomcat 9, operator-instrumented (`-Dbasquin.boundary=agent` + JaCoCo), `-Xmx2g` |
| Cluster | kind (single node), all traffic over the in-cluster `jpetstore-app` Service |
| Workload | 16 GET-safe catalog routes (viewCategory/viewProduct/viewItem/search), closed-loop |
| Concurrency | 50 (k6 VUs / Locust users / Basquin worker threads) |
| Duration | 2 min, 30s warmup |
| Control | **Fresh target restart before each run**, target explicitly in **load mode** (lock-free boundary) for all three — so the comparison measures the *load generator*, not boundary state |

Each tool ran as its own in-cluster pod/Job (k6 via the k6-operator `TestRun`; Locust as a headless
pod; Basquin as a `BasquinCampaign` load run).

## Load-generator comparison

| Tool | Throughput | p50 | p90 | p99 | max | Errors |
|---|--:|--:|--:|--:|--:|--:|
| **k6** (Go, 1 process) | **10,856 rps** | 2.2 ms | 6.4 ms | 19 ms | 5.2 s | 0% |
| **Locust** (8 processes) | **9,600 rps** | 4 ms | 8 ms | 14 ms | 760 ms | 0% |
| **Basquin** (JVM, 50 threads) | **6,848 rps** | **1 ms** | 9 ms | 34 ms | 8.6 s | 0% |
| _Locust (1 process)_ | _118 rps_ | _37 ms_ | _—_ | _1,400 ms_ | _21 s_ | _0.7%_ |

**All three real contenders converge into the same 7k–11k rps band** — they're bottlenecked by the
*target*, not the generator. That convergence is the result you want: **Basquin's load engine is
legitimately competitive with dedicated tooling** (63% of k6, 71% of distributed Locust), with the
**best median latency** of the three. It isn't leaving throughput on the table, nor manufacturing it.

The single-process Locust row (118 rps) is a fair-comparison footnote: one Locust process is
GIL-bound and can't parallelize 50 users — which is exactly why Locust ships a distributed
master/worker model (the 8-process row). k6 (Go) and Basquin (JVM threads) drive far more load per
instance.

### What Basquin adds that k6 and Locust structurally can't

During that same 6,848 rps run, Basquin also captured, from *inside* the target JVM:

- **heap drift** over the run (server-side `Runtime` deltas via the agent boundary),
- **thread/executor drift**,
- **availability invariant** checks (latency / heap-growth / leak),

with **0 server errors**. k6 and Locust see only client-observed latency and status codes — they
have no view of what the request did to the JVM. Same throughput class, plus the availability oracle.

## Finding: replay must be method-aware (motivated DD-035)

The corpus a fuzz campaign emits is only useful for load if it replays *faithfully*. Replaying the
**auto-emitted** corpus (which included JPetStore's cart/order **form** routes) as plain GETs —
what pre-0.3.0 did — produced:

| Corpus | Throughput | p99 | max | 5xx |
|---|--:|--:|--:|--:|
| Clean GET-safe catalog | 6,848 rps (sustained) | 34 ms | 8.6 s | **0%** |
| Auto-emitted, replayed as GET (old behavior) | 521 → **235 rps** (decaying) | 5,003 ms | 30,001 ms | **14.2%** |

The form handlers throw Stripes `SourcePageNotFoundException` when replayed as sessionless GETs →
~14% 5xx and a tail pinned at the 30 s read-timeout; workers stall in those timeouts and throughput
*decays* over the run (521 → 235 rps). This is a **corpus/replay artifact, not app degradation** —
and it's exactly what **DD-035** fixes: method-, session-, and sequence-aware replay (POST with a
body and a `JSESSIONID`, whole ordered sequences), so the emitted corpus replays as real user
transactions.

## Finding: the heap-fatigue curve needs a right-sized target (not a dedicated port)

The load run reports the target's **heap drift** by polling `/__basquin/drift`. On an undersized
target (`-Xmx512m`) under c=50, the instrumented JVM GC-thrashed (40 rps, p50 362 ms) and the drift
poll **timed out** — so `pollDrift` returned null and the report showed a silent `heapDriftKb:0`,
indistinguishable from a genuinely flat heap.

Raising the target to `-Xmx2g` resolved it completely:

| Target heap | Throughput | p50 | p99 | drift endpoint under load | heap drift |
|---|--:|--:|--:|--:|--:|
| 512 MB | 40 rps | 362 ms | 5,003 ms | HTTP 000 (3 s timeout) | 0 (starved) |
| 2 GB | 17,640 rps¹ | 1 ms | 19 ms | **HTTP 200 in ~2 ms** | **~296 MB captured** |

¹ transient early-window figure; sustained over 2 min it settles to the 6,848 rps above as the heap fills.

So the drift channel does **not** need its own port — the agent boundary answers in ~2 ms under
load when the target isn't thrashing. The root cause was an undersized heap, not the boundary. DD-035
also makes a genuinely failed poll **loud**: it now emits `driftUnavailable:true` instead of a
fabricated `0`, so "flat heap" and "couldn't measure" are never confused again.

## Reproducing

The controlled harness (restart target → set load mode → run → collect) drives all three against
the identical 16-route mix at c=50 for 2 min:

- **k6** — [`deploy/bench/k6/jpetstore.js`](../deploy/bench/k6/jpetstore.js) (VUS/DURATION/BASE
  env-parameterised), run via the k6-operator `TestRun`.
- **Locust** — [`deploy/bench/locust/locustfile.py`](../deploy/bench/locust/locustfile.py), headless
  with `--processes -1` (a single Locust process is GIL-bound to ~120 rps — the 8-process row).
- **Basquin** — the canonical [`deploy/e2e/e2e.sh`](../deploy/e2e/e2e.sh) load campaign, replaying a
  clean GET-safe catalog corpus.

All three hit the target in **load mode** (lock-free boundary) so the comparison measures the load
generator, not boundary state.
