# Running Basquin outside Kubernetes (local development / contributor setup)

The [README](../README.md) and [OPERATOR-USAGE](OPERATOR-USAGE.md) push the **operator/Kubernetes**
path — Helm-install a published image, `basquin instrument` a Deployment, drive it with the CLI.
That's the product. But underneath it is a standalone harness that runs anywhere a JVM does, and
that's what this guide is about: build the jars yourself, stand up an unmodified target app on
Tomcat via `docker compose` with the Basquin valve/agent mounted in, then drive **explore** and
**load** with the standalone runner against `localhost` — no operator, no cluster, no image
registry.

## Who this is for

- **Contributors iterating on Basquin's own code** — the fast loop is edit → `./gradlew jar` →
  re-run, not edit → build an image → `kind load` it → wait for a rollout.
- **Anyone trying Basquin without a cluster** — a quick eval, a CI job on a plain runner, or driving
  an app you haven't containerized at all (see [USAGE.md § Use with your app](USAGE.md#use-with-your-app)).

For the full flag/command reference, see **[USAGE](USAGE.md)** — this guide is the workflow
narrative that ties those commands together, not a replacement for it. For the in-cluster operator
path, see **[OPERATOR-USAGE](OPERATOR-USAGE.md)**.

## Build from source

Java 17+ (CI verifies both 17 and 21; see `build.gradle`'s `sourceCompatibility`), Gradle wrapper
included — nothing else to install for the JVM side.

```bash
git clone https://github.com/ianp94/basquin.git
cd basquin

./gradlew build             # compile + run the test suite
```

The jar tasks that matter for standalone use, and the compose files that expect their output at
these exact paths:

| Task | Output | Used by |
|------|--------|---------|
| `./gradlew jar` | `build/libs/basquin-0.3.0.jar` — fat jar: agent + runner + examples | all three `docker-compose*.yml` variants, `java -cp` runs |
| `./gradlew :tomcat-valve:jar` | `tomcat-valve/build/libs/basquin-valve-0.3.0.jar` — the Tomcat valve | `docker-compose.valve.yml`, `docker-compose.valve9.yml`, `docker-compose.coverage.yml` |
| `./gradlew stageAgents` | `build/stage/basquin-agent.jar`, `build/stage/basquin-valve.jar` (+ depends on `copyJacocoAgent` → `build/jacoco/jacocoagent.jar`) | `docker-compose.coverage.yml`; version-independent mount paths so a version bump never silently mounts an empty directory |
| `./gradlew runnerJar` | `build/libs/basquin-0.3.0-runner.jar` — self-contained fat jar (coverage + main + JaCoCo), `Main-Class: runner.coverage.CoverageGuidedRun` | the operator's driver Job image (`deploy/runner-image`); also directly runnable standalone with `java -jar` when you don't want to go through Gradle |

A one-liner that builds everything the Tomcat-valve path needs:

```bash
./gradlew jar :tomcat-valve:jar copyJacocoAgent
```

## Run a target app locally

Four `docker-compose*.yml` files at the repo root stand up **an unmodified target app** on Tomcat
with the Basquin jars bind-mounted in — no rebuild, no packaging. Which one to use depends on the
target's servlet namespace and whether you want coverage:

| Compose file | Tomcat | Servlet namespace | What it's for |
|---|---|---|---|
| `docker-compose.yml` | `tomcat:10.1-jdk17` | jakarta | Basquin's own demo WAR (bundles the in-WAR `IterationFilter` — no valve needed) |
| `docker-compose.valve.yml` | `tomcat:10.1-jdk17` | jakarta | any third-party jakarta WAR, boundary via the **valve** |
| `docker-compose.valve9.yml` | `tomcat:9.0-jdk17-temurin` | javax | any third-party javax WAR, boundary via the **valve** (`WAR_PATH` required — no demo WAR is javax) |
| `docker-compose.coverage.yml` | `tomcat:9.0-jdk17-temurin` | javax | valve **+** a JaCoCo `tcpserver` agent, for coverage-guided exploration |

To tell which your app needs, check `WEB-INF/web.xml`'s root namespace
(`http://java.sun.com/...` / `javax.servlet.jsp.jstl` → javax; `jakarta.*` → jakarta) — the same
table is in [THIRD-PARTY-APPS.md](THIRD-PARTY-APPS.md).

**Agent boundary vs. valve boundary.** These are mutually exclusive on one app: the demo WAR carries
`IterationFilter` in its own `web.xml` (in-WAR, requires editing the app), while a third-party
unmodified WAR gets its iteration boundary from the Tomcat **valve** instead (server-level, no WAR
changes — `com.basquin.valve.BasquinValve`, registered globally by `deploy/valve/context.xml`).
Running both on the same app double-wraps every request. The Basquin **agent** (`-javaagent` +
bootclasspath) is orthogonal to this and always runs in the target JVM either way — it's what
actually measures latency/heap/thread deltas and evaluates invariants; the filter/valve only mark
where one iteration begins and ends.

Run your own demo WAR (jakarta, in-WAR filter):

```bash
./gradlew :tomcat-war:build jar
docker compose up tomcat
# http://localhost:8080/crash?type=NPE  /latency?ms=250  /heap?kb=512
```

Run a third-party jakarta WAR via the valve:

```bash
./gradlew jar :tomcat-valve:jar
WAR_PATH=/abs/path/to/app.war docker compose -f docker-compose.valve.yml up tomcat-valve
```

Run a third-party javax WAR via the valve (e.g. a locally built JPetStore):

```bash
./gradlew jar :tomcat-valve:jar
WAR_PATH=/abs/jpetstore-6/target/jpetstore.war docker compose -f docker-compose.valve9.yml up tomcat9-valve
```

Run with coverage (valve + JaCoCo tcpserver, needed for the exploration driver below):

```bash
./gradlew stageAgents
WAR_PATH=/abs/app.war docker compose -f docker-compose.coverage.yml up tomcat9-coverage
```

All four accept `INVARIANT_MODE`, `INVARIANT_LATENCY_MS`, `INVARIANT_HEAP_KB`, and
`TOMCAT_HOST_PORT` as environment overrides — see the compose files themselves, they're short and
worth reading directly.

### Worked examples: the bench targets

`deploy/bench/{jpetstore,jspwiki}/` are reproducible, already-wired copies of this same
valve+agent-on-Tomcat-9 pattern against two real unmodified apps — a concrete example beyond the
repo's own demo WAR. See **[deploy/bench/README.md](../deploy/bench/README.md)** for how each WAR is
fetched/built and pinned; once the WAR is in place:

```bash
# JPetStore (MyBatis, in-memory HSQLDB)
cd deploy/bench/jpetstore && TOMCAT_HOST_PORT=8092 docker compose up -d

# JSPWiki (filesystem page store) — run setup.sh first to explode the WAR + seed pages
bash deploy/bench/jspwiki/setup.sh
cd deploy/bench/jspwiki && TOMCAT_HOST_PORT=8090 docker compose up -d
```

Both mount the same `build/libs/basquin-0.3.0.jar` / `tomcat-valve/build/libs/basquin-valve-0.3.0.jar`
you just built (via a `../../../` relative path from their own directory), so building once at the
repo root is enough for either.

## Drive explore + load with the standalone runner

With a target running on `localhost:8080` (any of the above), point the driver at it. All of these
are Gradle `JavaExec` tasks that forward `-Dexamples.http.*` and `-Dbasquin.*` system properties
straight through — the full flag catalog is in [USAGE.md § Flags](USAGE.md#flags).

**No coverage** — client-side latency + crash detection, live status screen, harvests server-side
`X-Basquin-Invariant-*` headers if the target has the valve/filter:

```bash
./gradlew runHttpDrive \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.invariant.latency.maxMs=50 -Dbasquin.invariant.mode=soft \
  -Ddrive.iterations=200
```

**Coverage-guided exploration** — mutates requests, keeps the ones that reach new code in the
target's own JaCoCo agent (requires the `docker-compose.coverage.yml` target above). Start the
standalone dashboard once, then run the driver:

```bash
./gradlew runDashboard &          # standalone dashboard, 127.0.0.1:7070, its own process

./gradlew runCoverageGuided \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.coverage.jacoco=localhost:6300 \
  -Dbasquin.coverage.classes=/path/to/WEB-INF/classes \
  -Dbasquin.grammar=examples/grammar/jpetstore.grammar \
  -Dbasquin.invariant.latency.maxMs=25 -Dbasquin.invariant.mode=soft \
  -Dbasquin.dashboard.push=localhost:7070
```

`-Dbasquin.grammar` (route templates + value space, see
[USAGE.md § Writing a request grammar](USAGE.md#writing-a-request-grammar)) takes precedence; without
one, `-Dbasquin.corpusDir=<dir>` seeds exploration from a plain directory of route lines (e.g. the
repo's own `examples/corpus/jpetstore/`). Coverage without guided mutation: `runHttpDriveCoverage`
(same flags, no mutation). No coverage at all: `runHttpDrive` above.

**Load mode** — replay a corpus verbatim at a fixed concurrency for a duration, watching the same
invariants. This is the *same* `CoverageGuidedRun` entry point with `-Dbasquin.mode=load`, which
dispatches internally to `runner.coverage.LoadRun`:

```bash
./gradlew runCoverageGuided \
  -Dbasquin.mode=load \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.corpusDir=examples/corpus/jpetstore \
  -Dbasquin.concurrency=50 \
  -Dbasquin.run.duration=5m \
  -Dbasquin.invariant.latency.maxMs=25 \
  -Dbasquin.dashboard.push=localhost:7070
```

`-Dbasquin.corpusDir` is doing double duty here, worth calling out explicitly (see
[LOAD-MODE-DESIGN.md](LOAD-MODE-DESIGN.md)): in `explore` its files are **mutation seeds/values**; in
`load` its files are **verbatim, fully-formed requests replayed as-is**, one per line. The repo's
`examples/corpus/*/` files are already in that ready-to-fire shape (full route strings, one per
file), so they work directly for a `load`-mode demo even without having run an `explore` pass first.
`-Dbasquin.warmup=<duration>` excludes an initial window from the reported metrics; `-Dbasquin.concurrency`
is load-only (ignored by `explore`).

Read the live status screen either way (`-Dbasquin.status=true`, on by default for these tasks;
interval via `-Dbasquin.status.intervalMs`) — see the
[site's "reading the status panel" reference](https://ianp94.github.io/basquin/getting-started.html#reading-the-status-panel)
for what each row means, or drive it with `runner.GenericRunner`/your own `IterationTarget` per
[USAGE.md § Use with your app](USAGE.md#use-with-your-app) if you're not driving over HTTP at all.

Prefer plain `java` over Gradle (e.g. scripting a CI job)? `./gradlew runnerJar` builds a
self-contained fat jar with the same `CoverageGuidedRun` entry point:

```bash
java -Dexamples.http.baseUrl=http://localhost:8080 -Dbasquin.mode=load \
  -Dbasquin.corpusDir=examples/corpus/jpetstore -Dbasquin.concurrency=50 \
  -jar build/libs/basquin-0.3.0-runner.jar
```

## The dev loop

Because the target's Tomcat container bind-mounts your locally built jars read-only, iterating on
Basquin's own code looks like:

```bash
# 1. edit agent/, tomcat-valve/, runner/, or examples/
# 2. rebuild the jar(s) that changed — same output path, so the mount doesn't need re-declaring
./gradlew jar :tomcat-valve:jar

# 3. restart the container so its JVM re-reads the (now-updated) mounted jar — a -javaagent
#    is loaded once at JVM boot, so a file edit alone does not hot-reload it
docker compose -f docker-compose.valve.yml restart tomcat-valve

# 4. re-run the driver against the same running target
./gradlew runHttpDrive -Dexamples.http.baseUrl=http://localhost:8080 -Dbasquin.invariant.mode=soft
```

That's the whole loop — no image build, no registry, no cluster. It's also faster to iterate on the
driver/runner side alone: since `runHttpDrive`/`runCoverageGuided` read straight from
`sourceSets.main`/`sourceSets.coverage` output, a plain `./gradlew runHttpDrive ...` picks up source
changes on its own next invocation, no `jar` rebuild needed at all — only the code that runs *inside
the target's JVM* (the agent, the valve) needs the container restarted.

Contrast with the **operator path**: there, a code change means rebuilding the agents/operator
*images* (`deploy/agents-image/build.sh`, `docker build -t basquin/operator:<tag> operator/`),
`kind load docker-image`-ing them into the cluster, and letting the operator or a pod restart pick
up the new image — a whole extra packaging + distribution step this standalone loop skips entirely.
See [USAGE.md § Kubernetes: instrument any app with the operator](USAGE.md#kubernetes-instrument-any-app-with-the-operator)
and [OPERATOR-USAGE.md](OPERATOR-USAGE.md) for that path.

## Where to go next

- **[USAGE.md](USAGE.md)** — every flag, every runnable Gradle task, grammar syntax, the exploration
  and coverage sections in full.
- **[THIRD-PARTY-APPS.md](THIRD-PARTY-APPS.md)** — the valve path against a real third-party WAR
  (JPetStore), with verified findings.
- **[deploy/bench/README.md](../deploy/bench/README.md)** — how the JPetStore and JSPWiki bench
  targets are built/fetched and pinned.
- **[LOAD-MODE-DESIGN.md](LOAD-MODE-DESIGN.md)** — the design rationale behind load mode, including
  the `corpusDir` semantic-overload note above.
- **[OPERATOR-USAGE.md](OPERATOR-USAGE.md)** — the in-cluster operator path (Helm install, CRDs, the
  `basquin` CLI) once you're ready to move off `localhost`.
