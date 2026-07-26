# DD-043 §8.1 — Does the Apicurio Registry *server* build as a GraalVM native image?

## Verdict: NO — on the current (3.x) line there is no native build path for the server at all.

The secondary source was describing the **2.x line**, which *did* build the server native with green,
CI-exercised jobs — but that support does not exist anywhere on current `main`. Target 5 as specced
(current Apicurio Registry) cannot be built native. Substitutes are proposed in §4; the least-cost
option keeps the Apicurio credibility row by pinning the **2.6.x** branch.

**Method.** Documentary only — no build attempted. Sparse blob-filtered clone of
`github.com/Apicurio/apicurio-registry` at `main` commit `23159df62ef7a0935f55b8423c3b0d458773ecfc`
(committed 2026-07-25), sparse pattern covering all `pom.xml`, `.github/workflows/**`, `Dockerfile*`,
`application.properties`, and root files; branch `2.6.x` fetched into the same clone; CI run
conclusions read from the GitHub Actions API on 2026-07-26. "Verified" below means I read the file or
API response; anything else is labeled inferred.

## 1. Answers to the five questions

### 1.1 Is there a native profile covering the server (`app`) module, distinct from the CLI?

**No — verified.** On `main`:

- `app/pom.xml` contains **zero** occurrences of the string `native`. Its profiles are
  `assembly`, codegen (`generate-api-ccompat-v3/v8`, `generate-api-iceberg-v1`, `gencode`,
  `addSource`), and copy steps — nothing native.
- A repo-wide grep of every `pom.xml` for `<id>native</id>`, `native-image`, or `quarkus.native`
  matches only: `cli/pom.xml` (the CLI), `support-chat/pom.xml` (an auxiliary chat service, not the
  registry server), and `examples/*` (demos). Same grep over every `application.properties` outside
  `cli/`, `examples/`, `support-chat/`: no matches.
- The server's only container recipe is `distro/docker/src/main/docker/Dockerfile.jvm`. No
  `Dockerfile.native` exists anywhere on `main` outside `examples/`.

Since a Quarkus native build must be reachable via pom, CI, or config, and none of the three
reference it for the server, there is no declared path — not merely an unexercised one.

### 1.2 What does `-DcliSkipNative` actually govern?

**The CLI only — verified.** `cli/pom.xml`:

- Lines 19–23: CLI native is **enabled by default** — comment "Native build is enabled by default.
  Use -DcliSkipNative to skip." with `<quarkus.native.enabled>true</quarkus.native.enabled>` and a
  pinned `quarkus.native.builder-image`.
- Lines ~293–312: profile `<id>cli-skip-native</id>`, activated by presence of property
  `cliSkipNative`, sets `<quarkus.native.enabled>false</quarkus.native.enabled>` and disables the
  CLI's `maven-assembly-plugin` ZIP (which would require the native binary).

So the spec's suspicion was exactly right: `-DcliSkipNative` is a CLI-native *opt-out*, and says
nothing about the server. `README.md:117` documents it the same way.

### 1.3 Is a server native build exercised in CI?

**On `main`: no server native anywhere in CI — verified.** The only native references in
`.github/workflows/` are:

- `verify-cli.yaml` — builds and verifies the **CLI** native binary on PRs.
- `release.yaml` (Phase 2c, ~line 227) — "Build CLI native binaries for each platform".
- `verify-build.yaml:44` — the comment states it outright:
  `# -DcliSkipNative: skip GraalVM native-image for CLI (separate verify-cli job handles this)`.
- `verify-build.yaml`, `verify-unit-tests.yaml`, `verify-extras.yaml`, `release-images.yaml` all
  pass `-DcliSkipNative` and build/ship the server as **JVM** (`Dockerfile.jvm`).

**On branch `2.6.x`: yes, genuinely exercised — verified.** This is the origin of the secondary
source's claim:

- `app/pom.xml` (2.6.x, ~line 590): profile `<id>native</id>` activated by property `native`
  (`-Dnative`), setting `quarkus.native.enabled=true`, `quarkus.package.jar.enabled=false`, and
  wiring failsafe to test the `-runner` binary.
- `distro/docker/src/main/docker/Dockerfile.native` and `Dockerfile.native-scratch` exist (2.6.x).
- `.github/workflows/verify.yaml` (2.6.x) has jobs `build-mem-native-images` ("Build and Test In
  Memory native images") and `build-sql-native-images` ("Build and Test SQL native images") that run
  `make ... build-mem-native`, push the image to ttl.sh, and run the in-memory integration and auth
  test suites **against the native image**. Triggers: push/PR on `2.6.x`.
- GitHub Actions API (checked 2026-07-26): the most recent `verify.yaml` run on `2.6.x`
  (2025-08-06, head `f52d8870`) concluded **success**, and within it both
  "Build and Test In Memory native images" and "Build and Test SQL native images" concluded
  **success**.

**Inferred (not verified):** native support was dropped somewhere in the 2.x→3.0 rewrite. I did not
locate the removing commit or an issue documenting the decision; the verified facts are only
"present and green on 2.6.x, absent on main".

### 1.4 Quarkus version

- `main`: `<quarkus.version>3.33.2.1</quarkus.version>` (`pom.xml:140`), registry version
  `3.3.1-SNAPSHOT` — close to DD-043's 3.37.3, but moot since the server has no native path.
- `2.6.x`: `<quarkus.version>3.15.3</quarkus.version>` (`pom.xml:154`), last tag `2.6.9.Final`
  (2025-05-30), last branch commit 2025-11-19. **3.15.3 vs the harness's 3.37.3 is a real gap** —
  22 minor versions — and 2.6.x's `app` uses `quarkus-resteasy-jackson` (RESTEasy **classic**,
  blocking; `app/pom.xml:106` on 2.6.x), not RESTEasy Reactive.

### 1.5 Infrastructure to boot

**None by default — verified on `main`** (`app/src/main/resources/application.properties`):
`apicurio.storage.kind=sql` (line 226), `apicurio.storage.sql.kind=h2` (line 238),
`apicurio.datasource.url=jdbc:h2:mem:db_${quarkus.uuid}` (line 242) — in-memory H2, zero external
services. PostgreSQL and KafkaSQL are opt-in storage kinds. The 2.6.x "mem" variant (the one whose
native image CI builds and tests) is likewise self-contained — that is what `build-mem-native-images`
runs integration tests against with no database service in the job (verified in the 2.6.x
`verify.yaml` job definition).

## 2. Substitute real-product Quarkus native targets

All three below have a native build that CI actually exercises, verified from their repos and the
Actions API on 2026-07-26.

### 2.1 Apicurio Registry 2.6.x — least-cost, keeps the credibility row's name

- **Product:** the same product, previous major line; still the honest "real product" row with a
  version asterisk.
- **Native in CI:** verified green (see §1.3 — both native jobs success on the latest 2.6.x run).
- **Infra:** none (in-memory variant) — the exact variant CI builds native.
- **Caveats (all verified):** maintenance-mode branch (last commit 2025-11-19); Quarkus 3.15.3 vs
  harness 3.37.3; RESTEasy classic/blocking, so it adds nothing to the reactive axis (acceptable —
  the roster's reactive axis is carried by rest-heroes; row 5's purpose is credibility). Last CI
  exercise was 2025-08 — older than the others below.

### 2.2 Eclipse Hono — strongest native-CI signal, reactive, heaviest infra

- **Product:** Eclipse IoT device-connectivity platform (`github.com/eclipse-hono/hono`); genuinely
  a product. Active: last commit 2026-07-21. Quarkus platform **3.27.4.1** (`bom/pom.xml:52`);
  Vert.x-based reactive core.
- **Native in CI:** verified. `build-native-image` profiles in `adapters/parent/pom.xml:205-210` and
  `services/parent/pom.xml:203-208` (`quarkus.native.enabled=true`); dedicated workflow
  `.github/workflows/native-images-tests.yml` builds all components as native images and runs the
  integration suite on a cron **3×/day**. Actions API: last 5 runs = 4 success; the single failure
  (2026-07-26) failed at step "Run integration tests with Mongo DB and Kafka", i.e. an
  integration-infra leg, not the native image build.
- **Caveats:** multi-component — the fuzz target would be one adapter (e.g. the HTTP adapter) plus
  required services; needs a messaging backend (Kafka or AMQP network) and a device registry
  (MongoDB or JDBC). Highest harness setup cost of the three.

### 2.3 Debezium Server — per-PR native CI, but thin HTTP surface

- **Product:** standalone CDC runtime (`github.com/debezium/debezium-server`); real product.
  Quarkus runtime **3.33.1.1** (debezium core `pom.xml:132`, fetched from
  `raw.githubusercontent.com/debezium/debezium/main/pom.xml`).
- **Native in CI:** verified. `.github/workflows/cross-maven.yml` job `native-build`
  ("Verify native build") builds `debezium-server-native-dist` with `-Passembly,native` on **every
  PR** to main and release branches; Actions API: 3 of the last 4 runs (2026-07-20..24) succeeded
  (I did not attribute the one failure to a specific job).
- **Caveats:** weak fuzz surface — it is a pipeline runtime whose HTTP API is a small management
  surface, not a full REST product API (**inferred** from its architecture; I did not enumerate its
  endpoints); needs a source database and a sink to do real work.

**Screened out (verified absence of native in CI workflows, sparse clones 2026-07-26):** Apache
Polaris, Project Nessie, `code.quarkus.io` — zero native references in their workflow files (and in
the build files checked: `build.gradle.kts`/`gradle.properties` for the first two, `pom.xml` for the
third); Kogito apps (`apache/incubator-kie-kogito-apps`) — only `jitexecutor-native` publishing
workflows, not the services. Keycloak was not screened and no claim is made about it.

## 3. Recommendation (for the spec author to decide)

Row 5's stated purpose is *real-product credibility*, and its gate was "does the server build
native". The truthful resolution: pin **Apicurio Registry 2.6.x** for row 5 with the caveats above
(zero infra, same product name, CI-green native, but Quarkus 3.15.3 and blocking), or take **Hono's
HTTP adapter** if the row should also be reactive and on a near-current Quarkus at the cost of Kafka
plus a registry service. Debezium Server is the fallback if the other two fail in practice, accepted
surface limitations and all. Current-line Apicurio (3.x) is not an option for a native row.

---

## 4. Controller addendum (2026-07-26) — spot-check, and a hazard the recommendation misses

**Spot-checked against primary sources** (not the sparse clone), all three confirmed:
`main@23159df app/pom.xml` → **0** occurrences of `native`; `2.6.x app/pom.xml:590` → `<id>native</id>`
and `:619` → `quarkus.native.enabled=true`; `Dockerfile.native` → **HTTP 200** on `2.6.x`, **404** on
`main`. Verdict **NO** stands.

**The hazard: §2.1 recommends the one substitute whose Quarkus version most likely cannot load our
extension.** The report treats 3.15.3-vs-3.37.3 as a generic "real gap" about the *target*. It is
sharper than that — it is a gap between the target and **`basquin-quarkus` itself**, which is compiled
against Quarkus 3.37.3 and consumes deployment-module APIs (`FilterBuildItem`, `RouteBuildItem`,
`FeatureBuildItem`) plus an extension-metadata contract that are **not** stable across 22 minor
versions. On 3.15.3 the plausible outcome is that augmentation fails outright, or — worse, and this is
DD-043 §1.1's relocated failure mode — that it succeeds and produces a silently uninstrumented binary.

So the cheapest-looking option carries an **unmeasured build-compatibility risk** the other two mostly
avoid: Debezium Server is on 3.33.1.1 and Hono on 3.27.4.1, both far closer to 3.37.3.

This reorders the recommendation. Ranked by *risk to the thing row 5 is for* (a real product we can
actually instrument), not by setup cost:

1. **Debezium Server** — closest Quarkus (3.33.1.1), native CI on every PR. Its thin HTTP surface is
   a genuine cost, but row 5 buys *credibility that a real product can be instrumented natively*, and
   the fuzz/load axes are already carried by heroes/villains.
2. **Eclipse Hono (HTTP adapter)** — 3.27.4.1 and genuinely reactive, strongest native-CI signal
   (cron 3×/day). Take this if row 5 should also extend the reactive axis and the Kafka + device-registry
   setup is affordable.
3. **Apicurio Registry 2.6.x** — only if a cross-version compatibility spike first shows
   `basquin-quarkus` augments on 3.15.3. Without that spike this is not the low-cost option, it is the
   option whose cost is hidden.

**Unmeasured for every target, not just this one:** the version range over which one build of
`basquin-quarkus` augments successfully. The roster pins heroes/villains at the toolchain's own 3.37.3,
so nothing so far has tested a mismatch. Whichever target row 5 takes, it is the first to exercise this
— which makes it a gate on row 5, in the same shape as §8.2 for PR-4. Not a PR-3 concern.
