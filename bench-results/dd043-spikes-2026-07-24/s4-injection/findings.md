# S4 — does Quarkus augmentation honour an injected dependency?

Question: when a Maven core extension (`-Dmaven.ext.class.path`, loaded via
`AbstractMavenLifecycleParticipant.afterProjectsRead`) mutates a
`MavenProject`'s in-memory model to add a dependency, does Quarkus's
bootstrap resolver see that dependency during augmentation — or does it
re-read `pom.xml` from disk and silently ignore the mutation? This is the
mechanism spec §5/§5.2 need for build-time instrumentation injection without
modifying application source.

## Method

`InjectProbe` (`probe-participant/src/main/java/com/basquin/spike/InjectProbe.java`,
verbatim from the brief) is a `@Named("basquin-inject-probe")` Maven
lifecycle participant. On `afterProjectsRead`, for every project in the
session it appends a `Dependency` for `io.quarkus:quarkus-smallrye-openapi:3.37.3`
to both `p.getModel().getDependencies()` and `p.getDependencies()`, then
prints `[INJECT-PROBE] added quarkus-smallrye-openapi to <artifactId>`.
`quarkus-smallrye-openapi` was chosen (per the brief) because it is absent
from the fixture and, if active, prints a distinctive feature name
(`smallrye-openapi`) in Quarkus's `Installed features:` startup banner.

Built into `probe-participant/target/inject-probe-1.0.jar` using the plain
`maven:3.9-eclipse-temurin-17` container (a separate, ordinary Java 17
project — this does not touch the JDK-25 fixture toolchain or the host JDK
17; see `probe-build.log`). The jar's `META-INF/sisu/javax.inject.Named`
index (auto-generated at compile time by the Sisu annotation processor
pulled in transitively via `maven-core`) confirms `@Named` will actually be
discoverable by Maven's Sisu/Guice component lookup when the jar is put on
`maven.ext.class.path` — not just that the class compiled.

Injection was done exactly as the brief's Step 4/5 specify: `EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe"`
mounts the probe jar into the build container, `EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar"`
puts it on Maven's core extension classpath, and `env/build.sh` (Task 1's
containerized build vehicle, unmodified) runs the actual build.

### Deviation from the brief: capturing the `Installed features:` banner

The brief's Step 3 pipes `env/build.sh package -DskipTests` output through
`grep -i "Installed features"`. That grep is against the **Maven build log**.
`Installed features:` is not a build-time log line at all — it is printed by
Quarkus at **application startup** (`io.quarkus.runtime.ApplicationLifecycleManager`,
logged when the app's `main()` runs), not during `quarkus:build`/augmentation.
This was confirmed two ways before proceeding: (1) `env/build-jvm.log` and
`s3-boundary/build.log` (both full transcripts of a prior `mvn package`, no
injection) contain zero occurrences of the string; (2) `s3-boundary/startup.log`,
which *does* contain it, was captured by Task 2 by actually running the built
jar (`docker run --entrypoint java -jar quarkus-run.jar`), not from a build
log. Running `env/build.sh package -DskipTests 2>&1 | grep -i "Installed features"`
here (baseline attempt, `build-baseline-full.log`) reproduced this: exit 0,
`BUILD SUCCESS`, zero matches, empty `banner-baseline.txt` if taken literally.

This is not a disagreement with the established baseline fact — it is the
same underlying fact (`s3-boundary/startup.log`'s banner) reached by a
different, necessary path. To get a real banner for every mode (baseline,
JVM+injected, native+injected) this spike ran the built artifact after each
build, exactly as the brief's own Step 5 already does for native mode, and
as Task 2/S3 did for JVM mode. Each run: start the artifact in a container
(JVM modes) or directly on the host (native mode, per ambiguity #2),
`curl localhost:8080/ok` to confirm it actually served a request, capture the
banner, stop/kill, confirm cleanup. Full startup transcripts are
`banner-baseline-run.log`, `banner-jvm-injected-run.log`, `banner-native-run.log`;
the extracted `Installed features:` lines are `banner-baseline.txt` /
`banner-jvm-injected.txt` / `banner-native.txt` (the three named in the
brief's definition of done, plus `banner-jvm-injected.txt` as supplementary
evidence for signal 2 in JVM mode, which the brief's literal grep-the-build-log
recipe cannot produce).

## Step 3 — baseline (no injection)

Fixture built via `env/build.sh package -DskipTests` (no `EXTRA_MAVEN_OPTS`),
then run as `docker run --entrypoint java -jar quarkus-run.jar` against the
same pinned Mandrel-builder image used for the build. Confirms Task 1/2's
already-established baseline exactly:

```
Installed features: [cdi, rest, smallrye-context-propagation, vertx]
```

`smallrye-openapi` absent — matches `s3-boundary/startup.log` verbatim. The
probe extension choice is valid (per Step 3's own acceptance criterion).

## Step 4 — JVM mode with injection (the core question)

```
EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar" \
  env/build.sh package -DskipTests
```

**Signal 1 — did the participant run?** Yes. `build-jvm-injected.log` line 2:

```
[INJECT-PROBE] added quarkus-smallrye-openapi to fixture
```

**Corroborating evidence beyond the banner:** immediately after that line,
Maven's dependency resolver downloads `quarkus-smallrye-openapi-3.37.3` and
its full transitive deployment-time closure — `quarkus-smallrye-openapi-deployment`,
`quarkus-smallrye-openapi-spi`, `quarkus-smallrye-openapi-common-deployment`,
`quarkus-swagger-ui`/`quarkus-swagger-ui-deployment`, `smallrye-open-api-core`/
`-model`/`-ui`/`-jaxrs`/`-spring`/`-vertx`, `microprofile-openapi-api`, and
their transitive poms/jars — none of which are declared anywhere in
`fixture/pom.xml` on disk. A resolver that re-read the pom from disk would
have no reason to resolve any of this. This alone is strong evidence the
resolver used the mutated in-memory model, and it appears *before* signal 2
is checked, so it is independent corroboration, not the same observation
counted twice. Build result: `BUILD SUCCESS` (`build-jvm-injected.log:141`).

**Signal 2 — did the banner list it?** Yes (`banner-jvm-injected.txt`,
from actually running the resulting `quarkus-run.jar`):

```
Installed features: [cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]
```

`smallrye-openapi` present, alongside every feature from the baseline. The
running jar also served `/ok` → `200` correctly (`banner-jvm-injected-run.log`).

**JVM verdict: CONFIRMED.** Both signals present. Augmentation used the
mutated in-memory Maven model, not a disk re-read of `pom.xml`.

## Step 5 — native mode with injection

Per ambiguity #3, JVM mode was fully settled (CONFIRMED, both signals) before
starting the native build, since a REFUTED JVM result would have made the
expensive native build moot.

```
EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar" \
  env/build.sh package -DskipTests -Dnative
```

Ran alone (no concurrent native build), took 2:52 total Maven wall time
(`build-native-injected.log`), native-image proper 45.3s. `BUILD SUCCESS`
(`build-native-injected.log:108`).

**Signal 1 — did the participant run?** Yes, `build-native-injected.log`
line 2 (identical to JVM mode — the participant runs once per Maven
invocation, before Quarkus's `-Dnative` packaging even begins):

```
[INJECT-PROBE] added quarkus-smallrye-openapi to fixture
```

**Signal 2 — did the banner list it?** Yes. The native binary
(`fixture/target/fixture-1.0.0-SNAPSHOT-runner`, a UBI9/Mandrel-built ELF
executable) was run **directly on this Ubuntu/WSL2 host**, per ambiguity #2
— no loader or glibc incompatibility; it started in 0.168s and served `/ok`
→ `200` on the first request (`banner-native-run.log`):

```
Installed features: [cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]
```

Identical feature set to the JVM+injected run. `banner-native.txt` is the
head -20 capture the brief's Step 5 specifies. Process was killed
immediately after the curl check; `ss -ltnp | grep 8080` and a repo-wide
`docker ps` confirm no stray process or container survived (no later task's
port 8080 is at risk).

**Native verdict: CONFIRMED.** Both signals present, and the binary itself
ran cleanly on the host (no environment-only failure to fall back to
INCONCLUSIVE on, per ambiguity #2).

## Summary table

| Mode | Signal 1 (`[INJECT-PROBE]` ran) | Signal 2 (`smallrye-openapi` in banner) | Verdict |
|---|---|---|---|
| JVM | yes (`build-jvm-injected.log:2`) | yes (`banner-jvm-injected.txt`) | CONFIRMED |
| Native | yes (`build-native-injected.log:2`) | yes (`banner-native.txt`) | CONFIRMED |

## Overall verdict: CONFIRMED

**Quarkus's bootstrap resolver builds its `ApplicationModel` from Maven's
in-memory `MavenProject`/`Model`, not by re-reading `pom.xml` from disk.** A
dependency injected purely in memory via a Maven core extension
(`-Dmaven.ext.class.path`, `afterProjectsRead`) is visible to `javac`
*and* to Quarkus augmentation, in both JVM and native packaging modes,
without any modification to the application's `pom.xml` on disk. The
failure mode this spike was built to catch — signal 1 present, signal 2
absent, i.e. a build that succeeds and silently produces an uninstrumented
artifact — was **not observed** in either mode.

## Spec implication

Spec §5.1's disk-re-read hypothesis is **REFUTED** (the opposite of what §5.1
worried about is what actually happens): the in-memory-model injection
mechanism a Maven core extension gives us is sufficient for §5/§5.2's
no-source-modification constraint, in both JVM and native builds, on exactly
the pinned Quarkus 3.37.3 / `maven.compiler.release=25` toolchain this
project requires. PR-3 can proceed on the design as specified — no
alternative mechanism (e.g. patching `pom.xml` on disk before the build,
which *would* violate no-source-modification, or a custom Quarkus extension
registered via `META-INF/services`) is needed to get an injected dependency
into the augmented output. One caveat worth carrying into the spec text: this
result is specific to dependencies resolvable from Maven Central with a
normal `groupId:artifactId:version` (this spike injects an already-published
extension); it says nothing about injecting a *locally-built, unpublished*
jar via the same mechanism, which would need either a local repository
mount or an install-to-local-repo step the current mechanism doesn't cover.
