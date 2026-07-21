# Changelog — Native JVMTI agent

`libbasquinjvmti.so`: event-driven thread lifecycle tracking for the Java agent.
Release-level changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## [0.2.0] — 2026-07-21

First published release (ships inside `ghcr.io/ianp94/basquin-agents`, loaded via `-agentpath`).

### Added
- Event-driven thread **counts** via `ThreadStart`/`ThreadEnd` — no polling, no safepoint stack
  walks; the Java agent falls back to `ThreadMXBean` when the native agent isn't loaded.
- Event-driven leak **set**: weak global refs to non-daemon `Thread` objects tracked at
  `ThreadStart`/`ThreadEnd`, so the leak oracle needs no thread enumeration and still names
  leaked threads.
- Built for JDK 17 and 21 (CI matrix); compiled per-arch inside the agents-image Docker build
  (natively on amd64, under QEMU for arm64 — DD-027).

### Known limitations
- ⚠️ **arm64 is build-validated only**: CI compiles the arm64 `.so` under emulation, but no arm64
  runner has loaded it in a real JVM yet. A bad `-agentpath` library is fatal at JVM startup, so
  treat arm64 as unproven until a functional arm64 run lands.
