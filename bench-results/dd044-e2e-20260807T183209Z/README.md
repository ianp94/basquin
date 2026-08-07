# DD-044 / PR-3.5 task 5 — in-cluster e2e run (pre-instrumented targets)

**Verdict: RAN END TO END, ALL 8 ASSERTIONS PASSED.** `deploy/e2e/e2e-preinstrumented.sh` (authored
this task) was executed for real against a dedicated kind cluster (`basquin-dd044`), building the
injected **native** Quarkus image via containerized Mandrel exactly as the DD-043 recipe did, applying
a `BasquinTarget{preInstrumented: true}`, and driving it with a `mode: load` `BasquinCampaign`. This
directory is the captured evidence of that one real run (started 2026-08-07T18:32:09Z UTC).

## What ran, in order

1. **Native build** (`build-native.log`, 268 KB): `basquin-maven-injector` built
   (`./gradlew :basquin-maven-injector:jar`), `basquin-core` + `basquin-quarkus` (runtime +
   deployment) published to a scratch Maven repo served on `127.0.0.1:8010`, the fixture's local
   `.m2` purged of `com/basquin` (ambiguity control), then
   `bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests -Dnative` run with
   `-Dmaven.ext.class.path=<injector jar> -Dbasquin.inject.repo.url=http://localhost:8010/` — the
   **same containerized-Mandrel recipe DD-043 used**, entirely inside
   `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image` (pinned digest). No local `native-image`
   binary was installed or used anywhere in this run.
   - Injector announced itself: `build-native.log:878` —
     `[basquin-injector] instrumented fixture (com.basquin:basquin-quarkus:0.3.0 from http://localhost:8010/)`
   - All six `com/basquin` artifact fetches (pom+jar for `basquin-core`, `basquin-quarkus`,
     `basquin-quarkus-deployment`) came from `basquin-injected` (`build-native.log:1191,1194,1364,1366,1443,1565`);
     **zero** `Downloaded from central:` lines for any `com/basquin` path — same ambiguity-control
     result as `bench-results/dd043-pr3-native-2026-07-26/`.
   - `BUILD SUCCESS`, Maven total **04:28 min** (`build-native.log:1818,1820`); `native-image` proper
     finished in **39.0s** (`build-native.log:1814`).
   - Native binary produced at
     `bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner`: ELF 64-bit
     LSB executable, x86-64, dynamically linked, stripped, 48,659,512 bytes (verified with `file`
     post-hoc; not committed — `bench-results/` tracks no binaries).
2. **Container image**: packaged via the fixture's own `src/main/docker/Dockerfile.native`
   (`docker-build-fixture.log`) into `basquin/dd044-fixture-native:0.3.0`.
3. **Cluster**: a dedicated kind cluster `basquin-dd044` (created fresh for this run, distinct from
   the pre-existing `basquin` cluster — never touched). Operator image built
   (`docker-build-operator.log`) and runner image built (`docker-build-runner.log`); **no agents
   image was built or loaded** — the `preInstrumented` path never calls `injection.go`. A
   `curlimages/curl` probe image was pulled and loaded for the channel probe (the app's minimal UBI9
   base is not guaranteed to carry `curl`). Operator deployed via kustomize with namespaced RBAC,
   patched with `--runner-image` only.
4. **App deployed, snapshotted BEFORE the CR**: single-replica Deployment `dd044-fixture` running the
   injected native image, plus a ClusterIP Service. `deployment-before.json` captured.
5. **`BasquinTarget{preInstrumented: true}` applied**; reached `Phase=Observed` after 7 polls (~21s).
   `basquintarget-observed.yaml`: `Ready=True` condition, `reason: PreInstrumented`, message
   `"1/1 replica(s) ready; instrumentation is build-time (operator did not modify the pod template)"`,
   plus `ReplicaConfigSupported=True/SingleReplica`.
6. **The triple negative assertion** (`assertions.txt`, `deployment-before.json` /
   `deployment-after.json`): pod-template spec-hash equality (`before.template-hash.txt` ==
   `after.template-hash.txt`, both `ed715c43…`), `metadata.generation` unchanged (`1` == `1`), and
   zero `basquin.dev/*` keys on the Deployment's labels+annotations. **All three passed.**
7. **The channel probe** (`channel-probe.txt`, `fixture-pod-startup-banner.log`): from a dedicated
   `curlimages/curl` pod, a request to `/ok` carrying `X-Basquin-Req: dd044-e2e-probe-1`, then
   `/__basquin/result?id=dd044-e2e-probe-1` returned `0,0,0|0||` — a genuine cost line (per
   `ResultStore.format`: `costCsv|invariantCount|detail|leak`), **not `"miss"`**. Deliberately did
   **not** touch `/__basquin/drift` or `/__basquin/mode` (design spec §5 — those are Tomcat-valve-only
   and basquin-quarkus's `BasquinControlHandler` answers `err:unknown` for both, confirmed live in
   `driver-job.log`'s `"mode load toggle not confirmed (got \"err:unknown\")"`).
8. **`mode: load` BasquinCampaign to completion** (`basquincampaign-final.yaml`, `driver-job.log`):
   `mode: load` spelled explicitly, `dashboard.enabled: false` (dashboard image not needed for this
   assertion), corpus hand-authored as a single `ConfigMap` key `corpus.txt: "/ok"` (ordinary load
   replay needs no grammar — CEL rejects one for `mode: load` anyway). Reached `Phase=Completed` after
   9 polls (~24s): **149,424 requests**, **7471.0 rps**, driven entirely through the app's own
   `/ok` route, latency/heap/thread invariants correctly reported `notEvaluated` (no
   `invariants.latencyMaxMs` was set), and `driftUnavailable: true` — expected and non-fatal
   (`LoadRun.java`'s drift/mode polling soft-fails on `err:unknown` by design; it never touches the
   main request-firing loop).

## The decisive extra proof: the native binary's own banner, live in the pod

Beyond the build log, the **running pod itself** proves the compiled feature is `basquin`.
`kubectl logs` had already rotated the early lines out by the time evidence was collected (149k+
request log lines from the load campaign pushed the container's default-rotated log file past its
retention), but the rotated predecessor file was still present on the kind node and was recovered:
`fixture-pod-startup-banner.log` —

```
2026-08-07 18:38:19,194 INFO  [io.quarkus] (main) fixture 1.0.0-SNAPSHOT native (powered by Quarkus 3.37.3) started in 0.011s. Listening on: http://0.0.0.0:8080
2026-08-07 18:38:19,194 INFO  [io.quarkus] (main) Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]
```

This is the same native binary this run's own build produced (fixture is rebuilt in place at
`bench-results/dd043-spikes-2026-07-24/fixture/`, unmodified from DD-043), now proven live **inside
the kind cluster**, not merely on the build host as `bench-results/dd043-pr3-native-2026-07-26/` did.
The excerpt also captures, in order: the readiness-probe traffic, the channel probe's own
`/__basquin/result` hit (`status=200`), and the load campaign's first `/__basquin/mode` and
`/__basquin/drift` polls — all `status=200` (the control handler answers every subpath, including the
unsupported ones, with 200 + `err:unknown`; the client-side `err:unknown` mismatch is what
`driver-job.log` reports as "not confirmed", not an HTTP failure).

## Acceptance (spec §6) status

- **Item 1** — `BasquinTarget{preInstrumented: true}` over a build-time-instrumented **native**
  Quarkus Deployment reaches `Phase=Observed`/`Ready=True`, Deployment unmodified: **VERIFIED**, this
  run (`assertions.txt` 1-4).
- **Item 2** — `BasquinCampaign{mode: load}` runs to completion, findings from `/__basquin/result`:
  **VERIFIED** for "runs to completion" and for the channel's live functioning (the probe stage);
  the load campaign itself uses `/__basquin/drift`+`/__basquin/mode`, not `/__basquin/result` (by
  design — see `runner/coverage/LoadRun.java`; load-mode's own findings are HTTP-status/latency
  counters, not the result channel). The design spec's stage table separates these two proofs
  deliberately (§5's "channel probe" row is the `/__basquin/result` proof; the load campaign row is
  the completion proof) — both ran and both passed here.

## Files in this directory

| File | What it is |
|---|---|
| `build-native.log` | full native build (injector announce line 878; `Downloaded from basquin-injected` 1191-1565; `BUILD SUCCESS` 1818) |
| `docker-build-fixture.log` / `-operator.log` / `-runner.log` | the three `docker build` runs |
| `deployment-before.json` / `deployment-after.json` | full Deployment snapshots either side of `Phase=Observed` |
| `before.template-hash.txt` / `after.template-hash.txt` | `sha256(jq -S .spec.template)` — identical |
| `before.generation.txt` / `after.generation.txt` | `metadata.generation` — identical (`1`/`1`) |
| `assertions.txt` | the 8 PASS/FAIL lines this run produced |
| `basquintarget-observed.yaml` | full `BasquinTarget` status at `Phase=Observed` |
| `basquincampaign-final.yaml` | full `BasquinCampaign` status at `Phase=Completed` |
| `driver-job.log` | the load driver Job's full stdout (149,424 requests, drift/mode soft-fail lines) |
| `channel-probe.txt` | the `/__basquin/result` response captured by the probe stage |
| `fixture-pod-startup-banner.log` | recovered rotated pod log: startup banner (`Installed features: […, basquin, …]`) + first probe/mode/drift hits, all live in-cluster |
| `target-phase-final.txt` | final observed target phase marker |

Not tracked (consistent with `bench-results/` tracking no built artifacts anywhere): the native binary,
the three docker images, and the scratch Maven repo/injector-jar staging dirs
(`build/tmp/dd044-e2e-*`, gitignored via `build/*`).

## Reproduce

```bash
deploy/e2e/e2e-preinstrumented.sh              # build + run the full pipeline (dedicated kind cluster)
deploy/e2e/e2e-preinstrumented.sh --teardown   # delete the dedicated kind cluster when done
```

Prerequisites: Docker with internet access (pulls the pinned Mandrel builder image, the UBI9-minimal
base, and `curlimages/curl`), `kind`, `kubectl`, `jq`, `python3`, Java 17 + the Gradle wrapper. No
local `native-image` install is required — the native compile runs entirely inside the pinned Mandrel
container, as DD-043 established. Takes on the order of 10-15 minutes end to end (native build is the
long pole; this run's native build alone took 4:28 min once the fixture's cold `.m2` cache was
populated).
