# ClosureJVM

ClosureJVM is a persistent execution harness for JVM web applications that uses coverage-guided exploration to discover **availability, performance, and correctness failures** — not just crashes.

Instead of treating fuzzing as purely a security exercise, ClosureJVM treats **availability failures as first-class bugs**.

---

## Why?

Modern JVM web applications fail far more often due to:

- Pathological request inputs
- Resource leaks
- Thread and executor exhaustion
- Slow or rare code paths
- Cumulative state corruption

These failures are often:

- Input-dependent
- Hard to reproduce
- Invisible to traditional load testing
- Missed by unit and integration tests

ClosureJVM systematically explores request-level state space to find them.

---

## Core Idea

ClosureJVM runs **thousands of request executions inside a single JVM** while enforcing clean iteration boundaries.

Each iteration:

1. Executes an application request handler
2. Collects coverage and runtime metrics
3. Checks availability invariants
4. Resets application state (selectively or fully)
5. Feeds interesting inputs back into exploration

Coverage guides *where to explore*.  
Availability invariants define *what constitutes a bug*.

---

## What ClosureJVM Is Not

- ❌ A load-testing tool
- ❌ A benchmarking suite
- ❌ Limited to vulnerability discovery
- ❌ Tied to a specific web framework

---

## Availability Invariants (v1)

An iteration is considered *failing* or *interesting* if it violates any configured invariant:

- Request latency exceeds a threshold
- Heap usage grows beyond bounds
- New non-daemon threads remain after execution
- Executor queues retain work
- GC pauses exceed limits
- Request results become inconsistent

These invariants can be used as:

- Hard failure oracles
- Soft signals for exploration
- Triage metadata

---

## Architecture Overview

```
Inputs
  ↓
Harness / Runner
  ↓
Application Entry Point
  ↓
Metrics & Coverage
  ↓
Invariant Checks
  ↓
State Reset Enforcement
  ↓
Feedback Loop
```

---

## Components

### Runner

- Executes request iterations
- Manages iteration lifecycle
- Integrates with fuzzing engines (optional)

### Java Agent

- Tracks thread creation and executor usage
- Detects resource leaks
- Optionally tracks static state mutation

### Reset Engine

- **Soft reset**: enforce invariants, detect leaks
- **Hard reset**: reload application ClassLoader

---

## Getting Started (Early Preview)

> ⚠️ This project is under active development. APIs are unstable.

Basic usage pattern:

```java
public class RequestHarness {
  public static Response handle(byte[] input) {
    Request req = decode(input);
    return App.handle(req);
  }
}
```

Run with:

```bash
-javaagent:closurejvm-agent.jar
```

Configure invariants via:

```yaml
closurejvm:
  maxLatencyMs: 200
  maxHeapDeltaBytes: 1048576
  forbidThreadLeaks: true
```

---

## Use Cases

- Discover request patterns that degrade availability
- Find resource leaks triggered by rare code paths
- Validate request handlers are safe to replay
- Explore worst-case performance behavior
- Augment fuzzing with availability analysis

---

## Status

- [ ] Thread leak detection
- [ ] Executor leak detection
- [ ] Iteration boundary API
- [ ] Basic runner CLI
- [ ] Example servlet target

---

## Philosophy

ClosureJVM is inspired by research on persistent execution and fine-grained state rollback. It prioritizes correctness, determinism, and observability over raw throughput.

If your application only fails after thousands of requests, ClosureJVM is built to find it.
