# Running Basquin against third-party apps

Third-party apps can't be modified, so Basquin injects its instrumentation instead: a **Tomcat
valve** at runtime for servlet WARs (this page, first), and a **Maven core extension** at build time
for Quarkus/native targets (the build-time-injection section at the end).

## Tomcat valve (runtime attachment, servlet WARs)

Our own demo WAR bundles `IterationFilter` in its `web.xml`. Third-party apps
(JPetStore, JSPWiki, …) can't be modified, so Basquin attaches iteration
boundaries with a **Tomcat valve** instead — no WAR repacking, no `web.xml` edits.
Rationale and rejected alternatives: `DESIGN-DECISIONS.md` DD-009.

> **Mutually exclusive with the in-WAR filter.** Use the *filter* for our demo WAR,
> the *valve* for third-party WARs. Running both wraps every request twice (verified:
> 6 `beginIteration` calls for 3 requests), producing nested, meaningless iteration
> boundaries. Never enable both on the same app.

## Servlet namespace → Tomcat → compose

The valve jar is **namespace-free** (DD-011): one artifact runs on both Tomcat lines.
Pick the Tomcat image to match the *app's* servlet namespace:

| App servlet namespace | Tomcat image        | Compose file                 |
|-----------------------|---------------------|------------------------------|
| `jakarta.servlet`     | `tomcat:10.1-jdk17` | `docker-compose.valve.yml`   |
| `javax.servlet`       | `tomcat:9.0-jdk17`  | `docker-compose.valve9.yml`  |

To check an app: `unzip -l app.war | grep -E 'spring-web|servlet'` and look at
`WEB-INF/web.xml`'s root namespace (`http://java.sun.com/...` and `javax.servlet.jsp.jstl`
→ javax; `jakarta.*` → jakarta).

## Pieces

- `tomcat-valve/` — `com.basquin.valve.BasquinValve` (extends `ValveBase`), built
  to `basquin-valve-<v>.jar`. Goes in Tomcat's `lib/`.
- `deploy/valve/context.xml` — global context that registers the valve for every
  deployed app. Mounted over `conf/context.xml`.
- `build/libs/basquin-<v>.jar` — the agent, injected via `CATALINA_OPTS`
  (`-javaagent` + bootclasspath) so invariants are captured inside the server JVM.
- `docker-compose.valve.yml` — wires all of the above around a WAR under test.

## Quick run (against the demo WAR, to exercise the valve path)

```
./gradlew jar :tomcat-war:build :tomcat-valve:jar
# demo WAR already has the filter, so for a pure valve test point WAR_PATH at a
# filterless WAR (see JPetStore below). To just confirm the stack deploys:
docker compose -f docker-compose.valve.yml up tomcat-valve
```

Verified deploy signals: `Basquin Agent initialized` in the log (premain ran),
WAR deploys with no `SEVERE`, routes serve, and `GET /basquin/status` shows
server-side `requests`/`crashes`/`invariants` counters advancing.

## JPetStore (MyBatis) — first real target — DONE

JPetStore-6 is `javax.servlet` (bundles `spring-web-5.3.39`, old javaee `web.xml`), so it
runs on **Tomcat 9** with the (namespace-free) valve.

1. **Build the WAR.** JPetStore master targets JDK 17 but its build plugins demand newer
   tooling; build with Maven 3.9+ and the version gates skipped:
   ```
   git clone --depth 1 https://github.com/mybatis/jpetstore-6.git
   cd jpetstore-6
   mvn -DskipTests -Denforcer.skip=true -Dmaven.gitcommitid.skip=true package
   # -> target/jpetstore.war (uses an in-memory HSQLDB; no external DB needed)
   ```
2. **Deploy with the valve on Tomcat 9:**
   ```
   ./gradlew jar :tomcat-valve:jar
   WAR_PATH=$PWD/jpetstore-6/target/jpetstore.war docker compose -f docker-compose.valve9.yml up tomcat9-valve
   ```
   JPetStore has no `IterationFilter`, so the valve is the sole boundary — no double-wrap
   (iteration numbers increment 1,2,3,… one per request).
3. **Drive it** — hit real routes and read findings from the server-side agent log
   (`[Basquin][Invariant] …`) and the `X-Basquin-Invariant-*` response headers:
   ```
   curl -s -D - "http://localhost:8080/actions/Catalog.action?viewCategory=&categoryId=FISH" | grep X-Basquin
   ```

### Verified findings (2026-07-19, soft invariants latency>5ms / heap>64KB)

Server-side, inside JPetStore's Tomcat 9 JVM via the valve:

| Route                                   | latency | heap delta | invariant |
|-----------------------------------------|--------:|-----------:|-----------|
| `/` (index)                             |  11ms   |  +2964KB   | latency, heap |
| `Catalog.action` (first, cold)          |  531ms  | +44149KB   | latency, heap |
| `Catalog.action?categoryId=FISH`        |  98ms   |            | latency |
| `Catalog.action?viewProduct=FI-SW-01`   |  81ms   | +17636KB   | latency, heap |

The cold first-catalog spike (531ms) and per-request multi-MB heap growth are exactly the
input/state-dependent availability pathologies the harness targets — surfaced in an
unmodified third-party app with no code changes.

### Status of this slice

- [x] Reusable, namespace-free valve (DD-011): one jar for Tomcat 9 and 10.
- [x] Deploy scaffolding (`docker-compose.valve.yml`, `docker-compose.valve9.yml`, context.xml).
- [x] Live run against a real JPetStore WAR on Tomcat 9 with server-side invariants captured.
- [ ] Next: an HTTP driver target + seed corpus to explore routes automatically (vs. manual curls).

## Quarkus targets — build-time injection (no source modification)

The valve above attaches at **runtime**. A GraalVM-native Quarkus app has no runtime attachment
point, so Basquin attaches at **build time** instead: a Maven core extension
(`basquin-maven-injector`) injects the `basquin-quarkus` extension — the dependency *and* the
repository that resolves it — into the target's build, with **zero edits to any file in the
application tree**. Design and evidence: DD-043 spec §5
(`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`), spikes S4/S5, and the PR-3
acceptance runs (`bench-results/dd043-pr3-restvillains-2026-07-26/`,
`bench-results/dd043-pr3-native-2026-07-26/`).

### One command

Build the injector jar (`./gradlew :basquin-maven-injector:jar`), then run the target's own build with
the jar on `maven.ext.class.path`. **The snippet below is illustrative, not the shape the acceptance
runs used** — the minimum that must be true is that the property reaches the Maven JVM:

```
docker run --rm -v "$PWD":/w -w /w \
  -v /path/to/injector-dir:/inj \
  -e MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar" \
  maven:3.9-eclipse-temurin-25 ./mvnw -B package [-Dnative]
```

**It is not complete on its own, and an earlier version of this page wrongly said it was.** Two things
it omits:

1. **A reachable repository.** With no `-Dbasquin.inject.repo.url`, the injector resolves against the
   default Pages URL, which — as the section above says — **serves nothing until the first `v*` tag
   populates `docs/maven/`**. Until then every reader must either point it somewhere real
   (`-Dbasquin.inject.repo.url=…`) or use the offline fallback below.
2. **Whatever the target's own build needs.** The acceptance runs did not use this image or these
   flags. They used the Mandrel builder image pinned by digest, `--entrypoint bash`,
   `-u $(id -u):$(id -g)` with the `HOME`/`-Duser.home` workaround that script documents as mandatory,
   and `--network host` so a `localhost` repository URL was reachable from inside the container. The
   real shapes are `bench-results/dd043-pr3-restvillains-2026-07-26/build.sh` (JVM) and
   `bench-results/dd043-spikes-2026-07-24/env/build.sh` (native) — read those before adapting this.

**Verify it ran, every time.** Maven ignores a bad `maven.ext.class.path` **silently** — the build
succeeds and ships uninstrumented. Two checks, both required:

1. The build log contains `[basquin-injector] instrumented <artifactId> (…)` per module. Missing?
   Check the jar path is valid *inside* the container, and that the jar carries
   `META-INF/sisu/javax.inject.Named` (Maven discovers the participant through that index;
   `scripts/verify-dd043-pr3.sh`'s `jar` stage asserts it).
2. The built artifact's startup banner lists `basquin` under `Installed features` — the injection
   proof the spec's §5.2 requires. Anything less cannot distinguish "instrumented" from "silently
   uninstrumented".

### The three system properties

| Property | Default | Meaning |
|---|---|---|
| `basquin.inject.skip` | `false` | `true` injects nothing — the baseline/control build |
| `basquin.inject.repo.url` | `https://ianp94.github.io/basquin/maven/` | the repository the injected artifacts resolve from |
| `basquin.inject.version` | the injector's own version (baked at build time) | the injected extension version |

**The default repo URL serves nothing until the first `v*` release tag populates `docs/maven/`.**
Until then, publish the chain to a directory, serve it locally, and pass
`-Dbasquin.inject.repo.url=http://localhost:8000/` (the shape `scripts/verify-dd043-pr3.sh` and both
acceptance runs use) — or use the offline fallback below. No run so far has exercised the real Pages
HTTPS repository.

### Fail-loudly behaviours, and what to do about each

The injector hard-fails the build (`MavenExecutionException`) rather than ever proceeding silently
wrong:

- **`dependencyManagement` pins `com.basquin:*` to a different version.** A managed version governs
  transitively resolved Basquin artifacts, so the build would pair the extension with a different
  `basquin-core` than it was compiled against. Fix: align the versions, or pass
  `-Dbasquin.inject.version=<managed>` if the pin is genuinely intended.
- **The pom already declares `com.basquin:basquin-quarkus` at a different version.** An explicit
  declared version wins over anything injected, so the build would use it while the driver expects
  ours — a wire-format skew that surfaces as `/__basquin/result` polls returning `miss`, not as a
  build error. Fix: align the versions, pass `-Dbasquin.inject.version=<declared>` to inject the
  declared version deliberately, or `-Dbasquin.inject.skip=true` to leave the build uninstrumented.
- **`dependencyManagement` carries an `<exclusions>` entry on any `com.basquin:*` managed
  dependency.** Managed exclusions apply to the dependency this injector adds, so — like the
  declared-path exclusions case below — they can strip `basquin-core` from its transitive
  resolution while the extension jar itself still loads. The `Installed features` banner cannot
  tell the difference: the extension still appears in it, so a banner-only acceptance run would
  pass a build that ships broken. Fix: remove the managed exclusions, or
  `-Dbasquin.inject.skip=true` to leave the build uninstrumented deliberately.
- **The pom declares `com.basquin:basquin-quarkus` in a shape that cannot carry the extension.** Four
  shapes fail: a scope other than `compile`/`runtime`; a `type` other than `jar` (a `pom` type resolves
  the POM and never the jar); any `classifier`; and any `<exclusions>`. Each would make the injector
  treat the declaration as satisfying the injection and skip it, so the extension never reaches the
  module's classpath and the build succeeds **uninstrumented**, with `/__basquin/result` returning
  `miss`. Fix: make it a plain `compile`/`runtime`, `jar`-type, unclassified, exclusion-free
  dependency, remove it and let the injector add it, or `-Dbasquin.inject.skip=true`.

  The exclusions case deserves its own warning: together with the `dependencyManagement`-managed
  exclusions case above, these are the **two shapes the `Installed features` banner cannot
  detect**, because in both the extension still loads and still appears in the banner while a
  stripped `basquin-core` leaves it unusable. Do not treat a banner check as sufficient for either.

- **The pom directly declares a *different* `com.basquin` artifact — `basquin-core` above all — at
  a scope other than `compile`/`runtime`, or at a version other than the one this injector
  supplies.** This is a separate hazard from the `basquin-quarkus` shapes above: it is not about our
  own artifact, but about a sibling artifact the extension needs transitively. A direct declaration
  wins the scope or version for that artifact over what the extension would otherwise pull in, so a
  `test`/`provided` scope keeps `basquin-core` off the runtime classpath and a conflicting version
  pairs the extension with a `basquin-core` it was not built against — either way the build succeeds
  and `/__basquin/result` polls return `miss`. DD-044 / PR-3.5 is specifically about targets that
  already carry Basquin, which makes this reachable rather than theoretical, not a corner case. (This
  guard aborts the build before any banner is produced, so — unlike the two exclusions shapes above —
  it is not something a banner check could ever have been asked to catch.) Fix: remove the
  declaration and let the extension bring `basquin-core` in transitively, align the scope/version if
  the declaration is intentional, or `-Dbasquin.inject.skip=true` to leave the build uninstrumented
  deliberately.

- **A same-version declaration in a usable shape is not a failure:** the injector logs
  `already declares basquin-quarkus:<v>; adding the repository only` and adds just the repository —
  a declared dependency is not necessarily a resolvable one. **"Usable shape" is load-bearing in that
  sentence**, and an earlier version of this page stated the same-version case as unconditionally safe,
  which the guard above now contradicts: a same-version declaration at `scope=test` or `type=pom`
  hard-fails.

### Gradle targets — init script, no fail-loudly guards

`basquin-init.gradle` (repository root) is the Gradle counterpart, honouring the same three system
properties:

```
./gradlew -I /path/to/basquin-init.gradle build
```

**It implements only the `skip` opt-out.** The Maven injector's other five guards — conflicting
managed version, conflicting managed exclusions, conflicting declared version, an unusable
declaration shape (§5.1), and an unusable *sibling* declaration (a directly declared `com.basquin`
artifact other than `basquin-quarkus` — `basquin-core` above all — at a non-`compile`/`runtime`
scope or a conflicting version) — have no Gradle equivalent: the script does not even check whether
`com.basquin:basquin-quarkus` is already declared before adding another `implementation`
dependency, and it does not look at any other `com.basquin` artifact at all. A Gradle target that
already declares the artifact — at another version, with exclusions, or in a non-resolving
configuration — or that already declares a sibling like `basquin-core` at a bad scope or version,
gets Gradle's own default highest-version conflict resolution instead of a hard failure, silently
reproducing the DD-040 wire-format skew the Maven guards exist to prevent. Running the §5.2 banner
check once would not surface this: the banner only shows whether `basquin` loaded on *some* build,
not whether a conflicting declaration was silently resolved around it. §5.1's fail-loudly contract
is **not implemented** on this path — that is a stronger statement than "unverified," and true
independent of whether the check has run.

### Offline fallback — `publishToMavenLocal`

Where the build host cannot reach any repository URL, pre-populate the local repository instead:
`./gradlew publishToMavenLocal` from the Basquin repo publishes all four artifacts (`basquin-core`,
`basquin-quarkus`, `basquin-quarkus-deployment`, `basquin-maven-injector`) into `~/.m2/repository`;
a containerised build then needs that directory mounted. This is a documented **fallback**, not the
mechanism — the injector's repository injection makes it unnecessary whenever the URL is reachable
(spike S5, `bench-results/dd043-s5-repo-injection-2026-07-26/`).
