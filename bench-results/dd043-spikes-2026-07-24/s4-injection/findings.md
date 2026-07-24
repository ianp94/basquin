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

## Addendum: local-only artifact resolution

### The gap this closes

The result above injects `io.quarkus:quarkus-smallrye-openapi`, an artifact
already published to Maven Central. The real artifact spec §5/§5.2 need
injected (`com.basquin:basquin-quarkus`) will **not** be on Central, so the
CONFIRMED verdict above does not automatically transfer to it: if an
injected dependency only resolves when it is publicly published, §5 needs
an additional mechanism (injecting a `<repository>` declaration alongside
the dependency) that the spec currently does not mention.

### Question

Does an injected dependency that exists only in the build's local Maven
repository resolve during a containerized Quarkus build — with no change
to the fixture's `pom.xml` and no repository declaration injected?

### Method

1. **Installed a throwaway artifact under a groupId that certainly does not
   exist on Central**: `com.basquin.spike.localonly:local-probe-dep:1.0`.
   Confirmed absent from Central first (`curl` to
   `repo.maven.apache.org/maven2/com/basquin/spike/localonly/...` → `404`).
   Reused the existing `probe-participant/target/inject-probe-1.0.jar`
   bytes as the jar payload (per the brief — its contents don't matter for
   a pure resolution test), installed via `mvn install:install-file`
   **inside the `maven:3.9-eclipse-temurin-17` container** (never on the
   JDK-17 host), targeting the same `.m2` this spike's `env/build.sh`
   mounts at `/m2`. First attempt let `install-file` pull the jar's
   *embedded* pom (`META-INF/maven/.../pom.xml`, left over from when the
   same bytes were built as `com.basquin.spike:inject-probe:1.0`) — that
   produced a POM at the right repository path but with the wrong
   coordinates declared inside it, which would have muddied interpretation
   of any resolution failure. Re-ran with an explicit minimal
   `-DpomFile` stub declaring the correct
   `com.basquin.spike.localonly:local-probe-dep:1.0` GAV and no
   dependencies, so the installed artifact is a clean, coordinate-correct,
   dependency-free jar. Full command and output:
   `addendum-install.log`.

2. **Extended `InjectProbe.java`** (not a second participant — a system
   property gate on the existing one, so Task 3's original behaviour is
   preserved byte-for-byte): `-Dbasquin.inject.local=true` switches the
   injected dependency from `io.quarkus:quarkus-smallrye-openapi:3.37.3` to
   `com.basquin.spike.localonly:local-probe-dep:1.0`; with the property
   unset (Task 3's original invocation), the code path, the injected GAV,
   and the `[INJECT-PROBE] added quarkus-smallrye-openapi to fixture` log
   line are all unchanged. Rebuilt the probe jar in the same
   `maven:3.9-eclipse-temurin-17` container (`probe-rebuild.log`).

3. **Built the fixture through the unmodified `env/build.sh`**, with the
   probe jar mounted and both system properties on
   `EXTRA_MAVEN_OPTS`:

   ```
   EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe" \
   EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar -Dbasquin.inject.local=true" \
     env/build.sh package -DskipTests
   ```

   `fixture/pom.xml` was not touched, and no `<repository>` was declared
   anywhere — the entire point is that the target app's build stays
   untouched. Full transcript: `addendum-build.log`.

### Raw result

`addendum-build.log` line 2: participant ran —

```
[INJECT-PROBE] added local-probe-dep to fixture
```

No `Could not resolve dependencies`, `Could not find artifact`, `ERROR`, or
`WARN` anywhere in the 35-line log (`grep -in "ERROR\|Could not resolve\|
Could not find artifact\|WARN" addendum-build.log` → zero matches).
Quarkus augmentation ran and completed (`[io.quarkus.deployment.
QuarkusAugmentor] Quarkus augmentation completed in 10277ms`), and the
build finished:

```
[INFO] BUILD SUCCESS
[INFO] Total time:  44.136 s
```

Beyond the absence of an error, direct evidence the artifact reached the
packaged runtime classpath — not just that resolution silently no-opped:

```
$ find fixture/target/quarkus-app -iname "*local-probe*"
fixture/target/quarkus-app/lib/main/com.basquin.spike.localonly.local-probe-dep-1.0.jar
```

The jar is physically present in the augmented fast-jar's `lib/main/`
directory, Quarkus's packaged-runtime-dependency location.

### Verdict: CONFIRMED (for the local-repo case)

The local Maven repository — the one `env/build.sh` mounts at `/m2` — is
consulted for a dependency injected purely in memory by the Maven core
extension, exactly as it is for any normal, disk-declared `pom.xml`
dependency. No `<repository>` declaration, no publication anywhere, and no
change to the fixture's `pom.xml` were needed. **Installing the future
`com.basquin:basquin-quarkus` extension into the build's local repository
is a sufficient deployment path** for the injection mechanism spec §5/§5.2
describe — the repository-injection contingency this addendum was written
to test for turned out not to be needed, at least for plain dependency
resolution.

### What this does and does not establish

This tests *resolution* of a plain jar — that Maven's resolver (and, via
Task 3's already-established result, Quarkus's bootstrap resolver riding on
top of it) will find and package a dependency that exists only in the local
repository, with no repository declaration anywhere. It does **not** test
whether a locally-installed **Quarkus extension** is *discovered* by
augmentation: extension discovery works by scanning the classpath for
`META-INF/quarkus-extension.properties`, a mechanism this addendum's
`local-probe-dep` (a plain jar with no such marker) does not exercise. Task
3 already proved augmentation honours an injected dependency once resolved
(in both JVM and native mode, for a real Quarkus extension), so combining
that result with this one closes the specific gap this addendum targets —
whether resolution itself survives when the artifact isn't on Central — but
the two results were established separately, on two different jars, and
should be read as complementary rather than as one combined experiment.
