# DD-043 PR-1 — `basquin-core` extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the framework-neutral measurement core out of the root project into a standalone `basquin-core` artifact that a Maven-built Quarkus extension can depend on — with zero behaviour change.

**Architecture:** `basquin-core` becomes a new Gradle subproject holding `Invariants` and `ResultStore`. The root project depends on it and keeps bundling it into the fat agent jar. **The Java package stays `agent`** — see the Package Decision below; renaming it would silently change reset-loader behaviour, and that risk is not worth taking inside a refactor whose contract is "no behaviour change".

**Tech Stack:** Gradle 8.9 multi-project, Java 17, JUnit 4.

## Global Constraints

- **Zero behaviour change.** The measured baseline on `main` (commit `f6d143a`, run 2026-07-25) is **324 tests across 52 suites, 0 failures, 0 errors, 0 skipped**. Any task that ends with a different failure/error count has changed behaviour and is not done.
- **No Quarkus code in this PR.** No Maven project, no extension module, no `quarkus-*` dependency. PR-2 owns that.
- **The Java package of the moved classes stays `agent`.** Not negotiable in this PR (Package Decision below).
- **Do not touch** `agent/Agent.java`'s `Thread.sleep(25)` (`:118`), its thread enumerations, its `System.gc()` calls (`:96`, `:126`), or anything `ThreadLocal`-backed. Spec §4.1 draws that line and this PR does not cross it.
- **`Premain-Class: agent.Agent`** (`build.gradle:119`) and the valve's `compileOnly project(':')` (`tomcat-valve/build.gradle`) must keep working.
- Verify with counts derived from commands, never from recollection. Every "green" claim cites the parsed test totals.

## Package Decision — read before Task 1

The moved classes keep `package agent`, producing a package split across two artifacts. That is deliberate.

`runner/GenericRunner.java:218-221` decides which classes the reset ClassLoader loads **parent-first**:

```java
private boolean parentFirst(String name) {
    return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
            || name.startsWith("agent.") || name.startsWith("runner.") || (!targetPrefix.isEmpty() && !name.startsWith(targetPrefix));
}
```

`targetPrefix` defaults to empty (`:195`, `targetPrefix == null ? "" : targetPrefix`), which makes the final clause `false`. So with an empty `targetPrefix`, a class renamed to `com.basquin.core.*` would match **no** clause, be loaded child-first, and the reset loader would produce a **fresh `ResultStore` per reset** — results written to one instance and polled from another. That is DD-040's defect class (a result that silently vanishes), reintroduced by a rename.

The rename may still be worth doing later. It is deferred to its own change, with the guard from Task 2 already in place, so the failure mode is caught by a test rather than by a benchmark that under-reports.

## Dependency Inversion — read before Task 1

`Invariants` cannot move to `basquin-core` unchanged. It calls
`agent.Agent.recordInvariantEvidence(ctx, violations)` and takes an `agent.IterationContext`
parameter — both types stay in the root project. Since the root project depends on `basquin-core`
(Task 1 Step 4), a `basquin-core` class that calls back into the root creates a circular Gradle
project dependency (`basquin-core -> root -> basquin-core`), which Gradle refuses to build.

The 14 consumers named in File Structure below are the *inbound* direction — code that calls
`Invariants`/`ResultStore` — and grepping only that direction misses this: `Invariants`' own
*outbound* call into `Agent` is invisible to an inbound grep.

The fix is a dependency inversion, done as part of the move, not after it: `evaluateAndMaybeFail`
takes `int iterationNumber` and returns an `Invariants.Result(violations, hardFailureMessage)`
rather than mutating an `IterationContext` or throwing. The caller (`Agent.end()`) records evidence
onto the context and decides whether to throw. Task 1 Step 4 below assumes this inversion is already
in place.

## File Structure

| Path | Responsibility |
|---|---|
| `settings.gradle` | gains `include 'basquin-core'` |
| `basquin-core/build.gradle` | new: plain `java` library, no dependencies beyond the JDK |
| `basquin-core/src/main/java/agent/Invariants.java` | moved from `agent/Invariants.java`, with the dependency inversion above |
| `basquin-core/src/main/java/agent/ResultStore.java` | moved from `agent/ResultStore.java` |
| `build.gradle` | root depends on `project(':basquin-core')`; fat jar and `runnerJar` both keep bundling it |
| `.gitignore` | gains `basquin-core/build` — a new subproject gets its own build directory, alongside the existing `tomcat-war/build`/`tomcat-valve/build` entries |
| `test/agent/ResetLoaderParentFirstTest.java` | new: pins the hazard above |

Consumers that must keep compiling unchanged (verified by `grep`, 14 files): `agent/Agent.java`, `agent/LoadModeControl.java`, `agent/RequestBoundary.java`, `runner/coverage/CoverageGuidedRun.java`, `runner/coverage/PodPollTargets.java`, `runner/util/StatusReporter.java`, and 6 test files. **Because they stay in `package agent`, none of their imports change** — that is the point of the Package Decision. This is the *inbound* direction only; see Dependency Inversion above for the *outbound* one.

---

### Task 1: Create `basquin-core` and move the two classes

**Files:**
- Create: `basquin-core/build.gradle`
- Create (by `git mv`): `basquin-core/src/main/java/agent/Invariants.java`, `basquin-core/src/main/java/agent/ResultStore.java`
- Modify: `settings.gradle`, `build.gradle`

**Interfaces:**
- Produces: a `:basquin-core` subproject whose jar contains exactly `agent.Invariants` and `agent.ResultStore` (plus nested types), depended on by the root project.
- Consumes: nothing new. All 14 consumer files are unchanged by this task.

- [ ] **Step 1: Confirm the baseline before touching anything**

```bash
cd "$(git rev-parse --show-toplevel)"
./gradlew test --console=plain 2>&1 | tail -3
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob("build/test-results/test/*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"BASELINE {t} tests, {f} failures, {e} errors, {s} skipped")
PY
```

Expected: `BUILD SUCCESSFUL` and `BASELINE 324 tests, 0 failures, 0 errors, 0 skipped`. **If the count differs from 324, stop and report** — the plan's acceptance criterion is calibrated to that number and a different baseline means the tree is not what this plan was written against.

- [ ] **Step 2: Create the subproject**

```bash
mkdir -p basquin-core/src/main/java/agent
cat > basquin-core/build.gradle <<'EOF'
// basquin-core — the framework-neutral measurement core (DD-043 §4.1).
//
// Deliberately dependency-free: it must be consumable by the agent (bootclasspath),
// the driver, the Tomcat valve, and later a Maven-built Quarkus extension. Anything
// added here has to be available in all four, so add nothing without checking.
//
// The Java package is `agent`, not `com.basquin.core`. See the Package Decision in
// docs/superpowers/plans/2026-07-25-dd043-pr1-basquin-core.md: renaming it would make
// these classes child-first in GenericRunner's reset loader and yield a fresh
// ResultStore per reset.
plugins {
    id 'java'
}

group = 'com.basquin'
version = '0.3.0'
sourceCompatibility = '17'
targetCompatibility = '17'

repositories { mavenCentral() }

jar {
    archiveBaseName = 'basquin-core'
}
EOF
printf "include 'basquin-core'\n" >> settings.gradle
tail -4 settings.gradle
printf "basquin-core/build\n" >> .gitignore
tail -1 .gitignore
```

Expected: `settings.gradle` now includes `basquin-core` alongside `tomcat-war` and `tomcat-valve`, and `.gitignore` gains `basquin-core/build` alongside the existing `tomcat-war/build`/`tomcat-valve/build` entries — a new subproject gets its own build directory.

- [ ] **Step 3: Move the two classes with `git mv` so history follows**

```bash
git mv agent/Invariants.java  basquin-core/src/main/java/agent/Invariants.java
git mv agent/ResultStore.java basquin-core/src/main/java/agent/ResultStore.java
git status --short | head -5
head -1 basquin-core/src/main/java/agent/Invariants.java basquin-core/src/main/java/agent/ResultStore.java
```

Expected: two renames staged, and **both files still declare `package agent;`** — do not edit the package line.

- [ ] **Step 4: Wire the root project to depend on it**

In `build.gradle`, inside the existing top-level `dependencies { … }` block (the one near line 95 that already has `fuzzImplementation`/`coverageImplementation` entries), add:

```groovy
    // DD-043 PR-1: the framework-neutral measurement core (Invariants, ResultStore).
    // `api` rather than `implementation` so the valve's `compileOnly project(':')` still
    // resolves these types transitively at compile time.
    api project(':basquin-core')
```

`api` requires the `java-library` plugin. Check which plugins the root applies:

```bash
grep -n "^plugins" -A6 build.gradle
```

If it applies `java` but not `java-library`, add `id 'java-library'` to that block. If adding `java-library` changes any other configuration's behaviour, prefer `implementation project(':basquin-core')` plus an explicit `compileOnly project(':basquin-core')` in `tomcat-valve/build.gradle`, and record which you chose in the report.

- [ ] **Step 5: Verify every shipped jar bundles the core classes — there are two, not one**

`build.gradle` builds **two** jars that ship to operators: the agent fat jar (`jar` task,
`Premain-Class: agent.Agent`) from `configurations.runtimeClasspath`, and `runnerJar` — the campaign
driver's `ENTRYPOINT` (`deploy/runner-image/Dockerfile:16`) — built from the `coverage` source set's
own, **separate** runtime classpath, which does not automatically inherit a dependency added to the
root project's main `dependencies { }` block. Verifying only the first jar proves nothing about the
second, and the second is what actually ships to the campaign driver.

Verify both jars by exact filename, not by glob. `unzip -l` treats extra command-line arguments as
in-archive member filters, not additional archives, so `unzip -l build/libs/basquin-*.jar` silently
prints nothing once the glob expands to two or more files, instead of failing loudly:

```bash
./gradlew jar runnerJar --console=plain 2>&1 | tail -3
unzip -l build/libs/basquin-0.3.0.jar        | grep -E "agent/(Invariants|ResultStore)\.class"
unzip -l build/libs/basquin-0.3.0-runner.jar | grep -E "agent/(Invariants|ResultStore)\.class"
```

Expected: both class entries present in **both** jars. If either is absent, that jar's consumer will
`NoClassDefFoundError` at runtime while every test passes, because tests run on the classpath, not on
the jar. This manual check is necessarily incomplete — it is easy to verify one jar and forget the
second exists — which is why the shipped implementation also adds a permanent Gradle task,
`verifyShippedJarsContainCore` (`build.gradle:175`, wired into `check` at `:217`), that asserts this
on every build rather than only when someone remembers to run this step by hand.

- [ ] **Step 6: Verify the valve still compiles and tests still pass**

```bash
./gradlew :tomcat-valve:jar test --console=plain 2>&1 | tail -5
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob("build/test-results/test/*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"AFTER {t} tests, {f} failures, {e} errors, {s} skipped")
PY
```

Expected: `BUILD SUCCESSFUL`, and `AFTER 324 tests, 0 failures, 0 errors, 0 skipped` — the same numbers as Step 1. A changed test count means files moved between source sets unintentionally.

- [ ] **Step 7: Commit**

```bash
git add -A settings.gradle build.gradle .gitignore basquin-core
git commit -m "refactor(dd043): extract basquin-core — Invariants + ResultStore into their own artifact

Creates the :basquin-core subproject so a Maven-built Quarkus extension (PR-2) can
depend on the measurement core without depending on the agent, the valve, or Tomcat.

The Java package stays \`agent\`. Renaming to com.basquin.core would stop matching
GenericRunner.parentFirst()'s \"agent.\" prefix; with targetPrefix empty (its default)
the classes would load child-first and the reset loader would hand out a fresh
ResultStore per reset — results written to one instance, polled from another. Task 2
adds the guard test; the rename is deferred to its own change.

Behaviour unchanged: 324 tests, 0 failures, before and after."
```

---

### Task 2: Pin the reset-loader hazard with a test

Without this, the Package Decision is a comment someone will delete.

**Files:**
- Create: `test/agent/ResetLoaderParentFirstTest.java`

**Interfaces:**
- Consumes: `runner.GenericRunner`'s nested `ChildFirstURLClassLoader` and its `parentFirst(String)` predicate.
- Produces: a failing test the moment a core class stops being parent-first.

- [ ] **Step 1: Determine how to reach the predicate**

`ChildFirstURLClassLoader` and `parentFirst` are private/nested (`runner/GenericRunner.java:192-221`). Inspect the exact modifiers and pick the least invasive access:

```bash
sed -n '185,222p' runner/GenericRunner.java
grep -n "class ChildFirstURLClassLoader\|private boolean parentFirst" runner/GenericRunner.java
```

Prefer, in order: (a) if the nested class is package-private and the test is in a package that can see it, instantiate directly; (b) reflection on the nested class; (c) widening `parentFirst` to package-private **and nothing more**. Record which you used and why in the report. Do not restructure `GenericRunner` to make testing easier — that is behaviour-adjacent and out of scope.

- [ ] **Step 2: Write the failing test**

The assertion is about the *classes basquin-core owns*, named explicitly so the test breaks on a rename rather than following it:

```java
package agent;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * DD-043 PR-1 guard. basquin-core's classes MUST load parent-first in GenericRunner's
 * reset ClassLoader. If they do not, each reset gets a fresh ResultStore and per-request
 * results are written to one instance and polled from another — DD-040's defect class,
 * silently.
 *
 * This is why basquin-core keeps `package agent` instead of `com.basquin.core`: the
 * predicate matches on the literal "agent." prefix, and targetPrefix defaults to empty,
 * which disables the only other clause that would have covered a renamed package.
 */
public class ResetLoaderParentFirstTest {

    @Test
    public void coreClassesLoadParentFirst() throws Exception {
        assertTrue("agent.ResultStore must be parent-first or the reset loader forks the store",
                isParentFirst("agent.ResultStore"));
        assertTrue("agent.Invariants must be parent-first",
                isParentFirst("agent.Invariants"));
    }

    @Test
    public void aRenamedCorePackageWouldNotBeParentFirst() throws Exception {
        // Pins WHY the package is not renamed. If this ever passes, the predicate has been
        // taught about the new prefix and the rename is safe to do.
        assertFalse("com.basquin.core.* is not covered by parentFirst with an empty targetPrefix",
                isParentFirst("com.basquin.core.ResultStore"));
    }

    /** Invokes GenericRunner's reset-loader predicate for `name` with an empty targetPrefix. */
    private boolean isParentFirst(String name) throws Exception {
        // Implement per the access route chosen in Step 1.
        throw new UnsupportedOperationException("wire to GenericRunner.parentFirst per Step 1");
    }
}
```

Replace the `isParentFirst` body with the access route from Step 1. Keep both test methods: the first guards the invariant, the second documents the constraint and becomes the signal that a rename has been made safe.

- [ ] **Step 3: Run it and watch it fail for the right reason**

```bash
./gradlew test --tests 'agent.ResetLoaderParentFirstTest' --console=plain 2>&1 | tail -12
```

Expected before wiring: failure from `UnsupportedOperationException`, not from an assertion. That confirms the test executes.

- [ ] **Step 4: Wire it, then confirm it passes and can fail**

After implementing `isParentFirst`, run it again — both tests must pass. Then **prove it can fail**: temporarily change the first assertion's argument to `"com.basquin.core.ResultStore"`, re-run, confirm it fails, and revert. A guard test that cannot fail is the defect it exists to prevent.

```bash
./gradlew test --tests 'agent.ResetLoaderParentFirstTest' --console=plain 2>&1 | tail -6
```

- [ ] **Step 5: Full suite and commit**

```bash
./gradlew test --console=plain 2>&1 | tail -3
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for p in glob.glob("build/test-results/test/*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
print(f"AFTER {t} tests, {f} failures, {e} errors, {s} skipped")
PY
git add -A test runner
git commit -m "test(dd043): pin basquin-core's classes as parent-first in the reset loader

GenericRunner.parentFirst() matches the literal \"agent.\" prefix and targetPrefix
defaults to empty, so a core class renamed out of that package would load child-first
and the reset loader would hand out a fresh ResultStore per reset. Mutation-checked:
pointing the assertion at com.basquin.core.ResultStore fails it.

The second test asserts the renamed package is NOT covered — it documents the
constraint, and becomes the signal that a future rename has been made safe."
```

Expected: **326 tests** (324 + 2), 0 failures.

---

### Task 3: Record the decision where the next implementer will read it

**Files:**
- Modify: `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md` (§4.1)
- Modify: `docs/ROADMAP.md` (DD-043 ladder row)

- [ ] **Step 1: Add the package constraint to spec §4.1**

§4.1 currently gives the extraction table (what moves, what stays) without saying anything about the Java package. Add a short subsection recording: the package stays `agent`; the reason is `GenericRunner.parentFirst()` plus the empty `targetPrefix` default; the guard is `test/agent/ResetLoaderParentFirstTest.java`; and a rename is permissible only once the predicate is taught the new prefix, at which point the second test flips.

State it as a constraint on PR-2, since PR-2 builds the Quarkus extension against this artifact and would otherwise be the natural place to "tidy" the package.

- [ ] **Step 2: Update the DD-043 ladder row**

Mark PR-1 as done in `docs/ROADMAP.md`'s DD-043 row and note that PR-2 is cleared. Keep the two unmeasured entry gates (§8.2 plugin-execution injection for PR-4, §6.2 native JFR for PR-5) exactly as they are — PR-1 does not touch them.

- [ ] **Step 3: Commit and push**

```bash
git add docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md docs/ROADMAP.md
git commit -m "docs(dd043): record the basquin-core package constraint and clear PR-2"
git push -u origin dd043-pr1-basquin-core
```

---

## Execution order

Strictly sequential: Task 1 → Task 2 → Task 3. Task 2 needs the module to exist; Task 3 records what the first two decided. Nothing here is parallelisable, and every task runs the same Gradle build directory, so concurrent tasks would collide the way the Phase-0 plan's wave table did.
