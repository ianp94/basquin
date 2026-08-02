# DD-043 PR-3 — `basquin-maven-injector` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **How to read this document (added post-PR-#103, round 4):** this plan is a historical,
> already-executed record — its task steps narrate what was done, in the words used at the time,
> including code samples and commit-message templates that match what was actually committed. It is
> **not** kept in sync with the codebase as it evolves. Where a later review round found a step's
> claim stale, the original narration is left intact (do not rewrite history) and a bolded
> **"Amended — …"** paragraph is added immediately after it, pointing at the corrected fact and its
> current source. An unamended sentence is not thereby guaranteed current — it may simply be a claim
> no review round has re-checked yet. The authoritative current state of the code and its guards lives
> in `BasquinInjector.java`'s own Javadoc, `docs/THIRD-PARTY-APPS.md`, the design spec, and the cited
> `bench-results/` evidence directories, never in this plan's prose.

**Goal:** Ship a Maven core extension that adds Basquin's instrumentation to a Quarkus application at build time with **zero edits to any file in the application tree**.

**Architecture:** A jar containing one `AbstractMavenLifecycleParticipant`, activated by
`-Dmaven.ext.class.path`. Its `afterProjectsRead` mutates every `MavenProject` in the reactor to add
(a) the `com.basquin:basquin-quarkus` dependency and (b) the repository that makes it resolvable.
The repository injection is **two-level** — a `Repository` on the `Model` is a no-op on its own; the
effective list must also be refreshed via `setRemoteArtifactRepositories(...)`. Both halves are
measured, not assumed: S4 for the dependency, S5 for the repository.

**Tech Stack:** Java 17 · Gradle · `maven-core` 3.9.6 (`compileOnly`) · JSR-330 (`javax.inject`) ·
JUnit 4.13.2 with `useJUnit()` · Quarkus 3.37.3 targets built through containerised Maven.

## Global Constraints

- **The target application's source is never modified.** No file in the app tree is created, edited,
  or deleted. This is the property PR-3 exists to demonstrate; a task that "passes" by editing a pom
  has failed.
- **The extension chain is three artifacts**, all of which must resolve: `com.basquin:basquin-core`,
  `com.basquin:basquin-quarkus`, `com.basquin:basquin-quarkus-deployment`, all at version `0.3.0`.
- **Injecting a `Repository` into the `Model` alone does nothing.** `afterProjectsRead` runs *after*
  effective repository lists are computed. The participant MUST also append a
  `MavenArtifactRepository` to `getRemoteArtifactRepositories()` and call
  `setRemoteArtifactRepositories(...)`, whose maven-core 3.9.16 implementation refreshes the Aether
  `remoteProjectRepositories` list that Maven resolution and `quarkus-maven-plugin` actually consume.
  Verified: `bench-results/dd043-s5-repo-injection-2026-07-26/findings.md`.
- **Allocate every Maven model object fresh inside the per-project loop** (`Dependency`, `Repository`,
  `ArtifactRepository`). Model objects are mutable; hoisting an allocation aliases one instance across
  the whole reactor. **This passes every single-module test** — which is why it is a written constraint
  rather than implementation taste.
- **Fail loudly, never silently** (spec §5.1). A build that succeeds and produces an uninstrumented
  binary is the failure mode this whole design exists to prevent.
- **Acceptance is the banner, not "the participant ran"** (spec §5.2): the built artifact's startup
  output lists `basquin` under `Installed features`.
- **Version `0.3.0`** across all modules; the injector's default injected version is **baked from
  `project.version` at build time**, never typed into Java source.
- Java **17** bytecode (`sourceCompatibility`/`targetCompatibility`), so the participant loads in
  target builds running JDK 17 through 25.
- Only the human merges. Do not merge any PR.

---

## File Structure

| File | Responsibility |
|---|---|
| `.github/workflows/ci.yml` | **Modify.** Path filters currently omit `basquin-quarkus/**`; add it and `basquin-maven-injector/**`, or these modules' tests never run in CI. |
| `settings.gradle` | **Modify.** `include 'basquin-maven-injector'`. |
| `basquin-maven-injector/build.gradle` | **Create.** Java + `maven-publish`; `maven-core` and `javax.inject` as `compileOnly`; bakes `version` into a resource. |
| `basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java` | **Create.** The participant. Only class with `afterProjectsRead`. |
| `basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named` | **Create.** The Sisu index naming `BasquinInjector`. **Maven discovers core extensions through this file, not by scanning `@Named`** — both measured spike jars contain one. Gradle will not generate it (processors run only from `annotationProcessor`), and without it the build succeeds, the tests pass, and the participant silently never runs. |
| `basquin-maven-injector/src/main/java/com/basquin/maven/InjectorVersion.java` | **Create.** Reads the baked version resource. Single responsibility so the participant needn't know about resource loading. |
| `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorTest.java` | **Create.** Injection behaviour: both halves, per-project freshness, multi-module reactor. |
| `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorGuardsTest.java` | **Create.** Operator guards: fail-loudly, idempotence, skip. |
| `basquin-quarkus/runtime/build.gradle`, `basquin-quarkus/deployment/build.gradle` | **Modify.** Add the `pages` publish target — today only `basquin-core` has one. |
| `.github/workflows/release.yml` | **Modify.** Publish the three extension-chain artifacts (Task 2) and the injector itself (Task 3 Step 2c, once the module exists); extend the version-vs-tag assertion to all four. |
| `basquin-init.gradle` | **Create.** Gradle-side equivalent of the participant, per spec §5. |
| `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md` | **Modify.** S5's four amendments + §8.1's resolution. |
| `docs/THIRD-PARTY-APPS.md`, `docs/ARCHITECTURE.md` | **Modify.** Build-time injection section; build-vs-runtime symmetry. |

---

### Task 1: Close the CI path-filter gap — **DONE (commit `ad435e0`)**

A guard that cannot run is not a guard. `ci.yml`'s `paths:` filters listed `agent/**`,
`basquin-core/**`, `runner/**`, `examples/**`, `test/**`, `native/**`, `tomcat-war/**`,
`tomcat-valve/**`, `build.gradle`, `settings.gradle`, `gradle/**`, `gradlew`, `gradlew.bat`,
`deploy/bench/**` — and **not** `basquin-quarkus/**`. PR #102's CI ran only because it incidentally
touched `basquin-core/build.gradle`, `settings.gradle` and `test/**`. A change confined to
`basquin-quarkus/**` ran no CI at all — the same defect PR-1 shipped when the extracted core fell
outside every filter.

**Status: completed on this branch before the other tasks began**, because every later task's tests
are worthless in CI until it is. Both `push` and `pull_request` blocks now carry
`basquin-quarkus/**` and `basquin-maven-injector/**` (16 paths each, verified by parsing the YAML).

> **Note for anyone re-reading this plan:** round 1's review flagged this task as "already done on
> main, the plan asserts a stale gap". That was an artifact of the review running concurrently with
> commit `ad435e0` — the reviewer read the branch working tree after the fix landed. `main` at that
> moment had **zero** occurrences (`git show main:.github/workflows/ci.yml | grep -c 'basquin-quarkus/\*\*'`
> → `0`). The gap was real and this task closed it; nothing here needs re-doing.

**Files:**
- Modified: `.github/workflows/ci.yml` (both the `push` and `pull_request` `paths:` blocks)

**Interfaces:**
- Consumes: nothing.
- Produces: CI coverage for `basquin-quarkus/**` and `basquin-maven-injector/**`, which every later
  task depends on for its tests to mean anything in CI.

- [x] **Step 1: Verified the gap existed** — `grep -c "basquin-quarkus" .github/workflows/ci.yml`
      returned `0` before the change.
- [x] **Step 2: Added both directories to both `paths:` blocks**, each with a comment recording why.
- [x] **Step 3: Verified both blocks changed** — `grep -c "basquin-quarkus/\*\*"` returns `2`.
- [x] **Step 4: Verified the YAML parses and both triggers carry both entries** — 16 paths on each.
- [x] **Step 5: Committed** as `ad435e0`.

---

### Task 2: Publish the whole extension chain to the Pages Maven repo

Spike S5 surfaced this gap: only `basquin-core` has a `pages` publish target. `basquin-quarkus` and
`basquin-quarkus-deployment` publish solely to `dd043Spike` (a local directory). The injector's default
repository URL is the Pages repo, so **all three** must be there or the zero-configuration story is
fiction.

**Files:**
- Modify: `basquin-quarkus/runtime/build.gradle`, `basquin-quarkus/deployment/build.gradle`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: `publishAllPublicationsToPagesRepository` on all three modules; the
  `basquinPagesDir` Gradle property (default `docs/maven`) allowing a test publish to a scratch dir.

- [ ] **Step 1: Add the `pages` target to both Quarkus modules**

In **both** `basquin-quarkus/runtime/build.gradle` and `basquin-quarkus/deployment/build.gradle`,
inside the existing `publishing { repositories { … } }` block, add this **above** the existing
`dd043Spike` entry:

```gradle
        maven {
            // DD-043 PR-3: the injector's default repository URL is the Pages repo, so the whole
            // extension chain must live there — not just basquin-core. Spike S5 surfaced this gap:
            // resolution needs basquin-quarkus, basquin-quarkus-deployment AND basquin-core, and the
            // deployment artifact is named only by the runtime jar's quarkus-extension.properties,
            // so nothing in a consumer's pom would ever pull it from elsewhere.
            // basquinPagesDir is overridable so a test publish need not write into docs/maven.
            name = 'pages'
            url = rootProject.layout.projectDirectory.dir(
                    providers.gradleProperty('basquinPagesDir').getOrElse('docs/maven'))
        }
```

- [ ] **Step 2: Make `basquin-core`'s existing target honour the same property**

In `basquin-core/build.gradle`, replace the `pages` repository's `url` line with the overridable form
so a scratch publish covers all three consistently:

```gradle
            url = rootProject.layout.projectDirectory.dir(
                    providers.gradleProperty('basquinPagesDir').getOrElse('docs/maven'))
```

- [ ] **Step 3: Publish all three to a scratch directory and assert the full closure landed**

```bash
rm -rf build/tmp/pages-test
./gradlew -q --no-daemon -PbasquinPagesDir=build/tmp/pages-test \
  :basquin-core:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository
for a in basquin-core basquin-quarkus basquin-quarkus-deployment; do
  test -f "build/tmp/pages-test/com/basquin/$a/0.3.0/$a-0.3.0.pom" \
    && test -f "build/tmp/pages-test/com/basquin/$a/0.3.0/$a-0.3.0.jar" \
    && echo "OK  $a" || { echo "MISSING $a"; exit 1; }
done
```

Expected: three `OK` lines. Confirm `docs/maven` was **not** written:

```bash
git status --porcelain docs/maven
```

Expected: empty output.

- [ ] **Step 4: Extend the release workflow to publish all three**

In `.github/workflows/release.yml`, replace the single-module publish step:

```yaml
      - name: Publish basquin-core into the Pages Maven repo
        run: ./gradlew :basquin-core:publishAllPublicationsToPagesRepository --no-daemon
```

with:

```yaml
      # DD-043 PR-3: the injector resolves the whole extension chain from this repo, so all three
      # artifacts ship together. Publishing only basquin-core would leave an injected build failing
      # to resolve basquin-quarkus — loudly, but only at a user's build rather than here.
      - name: Publish the extension chain into the Pages Maven repo
        run: |
          ./gradlew --no-daemon \
            :basquin-core:publishAllPublicationsToPagesRepository \
            :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
            :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository
      - name: Assert the extension chain reached docs/maven
        run: |
          set -euo pipefail
          TAG='${{ steps.v.outputs.tag }}'
          for a in basquin-core basquin-quarkus basquin-quarkus-deployment; do
            f="docs/maven/com/basquin/$a/$TAG/$a-$TAG.jar"
            [ -f "$f" ] || { echo "::error::$f missing after publish"; exit 1; }
          done
          echo "extension chain present in docs/maven"
```

The version comes from the resolved tag rather than a hardcoded `0.3.0`.

**A fourth artifact — the injector itself — joins this list in Task 3 Step 2c**, where the module is
created. It is deliberately not added here: `:basquin-maven-injector` does not exist yet, and a task
whose verification cannot run until a later task has landed is not independently reviewable.

- [ ] **Step 5: Extend the version-vs-tag assertion to all three modules**

The existing step asserts only `basquin-core`'s version. Replace its body with a loop:

```yaml
      - name: Assert every published module's version matches the release tag
        run: |
          set -euo pipefail
          TAG='${{ steps.v.outputs.tag }}'
          fail=0
          for p in :basquin-core :basquin-quarkus:runtime :basquin-quarkus:deployment; do
            V="$(./gradlew -q "$p:properties" --no-daemon | awk -F': ' '/^version:/{print $2}')"
            echo "$p version=$V  release tag=$TAG"
            if [ "$V" != "$TAG" ]; then
              echo "::error::$p has version '$V' but this release is '$TAG'. Bump it in the release" \
                   "commit, or the Pages Maven repo will advertise the wrong coordinate."
              fail=1
            fi
          done
          [ "$fail" -eq 0 ]
```

- [ ] **Step 6: Verify the workflow parses and the loop covers three modules**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"
grep -c "publishAllPublicationsToPagesRepository" .github/workflows/release.yml
```

Expected: `YAML OK`, then `3`.

- [ ] **Step 7: Commit**

```bash
git add basquin-core/build.gradle basquin-quarkus/runtime/build.gradle \
        basquin-quarkus/deployment/build.gradle .github/workflows/release.yml
git commit -m "build: publish the whole extension chain to the Pages Maven repo

Spike S5 surfaced the gap: only basquin-core had a pages target, but resolving an
injected build needs basquin-quarkus and basquin-quarkus-deployment too, and nothing
in a consumer pom ever names the deployment artifact. Version assertion now covers
all three, so a release commit that bumps one and forgets another fails here."
```

---

### Task 3: The injector — module, participant, and both injection halves

**Files:**
- Modify: `settings.gradle`
- Create: `basquin-maven-injector/build.gradle`
- Create: `basquin-maven-injector/src/main/java/com/basquin/maven/InjectorVersion.java`
- Create: `basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java`
- Test: `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `com.basquin.maven.BasquinInjector` — `@Named("basquin-injector") @Singleton`, extends
    `AbstractMavenLifecycleParticipant`.
  - `void BasquinInjector.inject(List<MavenProject> projects, Properties props) throws MavenExecutionException`
    — package-private seam the tests drive; `afterProjectsRead` delegates to it. Task 4 adds guards
    inside this same method.
  - Constants used by Task 4's tests: `BasquinInjector.GROUP_ID` (`"com.basquin"`),
    `ARTIFACT_ID` (`"basquin-quarkus"`), `REPO_ID` (`"basquin-injected"`),
    `DEFAULT_REPO_URL` (`"https://ianp94.github.io/basquin/maven/"`),
    `PROP_SKIP` (`"basquin.inject.skip"`), `PROP_REPO_URL` (`"basquin.inject.repo.url"`),
    `PROP_VERSION` (`"basquin.inject.version"`).
  - `String InjectorVersion.value()` — the baked version.

**Why a `List<MavenProject>` seam rather than testing `afterProjectsRead` directly:** constructing a
real `MavenSession` needs a Plexus container, a repository session and a full `MavenExecutionRequest`.
`session.getProjects()` is the only thing the participant reads from it, so the seam costs nothing and
makes every behaviour below unit-testable. `new MavenProject()` yields a project with a live empty
`Model`, which is all the tests need.

- [ ] **Step 1: Register the module**

In `settings.gradle`, append:

```gradle
// DD-043 PR-3: the Maven core extension that injects basquin-quarkus into a target application's
// build with zero edits to its source (-Dmaven.ext.class.path). Deliberately a plain jar with no
// runtime dependencies — it is loaded by the TARGET's Maven, so anything it needed would have to be
// on that classpath too.
include 'basquin-maven-injector'
```

- [ ] **Step 2: Create `basquin-maven-injector/build.gradle`**

```gradle
// DD-043 PR-3 — the Maven core extension (AbstractMavenLifecycleParticipant) that adds Basquin's
// instrumentation to a Quarkus application at build time, with zero edits to the application tree.
//
// Loaded into the TARGET application's Maven via -Dmaven.ext.class.path, which means:
//   * maven-core and javax.inject are compileOnly — the target's Maven supplies them, and shipping
//     our own copies would risk shadowing the host Maven's classes.
//   * no runtime dependencies at all, so the jar can be put on that classpath by itself.
plugins {
    id 'java'
    id 'maven-publish'
}

group = 'com.basquin'
version = '0.3.0'
sourceCompatibility = '17'
targetCompatibility = '17'

repositories { mavenCentral() }

dependencies {
    // 3.9.6 is what both spike participants COMPILED against (their poms); they then EXECUTED
    // correctly under the wrapper's Maven 3.9.16, which is the actual evidence this pin rests on.
    compileOnly 'org.apache.maven:maven-core:3.9.6'
    // DefaultRepositoryLayout lives in maven-compat, not maven-core. S5's probe pom carries the
    // verified note that the Maven 3.9.16 distribution ships maven-compat in lib/, so it is on the
    // core classrealm that -Dmaven.ext.class.path extends — compileOnly is correct for the same
    // reason it is correct for maven-core: the host Maven supplies it, and shipping our own copy
    // would risk shadowing the host's classes.
    compileOnly 'org.apache.maven:maven-compat:3.9.6'
    compileOnly 'javax.inject:javax.inject:1'

    testImplementation 'org.apache.maven:maven-core:3.9.6'
    testImplementation 'org.apache.maven:maven-compat:3.9.6'
    testImplementation 'javax.inject:javax.inject:1'
    testImplementation 'junit:junit:4.13.2'
}

test {
    useJUnit()
}

jar {
    archiveBaseName = 'basquin-maven-injector'
}

// The injected dependency's version is the injector's OWN version, baked in at build time rather
// than typed into Java source. A hand-typed constant drifts from `version` above the first time one
// is bumped without the other, and the symptom would be a resolution failure in someone else's build.
def versionResourceDir = layout.buildDirectory.dir('generated/basquin-version')
def writeVersionResource = tasks.register('writeVersionResource', WriteProperties) {
    destinationFile = versionResourceDir.map { it.file('basquin-injector.properties') }
    property 'version', project.version.toString()
}
sourceSets.main.output.dir(versionResourceDir, builtBy: writeVersionResource)

java {
    withSourcesJar()
}

publishing {
    publications {
        injector(MavenPublication) {
            from components.java
            pom {
                name = 'basquin-maven-injector'
                description = 'Maven core extension that injects the Basquin Quarkus extension into ' +
                        'a target application build without modifying its source.'
                url = 'https://github.com/ianp94/basquin'
            }
        }
    }
    repositories {
        maven {
            name = 'pages'
            url = rootProject.layout.projectDirectory.dir(
                    providers.gradleProperty('basquinPagesDir').getOrElse('docs/maven'))
        }
    }
}
```

- [ ] **Step 2b: Create the Sisu index — without it the injector is never discovered**

Create `basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named` containing
exactly one line:

```
com.basquin.maven.BasquinInjector
```

**Why this file decides whether any of this works.** Maven's container discovers components on
`maven.ext.class.path` through this index, not by scanning for `@Named`. S4's findings name it
explicitly as the mechanism ("confirms `@Named` will actually be discoverable by Maven's Sisu/Guice
component lookup … not just that the class compiled"), and both measured probe jars contain it —
S5's holds the single line `com.basquin.spike.RepoInjectProbe`.

Those jars got it for free because they were built by **Maven**, which runs annotation processors it
finds on the compile classpath. **Gradle runs processors only from the `annotationProcessor`
configuration**, and this module's Maven artifacts are `compileOnly`. So a Gradle-built jar carries
no index, Maven never instantiates `BasquinInjector`, `afterProjectsRead` never fires — and the
build succeeds, producing an **uninstrumented** application. That is spec §5.1's silent-uninstrumented
failure reproduced inside PR-3's own deliverable, and every unit test in Tasks 3–5 still passes,
because they drive `inject(...)` directly and deliberately bypass container wiring.

A static resource is used rather than `annotationProcessor 'org.eclipse.sisu:org.eclipse.sisu.inject'`
because the static file is byte-identical to what the measured jars contain, whereas the processor
route adds a moving part no spike exercised.

Put this comment at the top of the participant class so the file is never tidied away as inert:

```java
 * <p><b>Discovery depends on {@code src/main/resources/META-INF/sisu/javax.inject.Named}</b>, which
 * must name this class. Maven's container finds components on {@code maven.ext.class.path} through
 * that index rather than by scanning for {@code @Named}; both measured spike probes carry one
 * (S4 findings, Method section). Delete it and this participant silently never runs — the build
 * still succeeds and the application ships uninstrumented.
```

- [ ] **Step 2c: Add the injector to the release publish, so operators can obtain the jar**

Task 2 wired three artifacts into `.github/workflows/release.yml`. Now that the module exists, add the
fourth. In the publish step append the line:

```yaml
            :basquin-maven-injector:publishAllPublicationsToPagesRepository
```

then add `basquin-maven-injector` to that step's `for a in …` assertion list, and
`:basquin-maven-injector` to the version-vs-tag loop's project list.

Without this the operator documentation Task 8 writes — "one command, nothing else" — carries an
unstated build-from-source prerequisite: the jar an operator puts on `maven.ext.class.path` would
exist nowhere they could fetch it.

```bash
grep -c "publishAllPublicationsToPagesRepository" .github/workflows/release.yml
```

Expected: `4`.

- [ ] **Step 3: Create `InjectorVersion.java`**

```java
package com.basquin.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The injector's own version, read from a resource Gradle writes from {@code project.version}.
 *
 * <p>Deliberately not a string literal in Java source. The version appears in three places that must
 * agree — this module's {@code version}, the coordinate injected into target builds, and the Pages
 * repository layout — and a literal drifts from the build file the first time one is bumped without
 * the other. The symptom would surface as an unresolvable dependency in somebody else's build.
 */
final class InjectorVersion {

    private static final String RESOURCE = "/basquin-injector.properties";

    private InjectorVersion() {
    }

    static String value() {
        try (InputStream in = InjectorVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the injector jar; "
                        + "it is generated by the writeVersionResource Gradle task");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            if (v == null || v.isBlank()) {
                throw new IllegalStateException(RESOURCE + " has no 'version' property");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
```

- [ ] **Step 4: Write the failing tests**

Create `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorTest.java`:

```java
package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

/**
 * DD-043 PR-3. Drives {@link BasquinInjector#inject(List, Properties)} directly rather than
 * {@code afterProjectsRead}: a real {@code MavenSession} needs a Plexus container, a repository
 * session and a full execution request, while {@code getProjects()} is the only thing the
 * participant reads from it.
 */
public class BasquinInjectorTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static MavenProject project(String artifactId) {
        MavenProject p = new MavenProject();
        p.setArtifactId(artifactId);
        p.setRemoteArtifactRepositories(new ArrayList<>());
        return p;
    }

    @Test
    public void injectsTheDependencyIntoEveryProjectInTheReactor() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (MavenProject p : reactor) {
            List<Dependency> deps = p.getModel().getDependencies();
            assertEquals("exactly one dependency injected into " + p.getArtifactId(), 1, deps.size());
            assertEquals(BasquinInjector.GROUP_ID, deps.get(0).getGroupId());
            assertEquals(BasquinInjector.ARTIFACT_ID, deps.get(0).getArtifactId());
        }
    }

    /**
     * The version injected is the injector's own, baked from {@code project.version} — never a
     * literal in Java source.
     */
    @Test
    public void injectedVersionIsTheInjectorsOwnBakedVersion() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        assertEquals(InjectorVersion.value(), p.getModel().getDependencies().get(0).getVersion());
    }

    /**
     * The load-bearing half. A {@code Repository} on the Model alone is a no-op at
     * {@code afterProjectsRead} — effective repository lists are already computed. The participant
     * must also refresh {@code getRemoteArtifactRepositories()}, which is what Maven resolution and
     * quarkus-maven-plugin consume. Measured in spike S5.
     */
    @Test
    public void injectsTheRepositoryAtBothTheModelAndEffectiveListLevels() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        List<Repository> modelRepos = p.getModel().getRepositories();
        assertEquals(1, modelRepos.size());
        assertEquals(BasquinInjector.REPO_ID, modelRepos.get(0).getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, modelRepos.get(0).getUrl());

        List<ArtifactRepository> effective = p.getRemoteArtifactRepositories();
        assertEquals("the effective list is the half that actually resolves", 1, effective.size());
        assertEquals(BasquinInjector.REPO_ID, effective.get(0).getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, effective.get(0).getUrl());
    }

    @Test
    public void repositoryUrlIsOverridable() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p),
                props(BasquinInjector.PROP_REPO_URL, "http://localhost:8000/"));

        assertEquals("http://localhost:8000/", p.getModel().getRepositories().get(0).getUrl());
        assertEquals("http://localhost:8000/", p.getRemoteArtifactRepositories().get(0).getUrl());
    }

    @Test
    public void injectedVersionIsOverridable() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props(BasquinInjector.PROP_VERSION, "9.9.9"));

        assertEquals("9.9.9", p.getModel().getDependencies().get(0).getVersion());
    }

    /**
     * Maven's model objects are mutable, so one hoisted allocation would alias a single instance
     * across the whole reactor and a later in-place mutation on one module would bleed into all the
     * others. A single-module reactor cannot catch this, which is exactly why the test uses three.
     */
    @Test
    public void allocatesFreshModelObjectsPerProject() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (int i = 0; i < reactor.size(); i++) {
            for (int j = i + 1; j < reactor.size(); j++) {
                assertNotSame("Dependency aliased across projects",
                        reactor.get(i).getModel().getDependencies().get(0),
                        reactor.get(j).getModel().getDependencies().get(0));
                assertNotSame("Repository aliased across projects",
                        reactor.get(i).getModel().getRepositories().get(0),
                        reactor.get(j).getModel().getRepositories().get(0));
                assertNotSame("ArtifactRepository aliased across projects",
                        reactor.get(i).getRemoteArtifactRepositories().get(0),
                        reactor.get(j).getRemoteArtifactRepositories().get(0));
            }
        }
    }

    /** Pre-existing repositories must survive — we append, never replace. */
    @Test
    public void preservesRepositoriesTheProjectAlreadyHad() throws Exception {
        MavenProject p = project("a");
        ArtifactRepository central = new org.apache.maven.artifact.repository.MavenArtifactRepository(
                "central", "https://repo.maven.apache.org/maven2",
                new org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout(), null, null);
        p.setRemoteArtifactRepositories(new ArrayList<>(Arrays.asList(central)));

        new BasquinInjector().inject(Arrays.asList(p), props());

        List<ArtifactRepository> effective = p.getRemoteArtifactRepositories();
        assertEquals(2, effective.size());
        assertSame("the project's own repository must not be dropped", central, effective.get(0));
        assertTrue("ours is appended after", BasquinInjector.REPO_ID.equals(effective.get(1).getId()));
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

```bash
./gradlew :basquin-maven-injector:test --no-daemon
```

Expected: FAIL — compilation error, `BasquinInjector` does not exist.

- [ ] **Step 6: Create `BasquinInjector.java`**

```java
package com.basquin.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.project.MavenProject;

/**
 * DD-043 PR-3 — adds the Basquin Quarkus extension to a target application's build without editing
 * a single file in the application tree.
 *
 * <p>Activated by putting this jar on {@code -Dmaven.ext.class.path}. Maven loads it as a core
 * extension and calls {@link #afterProjectsRead} once, before per-project execution plans are
 * computed.
 *
 * <p><b>Two injections, and the second has a trap.</b> Adding the {@code <dependency>} is the
 * obvious half and was measured by spike S4. Making it <i>resolvable</i> is the other half, measured
 * by spike S5, and a {@link Repository} added to the {@link org.apache.maven.model.Model} alone
 * does nothing: by {@code afterProjectsRead} the project's effective repository lists have already
 * been computed during project building. The participant must additionally call
 * {@link MavenProject#setRemoteArtifactRepositories}, whose implementation refreshes the Aether
 * {@code remoteProjectRepositories} list that both Maven's dependency resolution and
 * quarkus-maven-plugin's {@code ${project.remoteProjectRepositories}} parameter actually read.
 * Omitting it yields a build that fails to resolve — or, with a populated local repository, one that
 * silently succeeds for the wrong reason.
 *
 * <p>Evidence: {@code bench-results/dd043-spikes-2026-07-24/s4-injection/} and
 * {@code bench-results/dd043-s5-repo-injection-2026-07-26/}.
 */
@Named("basquin-injector")
@Singleton
public class BasquinInjector extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "com.basquin";
    static final String ARTIFACT_ID = "basquin-quarkus";
    static final String REPO_ID = "basquin-injected";
    static final String DEFAULT_REPO_URL = "https://ianp94.github.io/basquin/maven/";

    static final String PROP_SKIP = "basquin.inject.skip";
    static final String PROP_REPO_URL = "basquin.inject.repo.url";
    static final String PROP_VERSION = "basquin.inject.version";

    private static final String LOG = "[basquin-injector] ";

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        inject(session.getProjects(), System.getProperties());
    }

    /**
     * The testable seam. {@code getProjects()} is the only thing read from the session, so taking the
     * list directly keeps every behaviour unit-testable without standing up a Plexus container.
     */
    void inject(List<MavenProject> projects, Properties props) throws MavenExecutionException {
        String version = orDefault(props.getProperty(PROP_VERSION), InjectorVersion.value());
        String url = orDefault(props.getProperty(PROP_REPO_URL), DEFAULT_REPO_URL);

        for (MavenProject p : projects) {
            // Every model object below is allocated fresh inside this loop. Maven's model objects are
            // mutable; hoisting an allocation would alias one instance across the whole reactor, so a
            // later in-place mutation on one module would bleed into all the others. A single-module
            // build cannot detect that, which is why this is a written constraint and not taste.
            addDependency(p, version);
            addRepository(p, url);
            System.out.println(LOG + "instrumented " + p.getArtifactId()
                    + " (" + GROUP_ID + ":" + ARTIFACT_ID + ":" + version + " from " + url + ")");
        }
    }

    private void addDependency(MavenProject p, String version) {
        Dependency d = new Dependency();
        d.setGroupId(GROUP_ID);
        d.setArtifactId(ARTIFACT_ID);
        d.setVersion(version);
        // MavenProject.getDependencies() delegates to getModel().getDependencies(), so adding to both
        // would add the same object twice to one list.
        p.getModel().getDependencies().add(d);
    }

    private void addRepository(MavenProject p, String url) {
        Repository r = new Repository();
        r.setId(REPO_ID);
        r.setUrl(url);
        RepositoryPolicy releases = new RepositoryPolicy();
        releases.setEnabled(true);
        RepositoryPolicy snapshots = new RepositoryPolicy();
        snapshots.setEnabled(false);
        r.setReleases(releases);
        r.setSnapshots(snapshots);
        p.getModel().getRepositories().add(r);

        // The load-bearing half — see the class comment. Without this the model entry above is inert.
        ArtifactRepository ar = new MavenArtifactRepository(
                REPO_ID,
                url,
                new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(false,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
                new ArtifactRepositoryPolicy(true,
                        ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));
        List<ArtifactRepository> repos = new ArrayList<>(p.getRemoteArtifactRepositories());
        repos.add(ar);
        p.setRemoteArtifactRepositories(repos);
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew :basquin-maven-injector:test --no-daemon
```

Expected: PASS, 7 tests.

- [ ] **Step 8: Verify the jar carries BOTH the baked version and the discovery index**

The index check is the one that matters. Every test in this module passes with discovery broken, so
without this assertion the first symptom would be Task 6 failing five tasks later for a reason that
looks like anything but a missing resource.

```bash
./gradlew :basquin-maven-injector:jar --no-daemon
J=basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar
unzip -p "$J" basquin-injector.properties
unzip -p "$J" META-INF/sisu/javax.inject.Named
```

Expected: `version=0.3.0`, then `com.basquin.maven.BasquinInjector`.

Then confirm the index names a class that actually exists in the jar — a stale index after a rename
is as silent as a missing one:

```bash
CLS=$(unzip -p "$J" META-INF/sisu/javax.inject.Named | tr -d '\r' | head -1 | tr '.' '/')
unzip -l "$J" | grep -q "$CLS.class" && echo "OK  index names a class present in the jar" \
  || { echo "BROKEN  index names $CLS but no such class in the jar"; exit 1; }
```

Expected: `OK  index names a class present in the jar`.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle basquin-maven-injector
git commit -m "feat(dd043): basquin-maven-injector — zero-pom-edit build-time injection

An AbstractMavenLifecycleParticipant loaded via -Dmaven.ext.class.path that adds
basquin-quarkus to every project in the reactor, plus the repository that makes it
resolvable. The repository half is two-level: a Repository on the Model is inert at
afterProjectsRead, so the effective list is refreshed via setRemoteArtifactRepositories
(measured in spike S5). Injected version is baked from project.version, not typed."
```

---

### Task 4: Operator guards — fail loudly, never silently

Spec §5.1 requires the injector to fail loudly rather than silently. A build that succeeds and
produces an uninstrumented binary is DD-043 §1.1's relocated failure mode, and it is the outcome all
of this exists to prevent.

**Amended — this list is the guard set as originally scoped when this plan was written, and PR #103's
review rounds grew it well past three.** As implemented in `BasquinInjector.java` on this branch
there are three guard *methods* — `failOnConflictingManagedVersion`, `failOnUnusableDeclaration`,
`failOnConflictingDeclaredVersion` — enforcing **seven** fail-loudly conditions, not the three below:
two in the managed-dependency path (a managed version conflict, item 1 below; and a managed entry
carrying `<exclusions>`, added later, which strips `basquin-core` transitively), four in the
declared-dependency *usability* path (a `scope` other than `compile`/`runtime`; a `type` other than
`jar`; any `classifier`; any `<exclusions>`), and one more in the declared-*version* path (a declared
version conflict, distinct from the usability check). Item 2 below — "skip the dependency, still add
the repository" — is what happens only when a pre-existing declaration hits **none** of those seven;
four of the shapes (declared scope, declared type, declared classifier, declared exclusions) now
hard-fail the build instead of being skipped, exactly the "already declares" case item 2 describes as
uniformly safe. See each guard method's Javadoc in `BasquinInjector.java` for the shape-by-shape
history, and `docs/THIRD-PARTY-APPS.md`'s "Fail-loudly behaviours" section for the operator-facing
description kept in sync with the current code. `-Dbasquin.inject.skip=true` (item 3 below) is
unaffected by any of this — it is an operator opt-out, not a guard against silent bypass.

Three guards, each answering a way the injection can be defeated without anyone noticing — as
originally scoped; **item 2 is superseded, see the amendment above:**

1. **`dependencyManagement` pins our group to a different version.** Managed versions govern the
   transitive `basquin-core`, so a BOM pinning `com.basquin:*` silently changes what resolves. Fail.
2. **The project already declares `basquin-quarkus`** (e.g. a pom edited during PR-2's acceptance).
   Injecting again would duplicate the dependency. ~~Skip the dependency, still add the repository~~
   — true only when the existing declaration is in a usable shape (plain `compile`/`runtime`,
   `jar`-type, unclassified, exclusion-free, matching version); otherwise the build now hard-fails.
   A declared dependency is not necessarily a *resolvable* one, which is why the repository is still
   added even in the safe case.
3. **`-Dbasquin.inject.skip=true`** so an operator can run a clean baseline build with the injector
   still on the classpath — which is how a benchmark's control cell is produced.

**Files:**
- Modify: `basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java`
- Test: `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorGuardsTest.java`

**Interfaces:**
- Consumes: `BasquinInjector.inject(List, Properties)` and the constants from Task 3.
- Produces: no new public API; `inject` now throws `MavenExecutionException` on guard 1.

- [ ] **Step 1: Write the failing tests**

Create `basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorGuardsTest.java`:

```java
package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

/** DD-043 PR-3 — spec §5.1: the injector fails loudly, never silently. */
public class BasquinInjectorGuardsTest {

    private static MavenProject project(String artifactId) {
        MavenProject p = new MavenProject();
        p.setArtifactId(artifactId);
        p.setRemoteArtifactRepositories(new ArrayList<>());
        return p;
    }

    private static void manage(MavenProject p, String artifactId, String version) {
        DependencyManagement dm = new DependencyManagement();
        Dependency managed = new Dependency();
        managed.setGroupId(BasquinInjector.GROUP_ID);
        managed.setArtifactId(artifactId);
        managed.setVersion(version);
        dm.addDependency(managed);
        p.getModel().setDependencyManagement(dm);
    }

    /**
     * A BOM pinning our group to another version wins over what we inject for anything resolved
     * transitively (basquin-core). Silently building against a different core is precisely the
     * "succeeds but is wrong" outcome §5.1 forbids.
     */
    @Test
    public void failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion() {
        MavenProject p = project("app");
        manage(p, "basquin-core", "0.0.1-conflicting");

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("expected MavenExecutionException — a conflicting managed version must not be silent");
        } catch (MavenExecutionException e) {
            String m = e.getMessage();
            assertTrue("message must name the artifact: " + m, m.contains("basquin-core"));
            assertTrue("message must name the conflicting version: " + m,
                    m.contains("0.0.1-conflicting"));
            assertTrue("message must name the version we inject: " + m,
                    m.contains(InjectorVersion.value()));
        }
    }

    /** A managed entry that AGREES with us is harmless and must not fail the build. */
    @Test
    public void acceptsDependencyManagementThatAgreesWithTheInjectedVersion() throws Exception {
        MavenProject p = project("app");
        manage(p, "basquin-core", InjectorVersion.value());

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /** Management of an unrelated group is none of our business. */
    @Test
    public void ignoresDependencyManagementOfOtherGroups() throws Exception {
        MavenProject p = project("app");
        DependencyManagement dm = new DependencyManagement();
        Dependency other = new Dependency();
        other.setGroupId("io.quarkus");
        other.setArtifactId("quarkus-rest");
        other.setVersion("3.37.3");
        dm.addDependency(other);
        p.getModel().setDependencyManagement(dm);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /**
     * If the pom already declares basquin-quarkus, do not add a second copy — but DO still inject the
     * repository. A declared dependency is not necessarily a resolvable one, and the repository is
     * what makes it resolvable.
     */
    @Test
    public void doesNotDuplicateAnAlreadyDeclaredDependencyButStillAddsTheRepository()
            throws Exception {
        MavenProject p = project("app");
        Dependency existing = new Dependency();
        existing.setGroupId(BasquinInjector.GROUP_ID);
        existing.setArtifactId(BasquinInjector.ARTIFACT_ID);
        existing.setVersion("0.3.0");
        p.getModel().getDependencies().add(existing);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals("must not duplicate", 1, p.getModel().getDependencies().size());
        assertEquals("the repository is still required", 1, p.getRemoteArtifactRepositories().size());
    }

    /** The control-cell switch: a baseline build with the injector still on the classpath. */
    @Test
    public void injectsNothingWhenSkipIsSet() throws Exception {
        MavenProject p = project("app");
        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_SKIP, "true");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals(0, p.getModel().getDependencies().size());
        assertEquals(0, p.getModel().getRepositories().size());
        assertEquals(0, p.getRemoteArtifactRepositories().size());
    }

    /**
     * Skip is opt-in: injection stays on unless the property parses as true. Note
     * {@code Boolean.parseBoolean} is case-insensitive, so {@code TRUE} skips as well.
     */
    @Test
    public void skipIsOffUnlessExplicitlyTrue() throws Exception {
        MavenProject p = project("app");
        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_SKIP, "false");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals(1, p.getModel().getDependencies().size());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./gradlew :basquin-maven-injector:test --no-daemon --tests '*BasquinInjectorGuardsTest*'
```

Expected: FAIL — **three** tests fail with no guard code present:
`injectsNothingWhenSkipIsSet`, `failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion`,
and `doesNotDuplicateAnAlreadyDeclaredDependencyButStillAddsTheRepository` (which adds a second
dependency without the idempotence check). The other three pass already, which is correct — they
assert behaviour Task 3 delivered.

- [ ] **Step 3: Add the guards to `BasquinInjector.inject`**

Replace the body of `inject` with:

```java
    void inject(List<MavenProject> projects, Properties props) throws MavenExecutionException {
        if (Boolean.parseBoolean(props.getProperty(PROP_SKIP))) {
            System.out.println(LOG + PROP_SKIP + "=true — injecting nothing (baseline build)");
            return;
        }
        String version = orDefault(props.getProperty(PROP_VERSION), InjectorVersion.value());
        String url = orDefault(props.getProperty(PROP_REPO_URL), DEFAULT_REPO_URL);

        for (MavenProject p : projects) {
            failOnConflictingManagedVersion(p, version);
            // Every model object below is allocated fresh inside this loop. Maven's model objects are
            // mutable; hoisting an allocation would alias one instance across the whole reactor, so a
            // later in-place mutation on one module would bleed into all the others. A single-module
            // build cannot detect that, which is why this is a written constraint and not taste.
            if (declaresOurDependency(p)) {
                // Not a duplicate — but the repository is still required: a declared dependency is
                // not necessarily a resolvable one.
                System.out.println(LOG + p.getArtifactId() + " already declares "
                        + ARTIFACT_ID + "; adding the repository only");
            } else {
                addDependency(p, version);
            }
            addRepository(p, url);
            System.out.println(LOG + "instrumented " + p.getArtifactId()
                    + " (" + GROUP_ID + ":" + ARTIFACT_ID + ":" + version + " from " + url + ")");
        }
    }

    private boolean declaresOurDependency(MavenProject p) {
        for (Dependency d : p.getModel().getDependencies()) {
            if (GROUP_ID.equals(d.getGroupId()) && ARTIFACT_ID.equals(d.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spec §5.1: a strict dependencyManagement/BOM may pin something the extension needs, and a
     * managed version governs anything resolved transitively — notably basquin-core. Building
     * against a different core than the extension was compiled against is the "succeeds but is
     * silently wrong" outcome this design exists to prevent, so it is a hard failure.
     */
    private void failOnConflictingManagedVersion(MavenProject p, String version)
            throws MavenExecutionException {
        DependencyManagement dm = p.getModel().getDependencyManagement();
        if (dm == null) {
            return;
        }
        for (Dependency managed : dm.getDependencies()) {
            if (!GROUP_ID.equals(managed.getGroupId())) {
                continue;
            }
            String managedVersion = managed.getVersion();
            if (managedVersion != null && !managedVersion.equals(version)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + "'s dependencyManagement pins "
                                + GROUP_ID + ":" + managed.getArtifactId() + " to " + managedVersion
                                + ", but this injector supplies " + version + ". The managed version"
                                + " would win for transitively resolved Basquin artifacts, producing"
                                + " a build instrumented with a different core than the extension was"
                                + " compiled against. Align the versions, or set -D" + PROP_VERSION
                                + "=" + managedVersion + " if that is genuinely intended.",
                        p.getFile());
            }
        }
    }
```

Add the import `org.apache.maven.model.DependencyManagement;`.

- [ ] **Step 4: Run the whole module's tests**

```bash
./gradlew :basquin-maven-injector:test --no-daemon
```

Expected: PASS, 13 tests (7 from Task 3 + 6 here).

**Amended, same expansion as the guard-count note above:** 6 was `BasquinInjectorGuardsTest.java`'s
size when Task 4 was originally three guards. PR #103's review rounds grew the guard set to seven
fail-loudly conditions and grew the test file to match — at the time of the round-4 docs pass it
carried 21 `@Test` methods (`grep -c '@Test' basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorGuardsTest.java`),
not 6, making the module total 28, not 13. Re-run that command rather than trusting either number;
this module is still under active review.

- [ ] **Step 5: Mutation-check the two guards that could pass vacuously**

A test that passes whether or not the behaviour exists proves nothing. Verify each guard's test can
actually fail, one at a time:

```bash
# (a) neuter the skip guard: change `if (Boolean.parseBoolean(...))` to `if (false)`
# (b) neuter the management guard: make failOnConflictingManagedVersion return immediately
# After EACH edit, run the suite and confirm the specific test fails, then REVERT.
./gradlew :basquin-maven-injector:test --no-daemon
```

Expected: (a) `injectsNothingWhenSkipIsSet` fails; (b)
`failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion` fails. Both pass again after
reverting. Record the observed failures in the task report.

- [ ] **Step 6: Commit**

```bash
git add basquin-maven-injector
git commit -m "feat(dd043): injector guards — fail loudly, never silently (spec 5.1)

Three ways injection can be defeated unnoticed, each now handled: a BOM pinning
com.basquin to another version (hard failure — the managed version governs the
transitively resolved core); an already-declared dependency (skip the dependency,
still add the repository, since declared is not resolvable); and an explicit
-Dbasquin.inject.skip=true for producing a baseline control cell. Both guards
mutation-checked."
```

---

### Task 5: The Gradle init-script equivalent

Spec §5 specifies a Gradle counterpart: "an init script (`-I basquin-init.gradle`) doing the same via
`allprojects { … }`". Gradle-built Quarkus applications are a real slice of the target space, and the
injector is Maven-only without it.

**Files:**
- Create: `basquin-init.gradle`

**Interfaces:**
- Consumes: the same three system properties as `BasquinInjector` — `basquin.inject.skip`,
  `basquin.inject.repo.url`, `basquin.inject.version` — so one operator contract covers both build
  systems.
- Produces: nothing other tasks consume.

**Scope, stated honestly:** this is the **stub** §9's PR-3 row calls for. It is not exercised against a
Gradle-built Quarkus target in this PR — the acceptance targets (rest-villains, the Phase-0 fixture)
are both Maven. Task 8 records that limitation in the spec rather than letting the file imply a
coverage it does not have.

**Amended — "stub"/"not exercised" (here, and in the identical wording of the script's own header
comment in Step 1's code block below) describes the gap as one of exercise, i.e. as if running the
§5.2 banner check against a Gradle target would settle it. PR #103's round-4 review found that
understates it: `basquin-init.gradle` implements only the `skip` opt-out and has no equivalent of the
other four fail-loudly guards enumerated in Task 4's amendment above — it does not even check
whether `com.basquin:basquin-quarkus` is already declared before adding another `implementation`
dependency, so a conflicting version/exclusions/configuration is silently resolved by Gradle's own
highest-version default instead of failing loudly, reproducing the DD-040 failure mode the Maven
guards exist to prevent. A banner check would not surface this either: the banner only shows whether
`basquin` loaded on *some* build, not whether a conflicting declaration was silently resolved around
it. `docs/THIRD-PARTY-APPS.md`'s "Gradle targets" section carries the corrected, kept-in-sync
operator-facing statement: §5.1's fail-loudly contract is **not implemented** on the Gradle path — a
stronger and more accurate claim than "unverified." The script's own header comment in the code block
below is left exactly as originally written and as it stands in the real `basquin-init.gradle` file
today; editing only this plan's quoted copy while the real file keeps the same words would relocate
the mismatch rather than fix it.**

- [ ] **Step 1: Create `basquin-init.gradle`**

```gradle
// DD-043 PR-3 — the Gradle counterpart of basquin-maven-injector (spec §5).
//
// Usage:  ./gradlew -I /path/to/basquin-init.gradle build
//
// Same operator contract as the Maven injector, deliberately: the same three system properties mean
// the same things, so an operator instrumenting a mixed estate learns one interface.
//
//   -Dbasquin.inject.skip=true      inject nothing (baseline/control build)
//   -Dbasquin.inject.repo.url=URL   override the artifact repository
//   -Dbasquin.inject.version=V      override the injected extension version
//
// SCOPE: this script is a stub in the sense that PR-3 does not exercise it against a Gradle-built
// Quarkus application — both of PR-3's acceptance targets are Maven-built. The Maven path is the
// measured one (spikes S4 and S5). Treat a Gradle target as unverified until someone runs the
// §5.2 banner check against one.
initscript {
    // Intentionally empty: this script adds a dependency and a repository, needing nothing itself.
}

def skip = Boolean.parseBoolean(System.getProperty('basquin.inject.skip'))
def repoUrl = System.getProperty('basquin.inject.repo.url') ?: 'https://ianp94.github.io/basquin/maven/'
def version = System.getProperty('basquin.inject.version') ?: '0.3.0'

if (skip) {
    logger.lifecycle('[basquin-injector] basquin.inject.skip=true — injecting nothing (baseline build)')
} else {
    allprojects { proj ->
        proj.repositories {
            maven {
                name = 'basquin-injected'
                url = repoUrl
            }
        }
        proj.plugins.withId('java') {
            proj.dependencies.add('implementation', "com.basquin:basquin-quarkus:${version}")
            logger.lifecycle("[basquin-injector] instrumented ${proj.path} " +
                    "(com.basquin:basquin-quarkus:${version} from ${repoUrl})")
        }
    }
}
```

- [ ] **Step 2: Verify the script is syntactically valid and its skip path works**

Run it against this repository itself — not to instrument anything, but because a Groovy syntax error
or a bad `allprojects` block fails immediately:

```bash
./gradlew -I basquin-init.gradle -Dbasquin.inject.skip=true projects --no-daemon 2>&1 | head -20
```

Expected: the project list, preceded by
`[basquin-injector] basquin.inject.skip=true — injecting nothing (baseline build)`.

**Do not add `-q`.** It sets the log level to QUIET, which is above LIFECYCLE, so every
`logger.lifecycle` line the script emits is suppressed — the verification would fail while the script
under test is perfectly fine.

- [ ] **Step 3: Verify the injecting path parses and reports per project**

```bash
./gradlew -I basquin-init.gradle -Dbasquin.inject.repo.url=http://localhost:8000/ \
  projects --no-daemon 2>&1 | grep "basquin-injector" | head -5
```

Expected: one `[basquin-injector] instrumented :<project> (com.basquin:basquin-quarkus:0.3.0 from
http://localhost:8000/)` line per java-plugin project. This exercises script evaluation and the
`allprojects` wiring; it does not resolve anything.

- [ ] **Step 4: Commit**

```bash
git add basquin-init.gradle
git commit -m "feat(dd043): basquin-init.gradle — the Gradle counterpart of the injector

Same three system properties as the Maven injector so one operator contract covers
both build systems. Stub in the sense §9's PR-3 row intends: not exercised against a
Gradle-built Quarkus target here, since both acceptance targets are Maven-built."
```

---

### Task 6: Acceptance — rest-villains, JVM mode, zero pom edits

This is PR-3's reason to exist. PR-2's rest-villains run reached its banner via **a manual pom edit**
(`bench-results/dd043-pr2-restvillains-2026-07-26/pom-deviation.patch`), and that README explicitly
hands PR-3 the job of proving the same result with no edit at all.

**Files:**
- Create: `bench-results/dd043-pr3-restvillains-2026-07-26/` (README.md, build.sh, logs, banner)

**Interfaces:**
- Consumes: the injector jar from Task 3/4; the Pages publish target from Task 2.
- Produces: the JVM half of spec §5.2's acceptance.

**Setup notes carried from PR-2's harness, which this reuses:**
- `bench-results/dd043-pr2-restvillains-2026-07-26/build.sh` already forwards `EXTRA_MAVEN_OPTS` into
  the container's `MAVEN_OPTS`. That is the hook for `-Dmaven.ext.class.path`; no new mechanism needed.
- The app lives in a **sibling clone** of `quarkusio/quarkus-super-heroes`, never committed here —
  same pattern PR-2 used (not tracked; see PR-2's `build.sh` header, not
  `docs/THIRD-PARTY-APPS.md:58`, which is about JPetStore/Tomcat 9 and carries no such precedent —
  a citation that did not resolve, corrected the same way in the restvillains evidence directories
  themselves). `APP_DIR` points at its `rest-villains`.
- The build runs in the pinned Mandrel image; the container's local repo is the host's real `~/.m2`.

- [ ] **Step 1: Confirm the target tree is pristine — this is the property under test**

```bash
cd "$APP_DIR" && git status --porcelain && git diff --stat
```

Expected: **empty output**. If PR-2's `pom-deviation.patch` is still applied, revert it
(`git checkout -- pom.xml`) and re-check. A non-empty result invalidates the whole task.

- [ ] **Step 2: Purge the extension chain from the local repository**

Otherwise a leftover from PR-2's `publishToMavenLocal` makes resolution succeed for the wrong reason —
the S4-addendum ambiguity control, and the discipline spike S5 followed.

```bash
ls -d ~/.m2/repository/com/basquin/* 2>/dev/null
rm -rf ~/.m2/repository/com/basquin
find ~/.m2/repository/com/basquin -type f 2>&1 | head -3
```

Expected: the third command prints a "No such file or directory" error. Capture all three outputs into
`purge-proof.txt` in the evidence directory.

- [ ] **Step 3: Publish the chain to a scratch Pages repo and serve it over HTTP**

```bash
cd /mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM
rm -rf build/tmp/pr3-pages && mkdir -p bench-results/dd043-pr3-restvillains-2026-07-26
./gradlew -q --no-daemon -PbasquinPagesDir=build/tmp/pr3-pages \
  :basquin-core:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository
(cd build/tmp/pr3-pages && python3 -m http.server 8000 --bind 127.0.0.1) \
  > bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log 2>&1 &
echo $! > /tmp/pr3-http-server.pid
sleep 2 && curl -sf -o /dev/null -w "server up: %{http_code}\n" \
  http://localhost:8000/com/basquin/basquin-quarkus/0.3.0/basquin-quarkus-0.3.0.pom
```

Expected: `server up: 200`.

**Bind to `127.0.0.1` and reach it with `--network host`, exactly as spike S5 did — this is not an
incidental detail.** Maven 3.9.15's default `maven-default-http-blocker` mirror matches
`external:http:*` and **exempts localhost** — 3.9.15 is rest-villains' own wrapper version
(`bench-results/dd043-pr3-optional-declaration-2026-07-29/provenance.txt:57`, the wrapper's
`distributionUrl`; restated in `BasquinInjector.java`'s `failOnUnusableDeclaration` javadoc), not the
Phase-0 fixture's 3.9.16 that Task 7's native cell targets;
the two are different clones with different wrappers and the figure was originally conflated between
them. Serving on `0.0.0.0` and pointing the build at the docker
bridge gateway (`http://172.17.x.x:8000/`) is precisely the class that mirror matches, and whether any
resolver re-applies settings mirrors to an *injected* repository is **explicitly unmeasured** — S5's
README flags it as a scope limit. The fetch that would be blocked is the deployment artifact's, which
is Step 8's decisive unconfounded evidence. Reusing S5's measured channel keeps that risk out of an
acceptance run; do not substitute a gateway route without spiking it first.

The PID is captured to a file because `kill %1` does not work across separate shell invocations.

- [ ] **Step 4: Build rest-villains with the injector and nothing else**

**First, create the build wrapper this step needs.** PR-2's
`bench-results/dd043-pr2-restvillains-2026-07-26/build.sh` forwards `EXTRA_MAVEN_OPTS` but accepts no
extra `docker run` arguments, so it cannot mount the injector jar or set `--network host`. Copy it to
`bench-results/dd043-pr3-restvillains-2026-07-26/build.sh` and insert `${EXTRA_DOCKER_ARGS:-}` into its
`docker run` argument list, immediately before the `"$IMAGE"` argument.

Use the name `EXTRA_DOCKER_ARGS` — that is the established variable across this project
(`bench-results/dd043-spikes-2026-07-24/env/build.sh:41`, used by both S4 and S5). A second,
differently-named variable for the same purpose is how a convention quietly forks.

**Stage the injector jar outside the application's git clone.** With
`APP_DIR=…/quarkus-super-heroes/rest-villains`, `$APP_DIR/..` is the **root of the app's clone** — the
app tree is the whole clone, not just the module directory. Putting the jar there makes Step 5's
pristine check fail on a file this plan planted, defeating the very property under test.

```bash
export APP_DIR   # build.sh reads it from the environment
STAGE=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM/build/tmp/pr3-inj
mkdir -p "$STAGE"
cp basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar "$STAGE/"

EXTRA_DOCKER_ARGS="--network host -v $STAGE:/inj" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar -Dbasquin.inject.repo.url=http://localhost:8000/" \
  bench-results/dd043-pr3-restvillains-2026-07-26/build.sh clean package -DskipTests \
  2>&1 | tee bench-results/dd043-pr3-restvillains-2026-07-26/build.log
```

`--network host` plus a `localhost` URL is S5's measured channel — see Step 3's note on the
`maven-default-http-blocker` mirror for why this is load-bearing rather than a style choice.

Expected: `BUILD SUCCESS`, and `[basquin-injector] instrumented rest-villains` in the log.

**If that log line is absent, stop.** It means the participant never loaded — check the Sisu index
first (`unzip -p …jar META-INF/sisu/javax.inject.Named`), because Maven tolerates a nonexistent
`maven.ext.class.path` entry silently and will complete an **uninstrumented** build without error.

- [ ] **Step 5: Verify the target tree is still pristine after the build**

```bash
cd "$APP_DIR" && git status --porcelain
```

Expected: **empty**. This is the claim PR-3 makes; verify it after the fact, not only before.

- [ ] **Step 6: Run the app and capture the banner**

```bash
# Start postgres exactly as PR-2's README documents, then:
DB_CONTAINER=<name> DB_NETWORK=<network> \
  bench-results/dd043-pr2-restvillains-2026-07-26/run-app.sh
sleep 15
docker logs basquin-restvillains-app 2>&1 \
  | tee bench-results/dd043-pr3-restvillains-2026-07-26/app-startup.log \
  | grep "Installed features"
```

Expected: a line listing `basquin` among the installed features, e.g.
`Installed features: [agroal, basquin, cdi, hibernate-orm, jdbc-postgresql, ...]`.

- [ ] **Step 7: Confirm the boundary actually works, not just that the feature loaded**

A listed feature proves augmentation ran; it does not prove the filter is on the request path.

```bash
curl -s -H 'X-Basquin-Req: pr3-accept-1' http://localhost:8084/api/villains > /dev/null
curl -s "http://localhost:8084/__basquin/result?id=pr3-accept-1" \
  | tee bench-results/dd043-pr3-restvillains-2026-07-26/result-poll.txt
```

Expected: a CSV cost line, not `miss` — the same shape PR-2 recorded (`7,415,0|0||`).

- [ ] **Step 8: Verify resolution came from the injected repository**

```bash
grep -c "GET /com/basquin/" bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log
grep "basquin-quarkus-deployment" bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log | head -3
```

Expected: a non-zero count, including the **deployment** artifact — the unconfounded evidence, since
no pom anywhere names it.

- [ ] **Step 9: Write the evidence README and commit**

Create `bench-results/dd043-pr3-restvillains-2026-07-26/README.md` in this repo's evidence style:
verdict first, the question, what it establishes, **what it does not** (JVM only; native is Task 7;
one target; localhost HTTP rather than the real Pages HTTPS), a file manifest, and a `## Reproduce`
block with the exact commands above. State plainly that the app tree was verified pristine both
before and after.

```bash
docker rm -f basquin-restvillains-app
kill "$(cat /tmp/pr3-http-server.pid)" && rm -f /tmp/pr3-http-server.pid   # stop the HTTP server
git add bench-results/dd043-pr3-restvillains-2026-07-26
git commit -m "bench(dd043): PR-3 acceptance — rest-villains instrumented with zero pom edits

PR-2 reached this banner via a manual pom edit (its pom-deviation.patch) and handed
PR-3 the job of doing it with none. App tree verified pristine before AND after the
build; local repo purged first so resolution could not succeed for the wrong reason;
the deployment artifact — named by no pom anywhere — was fetched from the injected
repository. Banner lists basquin and the boundary answers a result poll."
```

**Amended — this step's instruction and the commit message above both say the app tree was "verified
pristine before AND after," and that is exactly what got committed at the time (`6aff73d`/`7f3b99d`).
PR #103's round-4 review found the directory's own artifact never supported the two-sided claim:
`pristine-proof.txt` records a single post-hoc `git status --porcelain`, taken after both the build
and the app run had already happened — there is no separate before-capture in this directory. The
two-sided property is real, but is established by `scripts/verify-dd043-pr3.sh`, not by this
directory: dirty-tree refusal before the build (the preflight
`git -C "$app" status --porcelain -- .`) and `jvm:zero-edits` after
(`git status --porcelain=v2 --branch -- .`), surfaced in
`bench-results/RUN-OF-RECORD/RESULTS.md`'s `jvm:zero-edits` row. All three are cited by
command text and row key rather than by line, because the line numbers here were wrong once and went
stale again while being fixed. The current
`bench-results/dd043-pr3-restvillains-2026-07-26/README.md` verdict and check-5 row carry the
corrected framing; this plan's step and commit-message template above are left as originally written
and executed, per this file's own history-preservation rule.**

---

### Task 7: Acceptance — native image

Spec §5.2 requires the banner for **both** the JVM jar and a native image.

**Target choice, and why it is not rest-villains.** Whether rest-villains builds native at all is
**unmeasured** — PR-2 validated it in JVM mode only, and DD-043 has no evidence either way. The
Phase-0 fixture, by contrast, is already proven to build native under injection (spike S4's
`banner-native.txt`). Running the native cell on the fixture tests *the injector* rather than
gambling PR-3's gate on an unrelated unknown. If the fixture's native cell passes and time allows,
attempt rest-villains native as a **bonus**, and record a failure there as a finding about the target,
not a PR-3 blocker.

**Files:**
- Modify: `bench-results/dd043-spikes-2026-07-24/fixture/pom.xml` (remove PR-2's leftover dependency)
- Create: `bench-results/dd043-pr3-native-2026-07-26/` (README.md, build log, banner)

**Interfaces:**
- Consumes: the injector jar; the scratch Pages repo from Task 6's steps 3.
- Produces: the native half of spec §5.2's acceptance.

- [ ] **Step 1: Remove the confound from the fixture pom**

Spike S5 recorded that the fixture's `pom.xml` still declares `com.basquin:basquin-quarkus` — a PR-2
leftover — so a passing build there cannot be attributed to injection. The fixture is **our** test
scaffolding, not a third-party app tree, so removing it is legitimate and required for the test to
mean anything.

```bash
grep -n -A3 "basquin-quarkus" bench-results/dd043-spikes-2026-07-24/fixture/pom.xml
```

Expected: the block at roughly lines 57–61, plus its explanatory comment just above it. Delete both
the `<dependency>` block **and** the now-orphaned comment, then confirm:

```bash
grep -c "artifactId>basquin-quarkus<" bench-results/dd043-spikes-2026-07-24/fixture/pom.xml
```

Expected: `0`.

**Do not verify with `grep -c "basquin"`** — it can never reach `0`. The fixture's own
`<groupId>com.basquin.spike</groupId>` matches, so that check fails no matter how correct the
removal is.

- [ ] **Step 2: Purge and re-serve, as in Task 6**

Purge `com/basquin` from the fixture's local repo
(`bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin`), capture the proof, and start
the HTTP server over a fresh scratch Pages publish. Same commands as Task 6 steps 2–3, with that local
repo path.

- [ ] **Step 3: Build the fixture native, with the injector as the only source of the extension**

Native compilation is **serialized on a mutex** (spec §7.2) — confirm no other native build or
benchmark is running first. It is slow; allow 15+ minutes.

**Use `env/build.sh` directly — it already has both hooks.** It forwards `EXTRA_MAVEN_OPTS` into the
container's `MAVEN_OPTS` (line 40) **and** expands `${EXTRA_DOCKER_ARGS:-}` (line 41), which is how
S4 mounted its probe (`EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe"`) and how S5 combined a mount with
`--network host`. Do not copy or fork this script; unlike PR-2's `build.sh` it needs no modification.

```bash
STAGE=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM/build/tmp/pr3-inj
EXTRA_DOCKER_ARGS="--network host -v $STAGE:/inj" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar -Dbasquin.inject.repo.url=http://localhost:8000/" \
  bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests -Dnative \
  2>&1 | tee bench-results/dd043-pr3-native-2026-07-26/build-native.log
```

Same measured transport as Task 6 — `--network host` with a `localhost` URL, for the
`maven-default-http-blocker` reason recorded in Task 6 Step 3.

The fixture's `native` profile is activated by the `native` property (`fixture/pom.xml:125-138`), so
`-Dnative` is the correct switch — the same one S4 used (`s4-injection/findings.md:132`).

**Use `clean package`, not S4's plain `package`.** `bench-results/dd043-pr2-spike-2026-07-25/README.md`
records that a plain `package` fails on the fixture's leftover JaCoCo offline-instrument plugin from
spike S1 — and, worse, that the failed run still emitted a correct-looking banner from the *previous*
build's artifact. Do not "restore" S4's form.

Expected: `BUILD SUCCESS` and `fixture/target/fixture-1.0.0-SNAPSHOT-runner`.

- [ ] **Step 4: Run the native binary and capture the banner**

Run it **directly on the host, not in a container** — that is S4's proven method for this artifact
(`s4-injection/findings.md:147-151`): the UBI9/Mandrel-built ELF executable ran on this Ubuntu/WSL2
host with no loader or glibc incompatibility, started in 0.168s, and served its first request. Do not
substitute a container run; it adds a variable S4 already eliminated.

```bash
BIN=bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner
test -x "$BIN" || { echo "native binary missing — step 3 did not produce it"; exit 1; }
"$BIN" > bench-results/dd043-pr3-native-2026-07-26/banner-native.log 2>&1 &
NPID=$!
sleep 5
curl -sf -o /dev/null -w "served /ok: %{http_code}\n" http://localhost:8080/ok
grep "Installed features" bench-results/dd043-pr3-native-2026-07-26/banner-native.log \
  | tee bench-results/dd043-pr3-native-2026-07-26/banner-native.txt
kill "$NPID"
```

Expected: `served /ok: 200`, then an `Installed features:` line **containing `basquin`**.

Note the baseline for comparison: S4's native run of this fixture reported
`[cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]` — S4 injected
`quarkus-smallrye-openapi` as its probe dependency, so `smallrye-openapi` there is *its* injected
marker, not a fixture default. Assert on the presence of `basquin`; do not expect S4's exact set.

- [ ] **Step 5: Write the evidence README and commit**

Same style as Task 6. State explicitly: the native cell ran on the **Phase-0 fixture**, not
rest-villains, and why; that the fixture's pom no longer declares the extension, so injection is the
only source; and whether the rest-villains native bonus was attempted and what happened.

```bash
git add bench-results/dd043-spikes-2026-07-24/fixture/pom.xml \
        bench-results/dd043-pr3-native-2026-07-26
git commit -m "bench(dd043): PR-3 acceptance — native image, extension injected only

Completes spec 5.2's two-artifact bar (JVM in Task 6, native here). Run on the Phase-0
fixture because rest-villains' native buildability is unmeasured and would gamble the
gate on an unrelated unknown; the fixture is already proven to build native under
injection (S4). Removed the PR-2 leftover dependency from the fixture pom first, so a
pass can only be attributed to the injector."
```

---

### Task 8: Spec amendments and operator documentation

Four amendments forced by spike S5, one by the §8.1 investigation, plus the two docs §9 says land with
this PR. **Correct the concept across the whole document, not the individual sentence** — this branch
has spent four review rounds on stale claims that survived a fix because they were worded differently
elsewhere.

**Files:**
- Modify: `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`
- Modify: `docs/THIRD-PARTY-APPS.md`, `docs/ARCHITECTURE.md`

**Interfaces:** none.

- [ ] **Step 1: Amend §5 — the injector injects a repository too, at two levels**

§5 (around line 550) currently describes the participant as adding "the `basquin-quarkus` dependency
and the offline-JaCoCo plugin execution". Add the repository as a third injection, and state the
two-level mutation with its rationale: a model-level `Repository` alone is inert at
`afterProjectsRead`, so `setRemoteArtifactRepositories(...)` is required. Note that the
fresh-instance-per-project discipline already specified for `Dependency` applies to `Repository` and
`ArtifactRepository` too. Cite `bench-results/dd043-s5-repo-injection-2026-07-26/`.

- [ ] **Step 2: Amend §5 — the operator contract needs no pre-populate step**

S4's local-repo path (`publishToMavenLocal` / `install:install-file`) demotes from *the* mechanism to
a documented **offline fallback**. Say so where §5 and §3 (line ~332) describe it.

- [ ] **Step 3: Amend §3 (line ~339) — disambiguate "consumers add that one `<repository>`"**

That sentence is true for an ordinary consumer and **false as a description of PR-3's mechanism**,
which forbids pom edits. Add the cross-reference so it cannot be read as PR-3's path.

- [ ] **Step 4: Amend §3 — the Pages repo now carries the whole chain**

It described only `basquin-core` being published. Task 2 publishes all three (four, counting the
injector itself). Update it to match.

- [ ] **Step 5: Resolve §8.1 — Apicurio's server does not build native**

Replace the open question with the finding and its consequence. Source:
`bench-results/dd043-apicurio-native-2026-07-26/README.md`. The verified facts: current `main` (`23159df`) has **zero**
occurrences of `native` in `app/pom.xml`; the only native profiles are in `cli/`, `examples/` and
`support-chat/`; `-DcliSkipNative` is defined in `cli/pom.xml` and governs the CLI only; the secondary
source described the **2.6.x** line, which did build the server native (`app/pom.xml:590`,
`Dockerfile.native`, CI-green native jobs). Record the substitute ranking — Debezium Server
(Quarkus 3.33.1.1) > Eclipse Hono HTTP adapter (3.27.4.1, reactive, heavier infra) > Apicurio 2.6.x —
**and the reason the cheapest option ranks last**: `basquin-quarkus` is pinned to Quarkus 3.37.3, so
2.6.x's 3.15.3 makes augmentation compatibility unmeasured, with silent-uninstrumented as a plausible
failure. Add the new row-5 gate this exposes: *the Quarkus version range over which one
`basquin-quarkus` build augments is untested*, since heroes and villains are both pinned at 3.37.3.

- [ ] **Step 6: Amend §9's ladder row for PR-3**

Record what PR-3 actually delivered, including the Gradle init script's unexercised status and the
native cell running on the fixture rather than rest-villains.

- [ ] **Step 7: Audit the concept across the whole document**

Do not trust a targeted edit. For each concept touched above, grep its identifiers and check every hit
against the code:

```bash
S=docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md
grep -n "publishToMavenLocal\|install:install-file" "$S"
grep -n "maven.ext.class.path\|afterProjectsRead\|lifecycle participant" "$S"
grep -n "Apicurio\|cliSkipNative\|Target 5\|row 5" "$S"
grep -n "docs/maven\|Pages repo\|<repository>" "$S"
```

For each hit ask: is it still true, is it **imperative** (a "do X here" instruction an implementer
would follow), and is it used as a **gate** (a criterion some later PR must satisfy)? Those last two
are never cosmetic. Report which sites you checked, not that "it is fixed".

- [ ] **Step 8: Write the operator documentation**

`docs/THIRD-PARTY-APPS.md` gains a build-time-injection section: the one-command form
(`-Dmaven.ext.class.path=…`), the three system properties, the fail-loudly behaviours and what to do
about each, the Gradle init-script form with its unexercised caveat, and the offline fallback.

`docs/ARCHITECTURE.md` gains the build-vs-runtime injection symmetry — the table at spec §5:585
(runtime: `CATALINA_OPTS`/`JAVA_TOOL_OPTIONS`, operator patches the pod template; build:
`MAVEN_OPTS`/`-Dmaven.ext.class.path`, lifecycle participant mutates the project model).

- [ ] **Step 9: Verify every number in the docs is derived, not typed**

Any figure quoted from the spikes must match its artifact:

```bash
grep -o "Installed features: \[[^]]*\]" bench-results/dd043-pr3-restvillains-2026-07-26/app-startup.log
grep -c "GET /com/basquin/" bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log
```

Cross-check each against what the prose claims. A hand-typed number drifts and prose has no guard.

- [ ] **Step 10: Commit**

```bash
git add docs
git commit -m "docs(dd043): spec amendments from spike S5 and the 8.1 investigation

S5 forces four: 5 gains the repository-injection half with its two-level mutation
(model Repository alone is inert at afterProjectsRead); the local-repo pre-populate
demotes to an offline fallback; 3's 'consumers add that one <repository>' gets the
cross-reference saying it is not PR-3's mechanism; and the Pages repo now carries the
whole chain, not just basquin-core.

8.1 resolves NO: the Apicurio server has no native path on the 3.x line (main's
app/pom.xml has zero occurrences of 'native'); the secondary source described 2.6.x.
Substitutes ranked, with the cheapest last because basquin-quarkus is pinned to
Quarkus 3.37.3 and 2.6.x's 3.15.3 makes augmentation compatibility unmeasured — which
exposes a new row-5 gate nobody had written down."
```

---

## Self-Review

**Spec coverage.** §5's dependency injection → Task 3; §5's repository injection (S5) → Tasks 3, 8;
§5's Gradle init script → Task 5; §5.1's fail-loudly → Task 4; §5.2's two-artifact banner → Tasks 6
(JVM) and 7 (native); §3's publishing gap → Task 2; §8.1 → Task 8; §9's "docs land with their PR" →
Task 8.

**Deliberately out of scope, and where each lands instead:**
- §5's **offline-JaCoCo plugin execution** is *not* injected here. It is coverage machinery whose
  entry gate (§8.2, plugin-execution injection — still unmeasured) belongs to **PR-4**. Task 8's §5
  amendment must keep saying so rather than implying PR-3 delivered it.
- A **Gradle-built Quarkus target** is not exercised (Task 5's stated limitation).
- **Real Pages HTTPS** resolution is not exercised — every acceptance uses localhost HTTP. The first
  `v*` tag populates `docs/maven`; until then this cannot be tested, exactly as spec §3 already
  records for `basquin-core`.

**Placeholder scan:** no TBD/TODO; every code step carries complete code; every verification step
carries its exact command and expected output. Task 6 Step 4 requires a **copy** of PR-2's `build.sh`
in our evidence tree with `${EXTRA_DOCKER_ARGS:-}` inserted (that script genuinely lacks the hook);
Task 7 calls `env/build.sh` **directly**, because it already has both hooks at lines 40–41 and both
spikes used them. Both are called out in the steps rather than left implicit.

**Round-1 review corrections applied.** An adversarial review
returned eight blocking findings; seven were real and
are fixed above. The two that would have cost the most:
- **No Sisu index** — the jar would build, all 13 tests pass, and Maven never discover the
  participant, producing a green build of an *uninstrumented* application. That is spec §5.1's exact
  failure mode reproduced inside PR-3's own deliverable, invisible until Task 6. Now a created
  resource, a jar assertion, and a class comment (Task 3 Steps 2b and 8).
- **A stale claim written as an imperative** — Task 7 asserted `env/build.sh` accepts no extra docker
  arguments and instructed forking it, when `${EXTRA_DOCKER_ARGS:-}` sits at line 41 and both spikes
  use it. This is the defect class this branch has spent four review rounds on, in my own plan.

The eighth (**B6**, "Task 1 is already done on main, the plan asserts a stale gap") was a **false
positive**: the review ran concurrently with commit `ad435e0`, so it read the branch tree after the
fix. `main` had zero occurrences at that moment. Task 1's gap was real; it is marked done, not dropped.

Also corrected from that review: `maven-compat` added (`DefaultRepositoryLayout` is not in
maven-core, so the code as drafted would not compile); the acceptance transport reverted to S5's
measured `--network host` + `localhost` rather than a docker-gateway URL the default
`maven-default-http-blocker` mirror would match; the injector jar staged outside the app's git clone
(`$APP_DIR/..` is the clone root, so the plan's own file would have failed its own pristine check);
`-q` dropped from Task 5's commands (it suppresses `logger.lifecycle`, failing a working script); and
the injector added to the release publish so the operator docs' "one command" has no unstated
build-from-source step.

**Type consistency:** `inject(List<MavenProject>, Properties)` has the same signature in Tasks 3 and 4;
the constants (`GROUP_ID`, `ARTIFACT_ID`, `REPO_ID`, `DEFAULT_REPO_URL`, `PROP_SKIP`, `PROP_REPO_URL`,
`PROP_VERSION`) are declared in Task 3 and used unchanged by Task 4's tests; `InjectorVersion.value()`
is defined in Task 3 and used in Tasks 3 and 4; `basquinPagesDir` is introduced in Task 2 and used in
Tasks 2, 6 and 7. The three system-property names are identical between `BasquinInjector` and
`basquin-init.gradle`, which is the point of Task 5's shared operator contract.

**Test-count expectations:** Task 3 adds 7 tests, Task 4 adds 6 — the repo total should rise from 359
to 372. Verify with the whole-suite run rather than assuming.

**Amended:** the "6"/"372" figures are as-scoped for Task 4's original three guards; PR #103's guard
growth (see Task 4's amendment) took `BasquinInjectorGuardsTest.java` well past 6, so 372 no longer
holds as either the module or the repo total, and the repo-wide count keeps moving as later work
lands. This paragraph's own closing advice — verify with the whole-suite run rather than assuming —
is the part to keep; do not treat 359 or 372 as current.
