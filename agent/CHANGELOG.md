# Changelog — Java agent

In-JVM measurement layer: iteration boundaries, availability invariants, leak detection, reset.
Release-level changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## [0.2.0] — 2026-07-21

First published release (ships inside `ghcr.io/ianp94/basquin-agents`).

### Added
- Iteration boundary API: `beginIteration()` / `endIteration()`, plus the explicit
  `IterationContext` API (`ctx = Agent.begin(); Agent.end(ctx)`) with per-context latency, heap,
  thread-set, and sampler baselines (DD-010). Legacy calls remain as ThreadLocal-backed wrappers.
- Availability invariants — latency, heap delta, thread delta — configurable via
  `-Dbasquin.invariant.*`, each with **hard-fail** or **soft-signal** mode (global and
  per-invariant), checked at iteration end with stack evidence on violation.
- Thread leak detection (new non-daemon threads after an iteration) and best-effort
  executor/timer leak tracking (weak refs, `ScheduledThreadPoolExecutor` details).
- Event-driven thread accounting via the native JVMTI agent when loaded
  (`ThreadStart`/`ThreadEnd`, leak set from weak global refs — no enumeration on the hot path),
  with a `ThreadMXBean` fallback.
- Metrics snapshots at iteration boundaries; latency measured **before** the end-of-iteration
  grace sleep; opt-in `-Dbasquin.heap.gcBeforeMeasure` so heap delta measures retention.
- Hard-reset fallback via child-first ClassLoader swap (`-Dbasquin.reset=classloader`,
  `.onFailure`, `.maxResets`).
- `-Dbasquin.forceExitOnLeak=true` for demos/CI where leaked non-daemon threads would hang the JVM.

### Notes
- Heap/thread deltas are process-global: only trustworthy under serialized or single-flight
  iterations (which is why the valve/filter serialize requests by design).
