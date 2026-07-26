# DD-043 PR-2 acceptance — `basquin-quarkus` against a real third-party app (`rest-villains`)

**Why this exists.** PR-2's extension was already proven end-to-end against the Phase-0 fixture
(`bench-results/dd043-pr2-boundary-2026-07-25/`) — a minimal app written for this project. Spec §9
makes PR-2's real acceptance `rest-villains`, from `quarkusio/quarkus-super-heroes`: an app we did not
write, with a datasource, generated (contract-first) resources, Hibernate, an observability stack, and
routes we did not design. This directory is the evidence that the extension instruments it. **JVM mode
only — no native build was run.**

## Result summary

1. `rest-villains` builds and starts with `com.basquin:basquin-quarkus:0.3.0` on the classpath. The
   startup banner lists **`basquin`** under `Installed features` — `app-startup.log:25`.
2. A real application route (`GET /api/villains`, the villains collection — generated from
   `src/main/resources/openapi/openapi.yml`) was driven with `X-Basquin-Req: villains-1`.
3. `curl "http://localhost:8084/__basquin/result?id=villains-1"` returned `7,415,0|0||` — a real
   `costCsv|invariantCount|detail|leak` line, not `miss` — `curl-transcript.txt:14-16`.
4. A request to the same route **without** the header stored nothing: polling a never-sent id returned
   `miss` — `curl-transcript.txt:18-26`.
5. Latency/heap figures and their plausibility: see "Latency and heap figures" below.

## Provenance

- Repo: `https://github.com/quarkusio/quarkus-super-heroes`, commit `c9b46d745620708e1519859bb4775469114381e5`
  (`main`, fetched 2026-07-26).
- Cloned **outside** this repository, per `docs/THIRD-PARTY-APPS.md:58`'s precedent (JPetStore): a
  shallow (`--depth 1`), sparse (`rest-villains` only) clone to a sibling directory,
  `../quarkus-super-heroes` (sibling of `closureJVM`, i.e.
  `/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains`). Not committed
  here — only this evidence directory is.
- Toolchain pins re-checked against the already-committed
  `bench-results/dd043-target-pins-2026-07-24/rest-villains-pom.xml`: byte-identical (modulo line
  endings) to the freshly cloned `rest-villains/pom.xml` — `maven.compiler.release=25`,
  `quarkus.platform.version=3.37.3`, same as `basquin-quarkus`. No version skew, confirmed rather than
  assumed:
  ```
  diff <(tr -d '\r' < rest-villains/pom.xml) \
       <(tr -d '\r' < bench-results/dd043-target-pins-2026-07-24/rest-villains-pom.xml)
  # -> no output (identical)
  ```

## The one deviation: a manual pom edit, not zero-pom-edit injection

**PR-2's acceptance used a manual dependency edit. This is not evidence the injector (PR-3) works —
PR-3 owns proving zero-pom-edit injection via the Maven lifecycle participant, and spec §5.2's banner
check is *its* acceptance, run against a build that never touched `pom.xml`.**

The only change made to `rest-villains` is one `<dependency>` block in `pom.xml`, adding
`com.basquin:basquin-quarkus:0.3.0` (see `pom-deviation.patch`, or the block itself, with its own
comment pointing back here):

```xml
<dependency>
  <groupId>com.basquin</groupId>
  <artifactId>basquin-quarkus</artifactId>
  <version>0.3.0</version>
</dependency>
```

No other file in the app tree was touched. `application.properties`, the OpenAPI contract, the Java
sources, and the build plugins are all unmodified upstream bytes.

## Deviations from the app's own "official" run, and why each is legitimate

The app's own `src/main/docker-compose/{infra,java25}.yml` show the maintainers' own prescribed way to
run a packaged `rest-villains`. Two choices below diverge from that file; both are legitimate for what
this acceptance measures (whether the extension instruments the app), not a fact about the app itself.

1. **Postgres started standalone, not via the app's compose files.** `docker run postgres:18` (matching
   the image the app's own `infra.yml` pins) with `POSTGRES_USER=superbad`, `POSTGRES_PASSWORD=superbad`,
   `POSTGRES_DB=villains_database` — the same credentials `java25.yml` uses. No init SQL was mounted.
2. **Schema strategy left at its application-default (`drop-and-create`), not overridden to `validate`.**
   `java25.yml` sets `QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=validate` paired with a
   separately-mounted `deploy/db-init/initialize-tables.sql`, because their compose stack expects a
   pre-seeded, schema-stable database. `application.properties`'s own default,
   `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`, needs no such pre-seeding —
   Hibernate creates the schema from the entity mappings on every startup — so it was left as-is rather
   than fetching and mounting a second file. `%prod.quarkus.hibernate-orm.sql-load-script=import.sql`
   (also a default, unmodified) then seeds 100 real villain rows on top of that schema — this is what
   made `GET /api/villains` return real data (`curl-transcript.txt:3`) instead of an empty array.
3. **OpenTelemetry disabled at runtime: `QUARKUS_OTEL_SDK_DISABLED=true`.** `rest-villains` depends on
   `quarkus-opentelemetry` and `quarkus-micrometer-opentelemetry` (both default/compile scope, so both
   ship in the packaged jar — confirmed in `quarkus-app/quarkus-app-dependencies.txt`), plus
   `quarkus-observability-devservices-lgtm` (**`<scope>provided</scope>`** — compile-time only, never
   packaged; and Dev Services only fires in dev/test mode per the task brief, never for a packaged
   `prod` run, so no LGTM stack was ever at risk of being started). Left enabled, the OTel SDK's default
   OTLP exporter would try to reach `http://localhost:4318` (the endpoint `java25.yml` points at the
   `otel-lgtm` compose service) every export interval, on a target with no collector — background
   noise/log spew unrelated to what this acceptance measures. `quarkus.otel.sdk.disabled` is documented
   (quarkus.io) as the runtime property that fully disables the SDK; confirmed effective — the full
   startup+run log (`app-log-full.log`) contains no OTel-related WARN/ERROR/exception of any kind.
4. **`-DskipTests` on the Maven build.** `rest-villains`' own test suite (Pact, Playwright, REST-assured)
   is a correctness check on the *app*, not on the extension, and several of those tests carry their own
   infrastructure requirements out of scope here. Packaging (`quarkus:build`) does not depend on the
   test phase passing.

**`quarkus-jacoco` did not need any intervention.** It is present (`test` scope) but, unlike the
Phase-0 fixture (whose archetype-generated pom bound `jacoco:instrument` directly in the default
build), `rest-villains`' `jacoco-maven-plugin` execution only exists inside the `it-coverage` Maven
profile, which this build never activated. Confirmed by grepping the build log: no
`jacoco:...:instrument` execution appears anywhere in `build.log`, and there was no
"already instrumented" failure to work around.

## Reproduce

Prerequisites: `basquin-core` and `basquin-quarkus` (`runtime` + `deployment`) published into the
**host's** `~/.m2` (not the Phase-0 fixture's `dd043Spike` repository) — required because this build
runs entirely outside the `closureJVM` tree:

```bash
cd closureJVM
./gradlew :basquin-quarkus:runtime:publishToMavenLocal :basquin-quarkus:deployment:publishToMavenLocal
```

Clone and patch the target (outside this repo):

```bash
DEST=../quarkus-super-heroes   # sibling of closureJVM, per docs/THIRD-PARTY-APPS.md:58
git clone --filter=blob:none --no-checkout --depth 1 \
  https://github.com/quarkusio/quarkus-super-heroes.git "$DEST"
cd "$DEST"
git sparse-checkout init --cone && git sparse-checkout set rest-villains && git checkout
git apply /path/to/closureJVM/bench-results/dd043-pr2-restvillains-2026-07-26/pom-deviation.patch
```

Start Postgres and a shared network:

```bash
docker network create basquin-restvillains-net
docker run -d --name basquin-restvillains-db \
  --network basquin-restvillains-net \
  -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
  -p 55432:5432 postgres:18
```

Build (containerized, JDK 25 — the host is JDK 17; see `build.sh`'s header for the two deviations it
reuses verbatim from `bench-results/dd043-spikes-2026-07-24/env/build.sh`, including the `-e HOME=/m2`
fix for the container's UID having no `/etc/passwd` entry):

```bash
APP_DIR="$DEST/rest-villains" ./build.sh clean package -DskipTests
```

Run (same JDK-25 image, since `maven.compiler.release=25` means even *running* — not just building —
needs a JDK 25 runtime):

```bash
APP_DIR="$DEST/rest-villains" DB_CONTAINER=basquin-restvillains-db \
  DB_NETWORK=basquin-restvillains-net ./run-app.sh
```

Drive it — see `curl-transcript.txt` for the full transcript this README quotes from.

## Latency and heap figures

All five samples below are real requests against the running container, each driven once with a
unique `X-Basquin-Req` id and immediately polled (`curl-transcript.txt`). `costCsv` is
`elapsedMs,heapDeltaKB,threadsDelta` (`BasquinBoundaryFilter.publish`, mirroring
`agent/RequestBoundary.java:177-178`'s format).

| id | route | elapsed ms | heap delta | threads delta | notes |
|---|---|---:|---:|---:|---|
| `villains-1` | `GET /api/villains` (100 rows, Hibernate+Panache) | 7 | +415 KB | 0 | first hit, 102 KB JSON body |
| `villains-2` | `GET /api/villains` again | 6 | +477 KB | +4 | thread count rose — virtual-thread/pool warmup |
| `villains-3` | `GET /api/villains/random` | 53 | +962 KB | 0 | random-selection query costs more than a plain list |
| `villains-4` | `GET /api/villains/50` (single row) | 47 | **−16,456 KB** | 0 | negative — see below |
| `villains-5` | `GET /api/villains/hello` (no DB, plain text) | 0 | +97 KB | 0 | trivial route, smallest delta of the five |

**Plausibility.** None of these are suspiciously round, and none read as exactly `0` except
`villains-5`'s latency, which is `Math.max(0, elapsedNanos/1e6)` on a sub-millisecond, no-DB text
response — the floor the code itself defines, not a measurement failure. The heap deltas scale roughly
with route cost (trivial ping route smallest, random-selection query largest), and the 100-row JSON
payload's ~415–477 KB deltas are a believable cost for a Postgres round trip plus Jackson
serialization of a wide entity (each villain has a `powers` string that runs to hundreds of characters).

**On the quantization concern named in the task brief:** §6.1 of the spec measured
`Runtime.freeMemory()` quantizing at exactly 524,288 B **under SubstrateVM** (native image) — every
sample in that spike's series was an exact multiple of that quantum
(`bench-results/dd043-spikes-2026-07-24/s2-memory/series.txt`). This run is **JVM mode**, not native:
HotSpot's `Runtime.freeMemory()`/`totalMemory()` is not known to quantize that way, and none of the
five deltas above are multiples of 524,288 B (415 KB, 477 KB, 962 KB, 97 KB are all "odd" values in
that sense) — consistent with a continuous, non-quantized reading rather than the native floor. The
quantization finding does not transfer to this evidence and is not being claimed here.

**On `villains-4`'s negative delta:** this is not a defect, and the spec predicts it. §6.1 states
plainly that on the reactive path "today's semantics are lock-enforced exclusivity; this is
client-side politeness" — there is no `ITERATION_LOCK` serializing the world, so a GC cycle (or any
background JVM/Hibernate/Agroal-pool housekeeping) landing between the filter's baseline read and the
`addEndHandler` read can show up as `used` heap *shrinking* across the window, exactly what a −16,456 KB
delta looks like. It is reported evidence of exactly the weakened-exclusivity behavior §6.1 already
named as expected on this path, not a suspicious or fabricated number.

## Files in this directory

- `build.sh` — the containerized Maven build entrypoint (JDK 25, same Mandrel image digest Phase-0
  validated).
- `run-app.sh` — runs the packaged fast-jar in the same JDK-25 image, wired to the Postgres container.
- `build.log` — full `clean package -DskipTests` output (`BUILD SUCCESS` at the end).
- `app-startup.log` — the app container's log from boot through the `Installed features` banner.
- `app-log-full.log` — the full app container log after all curl traffic in `curl-transcript.txt` (no
  `[Basquin]` error lines, no OTel WARN/ERROR/exception — only two pre-existing, app-internal Hibernate
  Validator deprecation warnings unrelated to this work).
- `curl-transcript.txt` — every curl command and its raw output for acceptance steps 2-5, plus five
  additional driven requests for the latency/heap table above.
- `pom-deviation.patch` — the sole edit made to `rest-villains/pom.xml`.

## Global constraints honoured

No file under `agent/`, `runner/`, `tomcat-valve/`, `basquin-core/`, or `basquin-quarkus/` was modified
for this acceptance — the only local action against those directories was
`./gradlew :basquin-quarkus:{runtime,deployment}:publishToMavenLocal`, which packages already-committed
source, it does not change it. `./gradlew check` was green (328 root tests, extension module 24, 0
failures) before this work started, and no source in those directories changed afterward, so it remains
green.
