# `closurejvm/agents` image

The image the operator injects. When a `ClosureJVMTarget` is reconciled (docs/OPERATOR-DESIGN.md §4,
DD-024), the operator adds an initContainer running **this image** that copies the agents into a
shared `emptyDir` (`cp -r /agents/. /closurejvm/`), which the app container then loads via
`-javaagent`/`-agentpath`.

## Contents (`/agents/`)

| File | Injected as | Purpose |
|------|-------------|---------|
| `closurejvm-agent.jar` | `-javaagent` + `-Xbootclasspath/a` | invariant/leak oracle |
| `jacocoagent.jar` | `-javaagent` tcpserver | coverage-over-HTTP (DD-012/DD-023) |
| `libclosurejvmti.so` | `-agentpath` | native JVMTI thread/leak tracker (DD-004) — **linux/amd64** |
| `closurejvm-valve.jar` | (mounted later) | Tomcat valve; valve mounting is deferred, jar ships ready |

## Build

```bash
deploy/agents-image/build.sh [TAG] [KIND_CLUSTER]
```

- Runs `stageAgents copyJacocoAgent buildNativeAgent` (needs a C compiler + JDK headers for the
  native `.so`), stages the four artifacts into `agents/`, and `docker build`s `closurejvm/agents:TAG`
  (+ `:latest`). `TAG` defaults to the Gradle project version.
- Pass a kind cluster name to `kind load docker-image` it for local e2e, e.g.:

```bash
deploy/agents-image/build.sh 0.2.0 closurejvm
```

## Notes

- **Architecture.** `libclosurejvmti.so` is compiled for the build host's arch (linux/amd64 here).
  A cluster on arm64 needs the image rebuilt on/for arm64 (multi-arch build is future work).
- **Versioning.** Prefer a fixed tag over `:latest` in real deployments (DD-022: a stale `:latest`
  silently serves an old build). Point the operator at it with `--agents-image=closurejvm/agents:TAG`.
- **Publishing.** This builds locally and (optionally) loads into kind. Pushing to a registry
  (`docker push`) is a deploy-time step with your own credentials; not done here.
