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
- **`dependencyManagement` pins a `com.basquin` sibling artifact — `basquin-core` above all — to a
  scope other than `compile`/`runtime`.** Maven's resolver applies a managed `scope` from
  dependency-resolution depth 2 down, and `basquin-core` is exactly that depth-2 node (reached
  transitively through the injected `basquin-quarkus`), so a managed `test`/`provided` scope keeps
  it off the application's runtime classpath while `basquin-quarkus` itself still loads at
  `compile` — the identical resolved state the declared-sibling guard below hard-fails, reached
  through `dependencyManagement` instead. Like the managed-exclusions case above, the
  `Installed features` banner cannot tell the difference. Fix: remove the managed scope, or
  `-Dbasquin.inject.skip=true` to leave the build uninstrumented deliberately.
- **The pom declares `com.basquin:basquin-quarkus` in a shape that cannot carry the extension.** Four
  shapes fail: a scope other than `compile`/`runtime`; a `type` other than `jar` (a `pom` type resolves
  the POM and never the jar); any `classifier`; and any `<exclusions>`. Each would make the injector
  treat the declaration as satisfying the injection and skip it, so the extension never reaches the
  module's classpath and the build succeeds **uninstrumented**, with `/__basquin/result` returning
  `miss`. Fix: make it a plain `compile`/`runtime`, `jar`-type, unclassified, exclusion-free
  dependency, remove it and let the injector add it, or `-Dbasquin.inject.skip=true`.

  The exclusions case deserves its own warning, and by now it is not alone: together with the
  `dependencyManagement`-managed exclusions and managed-scope cases above, and the declared-sibling
  scope case below, these are shapes the `Installed features` banner cannot detect — in every one the
  extension still loads and still appears in the banner while a `basquin-core` that has been stripped
  or pushed off the runtime classpath leaves the application uninstrumented. This class has grown
  every time review found another route to that same resolved state, so treat any specific count here
  as a snapshot, not a fact — the checkable enumeration is the javadoc on `failOnUnusableDeclaration`,
  `failOnConflictingManagedVersion` and `failOnUnusableSiblingDeclaration` in `BasquinInjector.java`,
  kept in sync with `BasquinInjectorGuardsTest.java`'s matching tests. As of this writing it is four
  shapes: declared `basquin-quarkus` exclusions, managed `dependencyManagement` exclusions, a managed
  sibling scope, and a declared sibling scope. Do not treat a banner check as sufficient for any of
  them.

- **The pom directly declares a *different* `com.basquin` artifact — `basquin-core` above all — at
  a scope other than `compile`/`runtime`, or at a version other than the one this injector
  supplies.** This is a separate hazard from the `basquin-quarkus` shapes above: it is not about our
  own artifact, but about a sibling artifact the extension needs transitively. A direct declaration
  wins the scope or version for that artifact over what the extension would otherwise pull in, so a
  `test`/`provided` scope keeps `basquin-core` off the runtime classpath and a conflicting version
  pairs the extension with a `basquin-core` it was not built against — either way the build succeeds
  and `/__basquin/result` polls return `miss`. DD-044 / PR-3.5 is specifically about targets that
  already carry Basquin, which makes this reachable rather than theoretical, not a corner case. (The
  scope half of this hazard belongs to the same banner-blind class noted above: this guard is about a
  *different* artifact than `basquin-quarkus`, so the injector still adds `basquin-quarkus` itself,
  the extension still loads, and `Installed features` still lists `basquin` while `basquin-core` sits
  off the runtime classpath at `test`/`provided` scope — the managed-scope guard above hard-fails the
  identical resolved state reached through `dependencyManagement` instead. The version half is not in
  that class: a conflicting version keeps `basquin-core` on the runtime classpath, just at the wrong
  one, which is the same wire-format-skew hazard as the two version guards above, not a banner-blind
  one.) Fix: remove the
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

**It implements only the `skip` opt-out.** `BasquinInjector.java` has four private methods that can
throw `MavenExecutionException` (`failOnUnusableDeclaration`, `failOnConflictingDeclaredVersion`,
`failOnConflictingManagedVersion`, `failOnUnusableSiblingDeclaration`), and two of those four each
guard more than one independent condition with their own separate `throw` — `failOnConflictingManagedVersion`
throws once for a managed exclusion, once for an unusable managed scope and once for a managed version
conflict; `failOnUnusableSiblingDeclaration` throws once for an unusable sibling scope and once for a
conflicting sibling version — so the fail-loudly surface is **seven conditions, each an
independently-triggerable `throw`, across those four methods**: conflicting managed version,
conflicting managed exclusions, an unusable managed scope, conflicting declared version, an unusable
declaration shape (§5.1, one `throw` covering four sub-shapes — scope/type/classifier/exclusions), an
unusable *sibling* scope, and a conflicting *sibling* version (the last two on a directly declared
`com.basquin` artifact other than `basquin-quarkus` — `basquin-core` above all). This count moves
whenever a guard is added — verify it against `BasquinInjector.java`'s own `throw` sites rather than
trusting the number. None of those seven has
a Gradle equivalent: the script does not even check whether
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

### Coverage — offline JaCoCo over HTTP, on the same build-time-injected target

DD-043 PR-4 adds a third thing to the injection above, and a route to read it back. Design and
evidence: DD-043 spec §5/§6.4/§8.2
(`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`) and the native 2×2
acceptance below.

**What the injector adds.** Alongside the `basquin-quarkus` dependency and the repository (the two
injections described above), the injector also adds a `jacoco-maven-plugin:instrument` execution
(id `basquin-injected-offline-instrument`, bound to the `process-classes` phase) to each project's
model — the plugin-execution injection that spec §8.2 flagged as the one unmeasured third of §5's
mechanism, and that this PR settles by building it into the injector and exercising it on real
targets. This offline-instruments the target's own compiled classes in place and preserves the
pre-instrumentation originals under `target/generated-classes/jacoco`, exactly as JaCoCo's own
offline mode always has — no other file in the target's tree changes.

**How the runtime side reads it back — decision A1.** The jacoco *agent runtime* that actually
serves that execution data does not arrive through a fourth injector mutation. It arrives through
`basquin-quarkus`'s own runtime module pom: the extension declares
`org.jacoco:org.jacoco.agent:<version>:runtime` as an ordinary `implementation` dependency. That
single declaration does two jobs at once — it lands on the extension's own compile classpath, so
the direct, compile-time-typed `RT.getAgent().getExecutionData(false)` call the spec's §6.4 requires
actually compiles; and because the module publishes via plain `from components.java`, the dependency
is published in the extension's own pom at ordinary `<scope>runtime</scope>` and so propagates
transitively to any target the injector has already added `basquin-quarkus` to. One channel, no new
injected-artifact guard surface, and the version stays in lockstep with the injected `instrument`
execution automatically because both are baked from the same source.

The extension serves the resulting execution data at `/__basquin/coverage`: a binary
`application/octet-stream` body on success, and — when no jacoco agent is reachable — a **distinct
non-2xx**, never an empty-but-200 body, matching the "a reported zero must mean checked and clean"
rule the rest of this repo's boundary already follows. The runner (`JacocoCoverageProvider`) reads
that route the same way it already reads a tcpserver coverage endpoint, just over a different
transport: its endpoint spec accepts a comma-separated mix of `host:port` and URL forms, and a URL
entry is dumped over an HTTP GET instead of a raw socket.

**Version lockstep, and why a stale JaCoCo silently lies.** The injected `instrument` execution, the
extension's A1 runtime dependency, and the runner's own analyzer/jacoco-cli are all pinned from one
shared Gradle property, never a hand-typed literal in more than one place — offline-instrumented
classes reference a version-specific shaded package name, so a plugin/agent version skew breaks the
read at runtime without failing to compile. That property was bumped from a pre-existing `0.8.12` to
`0.8.15`: `0.8.12`'s ASM could not parse the targets' Java-25 classfiles, and the old analyzer
swallowed the resulting exception per class, silently reporting a "clean" zero for a target it never
actually analyzed — the exact "reported zero means checked and clean" failure mode DD-040 exists to
prevent, now fixed on this path too (decision D1). The analyzer now counts unanalyzable classes and
**fails loudly** rather than publish that zero when every supplied class is unanalyzable.

**Fail loudly on a conflicting jacoco declaration (decision D2).** The injector is about to add its
own `instrument` execution, so — the same posture as every other injector guard on this page — it
hard-fails rather than compose silently with a target that already declares one: an *active*
`jacoco-maven-plugin` `instrument`-bound execution, or any declared `jacoco-maven-plugin` version
other than the one it supplies. A target's own `prepare-agent` execution (JaCoCo's *online* mode) is
deliberately not itself a conflict — it instruments a different way, in a running JVM, and does not
compete for the same bytecode — so it trips neither branch as long as any declared version agrees.
The guard message names the same `basquin.inject.skip` escape hatch as the other guards.

**Verified end-to-end — the native 2×2.** Both `rest-villains` (blocking, JDBC/Hibernate ORM) and
`rest-heroes` (reactive, Hibernate Reactive/reactive-pg-client) — the two targets already named
above — were built with **zero pom edits**, in both JVM and native (Mandrel) packaging: all four
cells completed, each one showing, in order, the read succeeding first (an HTTP 2xx plus JaCoCo's
own execution-data magic bytes on `/__basquin/coverage`, checked *before* any application route was
driven — an error body is non-empty too, so "non-empty" is never accepted as the read check),
execution data growing across further dump points, and a real per-request route-method coverage
flip read back against the preserved pre-instrumentation classes. On the two native cells, the
built binary itself was inspected directly and found to carry the jacoco runtime classes, confirming
the extension's `RT.getAgent()` call is what keeps the agent reachable through native-image's
closed-world analysis, not merely compiled against. No cross-mode coverage percentage is computed
anywhere in that evidence — native's denominator differs from the JVM's (spec §6.4/§7.4) and stays
out of scope here. Evidence: `bench-results/dd043-pr4-2x2-2026-08-10/` (`README.md`'s summary table
and per-cell detail).

**The §8.2 single-module caveat, carried forward.** Both targets in that acceptance are standalone
poms — no `<parent>`, one project per sparse checkout, never a reactor member. The injector's
per-project loop, including the new `instrument`-execution injection, has therefore still only ever
run over a one-project reactor; a multi-module Maven reactor remains unexercised by this mechanism,
the same open caveat the dependency-and-repository half of this injector already carries from PR-3.
See `bench-results/dd043-pr4-2x2-2026-08-10/README.md`'s own caveat section for the checked detail.
