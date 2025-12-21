# ClosureJVM v0.1 Tasks

## Milestone: "Make leaks obvious"

### Core Requirements
- [ ] Create repo structure (`agent/`, `runner/`, `examples/`)
- [ ] Add README + this file
- [ ] Add a `TODO.md` with v0.1 tasks
- [ ] Add a "hello runner" that executes an iteration loop over a corpus directory
- [ ] Add thread leak check that fails a demo target

### Implementation Tasks
- [ ] Implement iteration boundary API: `beginIteration()` / `endIteration()`
- [ ] Implement thread leak detection (new non-daemon threads after iteration)
- [ ] Implement basic executor/timer leak detection (best-effort)
- [ ] Create minimal runner (CLI or JUnit extension)
- [ ] Create one example target + README quickstart

### Testing
- [ ] Test with a small servlet/controller example
- [ ] Verify thread leak detection works
- [ ] Test 10,000 iterations
- [ ] Demonstrate reliable detection of deliberate thread leak
