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
