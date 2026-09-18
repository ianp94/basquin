# PR-5 follow-through

A focused continuation of [the PR-5 plan](2026-08-13-dd043-pr5-reactive.md).
Continue on draft PR #111; merge only after the planned acceptance and review gates.

## Review checkpoints

- [x] Add explicit-disposition driver cost gating.
- [ ] Finish the disposition contract. Keep report recovery distinct from heap attribution;
      reject cost scoring when any recovered hop explicitly lacks a valid measurement.
      Retain real invariant findings even when heap cannot be measured.
- [x] Pin mixed-chain, unknown-disposition, and disconnected cost exclusion with driver tests.
- [x] Carry heap attribution into retained corpus entries. Replay export contains input strings
      only; unavailable cost cannot train retention or receive a composite score.
- [x] Negotiate wire=2; serve legacy Tomcat formatting and refuse legacy reactive polls
      without consuming results. Old-runner/new-producer leak loss is prevented at the endpoint.
- [x] Identify the model in negotiated responses. Older reactive producers require the documented
      legacyModel override because their response cannot identify the model.
- [x] Implement process-global in-flight tracking, overlap history, sub-quantum, negative and
      GC-contaminated heap exclusions; add lifecycle and unavailable-signal regression tests.
- [ ] Prove these producers through real-app controls, including GC ordering and all native paths.
- [x] Publish disconnect records without numeric measurements.
- [ ] Exclude disconnects from driver-side latency/crash populations end to end.
- [ ] Expose disposition/taint counters and control-pass state in summary records; add a loud
      failure threshold without conflating intentional heap exclusions with transport misses.
- [ ] Implement the separate-pass JFR cross-check and then the event-loop watchdog.
- [ ] Gate rendered figures on passing controls and complete the real-target JVM/native matrix.
- [x] Refresh roadmap status against merged PRs; include roadmap maintenance in each milestone.

## Validation

Each checkpoint gets focused tests and a reviewable diff. Changes to core packaging also run
`verifyShippedJarsContainCore`. Passing parser tests does not establish native end-to-end
measurement correctness. Do not mark the overall PR-5 milestone complete before its control runs.
