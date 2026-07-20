# ClosureJVM Architecture

This document explains the “why” and “how” behind ClosureJVM. The root README stays short and points here for details.

Significant design choices (and the alternatives we rejected) are logged in
[DESIGN-DECISIONS.md](DESIGN-DECISIONS.md) — consult it before revisiting settled questions.

## Why

Modern JVM web apps often fail due to availability issues rather than crashes:

- Pathological inputs, slow paths, and GC storms
- Resource leaks (threads, executors, timers, memory)
- Iteration contamination (state carried across requests)

These problems are input-dependent, hard to reproduce, and frequently invisible to conventional testing. ClosureJVM systematically explores request space while enforcing clean iteration boundaries and checking availability invariants.

## Core Idea

Run thousands of iterations inside a single JVM with strict iteration boundaries:

1. Execute a request/entrypoint
2. Collect coverage and runtime metrics
3. Check availability invariants
4. Reset state (prefer enforcement; fallback to hard reset)
5. Feed interesting inputs back to exploration

Coverage guides where to explore. Invariants define what constitutes a bug.

## What It’s Not

- Not a load tester or benchmark suite
- Not limited to security-only fuzzing
- Not tied to a specific framework

## Availability Invariants (v1)

An iteration is failing/interesting if it violates any configured invariant:

- Latency threshold exceeded
- Heap delta grows beyond bounds
- New non-daemon threads remain (thread leak)
- Executor queues retain work
- GC pauses exceed limits
- Nondeterministic outcomes for same input

These can serve as hard failure oracles or soft signals with triage metadata.

## Architecture Overview

Inputs → Harness/Runner → App Entry → Metrics & Coverage → Invariants → Reset → Feedback

### Components

- Runner: executes iterations, manages lifecycle, orchestrates checks
- Java Agent: instruments/observes runtime, tracks threads/executors
- Native Agent (optional, JVMTI): event-driven thread lifecycle tracking (ThreadStart/ThreadEnd)
  for count signals without polling or safepoint stack walks; harness falls back to ThreadMXBean
  when it is not loaded
- Reset Engine: enforce cleanliness; fallback to hard reset (ClassLoader swap)
- Tomcat valve (namespace-free): captures server-side invariants in an *unmodified* app on both
  `javax` (Tomcat 9) and `jakarta` (Tomcat 10) without a servlet-namespace dependency (DD-011)
- Coverage-over-HTTP: a JaCoCo tcpserver agent in the app JVM; the driver dumps + analyzes it into a
  live coverage %, union-merged across replicas (DD-012, DD-023)
- Dashboard: a standalone aggregator process drivers push status/findings to (decoupled from the app
  and the driver; loopback-guarded), with optional Claude-API triage of clustered findings (DD-013/14/15)
- **Kubernetes operator** (Go/kubebuilder, `operator/`): the deploy-time **control plane**. See below.

### Kubernetes operator (deploy-time instrumentation)

The operator instruments an app **at deploy time** rather than requiring a custom image. A namespaced
`ClosureJVMTarget` custom resource names a Deployment; the operator patches its pod template — an
initContainer copies the agents from a versioned image into a shared volume, and the agent flags are
**appended** to the container's `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS` (never replacing the app's own).
It's fully reversible (a finalizer restores the Deployment exactly on delete) and, when asked, creates
a headless coverage Service whose DNS backs the DD-023 union-coverage flag. The design is deliberately
a *runtime-agnostic* control plane — the CR/reconcile/inject/revert machinery is JVM-independent, only
the injected flags and the agents image are JVM-specific — so it can grow other runtime profiles later.
Full design: [OPERATOR-DESIGN.md](OPERATOR-DESIGN.md); usage: [USAGE.md](USAGE.md#kubernetes-instrument-any-app-with-the-operator).

## Early Usage Pattern (preview)

Call the target entrypoint per iteration, within begin/end boundaries:

```
Agent.beginIteration();
// call target handler here
Agent.endIteration();
```

For v0.1, focus on thread/executor leaks and making contamination obvious.

## Status (v0.1 focus)

- Iteration boundaries (begin/end)
- Thread leak detection (non-daemon thread diffs)
- Minimal runner and example target

Later milestones add invariant framework, reset strategies, and coverage-guided exploration.

