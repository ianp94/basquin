# `basquin/agents` image

The image the operator injects. When a `BasquinTarget` is reconciled (docs/OPERATOR-DESIGN.md §4,
DD-024), the operator adds an initContainer running **this image** that copies the agents into a
shared `emptyDir` (`cp -r /agents/. /basquin/`), which the app container then loads via
`-javaagent`/`-agentpath`.

## Contents (`/agents/`)

| File | Injected as | Purpose |
|------|-------------|---------|
| `basquin-agent.jar` | `-javaagent` + `-Xbootclasspath/a` | invariant/leak oracle |
| `jacocoagent.jar` | `-javaagent` tcpserver | coverage-over-HTTP (DD-012/DD-023) |
| `libbasquinjvmti.so` | `-agentpath` | native JVMTI thread/leak tracker (DD-004) — compiled **per-arch** (DD-027) |
| `basquin-valve.jar` | (mounted later) | Tomcat valve; valve mounting is deferred, jar ships ready |

## Build

```bash
deploy/agents-image/build.sh [TAG] [KIND_CLUSTER]
```

- Runs `stageAgents copyJacocoAgent`, staging the three **jars** into `agents/` and the native
  **source** into `native/`, then `docker build`s `basquin/agents:TAG` (+ `:latest`). `TAG`
  defaults to the Gradle project version. The `.so` is *not* built on the host — the Dockerfile
  compiles it per-arch in a `eclipse-temurin:17-jdk` builder stage (DD-027), so no C compiler or JDK
  headers are needed on the machine running `build.sh`.
- Pass a kind cluster name to `kind load docker-image` it for local e2e, e.g.:

```bash
deploy/agents-image/build.sh 0.2.0 basquin
```

- `STAGE_ONLY=1` stages the build context and stops (no `docker build`), so a caller can drive
  `docker buildx` for a multi-arch push — this is what `release.yml` does.

## Notes

- **Architecture (DD-027).** Published releases are multi-arch manifest lists (`linux/amd64` +
  `linux/arm64`): `release.yml` runs `docker buildx --platform`, and the Dockerfile's builder stage
  compiles `libbasquinjvmti.so` once per target arch (natively on amd64, QEMU-emulated on arm64).
  A plain local `build.sh` still produces a single **host-arch** image, which is what kind/e2e want.
  arm64 is **functionally validated in CI on real arm64 hardware** (`arm64-smoke.yml` on
  `ubuntu-24.04-arm`: native `.so` build, `-agentpath` load in a real arm64 JVM, JVMTI hooks
  asserted active, full iteration loop) on every native/agent change.
- **Versioning.** Prefer a fixed tag over `:latest` in real deployments (DD-022: a stale `:latest`
  silently serves an old build). Point the operator at it with `--agents-image=basquin/agents:TAG`.
- **Publishing.** `build.sh` builds locally and (optionally) loads into kind. Publishing is
  tag-driven: pushing a `v*` tag runs `release.yml`, which buildx-builds + pushes the multi-arch
  image to `ghcr.io/ianp94/basquin-agents:<version>` (+ `:latest`).
