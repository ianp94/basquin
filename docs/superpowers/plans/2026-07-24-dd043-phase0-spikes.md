# DD-043 PR-0 — Phase 0 Spikes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the four questions that gate DD-043 — with evidence files, not opinions — and amend the spec wherever the evidence contradicts it.

**Architecture:** A throwaway Quarkus fixture app, built inside a container that carries both JDK 25 and `native-image`, is subjected to four independent experiments (S1–S4). Nothing here is product code: the fixture, the probe filter, and the probe Maven participant all live under `bench-results/dd043-spikes-2026-07-24/` and are deleted or ignored afterwards. The deliverable is `REPORT.md` plus raw evidence.

**Tech Stack:** Quarkus 3.37.3 (pinned to match `rest-heroes`), JDK 25, Maven 3.9.x, GraalVM/Mandrel via the Quarkus builder image, JaCoCo 0.8.15, Docker.

## Global Constraints

- **Quarkus version is exactly `3.37.3`** — the version `rest-heroes/pom.xml` pins. A spike on a different version answers a different question.
- **`maven.compiler.release` is `25`.** The host JDK is 17; no Maven step may run on the host JVM.
- **JaCoCo version is exactly `0.8.15`** — matches the target's `jacoco.version` property.
- **No product code.** Nothing under `agent/`, `runner/`, `tomcat-valve/`, `operator/`, or a new `basquin-*` module may be created or modified by this PR. Spike scaffolding lives only under `bench-results/dd043-spikes-2026-07-24/`.
- **Native builds are serialized.** `native-image` wants ≥4 cores and several GB; this host has 8 cores / 15 GB. Never run two native builds concurrently, and never run one while a benchmark campaign is active.
- **Every spike records a verdict of `CONFIRMED` / `REFUTED` / `INCONCLUSIVE`** against the spec's stated claim, with the raw command output that supports it. "It seemed to work" is not a verdict.
- **A spike that refutes the spec must say which spec section is now void.** Task 6 carries those amendments.

---

## File Structure

Everything is created under `bench-results/dd043-spikes-2026-07-24/`:

| Path | Responsibility |
|---|---|
| `README.md` | How to re-run every spike from scratch |
| `REPORT.md` | The deliverable: four verdicts, evidence pointers, spec amendments |
| `env/build.sh` | The one containerized build entrypoint every spike calls |
| `env/ENVIRONMENT.md` | Which image was chosen for JDK 25 + native-image, and why |
| `fixture/` | Generated Quarkus fixture app (throwaway) |
| `s1-coverage/` | S1 evidence: exec dumps, analyzer output, class-init report |
| `s2-memory/` | S2 evidence: heap series, GC behaviour, monitoring-flag results |
| `s3-boundary/` | S3 evidence: per-disposition hook logs |
| `s4-injection/` | S4 evidence: banners, the probe participant, its sources |

---

### Task 1: Build vehicle and fixture app

Establishes the containerized JDK-25 build that BLOCKER 1 forced, and produces the fixture every later task uses. Nothing else can start until this works.

**Files:**
- Create: `bench-results/dd043-spikes-2026-07-24/env/build.sh`
- Create: `bench-results/dd043-spikes-2026-07-24/env/ENVIRONMENT.md`
- Create: `bench-results/dd043-spikes-2026-07-24/fixture/` (generated)
- Create: `bench-results/dd043-spikes-2026-07-24/README.md`

**Interfaces:**
- Produces: `env/build.sh <maven-args…>` — runs Maven inside a container carrying JDK 25 and `native-image`, with a spike-local `.m2` cache, from the fixture directory. Every later task invokes exactly this.
- Produces: `fixture/` — a Quarkus 3.37.3 app with routes `/ok`, `/boom`, `/redirect`, `/slow`, `/alloc`, and two classes of which one is never exercised.

- [ ] **Step 1: Create the spike directory and pick the build image**

The image must carry **both** JDK 25 and `native-image`, so that no step runs on the host JVM and native builds need no Docker socket. Try in order and keep the first that works:

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
mkdir -p "$SPIKE"/{env,fixture,s1-coverage,s2-memory,s3-boundary,s4-injection}

# Candidate A (preferred): Mandrel builder image — has JDK + native-image, no docker socket needed
docker run --rm quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  bash -lc 'java -version 2>&1; native-image --version 2>&1' | tee "$SPIKE/env/probe-A.txt"
```

Expected: prints a JDK 25 version **and** a native-image version. If the tag does not exist or the JDK is not 25, record that in `probe-A.txt` and try Candidate B:

```bash
# Candidate B: JDK 25 Maven image + docker socket, native-image runs as a sibling container
docker run --rm maven:3.9-eclipse-temurin-25 java -version 2>&1 | tee "$SPIKE/env/probe-B.txt"
```

- [ ] **Step 2: Write `env/build.sh` around the chosen image**

Written for Candidate A. If Candidate B was chosen, set `IMAGE` to the Maven image, add `-v /var/run/docker.sock:/var/run/docker.sock`, and append `-Dquarkus.native.container-build=true` to native invocations.

```bash
cat > bench-results/dd043-spikes-2026-07-24/env/build.sh <<'EOF'
#!/usr/bin/env bash
# The single containerized build entrypoint for all DD-043 Phase-0 spikes.
# Usage: env/build.sh <maven args...>      e.g. env/build.sh package -Dnative
set -euo pipefail
SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${BASQUIN_SPIKE_IMAGE:-quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25}"
mkdir -p "$SPIKE_DIR/.m2"
exec docker run --rm \
  -v "$SPIKE_DIR/fixture":/w -w /w \
  -v "$SPIKE_DIR/.m2":/m2 \
  -u "$(id -u):$(id -g)" \
  -e MAVEN_OPTS="-Duser.home=/m2 ${EXTRA_MAVEN_OPTS:-}" \
  ${EXTRA_DOCKER_ARGS:-} \
  "$IMAGE" ./mvnw -B "$@"
EOF
chmod +x bench-results/dd043-spikes-2026-07-24/env/build.sh
```

`EXTRA_MAVEN_OPTS` is the seam S4 uses to pass `-Dmaven.ext.class.path`; `EXTRA_DOCKER_ARGS` is how it mounts the probe jar. Both exist now so S4 does not have to modify this file.

- [ ] **Step 3: Generate the fixture app**

```bash
cd bench-results/dd043-spikes-2026-07-24
docker run --rm -v "$PWD/fixture":/w -w /w -v "$PWD/.m2":/m2 -u "$(id -u):$(id -g)" \
  -e MAVEN_OPTS="-Duser.home=/m2" quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  mvn -B io.quarkus.platform:quarkus-maven-plugin:3.37.3:create \
    -DprojectGroupId=com.basquin.spike -DprojectArtifactId=fixture \
    -Dextensions=rest -DnoCode
```

Expected: `fixture/fixture/pom.xml` exists. Flatten it so `build.sh`'s `-w /w` is the project root:

```bash
mv fixture/fixture/* fixture/fixture/.mvn fixture/fixture/mvnw* fixture/ 2>/dev/null; rmdir fixture/fixture
grep -n "quarkus.platform.version\|maven.compiler.release" fixture/pom.xml
```

Expected: `quarkus.platform.version` is `3.37.3`. Set `maven.compiler.release` to `25` if the archetype chose otherwise — the point is to reproduce the target's toolchain, not the archetype's default.

- [ ] **Step 4: Add the fixture routes and the never-exercised class**

Two classes matter for S1: one whose methods run, one whose methods never do.

```java
// fixture/src/main/java/com/basquin/spike/Probe.java
package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/")
public class Probe {
    @GET @Path("ok")       public String ok()       { return "ok"; }
    @GET @Path("boom")     public String boom()     { throw new RuntimeException("deliberate"); }
    @GET @Path("redirect") public Response redirect() {
        return Response.status(302).header("Location", "/ok").build();
    }
    @GET @Path("slow")     public String slow() throws Exception { Thread.sleep(5000); return "slow"; }
    @GET @Path("alloc")    public String alloc() {
        byte[][] keep = new byte[64][];
        for (int i = 0; i < 64; i++) keep[i] = new byte[64 * 1024];
        return "alloc " + keep.length;
    }
}
```

```java
// fixture/src/main/java/com/basquin/spike/NeverCalled.java
package com.basquin.spike;

/** No route reaches this class. S1 asserts its coverage reads exactly zero. */
public class NeverCalled {
    public int add(int a, int b) { return a + b; }
    public int mul(int a, int b) { return a * b; }
}
```

- [ ] **Step 5: Build JVM mode and confirm the toolchain**

```bash
bench-results/dd043-spikes-2026-07-24/env/build.sh package -DskipTests \
  2>&1 | tee bench-results/dd043-spikes-2026-07-24/env/build-jvm.log
```

Expected: `BUILD SUCCESS`, and `fixture/target/quarkus-app/quarkus-run.jar` exists. **This step alone falsifies or confirms BLOCKER 1's fix** — if it succeeds, a Java-25 build runs with a JDK-17 host, which is the whole point.

- [ ] **Step 6: Record the environment and commit**

Write `env/ENVIRONMENT.md` with: the chosen image and tag, its `java -version` and `native-image --version` output, and which candidate (A or B) was used with the reason. Write `README.md` with the exact re-run sequence.

```bash
git add bench-results/dd043-spikes-2026-07-24
git commit -m "spike(dd043): containerized JDK-25 build vehicle + fixture app (S0)"
```

---

### Task 2: S3 — request boundary hook semantics

Answers: does `addEndHandler` fire on errors, 3xx and client disconnects, and does `addHeadersEndHandler` survive a response rewrite? **JVM mode only — no native build**, so this runs concurrently with Task 3's JVM half.

**Files:**
- Create: `bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/BoundaryProbe.java`
- Create: `bench-results/dd043-spikes-2026-07-24/s3-boundary/findings.md`

**Interfaces:**
- Consumes: `env/build.sh` from Task 1; fixture routes `/ok`, `/boom`, `/redirect`, `/slow`.
- Produces: evidence that `addEndHandler` fires with an `AsyncResult` whose success flag distinguishes completion from disconnect — the fact §4.3 and §6.5 of the spec depend on.

- [ ] **Step 1: Write the probe filter**

```java
// fixture/src/main/java/com/basquin/spike/BoundaryProbe.java
package com.basquin.spike;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

public class BoundaryProbe {
    public void register(@Observes Filters filters) {
        filters.register(rc -> {
            long start = System.nanoTime();
            String id = "probe-" + System.nanoTime();
            rc.put("basquin.id", id);
            rc.addHeadersEndHandler(v ->
                rc.response().putHeader("X-Basquin-Req", id));
            rc.addEndHandler(ar -> {
                long ms = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("[PROBE] id=%s status=%d succeeded=%b cause=%s ms=%d%n",
                    id, rc.response().getStatusCode(), ar.succeeded(),
                    ar.succeeded() ? "-" : String.valueOf(ar.cause()), ms);
            });
            rc.next();
        }, 100);
    }
}
```

- [ ] **Step 2: Start the app in JVM mode**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/env/build.sh" package -DskipTests
docker run --rm -d --name spike-jvm -p 8080:8080 \
  -v "$PWD/$SPIKE/fixture/target/quarkus-app":/app \
  quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  java -jar /app/quarkus-run.jar
sleep 5; docker logs spike-jvm | tail -5
```

Expected: the Quarkus banner, including an `Installed features:` line.

- [ ] **Step 3: Exercise every disposition and capture the hook output**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
{
  echo "== /ok (200) ==";        curl -si localhost:8080/ok       | grep -i "^HTTP/\|X-Basquin-Req"
  echo "== /boom (500) ==";      curl -si localhost:8080/boom     | grep -i "^HTTP/\|X-Basquin-Req"
  echo "== /redirect (302) ==";  curl -si localhost:8080/redirect | grep -i "^HTTP/\|X-Basquin-Req"
  echo "== /slow (disconnect) =="; curl -s --max-time 1 localhost:8080/slow || echo "client aborted"
  sleep 6
} 2>&1 | tee "$SPIKE/s3-boundary/curl.txt"
docker logs spike-jvm 2>&1 | grep "\[PROBE\]" | tee "$SPIKE/s3-boundary/probe.log"
docker rm -f spike-jvm
```

- [ ] **Step 4: Record the verdict**

Write `s3-boundary/findings.md` with one row per disposition:

| Route | HTTP status | `X-Basquin-Req` present? | `addEndHandler` fired? | `ar.succeeded()` |
|---|---|---|---|---|

Then state the verdict. **CONFIRMED** requires all four rows to show `addEndHandler` firing, and the `/slow` row to show `succeeded=false`. If `/boom` shows no header, §4.4's claim that the id is "always safe" to write is refuted and the spec must say so.

- [ ] **Step 5: Commit**

```bash
git add bench-results/dd043-spikes-2026-07-24/s3-boundary \
        bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/BoundaryProbe.java
git commit -m "spike(dd043): S3 — boundary hook semantics across dispositions"
```

---

### Task 3: S4 — does augmentation honour an injected dependency?

Answers the spec's sharpest unknown: whether Quarkus's bootstrap resolver reads the mutated in-memory model or re-reads poms from disk. **No product code** — the probe participant injects an already-published extension (`quarkus-smallrye-openapi`, chosen because it is absent from the fixture and prints a distinctive feature name) and the acceptance is the banner.

**Files:**
- Create: `bench-results/dd043-spikes-2026-07-24/s4-injection/probe-participant/` (throwaway Maven project)
- Create: `bench-results/dd043-spikes-2026-07-24/s4-injection/findings.md`

**Interfaces:**
- Consumes: `env/build.sh`, and its `EXTRA_MAVEN_OPTS` / `EXTRA_DOCKER_ARGS` seams from Task 1.
- Produces: the verdict that gates §5 and §5.2 of the spec.

- [ ] **Step 1: Write the probe lifecycle participant**

```java
// s4-injection/probe-participant/src/main/java/com/basquin/spike/InjectProbe.java
package com.basquin.spike;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import javax.inject.Named;
import javax.inject.Singleton;

@Named("basquin-inject-probe")
@Singleton
public class InjectProbe extends AbstractMavenLifecycleParticipant {
    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject p : session.getProjects()) {
            Dependency d = new Dependency();
            d.setGroupId("io.quarkus");
            d.setArtifactId("quarkus-smallrye-openapi");
            d.setVersion("3.37.3");
            p.getModel().getDependencies().add(d);
            p.getDependencies().add(d);
            System.out.println("[INJECT-PROBE] added quarkus-smallrye-openapi to " + p.getArtifactId());
        }
    }
}
```

```xml
<!-- s4-injection/probe-participant/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.basquin.spike</groupId>
  <artifactId>inject-probe</artifactId>
  <version>1.0</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies>
    <dependency>
      <groupId>org.apache.maven</groupId><artifactId>maven-core</artifactId>
      <version>3.9.6</version><scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>javax.inject</groupId><artifactId>javax.inject</artifactId>
      <version>1</version><scope>provided</scope>
    </dependency>
  </dependencies>
  <build><plugins>
    <plugin>
      <groupId>org.codehaus.plexus</groupId><artifactId>plexus-component-metadata</artifactId>
      <version>2.1.1</version>
      <executions><execution><goals><goal>generate-metadata</goal></goals></execution></executions>
    </plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: Build the probe jar**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
docker run --rm -v "$PWD/$SPIKE/s4-injection/probe-participant":/w -w /w \
  -v "$PWD/$SPIKE/.m2":/m2 -u "$(id -u):$(id -g)" -e MAVEN_OPTS="-Duser.home=/m2" \
  maven:3.9-eclipse-temurin-17 mvn -B package
ls "$SPIKE/s4-injection/probe-participant/target/inject-probe-1.0.jar"
```

Expected: the jar exists.

- [ ] **Step 3: Baseline — confirm the extension is absent without injection**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/env/build.sh" package -DskipTests 2>&1 | grep -i "Installed features" \
  | tee "$SPIKE/s4-injection/banner-baseline.txt"
```

Expected: an `Installed features:` line **without** `smallrye-openapi`. If it is already present, pick a different probe extension — the test is meaningless otherwise.

- [ ] **Step 4: JVM mode with injection — the core question**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
PROBE_ABS="$PWD/$SPIKE/s4-injection/probe-participant/target"
EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar" \
  "$SPIKE/env/build.sh" package -DskipTests 2>&1 \
  | tee "$SPIKE/s4-injection/build-jvm-injected.log" \
  | grep -i "INJECT-PROBE\|Installed features"
```

Two distinct signals, and **both** must be checked:
- `[INJECT-PROBE] added …` proves the participant ran (the easy half).
- `Installed features: … smallrye-openapi …` proves **augmentation honoured it** (the half that matters).

The failure this spike exists to catch is the first line appearing without the second: a successful build producing a silently uninstrumented artifact.

- [ ] **Step 5: Native mode with injection**

Serialize this — no other native build may run concurrently.

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
PROBE_ABS="$PWD/$SPIKE/s4-injection/probe-participant/target"
EXTRA_DOCKER_ARGS="-v $PROBE_ABS:/probe" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/inject-probe-1.0.jar" \
  "$SPIKE/env/build.sh" package -DskipTests -Dnative 2>&1 \
  | tee "$SPIKE/s4-injection/build-native-injected.log"
"$SPIKE/fixture/target/fixture-1.0.0-SNAPSHOT-runner" 2>&1 | head -20 \
  | tee "$SPIKE/s4-injection/banner-native.txt" &
sleep 10; curl -s localhost:8080/ok; kill %1
```

Expected: the native binary's banner also lists `smallrye-openapi`.

- [ ] **Step 6: Record the verdict and commit**

Write `s4-injection/findings.md` stating, for JVM and native separately: did the participant run, did the banner show the injected feature, and the verdict. If the banner lacks it, record §5.1's disk-re-read hypothesis as **CONFIRMED** and note that §5 needs a different mechanism before PR-3 can proceed.

```bash
git add bench-results/dd043-spikes-2026-07-24/s4-injection
git commit -m "spike(dd043): S4 — does Quarkus augmentation honour an injected dependency"
```

---

### Task 4: S2 — memory, GC and monitoring flags under SubstrateVM

Answers four sub-questions §6.1 depends on. **Native build — serialize against Tasks 3 and 5.**

**Files:**
- Create: `bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/MemProbe.java`
- Create: `bench-results/dd043-spikes-2026-07-24/s2-memory/findings.md`

**Interfaces:**
- Consumes: `env/build.sh`; fixture route `/alloc`.
- Produces: verdicts on `Runtime` behaviour, `System.gc()`, post-response quiescence, and whether `quarkus.native.monitoring` accepts `nmt`.

- [ ] **Step 1: Add the memory probe route**

```java
// fixture/src/main/java/com/basquin/spike/MemProbe.java
package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/mem")
public class MemProbe {
    @GET @Path("used")
    public String used() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) + "," + r.totalMemory() + "," + r.maxMemory();
    }

    @GET @Path("gc")
    public String gc() {
        Runtime r = Runtime.getRuntime();
        long before = r.totalMemory() - r.freeMemory();
        System.gc();
        long after = r.totalMemory() - r.freeMemory();
        return before + "," + after;
    }
}
```

- [ ] **Step 2: Test whether `nmt` is an accepted monitoring value**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/env/build.sh" package -DskipTests -Dnative -Dquarkus.native.monitoring=jfr,nmt 2>&1 \
  | tee "$SPIKE/s2-memory/build-native-nmt.log" | tail -20
```

Expected: either `BUILD SUCCESS`, or a config error naming the invalid enum value. If it fails, re-run with the documented fallback and record which form works:

```bash
"$SPIKE/env/build.sh" package -DskipTests -Dnative \
  -Dquarkus.native.monitoring=jfr \
  -Dquarkus.native.additional-build-args=--enable-monitoring=nmt 2>&1 \
  | tee "$SPIKE/s2-memory/build-native-nmt-fallback.log" | tail -20
```

- [ ] **Step 3: Measure idle drift, allocation response and GC behaviour**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/fixture/target/fixture-1.0.0-SNAPSHOT-runner" > "$SPIKE/s2-memory/app.log" 2>&1 &
sleep 3
{
  echo "== idle series (30 samples, 1s apart, NO requests between) =="
  for i in $(seq 1 30); do echo "$i $(curl -s localhost:8080/mem/used)"; sleep 1; done
  echo "== alloc: used before / after /alloc =="
  echo "before $(curl -s localhost:8080/mem/used)"; curl -s localhost:8080/alloc >/dev/null
  echo "after  $(curl -s localhost:8080/mem/used)"
  echo "== post-response quiescence: 5 samples after the last request =="
  for i in $(seq 1 5); do echo "$i $(curl -s localhost:8080/mem/used)"; sleep 1; done
  echo "== System.gc() before,after =="; curl -s localhost:8080/mem/gc
} 2>&1 | tee "$SPIKE/s2-memory/series.txt"
pkill -f fixture-1.0.0-SNAPSHOT-runner
```

Note the idle series is itself the **positive-noise control** §7.3 requires: the `/mem/used` polls are the only traffic, so any monotonic climb is drift, not attribution.

**Scope limit, stated rather than glossed:** the fixture is a plain REST app with no database, so these samples measure the *floor* — Quarkus and SubstrateVM background activity alone. Spec §7.1 also asks whether **post-response work quiesces on Hibernate Reactive**, and this fixture cannot answer that: connection-pool maintenance, the idle reaper and deferred `Uni` continuations only exist once a reactive datasource does. That sub-question is **deferred to the `rest-heroes` JVM-mode cell in PR-2**, where it can be measured against the real stack. Record it in `findings.md` as `DEFERRED`, not as answered — a floor measured without a database says nothing about the polluter §6.1 actually worries about.

- [ ] **Step 4: Record the verdict and commit**

`s2-memory/findings.md` answers, each with a number from `series.txt`:
1. Does `Runtime.totalMemory()/freeMemory()` return plausible, changing values in native? (**CONFIRMED/REFUTED**)
2. Does the idle series drift, and by how much per minute? This is the floor beneath any per-request heap delta.
3. Does `/alloc` produce a delta distinguishable from that floor? If not, §6.1's heap invariant is not viable on native and the spec must say so.
4. Does `System.gc()` reduce used heap? (Decides whether DD-002's `gcBeforeMeasure` is usable here.)
5. Which monitoring-flag form works.

```bash
git add bench-results/dd043-spikes-2026-07-24/s2-memory \
        bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/MemProbe.java
git commit -m "spike(dd043): S2 — Runtime/GC/monitoring behaviour under SubstrateVM"
```

---

### Task 5: S1 — offline JaCoCo under AOT

The spike most likely to void a spec section. Tests three failure signatures, not one. **Native build — serialize against Tasks 3 and 4.**

**Files:**
- Modify: `bench-results/dd043-spikes-2026-07-24/fixture/pom.xml` (add the JaCoCo offline-instrumentation execution)
- Create: `bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/CoverageProbe.java`
- Create: `bench-results/dd043-spikes-2026-07-24/s1-coverage/findings.md`

**Interfaces:**
- Consumes: `env/build.sh`; fixture classes `Probe` (exercised) and `NeverCalled` (never exercised).
- Produces: the verdict gating §6.4, and the corrected S1 failure signatures for the spec.

- [ ] **Step 1: Add offline instrumentation and the JaCoCo runtime to the fixture**

```xml
<!-- fixture/pom.xml — inside <build><plugins> -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.15</version>
  <executions>
    <execution>
      <id>offline-instrument</id>
      <phase>process-classes</phase>
      <goals><goal>instrument</goal></goals>
    </execution>
  </executions>
</plugin>
```

```xml
<!-- fixture/pom.xml — inside <dependencies> -->
<dependency>
  <groupId>org.jacoco</groupId>
  <artifactId>org.jacoco.agent</artifactId>
  <version>0.8.15</version>
  <classifier>runtime</classifier>
</dependency>
```

The `instrument` goal backs the originals up to `target/generated-classes/jacoco`. §6.4 requires those preserved — the analyzer must run against them, not the instrumented copies.

- [ ] **Step 2: Add the coverage dump route**

Reads `RuntimeData` directly rather than through the agent-boot path, which is exactly what §6.4 specifies and what may not survive native.

```java
// fixture/src/main/java/com/basquin/spike/CoverageProbe.java
package com.basquin.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.io.ByteArrayOutputStream;

@Path("/coverage")
public class CoverageProbe {
    @GET
    @Produces("application/octet-stream")
    public byte[] dump() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object agent = Class.forName("org.jacoco.agent.rt.RT")
            .getMethod("getAgent").invoke(null);
        byte[] data = (byte[]) agent.getClass()
            .getMethod("getExecutionData", boolean.class).invoke(agent, false);
        out.write(data);
        return out.toByteArray();
    }
}
```

- [ ] **Step 3: Build native with instrumentation, and capture which classes were build-time initialized**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/env/build.sh" package -DskipTests -Dnative \
  -Dquarkus.native.additional-build-args=-H:+PrintClassInitialization 2>&1 \
  | tee "$SPIKE/s1-coverage/build-native.log" | tail -30
find "$SPIKE/fixture/target" -name "*class_initialization*" -o -name "generated-classes" -type d \
  | tee "$SPIKE/s1-coverage/artifacts.txt"
```

Expected: `BUILD SUCCESS`, and a class-initialization report. Copy it into `s1-coverage/` — §7.1 requires recording which classes Quarkus shifted to runtime init, because that sets the expected coverage floor.

- [ ] **Step 4: Dump coverage at three points**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
"$SPIKE/fixture/target/fixture-1.0.0-SNAPSHOT-runner" > "$SPIKE/s1-coverage/app.log" 2>&1 &
sleep 3
curl -s localhost:8080/coverage -o "$SPIKE/s1-coverage/t0-startup.exec"   # before any route
curl -s localhost:8080/ok >/dev/null
curl -s localhost:8080/coverage -o "$SPIKE/s1-coverage/t1-after-ok.exec"
curl -s localhost:8080/alloc >/dev/null; curl -s localhost:8080/redirect >/dev/null
curl -s localhost:8080/coverage -o "$SPIKE/s1-coverage/t2-after-more.exec"
ls -l "$SPIKE"/s1-coverage/*.exec
pkill -f fixture-1.0.0-SNAPSHOT-runner
```

Expected: three non-empty `.exec` files. An empty or failed `t0` means the `RuntimeData` path does not survive native — signature (iii), and §6.4 is void as written.

- [ ] **Step 5: Analyze against the *preserved originals* and check all three signatures**

```bash
SPIKE=bench-results/dd043-spikes-2026-07-24
for t in t0-startup t1-after-ok t2-after-more; do
  echo "=== $t ==="
  docker run --rm -v "$PWD/$SPIKE":/s -w /s \
    -v "$PWD/$SPIKE/.m2":/m2 -u "$(id -u):$(id -g)" -e MAVEN_OPTS="-Duser.home=/m2" \
    maven:3.9-eclipse-temurin-17 \
    java -jar /m2/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar \
      report "/s/s1-coverage/$t.exec" \
      --classfiles /s/fixture/target/generated-classes/jacoco \
      --csv "/s/s1-coverage/$t.csv"
  grep -E "NeverCalled|Probe" "$SPIKE/s1-coverage/$t.csv"
done 2>&1 | tee "$SPIKE/s1-coverage/analysis.txt"
```

If the CLI jar is not in the cache, fetch it first with `mvn dependency:get -Dartifact=org.jacoco:org.jacoco.cli:0.8.15:jar:nodeps`.

The three signatures, each a distinct assertion:

| Signature | Assertion | Meaning if it fires |
|---|---|---|
| (i) frozen probes | `t2` covered counts **>** `t1` **>** `t0` | if equal throughout, probes froze — §6.4 void |
| (ii) **inflated baseline** | `NeverCalled` shows **0** covered instructions at `t0`, `t1` *and* `t2` | if non-zero, build-time init polluted the image heap; the headline coverage % is wrong and "must increase" cannot detect it |
| (iii) class-id mismatch | the report resolves both classes by name with non-zero *total* instructions | if classes are missing or unmatched, the analyzer cannot match exec data to the originals |

- [ ] **Step 6: Record the verdict and commit**

`s1-coverage/findings.md` states each signature's result with the CSV numbers, then an overall verdict. Signature (ii) firing is the important one to state plainly: coverage would still *increase*, so every downstream control would pass while the published percentage is wrong.

```bash
git add bench-results/dd043-spikes-2026-07-24/s1-coverage \
        bench-results/dd043-spikes-2026-07-24/fixture/pom.xml \
        bench-results/dd043-spikes-2026-07-24/fixture/src/main/java/com/basquin/spike/CoverageProbe.java
git commit -m "spike(dd043): S1 — offline JaCoCo correctness under AOT (three signatures)"
```

---

### Task 6: Consolidate findings and amend the spec

**Files:**
- Create: `bench-results/dd043-spikes-2026-07-24/REPORT.md`
- Modify: `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`
- Modify: `docs/ROADMAP.md`

**Interfaces:**
- Consumes: the four `findings.md` files.
- Produces: the go/no-go that gates PR-1 through PR-5.

- [ ] **Step 1: Write `REPORT.md`**

Lead with a verdict table — spike, question, verdict, spec sections affected — then one section per spike quoting the decisive evidence. Every number is copied from a committed artifact; none are typed from memory.

- [ ] **Step 2: Amend the spec where evidence contradicts it**

For each **REFUTED** verdict, edit the named section rather than appending a note, and add a line to the spec's status block recording which spike forced the change. The two that can void design sections:
- S1 refuted → §6.4 is void; §2's full-parity goal reopens, since coverage is what forces the compile-in step.
- S4 refuted → §5's mechanism cannot meet the no-source-modification constraint; §5.1's degradation becomes the norm.

If neither is refuted, say so explicitly in the status block — a silent absence of amendments is indistinguishable from not having checked.

- [ ] **Step 3: Update the roadmap ladder**

Add a DD-043 row to `docs/ROADMAP.md`'s ladder with its state, and record whether Phase 0 cleared PR-1 to start.

- [ ] **Step 4: Commit and push**

```bash
git add bench-results/dd043-spikes-2026-07-24/REPORT.md \
        docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md docs/ROADMAP.md
git commit -m "spike(dd043): Phase-0 report + spec amendments forced by the evidence"
git push
```

- [ ] **Step 5: Hand off per the programmer directive**

The PR already exists (#98). Once checks are green:

```bash
set -a; . ~/.config/basquin-bot/env; set +a
BOT_TOKEN=$(bash ~/.config/basquin-bot/mint-token.sh)
GH_TOKEN="$BOT_TOKEN" gh pr edit 98 --add-label ready-for-approver
scripts/agent-bus/send approver review-requested --from programmer \
  --repo ianp94/basquin --pr 98 --note "DD-043 spec + Phase-0 spike evidence; S1/S4 verdicts gate PR-1"
```

---

## Execution order — fully serialized

**Tasks 1 → 6 run one at a time.** An earlier draft of this plan proposed running Task 2 and Task 3's JVM steps concurrently. That was wrong, and the reason is worth recording so it is not reintroduced:

- Both tasks invoke `env/build.sh`, which mounts the **same** `fixture/` directory as the container workdir and builds into `fixture/target/`. Two concurrent builds race on one output tree.
- Both share `$SPIKE/.m2` as the Maven local repository, and Maven has no robust cross-process locking — concurrent resolution into one local repo can corrupt artifacts.

Either failure produces *plausible-looking but wrong* spike output. On a PR whose entire subject is trustworthy measurement, corrupt build artifacts yielding bogus verdicts is the worst available outcome, and the parallelism it buys is a few minutes: Wave 2 was two short JVM-mode builds, while the three native builds must be serialized regardless and dominate wall-clock.

If parallelism is wanted later, the safe form is per-task isolation — its own `fixture/` copy and its own `.m2` (pre-warmed in Task 1, then copied) — not shared state with staggered timing.
