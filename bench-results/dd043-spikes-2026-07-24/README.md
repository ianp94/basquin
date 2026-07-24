# DD-043 Phase-0 spikes (2026-07-24)

Evidence-gathering spikes for extending Basquin's instrumentation to
GraalVM-native Quarkus apps. Task 1 (this directory's `env/` and `fixture/`)
is the shared build vehicle every later spike (S1 coverage, S2 memory, S3
boundary, S4 injection) depends on. See `env/ENVIRONMENT.md` for the full
image-selection rationale and every deviation from the plan.

## Layout

```
env/         the containerized build vehicle + its evidence
  build.sh          the single entrypoint every spike uses to run Maven
  ENVIRONMENT.md     which image won, why, and what had to change from the plan
  probe-A.txt        raw `java -version` / `native-image --version` from the chosen image
  build-jvm.log      full log of the Step 5 build (BUILD SUCCESS)
  generate-fixture.log  full log of the one-time fixture archetype generation
fixture/     the throwaway Quarkus 3.37.3 app the spikes probe
s1-coverage/ s2-memory/ s3-boundary/ s4-injection/   later spikes' evidence (empty for now)
```

## Re-running the build

Everything runs in the container in `env/build.sh`; no Maven step touches the
JDK-17 host.

```bash
cd bench-results/dd043-spikes-2026-07-24
env/build.sh package -DskipTests
```

Expect `BUILD SUCCESS` and `fixture/target/quarkus-app/quarkus-run.jar`. Any
Maven goal can follow the same pattern, e.g. a later native-mode spike would
run:

```bash
env/build.sh package -Dnative -DskipTests
```

`env/build.sh` reads two seams later spikes can use without editing the
script:

- `EXTRA_MAVEN_OPTS` — appended to `MAVEN_OPTS` inside the container (S4 uses
  this for `-Dmaven.ext.class.path`).
- `EXTRA_DOCKER_ARGS` — appended to the `docker run` invocation, e.g. to mount
  a probe jar.

## Regenerating the fixture from scratch

Not needed for normal use (the generated app is committed), but if
`fixture/` is deleted, regenerate it with:

```bash
cd bench-results/dd043-spikes-2026-07-24
mkdir -p fixture .m2
docker run --rm --entrypoint bash \
  -v "$PWD/fixture":/w -w /w \
  -v "$PWD/.m2":/m2 \
  -u "$(id -u):$(id -g)" \
  -e MAVEN_OPTS="-Duser.home=/m2" \
  quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  -c 'set -euo pipefail
      curl -sSL -o /tmp/maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
      tar xzf /tmp/maven.tar.gz -C /tmp
      /tmp/apache-maven-3.9.9/bin/mvn -B io.quarkus.platform:quarkus-maven-plugin:3.37.3:create \
        -DprojectGroupId=com.basquin.spike -DprojectArtifactId=fixture \
        -Dextensions=rest -DnoCode'
shopt -s dotglob nullglob
mv fixture/fixture/* fixture/
rmdir fixture/fixture
shopt -u dotglob nullglob
```

Unlike `env/build.sh`, this snippet does not need `-e HOME=/m2`: it invokes a
throwaway real `mvn` binary downloaded straight into `/tmp`, not the
`$HOME`-dependent `mvnw` wrapper (which doesn't exist yet at this point), so
the wrapper's cache-directory lookup that requires `HOME` never runs here.

Then, since the archetype's `create` goal resolves `quarkus.platform.version`
against the registry's current recommendation rather than the plugin
coordinate's version, re-pin it (this project requires exactly `3.37.3`):

```bash
sed -i 's/<quarkus.platform.version>.*<\/quarkus.platform.version>/<quarkus.platform.version>3.37.3<\/quarkus.platform.version>/' fixture/pom.xml
```

`maven.compiler.release` is already `25` from the archetype defaults; verify
it stayed that way (`grep -n maven.compiler.release fixture/pom.xml`) — the
point is reproducing the target toolchain, not the archetype's default.

Then re-add `fixture/src/main/java/com/basquin/spike/Probe.java` and
`NeverCalled.java` (see `env/ENVIRONMENT.md` or git history for the exact
contents) and re-run the build above.

## Routes the fixture exposes

- `GET /ok` → `200 ok`
- `GET /boom` → throws, `500`
- `GET /redirect` → `302` to `/ok`
- `GET /slow` → sleeps 5s, then `200 slow`
- `GET /alloc` → allocates and retains ~4MB, `200`

`com.basquin.spike.NeverCalled` is dead code no route reaches — S1 uses it to
assert coverage reads exactly zero for a class that's never exercised.
