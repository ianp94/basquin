# Changelog

Release-level changelog for Basquin. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions are git tags (`vX.Y.Z`), and one tag versions everything — the four container images, the
Helm chart's `appVersion`, and the CLI are all stamped from it.

Component-level detail lives with each component:

| Component | Changelog |
|-----------|-----------|
| Java agent (in-JVM measurement) | [`agent/CHANGELOG.md`](agent/CHANGELOG.md) |
| Runner (drivers, fuzzing, load, dashboard) | [`runner/CHANGELOG.md`](runner/CHANGELOG.md) |
| Native JVMTI agent | [`native/CHANGELOG.md`](native/CHANGELOG.md) |
| Tomcat valve | [`tomcat-valve/CHANGELOG.md`](tomcat-valve/CHANGELOG.md) |
| Kubernetes operator + `basquin` CLI | [`operator/CHANGELOG.md`](operator/CHANGELOG.md) |
| Helm chart | [`deploy/helm/basquin-operator/CHANGELOG.md`](deploy/helm/basquin-operator/CHANGELOG.md) |

## [0.2.0] — 2026-07-21

First published release. Everything ships from this tag: the four multi-arch container images on
ghcr (`ghcr.io/ianp94/basquin-{operator,agents,runner,dashboard}`), the `basquin` CLI binaries
(linux/darwin/windows × amd64/arm64), the packaged Helm chart, and the self-updating Pages Helm
repo (`https://ianp94.github.io/basquin/charts`).

### Added
- **Kubernetes operator** (namespaced by design): `BasquinTarget` for reversible in-place
  instrumentation of unmodified Deployments; `BasquinCampaign` for complete test runs with
  `explore` (coverage-guided fuzzing) and `load` (corpus replay) modes, coverage % + findings in
  status, and an emitted corpus ConfigMap per explore run.
- **Per-campaign dashboard** with token-authenticated pushes (a per-campaign 256-bit Secret),
  findings clustered by fingerprint, rich input drill-down, and optional Claude-powered analysis.
- **`basquin` CLI**: `instrument`, `run`, `status`, `dashboard`, `version`.
- **Measurement layer**: availability invariants (latency / heap delta / thread delta, hard or
  soft), event-driven JVMTI thread-leak tracking, iteration contexts, classloader reset fallback.
- **Exploration**: JaCoCo coverage signal over HTTP (union-merged across replicas), request
  grammars + seed corpora, multi-step sequences with sessions, ddmin input minimization.
- **Tomcat valve**: one namespace-free jar for both `javax.servlet` (Tomcat 9) and
  `jakarta.servlet` (Tomcat 10+).

### Known limitations
- arm64 images are **build-validated only** (the native `.so` has not yet been loaded by a real
  arm64 JVM; a bad `-agentpath` library is fatal at JVM startup).
- Dashboard **reads** are unauthenticated (writes are token-authenticated) — single-tenant only.
- Campaign `driver.corpusConfigMap` seed values are not yet mounted into the driver.
- War-only target images yield coverage 0 (the coverage-classes initContainer needs an exploded
  `WEB-INF/classes`).

[0.2.0]: https://github.com/ianp94/basquin/releases/tag/v0.2.0
