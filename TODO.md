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
- [ ] First reset strategy: hard-reset fallback (ClassLoader swap)

### Tasks
1) Invariant polish
   - [x] Hook invariant checks at iteration end (after metrics snapshot)
   - [ ] Optionally include invariant summary line in output with structured key=val pairs
   - [ ] Add heap/thread invariant tests similar to latency test

2) Reset strategy: Hard reset fallback
   - [ ] Implement child ClassLoader for target code; keep Agent/Runner in parent
   - [ ] Load `runner.api.IterationTarget` implementation via child loader; re-instantiate on reset
   - [ ] Add flags:
       - `-Dclosurejvm.reset=classloader` (enable)
       - `-Dclosurejvm.reset.onFailure=true` (reset after hard invariant/leak)
   - [ ] Minimal smoke test that induces a failure, triggers reset, and runs another iteration

3) Docs & DX
   - [ ] README: document invariant flags (added) and reset flags/behavior (pending)
   - [ ] Example snippet showing how to enable reset with GenericRunner
   - [ ] CI job to run an invariant-soft mode and confirm non-zero vs zero behavior as appropriate

### Non-goals (keep scope tight)
- No coverage integration yet (v0.3)
- No full static rollback; prefer classloader swap fallback
