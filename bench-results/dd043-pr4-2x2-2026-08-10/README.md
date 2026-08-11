# DD-043 PR-4 Task 4 — the native 2×2 acceptance

**Verdict: all four cells COMPLETED.** `rest-villains` (blocking) × `rest-heroes` (reactive), each in
JVM and native mode, all instrumented via `basquin-maven-injector` on `maven.ext.class.path` with
**zero pom edits**, all serving real, strictly-growing offline-JaCoCo execution data at
`/__basquin/coverage`, all showing a real per-request route-method coverage flip under jacoco-cli
0.8.15 against the preserved `target/generated-classes/jacoco` originals. This is the composed
mechanism Task 0's seam spike proved on a synthetic fixture (`.superpowers/sdd/dd043-pr4-seam-spike.md`,
gitignored, not in this repo's git history), now proven on the two real third-party targets the PR-4
plan names as its acceptance bar.

This directory reports **only what actually ran** — every number below is read from a committed
artifact in this same directory, not typed by hand. Two disclosed, honest caveats are folded in
below rather than hidden: an unexpected build-time jacoco.exec side effect in the external quarkus-super-heroes clone, and
a script bug in the shared S3 re-check helper that produced a 404 instead of a 200 on one cell
(corrected inline, both outcomes shown).

## Summary

| Cell | Mode | Result | Strongest evidence |
|---|---|---|---|
| `rest-villains` (blocking) | JVM | **COMPLETED** | banner `basquin`; t0 HTTP 200 + `01 c0 c0 10`; 182→258→743 B; `HelloVillainResource.hello` 0/4→4/4, `VillainResource.getRandomVillain` 0/10→10/10 |
| `rest-heroes` (reactive) | JVM | **COMPLETED** | banner `basquin`; t0 HTTP 200 + `01 c0 c0 10`; 98→168→693 B; `HelloHeroResource.hello` 0/6→6/6, `HeroResource.getRandomHero` 0/11→11/11 |
| `rest-villains` (blocking) | **native** | **COMPLETED** | ELF carries 31 `jacoco` strings incl. `org.jacoco.agent.rt.RT`; banner `basquin`; t0 HTTP 200 + `01 c0 c0 10`; 176→252→737 B; same method flip as JVM; S3 four-disposition re-check passed |
| `rest-heroes` (reactive) | **native** | **COMPLETED** | ELF carries 37 `jacoco` strings; banner `basquin`; t0 HTTP 200 + `01 c0 c0 10`; 92→162→687 B; same method flip as JVM; S3 four-disposition re-check passed |

No cell was blocked. **Mode is recorded per cell above and nowhere is a cross-mode percentage
computed** — every coverage number in this directory is a per-mode, per-method covered/missed count
read directly from that cell's own jacoco-cli XML, never combined across JVM and native.

## Provenance

- This repo: `bd0d1d1b511b8a7127e7ad30d764ba042195e62d` (`dd043-pr4-coverage`, reset from
  `origin/dd043-pr4-coverage` per Task 4's Step 0 — Tasks 1-3 (route, injection, runner transport)
  present, tree clean apart from this evidence directory).
- Injector + extension: `basquin-maven-injector-0.3.0.jar` and `basquin-quarkus` (runtime +
  deployment) `0.3.0`, built from the HEAD above via `./gradlew :basquin-maven-injector:jar` and
  `-PbasquinPagesDir=build/tmp/pr4-2x2-pages :basquin-core:... :basquin-quarkus:runtime:...
  :basquin-quarkus:deployment:...publishAllPublicationsToPagesRepository` (PR-3's exact recipe),
  served on `http://localhost:8010/` (`publish/http-access.log`). jacoco version in lockstep at
  **0.8.15** everywhere: the injected `instrument` execution, the extension's A1 runtime dependency,
  and the jacoco-cli used to analyze below.
- The host's `~/.m2/repository/com/basquin` was purged before the first build
  (`publish/m2-com-basquin-before.txt`, `publish/m2-com-basquin-purge-proof.txt`) — it held a
  **stale, pre-Task-1-3** `0.3.0` (no jacoco dependency in its cached pom at all), which would have
  been resolved instead of the fresh build without the purge, since Maven does not re-resolve a
  cached non-SNAPSHOT release. This was not just a provenance nicety here — it was functionally
  necessary for a correct read.
- Targets: `quarkusio/quarkus-super-heroes` at `c9b46d745620708e1519859bb4775469114381e5` (the same
  commit as PR-2/PR-3's acceptance), sibling clone at `../quarkus-super-heroes` (sparse-checkout
  widened from `rest-villains`-only to also include `rest-heroes`; both directories confirmed
  parent-less standalone poms — see the §8.2 caveat below). Toolchain pins
  (`bench-results/dd043-target-pins-2026-07-24/`): `maven.compiler.release=25`,
  `quarkus.platform.version=3.37.3`, `jacoco.version=0.8.15`, identical on both targets.
- Build vehicle: PR-3's `build.sh` (containerized Mandrel, pinned digest
  `...c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5`), `EXTRA_MAVEN_OPTS` carrying
  `-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar
  -Dbasquin.inject.repo.url=http://localhost:8010/`, `EXTRA_DOCKER_ARGS="--network host -v
  $STAGE:/inj"`. Native builds additionally passed
  `-Dquarkus.native.additional-build-args=-H:+PrintClassInitialization` and were **serialized** — no
  two native builds in flight at once (villains-native finished and was fully torn down before
  heroes-native started).
- jacoco-cli: `bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar`
  (already present from Phase-0 spike work, pinned at the same 0.8.15).

## The §8.2 single-module caveat — carried open, disclosed per cell

Both targets are standalone poms with no `<parent>` (`grep -c '<parent>' pom.xml` = 0 for both
`rest-villains/pom.xml` and `rest-heroes/pom.xml`; each is one project in a sparse multi-repo
checkout, not a reactor member). The injector's per-project loop ran over a one-project reactor in
every cell here, exactly as PR-3's own row carries this caveat. Multi-module reactors remain
unexercised by this acceptance.

## Per-cell detail

Each cell followed the checklist order: (1) banner lists `basquin`; (2) build log carries the
injected execution id; (3) the read itself, first — HTTP 2xx + `01 c0 c0 10` magic; (4) exec data
growth measured by **parsing entries via jacoco-cli**, never byte-diff (session-info timestamps
differ even for byte-identical coverage states); (5) a real route-method flip.

### `rest-villains` — JVM (`villains-jvm/`)

1. Injector line: `build.log:2` = `[basquin-injector] added org.jacoco:jacoco-maven-plugin:0.8.15:instrument (basquin-injected-offline-instrument) to rest-villains`; execution ran at `build.log:100` = `--- jacoco:0.8.15:instrument (basquin-injected-offline-instrument) @ rest-villains ---`. `BUILD SUCCESS`, `Total time: 01:05 min`.
2. Zero pom edits: `grep -c "artifactId>basquin-quarkus<" pom.xml` = `0` (checked against the sibling clone directly). A1 channel confirmed on the shipped classpath: `target/quarkus-app/lib/main/` contains `com.basquin.basquin-core-0.3.0.jar`, `com.basquin.basquin-quarkus-0.3.0.jar`, `org.jacoco.org.jacoco.agent-0.8.15-runtime.jar` — none declared in the fixture pom.
3. Banner (`banner.txt`): `Installed features: [agroal, basquin, cdi, hibernate-orm, hibernate-orm-panache, hibernate-validator, jdbc-postgresql, kubernetes, micrometer, narayana-jta, opentelemetry, qute, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx]`.
4. Read-first (`t0-headers.txt`, `t0.exec`): **HTTP 200**, first 4 bytes `01 c0 c0 10` — BEFORE any app route was driven.
5. Growth, by jacoco-cli parse (`cli-t0.out`/`cli-t1.out`/`cli-t2.out`): `182 → 258 → 743` bytes; each dump point analyzes as **15 classes, zero id-mismatch warnings**.
6. Flip (`flip.py` against `t0.xml`/`t1.xml`/`t2.xml`, class files preserved in `originals/` from `target/generated-classes/jacoco` before anything else touched the tree): after driving `GET /api/villains/hello` then `GET /api/villains/random` — `HelloVillainResource.hello` INSTRUCTION covered/missed `0/4 → 4/4 → 4/4`; `VillainResource.getRandomVillain` `0/10 → 0/10 → 10/10`. Exact request→flip correspondence: each route flips only its own method.

**Disclosed side effect:** the containerized build left a 0-byte jacoco.exec file at the external clone's
module root (the external clone's rest-villains module — outside this repo, cleaned up), consistent with the same build-time class-initialization
phenomenon the Task 0 seam spike documented for native builds (§3.2's "t0-pollution baseline" note) —
here it also occurred once in JVM-mode packaging. It carried zero bytes/no data, was not part of any
measurement, and was removed immediately after being observed (`git status --porcelain` confirmed
empty both before this build and after cleanup).

### `rest-heroes` — JVM (`heroes-jvm/`)

1. Injector line: `build.log:2` = `[basquin-injector] added org.jacoco:jacoco-maven-plugin:0.8.15:instrument (basquin-injected-offline-instrument) to rest-heroes`; execution ran at `build.log:191`. `BUILD SUCCESS`, `Total time: 56.751 s`.
2. Zero pom edits: `grep -c "artifactId>basquin-quarkus<" pom.xml` = `0`. A1 channel confirmed the same way (`basquin-core`/`basquin-quarkus`/jacoco runtime jars on `target/quarkus-app/lib/main/`).
3. Banner (`banner.txt`): `Installed features: [basquin, cdi, config-yaml, hibernate-orm, hibernate-reactive, hibernate-reactive-panache, hibernate-validator, kubernetes, micrometer, opentelemetry, qute, reactive-pg-client, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx]` — the reactive stack (`hibernate-reactive`, `reactive-pg-client`), `basquin` present alongside it.
4. Read-first (`t0-headers.txt`, `t0.exec`): **HTTP 200**, `01 c0 c0 10`, before any app route.
5. Growth: `98 → 168 → 693` bytes; each dump point **15 classes, zero id-mismatch warnings**.
6. Flip: after `GET /api/heroes/hello` then `GET /api/heroes/random` — `HelloHeroResource.hello` (the `Uni<String>`-returning reactive method) `0/6 → 6/6 → 6/6`; `HeroResource.getRandomHero` (`Uni<Response>`) `0/11 → 0/11 → 11/11`. The reactive/`Uni`-returning boundary flips exactly the same way as the blocking one.

**Disclosed side effect:** the same 0-byte `jacoco.exec` phenomenon also appeared once in
`rest-heroes/` (observed post-build/run, removed immediately, `git status --porcelain` empty
before and after).

### `rest-villains` — **native** (`villains-native/`) — headline cell 1

1. Injector line: `build.log:2`/`3`; execution ran at `build.log:78`. `BUILD SUCCESS`, `Total time: 04:33 min` (native-image proper: `Finished generating 'rest-villains-1.0-runner' in 1m 31s`, per the tail of `build.log`).
2. Zero pom edits: `grep -c "artifactId>basquin-quarkus<" pom.xml` = `0`.
3. **`-H:+PrintClassInitialization` report present**, as Task 5 needs: `class_initialization_report.csv`, copied verbatim from `target/rest-villains-1.0-native-image-source-jar/reports/class_initialization_report_20260811_001231.csv`.
4. **The composition, measured on the ELF itself**: `strings` on `target/rest-villains-1.0-runner` (a stripped, dynamically-linked x86-64 executable) finds **31** occurrences of `jacoco`, including `org.jacoco.agent.rt.RT`, `org.jacoco.agent.rt.IAgent`, and the shaded `org.jacoco.agent.rt.internal_bac9136.*` runtime classes — the same composition Task 0's seam spike found and hypothesized would generalize: the extension's typed `RT.getAgent()` call is what keeps the jacoco agent AOT-reachable in a real target's image, not just the Phase-0 fixture's.
5. **§7.2 S3 re-check — all four dispositions reproduced under SubstrateVM, without gating on `X-Basquin-Req`** (`s3-recheck.out`, `s3-app.log`): a normal 200 (`5,0,0|0||` published), a 3xx (`GET /q/swagger-ui` → `302 Found` → `0,0,0|0||` published), an app 500 (`/__basquin/control/defect/error5xx` → HTTP 500 → `0,0,0|0||` published), and a mid-response disconnect (`curl -m 1` against `/__basquin/control/defect/slow?ms=5000`, aborted — `curl` exit 28 — and `s3-app.log` shows `[Basquin] id=s3-disp-disconnect disconnected before response completed: io.vertx.core.http.HttpClosedException` while `/__basquin/result?id=s3-disp-disconnect` stayed `miss`, i.e. never published). This is a separate run with defect routes enabled (`-Dbasquin.quarkus.control.defectsEnabled=true`); the app was restarted clean before the published cell measurement below.
6. Banner (`banner.txt`): `Installed features: [agroal, basquin, cdi, hibernate-orm, hibernate-orm-panache, hibernate-validator, jdbc-postgresql, kubernetes, micrometer, narayana-jta, opentelemetry, qute, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx]` — identical feature set to the JVM cell. Started on the host directly (PR-3's proven native-run method) in `0.615s`.
7. Read-first (`t0-headers.txt`, `t0.exec`): **HTTP 200**, `01 c0 c0 10`, before any app route.
8. Growth: `176 → 252 → 737` bytes; each dump point **15 classes, zero id-mismatch warnings**.
9. Flip: `HelloVillainResource.hello` `0/4 → 4/4 → 4/4`; `VillainResource.getRandomVillain` `0/10 → 0/10 → 10/10` — the identical flip shape as the JVM cell, now under AOT.

No `jacoco.exec` side effect was observed for this native build (`git status --porcelain` on the
sibling clone stayed empty through build + run).

### `rest-heroes` — **native** (`heroes-native/`) — headline cell 2, the reactive target under AOT

1. Injector line: `build.log:2`/`3`; execution ran at `build.log:78`. `BUILD SUCCESS`, `Total time: 04:29 min` (native-image proper: `Finished generating 'rest-heroes-1.0-runner' in 1m 27s`).
2. Zero pom edits: `grep -c "artifactId>basquin-quarkus<" pom.xml` = `0`.
3. **`-H:+PrintClassInitialization` report present**: `class_initialization_report.csv`, copied from `target/rest-heroes-1.0-native-image-source-jar/reports/class_initialization_report_20260811_002152.csv`.
4. ELF composition: `strings` finds **37** `jacoco` occurrences on `target/rest-heroes-1.0-runner`.
5. **§7.2 S3 re-check — all four dispositions reproduced under SubstrateVM** (`s3-recheck.out`, `s3-app.log`): the 3xx, 500, and disconnect dispositions reproduced exactly as for villains-native (`302 Found`/`0,0,0|0||`; HTTP 500/`0,0,0|0||`; `curl` exit 28 + the same `[Basquin] id=... disconnected before response completed` log line + `miss` on poll). **One disclosed script bug, corrected inline**: the shared `s3-recheck.sh` helper's disposition-1 check fell through to `GET /api/villains/hello` on this target (that route does not exist here — `rest-heroes` only has `/api/heroes/*`), so the first recorded result is an **HTTP 404** that still published (`2,0,0|0||` — proving `addEndHandler`/`ar.succeeded()==true` fires on a 404 too, which is a superset of what a 200 would show, but not the disposition as labeled). The genuine 200 check was then run directly and appended to `s3-recheck.out`: `GET /api/heroes/hello` → **HTTP 200** → `/__basquin/result` → `4,512,0|0||` (published).
6. Banner (`banner.txt`): `Installed features: [basquin, cdi, config-yaml, hibernate-orm, hibernate-reactive, hibernate-reactive-panache, hibernate-validator, kubernetes, micrometer, opentelemetry, qute, reactive-pg-client, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx]` — reactive stack + `basquin`, now natively compiled. Started on the host directly in `0.596-0.598s`; **the reactive Postgres client composed with a native image and a real container DB with no headless-infra blocker** — this was the cell the Task 4 brief flagged as most likely to BLOCK, and it did not.
7. Read-first (`t0-headers.txt`, `t0.exec`): **HTTP 200**, `01 c0 c0 10`, before any app route.
8. Growth: `92 → 162 → 687` bytes; each dump point **15 classes, zero id-mismatch warnings**.
9. Flip: `HelloHeroResource.hello` `0/6 → 6/6 → 6/6`; `HeroResource.getRandomHero` `0/11 → 0/11 → 11/11` — the identical reactive flip shape as JVM, now under AOT.

No `jacoco.exec` side effect was observed for this native build.

## What this establishes, and what it does not

**Establishes:** on the pinned toolchain (containerized Mandrel, digest-pinned; Quarkus 3.37.3; JDK
25 release; jacoco 0.8.15 in lockstep everywhere), `basquin-maven-injector` + `basquin-quarkus`'s
A1 jacoco channel + the `/__basquin/coverage` typed read compose correctly, with **zero pom edits**,
on both a blocking (JDBC/Hibernate ORM) and a reactive (reactive-pg-client/Hibernate Reactive)
real third-party Quarkus application, in **both** JVM and **native** packaging — four cells, four
passes, no blocked cell. The native cells additionally reproduce the §7.2 S3 boundary-disposition
re-check under SubstrateVM and carry a `-H:+PrintClassInitialization` report for Task 5.

**Does not establish:**

- **Multi-module reactors** — both targets are standalone, parent-less poms (§8.2 caveat, carried
  open per PR-3's own precedent).
- **Cross-mode coverage comparison** — deliberately not computed anywhere in this directory; native's
  denominator differs from the JVM's (spec §6.4/§7.4), and PR-4 does not pre-empt PR-5's rendering rule.
- **Concurrent/load-driven coverage** — all reads here are sequential, single-client probes; behavior
  under the runner's concurrent-dump union-merge path is exercised by Task 3's own tests, not here.
- **HTTPS/TLS transport, mirror re-application** — same scope limit as every prior DD-043 acceptance
  in this series (localhost plain HTTP stood in for the real served-repo endpoint).

## Files in this directory

- `villains-jvm/`, `heroes-jvm/`, `villains-native/`, `heroes-native/` — one directory per cell:
  `build.sh`/`build.log`, `banner.txt`, `app-startup.log`, `t{0,1,2}.exec`/`t{0,1,2}-headers.txt`,
  `req-{hello,random}.txt`, `cli-t{0,1,2}.out`/`t{0,1,2}.xml` (the jacoco-cli report XML that
  `flip.py` reads — this is the committed, derived evidence for the flip claims above). Native
  cells add `class_initialization_report.csv`, `s3-recheck.out`, `s3-app.log`, `run-native.sh`,
  `run-native-s3.sh`, `run-db.sh`.
- `publish/` — the served-repo HTTP access log and the `~/.m2` purge proof.
- `s3-recheck.sh`, `analyze.sh`, `flip.py`, `wait-build.sh` — the shared probe/analysis scripts run
  against each cell (kept, not just their output, so the exact commands are reproducible).

The built jars/binaries, the served Pages repo, and each cell's `originals/` (the preserved
pre-instrumentation `.class` files copied from the target's own `target/generated-classes/jacoco`,
used as `jacoco-cli`'s `--classfiles` input to produce the committed `t*.xml`/`cli-t*.out`) are
**not** tracked — this directory tracks no third-party-target build artifacts, matching every prior
`bench-results/dd043-*` acceptance in this series. `originals/` is reproducible by re-running the
cell's own `build.sh` against the pinned target commit; the `t*.xml` reports it produced are the
committed, derived record.
