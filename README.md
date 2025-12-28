# ClosureJVM

A persistent execution harness for JVM web applications that uses coverage-guided exploration to discover availability, performance, and correctness failures — not just crashes.

## Quick Start

Prereqs: Java 17+, Gradle 8.x (or use the Gradle wrapper if present).

- Build: `gradle build`
- Run runner (simple): `java -jar build/libs/closurejvm-0.1.0.jar`
- Run example (proper): `gradle runExampleProper`
- Run runner demo (proper): `gradle runRunnerProper`

Leak demo (will report thread leaks and may not exit without a timeout):
- `gradle runRunnerLeak` (prints leak details, process may be held by non-daemon threads)

Javalin demo (optional; downloads Javalin only for this run):
- `gradle runRunnerJavalinLeak` (starts Javalin, hits /leak once, expects leak and non-zero exit)

Generic runner (plug in any target):
- Interface: implement `runner.api.IterationTarget` with `initialize/executeIteration/close`.
- Run with class name: `gradle runGenericProper` or `gradle runGenericLeak`.
- Direct usage: `java -cp build/libs/closurejvm-0.1.0.jar runner.GenericRunner 10 your.package.YourTarget`.

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
  - Run: `java -cp build/libs/closurejvm-0.1.0.jar:<your-app-cp> runner.GenericRunner 100 your.pkg.YourTarget`
- Via wrapper runner using a system property:
  - `java -Dclosurejvm.target=your.pkg.YourTarget -cp build/libs/closurejvm-0.1.0.jar:<your-app-cp> runner.Runner 100`

Notes
- Iteration metrics print per iteration (latency, heap delta, thread count).
- Leak oracles fail fast; add `-Dclosurejvm.forceExitOnLeak=true` to force a non-zero exit when leaks are found.
- Gradle tests capture stdout by default; run the app directly (above) to see live output.
