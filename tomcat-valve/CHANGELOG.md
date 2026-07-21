# Changelog — Tomcat valve

Per-request instrumentation of unmodified WARs on Tomcat.
Release-level changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## [Unreleased]

- **Lock-free load mode (DD-029):** the valve now branches on `LoadMode` — explore serializes (unchanged), load passes through lock-free — and intercepts `/__basquin/*` control requests (mode toggle / drift) on the app port. Still namespace-free (verified).


## [0.2.0] — 2026-07-21

First published release (ships inside `ghcr.io/ianp94/basquin-agents`).

### Added
- A valve that wraps every request in iteration boundaries and reports server-side invariant
  violations via `X-Basquin-Invariant-*` response headers, which the HTTP drivers harvest.
- **Namespace-free bytecode** (DD-011): a single jar runs on both `javax.servlet` (Tomcat 9) and
  `jakarta.servlet` (Tomcat 10+) — no servlet-API imports at all (`javap`-verified), narrowed
  `invoke` signature, headers via the Catalina `Response`. Verified against JPetStore-6 on
  Tomcat 9 and re-verified on Tomcat 10.
- Requests are **serialized** through the valve by design (DD-005/DD-010): heap/thread deltas are
  process-global and would lie under concurrent requests.
- Mutually exclusive with the in-WAR `IterationFilter` (use one or the other).
