# ClosureJVM v0.1 Tasks

## Milestone: "Make leaks obvious"

### Core Requirements
- [x] Create repo structure (`agent/`, `runner/`, `examples/`)
- [x] Add README + this file
- [x] Add a `TODO.md` with v0.1 tasks
- [x] Add a "hello runner" that executes an iteration loop over a corpus directory
- [x] Add thread leak check that fails a demo target

### Implementation Tasks
- [x] Implement iteration boundary API: `beginIteration()` / `endIteration()`
- [x] Implement thread leak detection (new non-daemon threads after iteration)
- [x] Implement basic executor/timer leak detection (best-effort)
- [x] Create minimal runner (CLI or JUnit extension)
- [x] Create one example target + README quickstart
- [x] Add forked leak integration test (expects non-zero exit and leak text)
- [x] Add Gradle wrapper for consistent CI/dev usage

### Testing
- [x] Test with a small servlet/controller example (Javalin leak demo task)
- [ ] Test 10,000 iterations locally and record metrics
- [x] Demonstrate reliable detection of deliberate thread leak (local + test)
- [x] Add CI workflow: build, test, proper demo; leak demo expected to fail

### Operational Notes
- Leak demo can hang the JVM due to non-daemon threads; use `-Dclosurejvm.forceExitOnLeak=true` in demos/CI to force fast termination on leak detection.

### Next Up (v0.1 hardening)
- [x] Add simple metrics snapshot (thread count, heap delta) at iteration boundaries (print-only)
- [x] Tighten executor/timer tracking (weak refs; ScheduledThreadPoolExecutor details)
- [x] Document quick flags in README (done) and add CI badge once wrapper lands

---

## Milestone: v0.2 — "Reset discipline"

Goal: Turn findings into enforceable guarantees.

### Deliverables
- [x] Configurable invariants (latency, heap delta, thread delta) via `-Dclosurejvm.invariant.*`
- [x] Hard failure vs soft signal modes (global and per-invariant)
- [x] First reset strategy: hard-reset fallback (ClassLoader swap)

### Tasks
1) Invariant polish
   - [x] Hook invariant checks at iteration end (after metrics snapshot)
   - [ ] Optionally include invariant summary line in output with structured key=val pairs
   - [x] Add heap/thread invariant tests similar to latency test

2) Reset strategy: Hard reset fallback
   - [x] Implement child-first ClassLoader for target package; keep Agent/Runner in parent
   - [x] Load `runner.api.IterationTarget` via child loader; re-instantiate on reset
   - [x] Flags:
       - `-Dclosurejvm.reset=classloader` (enable)
       - `-Dclosurejvm.reset.onFailure=true` (reset after hard invariant/leak)
       - `-Dclosurejvm.reset.maxResets=3` (cap)
   - [ ] Minimal smoke test that induces a failure, triggers reset, and runs another iteration

3) Docs & DX
   - [x] README: document invariant flags and reset flags/behavior
   - [x] Example snippet showing how to enable reset with GenericRunner
   - [x] CI job includes Gradle 'check' (verification tasks)

---

## Milestone: v0.3 — "Exploration"

Goal: Add coverage-guided exploration without destabilizing the harness.

### Deliverables
- [ ] Integration with a fuzzer: Jazzer or JQF (pick one)
- [ ] Save interesting inputs + triage metadata (classification, timestamp, invariant, thread dump if relevant)
- [ ] Minimization (basic ddmin or equivalent)

### Tasks
1) Pick exploration engine
   - [ ] Choose Jazzer or JQF based on ease of embedding and determinism
   - [ ] Minimal glue to feed inputs to IterationTarget

2) Input management
   - [ ] Store inputs that trigger invariant/leak/crash
   - [ ] Record triage metadata (classification, stack traces, runtime stats)
   - [ ] Provide a simple corpus directory layout

3) Minimization
   - [ ] Implement a simple ddmin-like reducer or leverage engine support
   - [ ] Ensure minimization respects iteration boundaries and invariants

4) Docs & DX
   - [ ] README quickstart for exploration runs
   - [ ] One example target wired to exploration

### Non-goals (keep scope tight)
- No coverage integration yet (v0.3)
- No full static rollback; prefer classloader swap fallback
