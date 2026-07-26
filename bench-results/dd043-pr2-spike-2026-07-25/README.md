# DD-043 PR-2 decision spike — a Gradle-built Quarkus extension, consumed by a Maven-built app

**The question.** DD-043 PR-2 builds `basquin-quarkus`, the extension that installs Basquin's request
boundary. Quarkus extensions are conventionally **Maven**-built, but this repo is entirely Gradle.
Does the `io.quarkus.extension` Gradle plugin produce an artifact a **Maven-built** Quarkus app
actually honours at augmentation?

**Verdict: CONFIRMED.** Gradle holds; PR-2 proceeds as Gradle subprojects.

## Why the choice mattered enough to spike

A Maven module would sit outside `check`, outside the `verifyShippedJarsContainCore` guard, and
outside every CI path filter — deliberately recreating the blind spot behind this branch's two most
serious defects (a shipped jar outside the guard in PR-1, and the moved core falling outside every CI
trigger). Keeping one build system keeps the new module inside controls that already exist.

Against that, the Gradle extension plugin is the less-trodden path. So the choice was decided by
evidence rather than by preference.

## Acceptance, and why a building jar was not enough

The signal is the consuming app's startup banner listing the extension under `Installed features:` —
the same criterion spike S4 established. A jar that merely compiles and carries metadata proves the
*build* worked; only the banner proves **augmentation honoured it**, which is the actual question.

## Result

```
Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]
```

`app-startup.log` is the full startup output. The marker augmentation reads is in
`quarkus-extension.properties`:

```
deployment-artifact=com.basquin:basquin-quarkus-deployment:0.3.0
```

## How it was run

| File | What it is |
|---|---|
| `fixture-build.log` | the Maven consumer's full build, `clean package -DskipTests` |
| `app-startup.log` | the app's startup output, containing the banner |
| `quarkus-extension.properties` | the marker extracted from the built runtime jar |

1. Both modules published into the fixture's Maven repository. That repository is
   `bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository`, **not** `~/.m2`: the fixture builds in a
   container with `-Duser.home=/m2`, so publishing to `mavenLocal()` is invisible to it.
2. The Phase-0 fixture — a Maven-built Quarkus 3.37.3 app — declares
   `com.basquin:basquin-quarkus:0.3.0` and is built through `env/build.sh`.
3. The built app is run and its banner read.

**The build must be `clean package`.** A plain `package` fails: the fixture still carries spike S1's
JaCoCo offline-instrument plugin, and re-instrumenting an already-instrumented `target/classes` errors
with *"Cannot process instrumented class … Please supply original non-instrumented classes."* That is
a stale-`target` artefact of S1, unrelated to the extension — but worth recording, because the first
verification run hit it, **still produced a correct-looking banner from the previous build's
artifact**, and would have been mistaken for a passing result.

## What this establishes, and what it does not

**Establishes:** a Gradle-built `io.quarkus.extension` artifact at Quarkus 3.37.3 emits a valid
`quarkus-extension.properties` and is honoured by a Maven-built consumer's augmentation, in JVM mode.

**Does not establish:** anything about native mode (deliberately not run — irrelevant to the toolchain
question and slow), nor that the extension *does* anything. Its only content is a `@BuildStep`
producing `FeatureBuildItem("basquin")`. The boundary filter, result store, routes and control defect
routes are PR-2 proper.

**Known cosmetic quirk**, recorded in `settings.gradle`: the runtime project's Gradle name is `runtime`,
so `quarkus-extension.yaml`'s self-reported `name`/`artifact` fields say `runtime` rather than
`basquin-quarkus`. Renaming the Gradle project was tried and reverted — it also changes the project
path used by `project(':basquin-quarkus:runtime')` and broke the deployment module's dependency
resolution. Augmentation reads `deployment-artifact` from `quarkus-extension.properties`, which is set
explicitly and unaffected, so this is presentation only.

## Reproduce

```bash
./gradlew :basquin-quarkus:runtime:publishAllPublicationsToDd043SpikeRepository \
          :basquin-quarkus:deployment:publishAllPublicationsToDd043SpikeRepository
bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests
docker run --rm -d --name pr2-verify -p 8080:8080 --entrypoint java \
  -v "$PWD/bench-results/dd043-spikes-2026-07-24/fixture/target/quarkus-app":/app \
  quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5 \
  -jar /app/quarkus-run.jar
sleep 10 && docker logs pr2-verify | grep "Installed features"
docker rm -f pr2-verify
```
