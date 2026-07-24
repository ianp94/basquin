# DD-043 Phase-0 spikes — build environment

## Candidate chosen: A (Mandrel builder image)

`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`

**Why:** it is the only image tried that carries both JDK 25 and `native-image`
in one place. That means no Maven step ever runs on the JDK-17 host, and later
native builds need no `-v /var/run/docker.sock:/var/run/docker.sock` — the
image *is* the native toolchain, not a client of a sibling container. Pinned
via `IMAGE`/`BASQUIN_SPIKE_IMAGE` in `env/build.sh`; overridable per-invocation
if a later spike needs to.

`env/build.sh`'s `IMAGE` default is the digest below, not the `jdk-25` tag —
the tag is a moving target, so pinning it would let Tasks 3-5 build against a
different image than the one validated here, with nothing announcing the
drift. `BASQUIN_SPIKE_IMAGE` still overrides it.

Probe command and raw output: `env/probe-A.txt` (reproduced below).

```
$ docker run --rm --entrypoint bash quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
    -lc 'java -version 2>&1; native-image --version 2>&1'
openjdk version "25.0.3" 2026-04-21 LTS
OpenJDK Runtime Environment Temurin-25.0.3+9 (build 25.0.3+9-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.3+9 (build 25.0.3+9-LTS, mixed mode, sharing)
native-image 25.0.3 2026-04-21
OpenJDK Runtime Environment Mandrel-25.0.3.0-Final (build 25.0.3+9-LTS)
OpenJDK 64-Bit Server VM Mandrel-25.0.3.0-Final (build 25.0.3+9-LTS, mixed mode)
```

Both JDK 25 and `native-image` confirmed present — Candidate B (`maven:3.9-eclipse-temurin-25`
+ docker socket) was not needed and was not tried.

Digest actually pulled, and the digest `env/build.sh`'s `IMAGE` default now
pins (not merely recorded — since `jdk-25` is a moving tag, pinning it here
is what makes the build reproducible):
`sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5`.

## Extension short name

`-Dextensions=rest` (the brief's exact value) resolved on the first try —
the create goal reported `selected extensions: io.quarkus:quarkus-rest`. The
`quarkus-rest` / `resteasy-reactive` fallbacks in the brief were not needed.

## Deviations from the brief

The brief's Step 1–3 shell was written assuming the image behaves like a
generic Maven+JDK container. Two properties of the actual Mandrel builder
image required changes; both are called out inline in `env/build.sh`.

1. **The image's `ENTRYPOINT` is `native-image`, not a shell.** Running
   `docker run ... "$IMAGE" ./mvnw -B "$@"` as literally written hands mvnw's
   argv to `native-image` instead of executing it (confirmed: the first probe
   attempt without an entrypoint override failed with
   `Error: Unrecognized option(s): 'java -version ...'`, i.e. bash's own args
   were being parsed as `native-image` flags). Fix: every invocation —
   the fixture-generation `docker run` and `env/build.sh` — passes
   `--entrypoint bash` and uses the `bash -c 'script "$@"' bash "$@"` idiom so
   the caller's Maven args still land correctly inside the container.

2. **The image has no system `mvn`, only `java` + `native-image`** (confirmed:
   `find / -iname mvn` and `rpm -qa | grep maven` both came back empty; `curl`,
   `unzip`, `tar` are present). This matters only for the one-time archetype
   generation in Step 3, since there is no `mvnw` wrapper yet to fall back on
   at that point. Fix: that one `docker run` first downloads a throwaway
   Apache Maven 3.9.9 binary distribution (`archive.apache.org`, verified
   reachable) into `/tmp` inside the container and uses its `bin/mvn` to run
   the `quarkus-maven-plugin:3.37.3:create` goal — nothing is installed into
   the image or persisted outside the container. `env/build.sh` itself never
   needs a system `mvn`: from Step 5 onward it drives the fixture's own
   `./mvnw`, which needs only `java` (present) plus network access to
   self-provision the pinned Maven 3.9.16 distribution
   (`fixture/.mvn/wrapper/maven-wrapper.properties`).

3. **`$HOME` inside the container resolves to `/`, not the mounted cache.**
   `env/build.sh` runs the container as the host UID/GID
   (`-u "$(id -u):$(id -g)"`) for correct file ownership on the bind mount,
   but that UID has no `/etc/passwd` entry, so `$HOME` falls back to `/`.
   `mvnw` picks its wrapper-distribution cache directory from
   `${MAVEN_USER_HOME:-${HOME}/.m2}` — a shell-level lookup, independent of
   the JVM's `-Duser.home` system property — so it tried to
   `mkdir -p -- //.m2` and died with `Permission denied`. Fix: `build.sh` now
   also sets `-e HOME=/m2`, alongside the existing
   `MAVEN_OPTS=-Duser.home=/m2`.

4. **Generated `pom.xml` set `quarkus.platform.version` to `3.37.4`, not
   `3.37.3`.** The `:3.37.3:create` in the generation command only pins which
   version of the `quarkus-maven-plugin` *runs the create goal*; it does not
   pin the platform BOM version written into the new project's `pom.xml` —
   the plugin queries `registry.quarkus.io` for the current recommended
   platform version unless told otherwise. Per the binding "Quarkus version is
   exactly 3.37.3" constraint, `quarkus.platform.version` was hand-edited from
   `3.37.4` to `3.37.3` in `fixture/pom.xml` after generation (single property,
   every other version reference in the pom derives from it).
   `maven.compiler.release` was already `25` from the archetype defaults, so
   ambiguity #4's override was not needed.

5. **Flattening `fixture/fixture/` → `fixture/` needed to include dotfiles.**
   The brief's `mv fixture/fixture/* fixture/fixture/.mvn fixture/fixture/mvnw* fixture/`
   does not move `.dockerignore`/`.gitignore` (bash's bare `*` glob does not
   match dotfiles, and those two files weren't in the explicit list), which
   would have left `fixture/fixture/` non-empty and the `rmdir` failing
   silently. Used `shopt -s dotglob nullglob` before the `mv` instead, then
   confirmed the flattened layout below.

## Final fixture layout (confirms no `fixture/fixture/` nesting)

```
fixture/
├── .dockerignore
├── .gitignore
├── .mvn/wrapper/...
├── README.md
├── mvnw
├── mvnw.cmd
├── pom.xml
└── src/main/{java/com/basquin/spike/{Probe.java,NeverCalled.java},resources,docker}
```

## Build result (Step 5 — the definition of done)

```
$ bench-results/dd043-spikes-2026-07-24/env/build.sh package -DskipTests
...
[INFO] --- quarkus:3.37.3:build (default-build) @ fixture ---
[INFO] [io.quarkus.deployment.QuarkusAugmentor] Quarkus augmentation completed in 10180ms
[INFO] BUILD SUCCESS
```

`fixture/target/quarkus-app/quarkus-run.jar` exists. Full log: `env/build-jvm.log`.
Compiler invocation confirms the toolchain actually used:
`javac [debug parameters release 25] to target/classes` (`env/build-jvm.log:683`).

Smoke-tested beyond the definition of done (not required, but cheap): ran
`java -jar target/quarkus-app/quarkus-run.jar` inside the same image and
curled it — `/ok` → `200 ok`, `/boom` → `500` (deliberate `RuntimeException`,
logged), `/redirect` → follows to `/ok`. Startup log confirms
`fixture 1.0.0-SNAPSHOT on JVM (powered by Quarkus 3.37.3)`.

**This confirms BLOCKER 1's fix:** a Java-25 build runs to `BUILD SUCCESS` with
no Maven step ever touching the JDK-17 host.
