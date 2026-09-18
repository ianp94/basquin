# PR-5 follow-through

A focused continuation of [the PR-5 plan](2026-08-13-dd043-pr5-reactive.md).
Existing uncommitted work belongs to the current branch and must be preserved.

## Review checkpoints

- [x] Add explicit-disposition driver cost gating.
- [ ] Finish the disposition contract. Keep report recovery distinct from heap attribution;
      reject cost scoring when any recovered hop explicitly lacks a valid measurement.
      Retain real invariant findings even when heap cannot be measured.
- [x] Pin mixed-chain, unknown-disposition, and disconnected cost exclusion with driver tests.
- [ ] Carry attribution into exported coverage-retained corpus entries; never label a partial
      heap value as a complete measurement.
- [ ] Resolve protocol compatibility before release: require an explicit negotiation or a
      versioned endpoint that prevents old runners silently losing leak flags. Updating the
      runner first is the interim documented rollout constraint, not a completed handshake.
- [ ] Identify the target measurement model explicitly so legacy Tomcat records remain usable
      while missing reactive dispositions stay unknown.
- [ ] Implement Task 2's process-global in-flight tracking, overlap history, sub-quantum,
      negative and GC-contaminated heap handling; decrement on every completion path.
- [ ] Publish disconnects without adding them to latency or crash populations.
- [ ] Expose disposition/taint counters and control-pass state in summary records; add a loud
      failure threshold without conflating intentional heap exclusions with transport misses.
- [ ] Implement the separate-pass JFR cross-check and then the event-loop watchdog.
- [ ] Gate rendered figures on passing controls and complete the real-target JVM/native matrix.
- [x] Refresh roadmap status against merged PRs; include roadmap maintenance in each milestone.

## Validation

Each checkpoint gets focused tests and a reviewable diff. Changes to core packaging also run
`verifyShippedJarsContainCore`. Passing parser tests does not establish native end-to-end
measurement correctness. Do not mark the overall PR-5 milestone complete before its control runs.
