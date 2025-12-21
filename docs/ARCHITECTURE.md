# ClosureJVM Architecture

This document explains the “why” and “how” behind ClosureJVM. The root README stays short and points here for details.

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
- Reset Engine: enforce cleanliness; fallback to hard reset (ClassLoader swap)

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

