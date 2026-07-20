# ClosureJVM

A persistent execution harness for JVM web applications that uses coverage-guided exploration to discover availability, performance, and correctness failures — not just crashes.

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
- `-Dclosurejvm.heap.gcBeforeMeasure=true` — run `System.gc()` before heap baseline and end-of-iteration measurement so heap delta reflects retention rather than allocation noise (slower; off by default).

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

## Real App Demo (Tomcat) — Roadmap

Purpose
- Provide a convincing, realistic demo where ClosureJVM finds both correctness crashes and availability violations (latency/heap) in one small app.
- Use this as the “buy‑in” slice to show that input‑dependent availability bugs can be found quickly and deterministically.

Plan (phased)
- Phase 1 — Embedded Tomcat (in‑JVM):
  - Start embedded Tomcat inside the harness for high‑signal metrics (threads/heap/latency).
  - Routes:
    - `/crash?type=…` → throws exceptions (HTTP 500). We rethrow client‑side so these are “crashes”.
    - `/latency?ms=…` → sleeps to trigger latency invariants; optional sampled stacks at threshold.
    - `/heap?kb=…` → allocates memory to trigger heap delta invariants.
  - Fuzz target maps `byte[]` to one HTTP request/iteration; corpus seeds for each route.
  - Results: per‑target outputs with both Crash and Invariant artifacts (stacks included).
- Phase 2 — WAR + External Tomcat (Docker):
  - Package the same routes as a WAR and run in Tomcat via docker‑compose alongside MySQL.
  - Inject the agent into Tomcat (`CATALINA_OPTS=-javaagent:...`) so invariants come from the server JVM.
  - Add a `/db` route to exercise DB latency/pool behavior and surface availability issues.
- Phase 3 — Enrichment:
  - Pool/queue sampling (servlet thread pools), preset invariants, triage bundles, and minimization workflow.

What this demonstrates
- Crashes and availability violations in the same app and run, saved with stacks and metadata.
- Deterministic replay of saved inputs with corpus runner; easy minimization to shrink repros.
- Path to real deployment compatibility (WAR + Docker) without losing the core “iteration cleanliness” signal.

Status: Phase 1 (embedded) is implemented; Phase 2 (WAR + Docker) baseline is available. The WAR includes a status page and headers for invariant triage, and a /db route for DB‑latency demos. Kept out of CI by default. See `agents.md` and `TODO.md`.

Embedded Tomcat (Phase 1) commands (opt‑in)
- Fuzz (coverage‑guided, seeds included): `./gradlew runFuzzTomcatJQF -DenableJQF=true`
- Replay seeds deterministically: `./gradlew runTomcatCorpus`
- Results: `fuzz-results/tomcat/` (both Crash and Invariant artifacts with stacks)
Note: These tasks run an embedded HTTP server and issue localhost requests; run locally (not CI) if your environment restricts network sockets.

WAR + Docker (Phase 2) — outline
- Build WAR: `./gradlew :tomcat-war:build`
- Build agent fat JAR: `./gradlew jar`
- Run: `docker compose up tomcat` (Compose v2; starts Tomcat, mounts the WAR to ROOT.war, injects the agent and adds agent classes to bootclasspath)
- Optional DB: `MYSQL_HOST_PORT=3307 docker compose --profile db up` (starts MySQL on an alternate host port if 3306 is busy)
- Hit endpoints:
  - `http://localhost:8080/crash?type=NPE`
  - `http://localhost:8080/latency?ms=250`
  - `http://localhost:8080/heap?kb=512`
  - `http://localhost:8080/db?sleepMs=500`
- Fuzz against Docker Tomcat:
  - Start docker-compose first.
  - `./gradlew runFuzzTomcatDockerJQF -DenableJQF=true -Dexamples.tomcat.baseUrl=http://localhost:${TOMCAT_HOST_PORT:-8080}`
  - Seeds from `examples/corpus/tomcat`; results in `fuzz-results/tomcat-docker`.
  - The WAR includes an IterationFilter that wraps each request with iteration boundaries and exposes invariant info via `X-ClosureJVM-Invariant-*` headers, so the external fuzzer can save non-crashing invariant inputs.
  - Status UI: `http://localhost:8080/closurejvm/index.html` shows requests, crashes, invariant events, recent violations, and a short stack snippet captured by the agent.
  - Status JSON: `GET /closurejvm/status` returns `{requests, crashes, invariants, recent[], stack}`.
  - Server-side invariants (enabled by default via compose environment):
    - `INVARIANT_MODE=soft`
    - `INVARIANT_LATENCY_MS=50` and `INVARIANT_LATENCY_SAMPLE=true`
    - `INVARIANT_HEAP_KB=128`
    - Override by exporting env vars before `docker-compose up`, or append extra with `CATALINA_OPTS_EXTRA`.
- Notes:
  - The agent runs inside the Tomcat JVM to capture threads/heap/latency on the server side.
  - MySQL is included for future `/db` route demos; not required for Phase 2 minimal demo.
  - Keep this out of CI; it’s for local demos.
