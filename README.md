# ClosureJVM

A persistent execution harness for JVM web applications that uses coverage-guided exploration to discover availability, performance, and correctness failures — not just crashes.

<!-- Replace OWNER/REPO with your GitHub repo to enable the badge -->
[![CI](https://github.com/ianp94/closureJVM/actions/workflows/ci.yml/badge.svg)](https://github.com/ianp94/closureJVM/actions/workflows/ci.yml)

## Quick Start

Prereqs: Java 17+, Gradle 8.x (or use the Gradle wrapper if present).

- Build: `gradle build`
- Run runner (simple): `java -jar build/libs/closurejvm-0.2.0.jar`
- Run example (proper): `gradle runExampleProper`
- Run runner demo (proper): `gradle runRunnerProper`

Leak demo (will report thread leaks and may not exit without a timeout):
- `gradle runRunnerLeak` (prints leak details, process may be held by non-daemon threads)

Javalin demo (optional; downloads Javalin only for this run):
- `gradle runRunnerJavalinLeak` (starts Javalin, hits /leak once, expects leak and non-zero exit)

Generic runner (plug in any target):
- Interface: implement `runner.api.IterationTarget` with `initialize/executeIteration/close`.
- Run with class name: `gradle runGenericProper` or `gradle runGenericLeak`.
- Direct usage: `java -cp build/libs/closurejvm-0.2.0.jar runner.GenericRunner 10 your.package.YourTarget`.

Soak (local, proper mode):
- `gradle runSoakProper` (10,000 iterations, prints metrics; use for stability checks)

Invariant/Reset demos:
- `gradle runLatencyInvariantDemo` (sets a 1ms latency threshold to show invariant failure; non-zero exit expected)
- `gradle runResetClassloaderDemo` (fails once, resets via classloader, then succeeds on the next iteration)

Reset (how it works):
- GenericRunner can reload your target via a child-first `URLClassLoader` scoped to the target’s package.
- Parent loader retains the harness (`agent.*`, `runner.*`) and JDK classes; only target code is reloaded.
- On reset, it closes the target and child loader, creates a fresh loader, re-instantiates the target, and calls `initialize()`.
- Caveats: long-lived resources (threads, sockets, timers) created by target code must be shut down or they will outlive resets. Use the leak checks to enforce cleanliness.

## Flags

- `-Dclosurejvm.target=<FQCN>` — target class for `runner.Runner` forwarder.
- `-Dclosurejvm.forceExitOnLeak=true` — exit process immediately when a leak is detected (useful for demos/CI).
- `-Dexamples.mode=leak|proper` — behavior selector for examples.
- `-Dexamples.sleepMs=<n>` — simulate work duration in ms (examples).
- `-Dexamples.threads=<n>` — number of example worker threads.
- `-Dexamples.javalin.port=<n>` — port for Javalin demo server.

## Directory Structure

- `agent/` — iteration boundaries and checks (v0.1: thread leak detection)
- `runner/` — minimal iteration runner and demo hook
- `examples/` — example targets and test cases
- `docs/` — architecture and design docs
- `TODO.md` — milestone tasks
- `AGENTS.md` — maintainer guardrails and project rules

## Docs

- Architecture & rationale: `docs/ARCHITECTURE.md`
- Maintainer guide: `AGENTS.md`

## Use With Your App (Generic Runner)

You can run ClosureJVM against any code by implementing a tiny interface and pointing the generic runner at it.

- Implement `runner.api.IterationTarget` in your app:
  - `initialize()` runs once at start (e.g., boot a server/client).
  - `executeIteration()` runs for each iteration (do one request/unit of work).
  - `close()` runs once at the end (optional cleanup).

Minimal target skeleton:

```
package your.pkg;

import runner.api.IterationTarget;

public class YourTarget implements IterationTarget {
  @Override public void initialize() throws Exception { /* start app/server */ }
  @Override public void executeIteration() throws Exception { /* one request/work */ }
  @Override public void close() throws Exception { /* cleanup */ }
}
```

Run it (two options):
- Direct generic runner with explicit class:
  - Build: `./gradlew clean jar`
  - Run: `java -cp build/libs/closurejvm-0.2.0.jar:<your-app-cp> runner.GenericRunner 100 your.pkg.YourTarget`
- Via wrapper runner using a system property:
  - `java -Dclosurejvm.target=your.pkg.YourTarget -cp build/libs/closurejvm-0.2.0.jar:<your-app-cp> runner.Runner 100`

Notes
- Iteration metrics print per iteration (latency, heap delta, thread count).
- Leak oracles fail fast; add `-Dclosurejvm.forceExitOnLeak=true` to force a non-zero exit when leaks are found.
- Gradle tests capture stdout by default; run the app directly (above) to see live output.

## v0.2 Preview: Invariants & Reset

- Configurable invariants (disabled by default; enable via props):
  - `-Dclosurejvm.invariant.latency.maxMs=100`
  - `-Dclosurejvm.invariant.heapDelta.maxKb=256`
  - `-Dclosurejvm.invariant.threadDelta.max=2`
  - Mode: `-Dclosurejvm.invariant.mode=hard|soft` (per-invariant override: `...latency.mode`, etc.)
- Reset strategy (preview, optional): hard reset fallback (ClassLoader swap) for targets
  - Enable: `-Dclosurejvm.reset=classloader`
  - Reset on failure: `-Dclosurejvm.reset.onFailure=true`
  - Optional max resets: `-Dclosurejvm.reset.maxResets=3`
  - Works with `runner.GenericRunner` by reloading your `IterationTarget` in a child-first loader for its package.

Invariant evidence (stacks)
- Capture mode: `-Dclosurejvm.invariant.stack=current|all|off` (default `current`).
- Frame limit: `-Dclosurejvm.invariant.stack.maxFrames=15`.
- Latency stack sampling (captures execution stack at threshold):
  - Enable: `-Dclosurejvm.invariant.latency.sample=true` (uses `closurejvm.invariant.latency.maxMs` as trigger).

## v0.3 Preview: Exploration (JQF)

- JQF harness class: `examples.fuzz.JQFIterationHarness` (@RunWith(JQF), @Fuzz byte[] input)
- Targets can optionally implement `runner.api.InputReceiver` to accept fuzz inputs.
- Run (preview):
  - `./gradlew runFuzzJQF -DenableJQF=true -Dclosurejvm.target=your.pkg.YourTarget` (downloads JQF artifacts)
  - Note: This task is disabled by default and not part of CI; enable explicitly via `-DenableJQF=true`.
  - Ensure a compatible JQF version is available; the task sets the `jqf-instrument` -javaagent automatically if found.

Example fuzzable app:
- Target: `examples.targets.CalculatorFuzzTarget` (wraps `examples.fuzzapps.SimpleCalculator`).
- Run: `./gradlew runFuzzCalculatorJQF -DenableJQF=true`.
- Latency target: `examples.targets.LatencyFuzzTarget` (derives sleep from bytes/ASCII number).
  - Fuzz: `./gradlew runFuzzLatencyJQF -DenableJQF=true` (soft latency invariant; seeds in `examples/corpus/latency`).
  - Corpus: `./gradlew runLatencyCorpus`.
- Heap target: `examples.targets.HeapFuzzTarget` (allocates KB from input; capped by `-Dexamples.heap.maxKb`).
  - Fuzz: `./gradlew runFuzzHeapJQF -DenableJQF=true` (soft heap delta invariant; seeds in `examples/corpus/heap`).
  - Corpus: `./gradlew runHeapCorpus`.
- Saving inputs:
  - By default, interesting inputs (exceptions) are saved under `fuzz-results/` with a `.bin` file and a `.meta.txt` report.
  - Configure directory with `-Dclosurejvm.fuzz.resultsDir=path`.
  - JQF also saves coverage-interesting inputs under the guidance outdir: set `-Djqf.ei.DIRECTORY=path` (defaults to `fuzz-results/` in our Gradle tasks).
  - Per-target defaults: Calculator → `fuzz-results/calculator`, HTTP → `fuzz-results/http`, JSON → `fuzz-results/json`, Generic → `fuzz-results/<target-simple-name>`.

Seeding the fuzzer with a corpus:
- Provide seeds to JQF with `-Dclosurejvm.fuzz.seedsDir=path`.
- Our fuzz tasks also pre-seed deterministically by replaying seeds once: `-Dclosurejvm.fuzz.preseedDir=path` (auto-set to seedsDir in tasks).
- Example:
  - Calculator: `./gradlew runFuzzCalculatorJQF -DenableJQF=true` (uses `examples/corpus/calculator` as seeds)
  - HTTP: `./gradlew runFuzzHttpJQF -DenableJQF=true`
  - JSON: `./gradlew runFuzzJsonJQF -DenableJQF=true`

Corpus replay & minimization (no JQF required):
- Replay a corpus directory: `./gradlew runCorpusReplay -Dclosurejvm.target=examples.targets.CalculatorFuzzTarget -Dclosurejvm.corpusDir=corpus`
- Example calculator seeds: `./gradlew runCalculatorCorpus` (uses `examples/corpus/calculator`)
- Example HTTP seeds: `./gradlew runHttpCorpus` (uses `examples/corpus/http`)
- Example JSON seeds: `./gradlew runJsonCorpus` (uses `examples/corpus/json`)
- Minimize a crashing input:
  - `./gradlew minimizeInput -Dclosurejvm.target=<FQCN> -Dclosurejvm.min.input=fuzz-results/<target>/input-<ts>.bin -Dclosurejvm.min.output=fuzz-results/<target>/minimized.bin`
