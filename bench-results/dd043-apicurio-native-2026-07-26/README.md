# DD-043 §8.1 — Does the Apicurio Registry *server* build as a GraalVM native image?

## Verdict: NO — on the current (3.x) line there is no native build path for the server at all.

The secondary source was describing the **2.x line**, which *did* build the server native with green,
CI-exercised jobs — but that support does not exist anywhere on current `main`. Target 5 as specced
(current Apicurio Registry) cannot be built native. Substitutes are proposed in §4; the least-cost
option keeps the Apicurio credibility row by pinning the **2.6.x** branch.

---

## 0. Evidence, and what changed when it was re-derived

### 0.1 Where the numbers come from

Everything load-bearing below is now backed by a file in [`capture/`](capture/), produced by
[`capture/capture.sh`](capture/capture.sh) — re-runnable, read-only (GitHub REST + raw file GETs),
and self-documenting: each output file records the exact query, the pinned ref, and the capture
timestamp. **No figure in this README is hand-typed; each is copied from the cited file.**

Capture run used for this revision: **`2026-07-29T15:16:38Z`**, `gh version 2.96.0 (2026-07-02)`
([`capture/00-manifest.tsv`](capture/00-manifest.tsv)).

All Actions queries carry `&status=completed`, so in-flight runs are excluded — a run still executing
has a `null` conclusion and would otherwise pollute a tally.

Refs pinned at that capture ([`capture/00-manifest.tsv`](capture/00-manifest.tsv)):

| repo | ref | resolved SHA | committer date |
|---|---|---|---|
| `Apicurio/apicurio-registry` | `2.6.x` | `b7f3e291fd5861717932294e4a08d94ddc6981fc` | 2025-11-19T12:22:18Z |
| `Apicurio/apicurio-registry` | `main` (README-pinned, §1) | `23159df62ef7a0935f55b8423c3b0d458773ecfc` | 2026-07-25 |
| `Apicurio/apicurio-registry` | `main` (head at capture, drift check) | `5d1dd525e08e76eb9b970d9b6d74e5f8488210cf` | 2026-07-29T14:40:23Z |
| `eclipse-hono/hono` | `master` | `8a4c437061c30d0269ce58a3406cbefb0d97c3c8` | 2026-07-21T15:41:02Z |
| `debezium/debezium-server` | `main` | `860760214720a5b3b09ad1fea28745eca235d914` | 2026-07-29T12:22:50Z |
| `debezium/debezium` | `main` | `38e9e602968c84c11c2cfbbc10b984b944787c5d` | 2026-07-29T12:22:23Z |

### 0.2 Three figures in the 2026-07-26 draft were superseded on re-derivation

Read this before using §4's ranking. The corrected figures are written into the body below; the old
ones are recorded here only so the change is visible.

| # | 2026-07-26 draft claimed | Re-derived 2026-07-29 | Source |
|---|---|---|---|
| D1 | 2.6.x's "last tag `2.6.9.Final` (2025-05-30)" | **Wrong.** Last `2.6.*` tag is **`2.6.13.Final` (2025-07-16)**; the branch's root pom is at `2.6.14-SNAPSHOT` | [`11`](capture/11-apicurio-2.6.x-versions.txt) |
| D2 | Hono: "last 5 runs = 4 success; the single failure (2026-07-26)" | **Superseded, and measured at the wrong level.** Over the last 15 runs: 11 success / 4 failure *at workflow level* — but the `Build native images` **step** was `success` in **15 of 15** | [`21`](capture/21-hono-native-image-runs.txt), [`24`](capture/24-hono-native-build-step.txt) |
| D3 | Debezium Server: "3 of the last 4 runs (2026-07-20..24) succeeded (I did not attribute the one failure to a specific job)" | **Superseded, and measured at the wrong level.** Over the last 12 runs: 7 success / 4 failure / 1 `action_required` at workflow level — but `Verify native build` was **7 success, 0 failure, 4 `skipped`, 1 absent**. The failures are now attributed: `native-build` is `needs: build`, and in 4 of 4 skipped cases `build` failed, so the native job never ran — it never *failed* | [`22`](capture/22-debezium-server-cross-maven-runs.txt), [`25`](capture/25-debezium-native-build-step.txt), [`27`](capture/27-debezium-skip-attribution.txt), [`41`](capture/41-debezium-native-declarations.txt) |

**D2 and D3 are the same defect:** the draft used a *workflow* conclusion as a proxy for "did the
native build pass". A workflow conclusion mixes in unrelated jobs. Re-derived at job and step level,
both substitutes look **stronger**, not weaker, than the draft claimed — so the §4 ranking survives
(see §5 for the re-assessment, including the one respect in which the evidence is now *worse* for
Apicurio 2.6.x).

### 0.2b Round-5 fix: `fetch()` could not distinguish a failed download from a genuine zero

`capture.sh`'s `fetch()` was `curl -sSL "$RAW/$1/$2/$3"` — no `-f`, no status check. A 404 returns
GitHub's `404: Not Found` body **with exit 0**, and every `grep -c`/`grep -n` over it reads as a
clean zero. Every load-bearing zero in §1.1/§1.3/§1.4 (`capture/12`'s `app/pom.xml`/`pom.xml` = 0,
the 25 zero-hit workflow files in `capture/13` including `verify.yaml`) was therefore indistinguishable
from a failed download rather than a checked absence. `capture.sh:30-138` already used the safe
pattern (`%{http_code}` recorded explicitly) for the Dockerfile spot-check; it was not applied
everywhere else.

**Fixed:** `fetch()` now uses `curl -f` and records the HTTP status; every call site that turns its
output into a hit-count or absence claim checks fetch's own exit status first and reports
`FETCH-FAILED` rather than a bare zero if the download failed
([`capture/capture.sh`](capture/capture.sh)).

**Re-verified, and no conclusion changes.** Because §1.1/§1.3/§1.4's zeros are all fetched at the
*pinned* ref `APIC_MAIN_PINNED` (a fixed SHA, not a moving branch), they are re-checkable
byte-for-byte without the Actions-tally drift that affects §2/§5's moving windows. A standalone
re-fetch of every URL behind those zeros — `app/pom.xml`, `pom.xml`, all 29 workflow files including
`verify.yaml` — using the same `-f` discipline returned **HTTP 200 for all of them, zero fetch
failures**: 25 of 29 workflow files zero-hit (matches `capture/13`), `verify.yaml` zero-hit and
confirmed fetched, both poms zero-hit and confirmed fetched
([`capture/16-s1-pinned-fetch-reverification-2026-07-29.txt`](capture/16-s1-pinned-fetch-reverification-2026-07-29.txt)).
The zeros were genuine. §8.1's verdict is unchanged.

### 0.3 Evidence index

| file | what it pins |
|---|---|
| [`capture/00-manifest.tsv`](capture/00-manifest.tsv) | capture timestamp, `gh` version, every ref → SHA |
| [`capture/10-apicurio-2.6.x-app-pom-native-profile.txt`](capture/10-apicurio-2.6.x-app-pom-native-profile.txt) | verbatim `app/pom.xml:585-625` on 2.6.x — the `<id>native</id>` profile |
| [`capture/11-apicurio-2.6.x-versions.txt`](capture/11-apicurio-2.6.x-versions.txt) | 2.6.x Quarkus version, RESTEasy artifact, all `2.6.*` tags with dates |
| [`capture/12-apicurio-main-no-native.txt`](capture/12-apicurio-main-no-native.txt) | `grep -c native` on `main` poms at the pinned ref, plus a head drift check |
| [`capture/13-apicurio-main-workflows-native-grep.txt`](capture/13-apicurio-main-workflows-native-grep.txt) | every `main` workflow file grepped for `native` |
| [`capture/14-apicurio-2.6.x-workflows-native-grep.txt`](capture/14-apicurio-2.6.x-workflows-native-grep.txt) | same for 2.6.x |
| [`capture/15-apicurio-main-cli-pom-and-appprops.txt`](capture/15-apicurio-main-cli-pom-and-appprops.txt) | `cli/pom.xml` native + `cli-skip-native` blocks, root `README.md:117`, `application.properties` storage keys |
| [`capture/16-s1-pinned-fetch-reverification-2026-07-29.txt`](capture/16-s1-pinned-fetch-reverification-2026-07-29.txt) | round-5 fix (§0.2b): every §1.1/§1.3/§1.4 zero re-fetched with `curl -f`, all HTTP 200, no conclusion changed |
| [`capture/20-apicurio-2.6.x-verify-runs.txt`](capture/20-apicurio-2.6.x-verify-runs.txt) | 2.6.x `verify.yaml` runs + native-job conclusions |
| [`capture/21-hono-native-image-runs.txt`](capture/21-hono-native-image-runs.txt) | Hono `native-images-tests.yml` runs + job conclusions |
| [`capture/22-debezium-server-cross-maven-runs.txt`](capture/22-debezium-server-cross-maven-runs.txt) | Debezium Server `cross-maven.yml` runs + `Verify native build` conclusions |
| [`capture/23-native-job-failure-steps.txt`](capture/23-native-job-failure-steps.txt) | per-step breakdown of every failing native job |
| [`capture/24-hono-native-build-step.txt`](capture/24-hono-native-build-step.txt) | Hono `Build native images` **step** conclusions |
| [`capture/25-debezium-native-build-step.txt`](capture/25-debezium-native-build-step.txt) | Debezium `Maven build Debezium Server Native` **step** conclusions |
| [`capture/26-apicurio-2.6.x-native-build-step.txt`](capture/26-apicurio-2.6.x-native-build-step.txt) | Apicurio 2.6.x native job steps — **all pruned by GitHub**, see §5.2 |
| [`capture/27-debezium-skip-attribution.txt`](capture/27-debezium-skip-attribution.txt) | every job of every sampled Debezium run — attributes the 4 `skipped` native builds |
| [`capture/30-dockerfile-native-http-status.txt`](capture/30-dockerfile-native-http-status.txt) | the §4 addendum's 200/404 spot-check, re-run at pinned SHAs |
| [`capture/40-hono-native-declarations.txt`](capture/40-hono-native-declarations.txt) | Hono Quarkus version, `build-native-image` profiles, workflow cron + step names |
| [`capture/41-debezium-native-declarations.txt`](capture/41-debezium-native-declarations.txt) | Debezium Quarkus version, `cross-maven.yml` triggers and `native-build` job |
| [`capture/50-screened-out-candidates.txt`](capture/50-screened-out-candidates.txt) | Polaris / Nessie / code.quarkus.io / Kogito workflow greps at pinned heads |

**Method.** Documentary only — no build attempted, nothing cloned or built. The original pass used a
sparse blob-filtered clone of `github.com/Apicurio/apicurio-registry` at `main` commit
`23159df62ef7a0935f55b8423c3b0d458773ecfc`; this revision re-derives everything through the GitHub
REST API and `raw.githubusercontent.com` at the pinned SHAs in §0.1. "Verified" below means the claim
is backed by a file in `capture/`; anything else is labeled inferred or unre-captured.

## 1. Answers to the five questions

### 1.1 Is there a native profile covering the server (`app`) module, distinct from the CLI?

**No — verified.** On `main` at `23159df`:

- `app/pom.xml` contains **zero** occurrences of the string `native`
  (`grep -c native app/pom.xml = 0`, [`capture/12`](capture/12-apicurio-main-no-native.txt)). The root
  `pom.xml` likewise: `grep -c native pom.xml = 0`. Both counts are still `0` at `main` head
  `5d1dd525` (drift check, same file), so this has not changed since the draft.
- All **29** entries under `.github/workflows/` on `main` grepped for `native`
  ([`capture/13`](capture/13-apicurio-main-workflows-native-grep.txt)): **10 hits total**, spread over
  exactly 4 files — `verify-cli.yaml` (4), `release.yaml` (3), `verify-build.yaml` (1), and
  `.github/workflows/README.md` (2, prose describing the CLI native build, not a workflow). All 10 are
  CLI, enumerated in §1.3. The other 25 entries score 0 hits, `verify.yaml` included.
- The server's only container recipe is `distro/docker/src/main/docker/Dockerfile.jvm` (HTTP **200**);
  `Dockerfile.native` and `Dockerfile.native-scratch` are both **404** on `main` at the pinned ref
  *and* at head ([`capture/30`](capture/30-dockerfile-native-http-status.txt)).

Since a Quarkus native build must be reachable via pom, CI, or config, and none of the three
reference it for the server, there is no declared path — not merely an unexercised one.

### 1.2 What does `-DcliSkipNative` actually govern?

**The CLI only — verified**, `cli/pom.xml` at `main@23159df`
([`capture/15`](capture/15-apicurio-main-cli-pom-and-appprops.txt)):

- Lines 19–23: CLI native is **enabled by default** — `:19` `<!-- Native build is enabled by default.
  Use -DcliSkipNative to skip. -->`, `:20` `<quarkus.native.enabled>true</quarkus.native.enabled>`,
  `:21-23` a pinned `<quarkus.native.builder-image>`
  (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-23`).
- Lines 295–320: profile with `:296 <id>cli-skip-native</id>`, activated by presence of property
  `:299 <name>cliSkipNative</name>`, setting `:303 <quarkus.native.enabled>false</quarkus.native.enabled>`
  and, at `:309-318`, phasing the CLI's `maven-assembly-plugin` `dist` execution to `none` — the ZIP
  would require the native binary. *(The draft said "~293–312"; the profile element actually spans
  295–320.)*

So the spec's suspicion was exactly right: `-DcliSkipNative` is a CLI-native *opt-out*, and says
nothing about the server. The repo's own root `README.md:117` documents it the same way —
`` | `-DcliSkipNative` | Skip CLI native image compilation (no executable is produced, but tests can
still run) | `` (same capture file).

### 1.3 Is a server native build exercised in CI?

**On `main`: no server native anywhere in CI — verified**
([`capture/13`](capture/13-apicurio-main-workflows-native-grep.txt), which lists all 29 entries under
`.github/workflows/` and their hit counts). The only native references are:

- `verify-cli.yaml` — 4 hits, all CLI: `:36` "forward-compatibility issues with GraalVM/native-image
  early in PRs", `:43` `native-image-job-reports: 'true'`, `:78` and `:84`
  `-Dquarkus.native.container-build=false`.
- `release.yaml` — 3 hits: `:227` `# Phase 2c: Build CLI native binaries for each platform.`, `:254`
  `native-image-job-reports: 'true'`, `:264` `-Dquarkus.native.container-build=false \`.
- `verify-build.yaml` — 1 hit, `:44`, which states it outright:
  `# -DcliSkipNative: skip GraalVM native-image for CLI (separate verify-cli job handles this)`.
- `.github/workflows/README.md` — 2 hits (`:134`, `:152`), both prose describing the **CLI** native
  build; not a workflow.
- The other 25 entries, including `verify.yaml`, `verify-unit-tests.yaml`, `verify-extras.yaml`,
  `verify-integration-tests.yaml` and `verify-publish.yaml`: **0 hits**.

**On branch `2.6.x`: yes, genuinely exercised — verified.** This is the origin of the secondary
source's claim:

- `app/pom.xml` on 2.6.x @ `b7f3e291`, verbatim at
  [`capture/10`](capture/10-apicurio-2.6.x-app-pom-native-profile.txt): `:590` `<id>native</id>`,
  activated by property `native` (`:593`); `:619` `<quarkus.native.enabled>true</quarkus.native.enabled>`;
  `:620` `<quarkus.package.jar.enabled>false</quarkus.package.jar.enabled>`; and failsafe wired at
  `:608` to `<native.image.path>…-runner`.
- `Dockerfile.native` and `Dockerfile.native-scratch` are both HTTP **200** on 2.6.x @ `b7f3e291`
  ([`capture/30`](capture/30-dockerfile-native-http-status.txt)).
- 2.6.x has 16 entries under `.github/workflows/`, carrying **20** `native` hits — and all 20 are in
  `verify.yaml` ([`capture/14`](capture/14-apicurio-2.6.x-workflows-native-grep.txt)), which on `main`
  scores 0. The hits are the two native jobs and what they do:
  `:205 build-mem-native-images` / `:206 name: Build and Test In Memory native images`;
  `:309 build-sql-native-images` / `:310 name: Build and Test SQL native images`;
  `:245 make SKIP_TESTS=true build-mem-native`; `:256 make build-mem-native-image push-mem-native-image`;
  and `:270` / `:273` running `run-in-memory-integration-tests` and `run-in-memory-auth-tests` against
  `ttl.sh/${{ github.sha }}/apicurio/apicurio-registry-mem-native:1d` — i.e. the integration and auth
  suites execute **against the native image**, exactly as the draft asserted.
- Actions API ([`capture/20`](capture/20-apicurio-2.6.x-verify-runs.txt), query
  `workflows/1200448/runs?per_page=10&branch=2.6.x`): the most recent `verify.yaml` run on `2.6.x` is
  **`16780646381`, 2025-08-06T14:59:43Z, head `f52d8870`, workflow conclusion `success`**, and within
  it both `Build and Test In Memory native images` and `Build and Test SQL native images` concluded
  **`success`**.
- Across the 10 runs the API returns for that query (window **2025-06-13 .. 2025-08-06**):
  workflow-level **6 success / 2 failure / 2 cancelled**; native-job-level (2 jobs per run)
  **14 success / 2 failure / 4 cancelled**; 0 runs with no native job at all.

**Inferred (not verified):** native support was dropped somewhere in the 2.x→3.0 rewrite. I did not
locate the removing commit or an issue documenting the decision; the verified facts are only
"present and green on 2.6.x, absent on main".

### 1.4 Quarkus version

- `main` @ `23159df`: `<quarkus.version>3.33.2.1</quarkus.version>` (`pom.xml:140`,
  [`capture/12`](capture/12-apicurio-main-no-native.txt)) — close to DD-043's 3.37.3, but moot since
  the server has no native path.
- `2.6.x` @ `b7f3e291` ([`capture/11`](capture/11-apicurio-2.6.x-versions.txt)):
  `<quarkus.version>3.15.3</quarkus.version>` (`pom.xml:154`); root pom `<version>2.6.14-SNAPSHOT</version>`
  (`pom.xml:7`); **last `2.6.*` tag `2.6.13.Final`, 2025-07-16T18:47:17Z** *(see D1 — the draft said
  `2.6.9.Final`, 2025-05-30, which is the fourth-newest tag, not the newest)*; last branch commit
  2025-11-19T12:22:18Z ([`capture/00`](capture/00-manifest.tsv)).
- **3.15.3 vs the harness's 3.37.3 is a real gap** — and 2.6.x's `app` uses
  `quarkus-resteasy-jackson` (RESTEasy **classic**, blocking; `app/pom.xml:106`,
  [`capture/11`](capture/11-apicurio-2.6.x-versions.txt)), not RESTEasy Reactive.

### 1.5 Infrastructure to boot

**None by default — verified on `main@23159df`**
(`app/src/main/resources/application.properties`,
[`capture/15`](capture/15-apicurio-main-cli-pom-and-appprops.txt)):
`:226 apicurio.storage.kind=sql`, `:238 apicurio.storage.sql.kind=h2`,
`:242 apicurio.datasource.url=jdbc:h2:mem:db_${quarkus.uuid}` — in-memory H2, zero external
services. PostgreSQL and KafkaSQL are opt-in storage kinds. The 2.6.x "mem" variant (the one whose
native image CI builds and tests) is likewise self-contained: `verify.yaml:205 build-mem-native-images`
builds `apicurio-registry-mem-native` and at `:270` / `:273` runs the in-memory integration and auth
suites against it ([`capture/14`](capture/14-apicurio-2.6.x-workflows-native-grep.txt)).

*(Both halves of this section are now file-backed: the `main` defaults by
[`capture/15`](capture/15-apicurio-main-cli-pom-and-appprops.txt), the 2.6.x job definition by
[`capture/14`](capture/14-apicurio-2.6.x-workflows-native-grep.txt).)*

## 2. Substitute real-product Quarkus native targets

All three below have a native build that CI actually exercises, verified from the pinned refs and
Actions reads in `capture/`.

### 2.1 Apicurio Registry 2.6.x — least-cost, keeps the credibility row's name

- **Product:** the same product, previous major line; still the honest "real product" row with a
  version asterisk.
- **Native in CI:** the most recent 2.6.x run, `16780646381` (2025-08-06), had both native jobs
  `success`. Across the 10-run window, though, native jobs were **14 success / 2 failure /
  4 cancelled** ([`capture/20`](capture/20-apicurio-2.6.x-verify-runs.txt)) — so "CI-green native" is
  true of the newest run, not of the window.
- **Not verifiable at step level.** GitHub has pruned `.steps[]` for all 10 of those runs (each
  returns `steps=0`), so — unlike Hono and Debezium — there is **no retrievable evidence isolating the
  native *image build* from the integration tests that follow it**
  ([`capture/26`](capture/26-apicurio-2.6.x-native-build-step.txt)). The 2 failures (`16327722821`
  SQL-native, `15740112725` mem-native) therefore **cannot be shown to be integration-leg failures
  rather than image-build failures** — the reassurance Hono and Debezium both get from step data.
  This is an evidence asymmetry, not proof of a defect in the target, but it is real and it does not
  improve with time.
- **Infra:** none (in-memory variant) — the exact variant CI builds native.
- **Caveats (all verified):** maintenance-mode branch (last commit 2025-11-19T12:22:18Z,
  [`capture/00`](capture/00-manifest.tsv)); Quarkus 3.15.3 vs harness 3.37.3; RESTEasy
  classic/blocking, so it adds nothing to the reactive axis (acceptable — the roster's reactive axis
  is carried by rest-heroes; row 5's purpose is credibility). **Newest CI exercise in the window is
  2025-08-06, against a capture date of 2026-07-29** — roughly a year stale, and far older than the
  others below, whose windows are days old.

### 2.2 Eclipse Hono — strongest native-CI signal, reactive, heaviest infra

- **Product:** Eclipse IoT device-connectivity platform (`github.com/eclipse-hono/hono`); genuinely
  a product. `master` head `8a4c4370`, 2026-07-21T15:41:02Z ([`capture/00`](capture/00-manifest.tsv)).
  Quarkus platform **3.27.4.1** (`bom/pom.xml:52`,
  [`capture/40`](capture/40-hono-native-declarations.txt)); Vert.x-based reactive core.
- **Native declared:** `build-native-image` profiles at `adapters/parent/pom.xml:205` and
  `services/parent/pom.xml:203`, both setting `quarkus.native.enabled=true` (`:207` / `:205`)
  ([`capture/40`](capture/40-hono-native-declarations.txt)). Workflow
  `.github/workflows/native-images-tests.yml`, job `Run integration tests with native images`, on
  `cron: '23 3,10,14 * * *'` — i.e. **3×/day** (same file).
- **Native in CI — re-derived** ([`capture/21`](capture/21-hono-native-image-runs.txt),
  [`capture/24`](capture/24-hono-native-build-step.txt)), window **2026-07-24 .. 2026-07-29**,
  15 runs: workflow level **11 success / 4 failure**; the `Build native images` **step**
  concluded `success` in **15 of 15 runs** (0 runs missing step data). Every one of the 4 failures
  happened *after* the image was built: per
  [`capture/23`](capture/23-native-job-failure-steps.txt), in runs `30372013196`, `30279722872` and
  `30187997674` step 5 `Build native images` was `success` and step 6 `Run integration tests with
  Mongo DB and Kafka` was `failure`; in run `30237612865` step 5 and step 6 were `success` and step 7
  `Run integration tests with PostgreSQL DB and Kafka` was `failure`.
- **Caveat on that 15/15:** all 15 runs carry head `8a4c4370`
  ([`capture/24`](capture/24-hono-native-build-step.txt)) — they are 15 cron executions against **one
  unchanged tree**, not 15 independent commits. It evidences build stability and integration-leg
  flakiness; it does not evidence resilience to code churn.
- **Caveats:** multi-component — the fuzz target would be one adapter (e.g. the HTTP adapter) plus
  required services; needs a messaging backend (Kafka or AMQP network) and a device registry
  (MongoDB or JDBC). Highest harness setup cost of the three.

### 2.3 Debezium Server — per-PR native CI, but thin HTTP surface

- **Product:** standalone CDC runtime (`github.com/debezium/debezium-server`); real product.
  `main` head `86076021`, 2026-07-29T12:22:50Z. Quarkus runtime **3.33.1.1** — `debezium/debezium`
  `pom.xml:132` `<quarkus.version.runtime>3.33.1.1</quarkus.version.runtime>`, read at pinned core
  commit `38e9e602` ([`capture/41`](capture/41-debezium-native-declarations.txt)), not at a moving
  `main` URL as the draft did.
- **Native declared:** `.github/workflows/cross-maven.yml:170` job `native-build` / name
  `Verify native build`, `needs: build`; step `:190` `Maven build Debezium Server Native` runs
  `mvnw clean install … -f server/debezium-server-native-dist/pom.xml -Passembly,native …`. Trigger
  is `pull_request` on branches `main, 1.*, 2.*, 3.*, 4.*` (`:14-21`) — so **every PR** to main and
  release branches ([`capture/41`](capture/41-debezium-native-declarations.txt)).
- **Native in CI — re-derived** ([`capture/22`](capture/22-debezium-server-cross-maven-runs.txt),
  [`capture/25`](capture/25-debezium-native-build-step.txt)), window **2026-07-16 .. 2026-07-28**,
  12 runs: workflow level **7 success / 4 failure / 1 `action_required`**; `Verify native build`
  job level **7 success, 0 failure, 4 `skipped`, 1 run where the job is absent**; and the
  `Maven build Debezium Server Native` **step** was `success` in **7 of 7** runs that carried step
  data. The 4 `skipped` are fully attributed
  ([`capture/27`](capture/27-debezium-skip-attribution.txt), which dumps every job of every sampled
  run): `native-build` has `needs: build`, and in **4 of 4** skipped cases the upstream `build` job
  concluded `failure` — e.g. run `30319772988`, `build => failure`, `Verify native build => skipped`.
  There is **no run in the window where the native build ran and failed.** The 1 absent job is run
  `30313669053`, `action_required` — a fork PR awaiting maintainer approval, so *no* job ran at all.
- **Read this correctly:** the native build has not failed once in this window, but it was only
  *exercised* in 7 of 12 runs. "Green whenever it runs" is the honest claim; "green on every PR" is
  not.
- **Caveats:** weak fuzz surface — it is a pipeline runtime whose HTTP API is a small management
  surface, not a full REST product API (**inferred** from its architecture; I did not enumerate its
  endpoints); needs a source database and a sink to do real work.

**Screened out — re-derived at pinned default-branch heads**
([`capture/50`](capture/50-screened-out-candidates.txt)); each repo's `.github/workflows` enumerated
via the contents API and every file grepped for `native`:

| repo | head | workflow files | files containing `native` |
|---|---|---|---|
| `apache/polaris` | `a15bc8d4` (2026-07-29) | 14 | **0** |
| `projectnessie/nessie` | `5627b0f0` (2026-07-28) | 8 | **0** |
| `quarkusio/code.quarkus.io` | `85eb11d1` (2026-06-30) | 3 | **0** |
| `apache/incubator-kie-kogito-apps` | `15b82850` (2026-07-29) | 7 | **2**, both `publish-jitexecutor-native*.yml` — the JIT executor, not the services |

Keycloak was not screened and no claim is made about it.

## 3. Recommendation (for the spec author to decide)

Row 5's stated purpose is *real-product credibility*, and its gate was "does the server build
native". The truthful resolution: pin **Apicurio Registry 2.6.x** for row 5 with the caveats above
(zero infra, same product name, CI-green native, but Quarkus 3.15.3 and blocking), or take **Hono's
HTTP adapter** if the row should also be reactive and on a near-current Quarkus at the cost of Kafka
plus a registry service. Debezium Server is the fallback if the other two fail in practice, accepted
surface limitations and all. Current-line Apicurio (3.x) is not an option for a native row.

**§4 reorders this list.** §3 above is the setup-cost ordering; §4 replaces it with a
risk ordering, and §5 confirms the re-derived evidence still supports §4's order.

---

## 4. Controller addendum (2026-07-26) — spot-check, and a hazard the recommendation misses

**Spot-checked against primary sources** (not the sparse clone) — and, unlike the 2026-07-26 draft,
now **re-captured to files at pinned SHAs**:

- `main@23159df app/pom.xml` → **0** occurrences of `native`
  ([`capture/12`](capture/12-apicurio-main-no-native.txt)).
- `2.6.x@b7f3e291 app/pom.xml:590` → `<id>native</id>` and `:619` →
  `<quarkus.native.enabled>true</quarkus.native.enabled>`
  ([`capture/10`](capture/10-apicurio-2.6.x-app-pom-native-profile.txt)).
- `distro/docker/src/main/docker/Dockerfile.native` → **HTTP 200** on `2.6.x@b7f3e291`, **404** on
  `main@23159df`, and **404** on `main` head `5d1dd525`. Control: `Dockerfile.jvm` is **200** at all
  three refs, so the 404s are file absence, not a bad path
  ([`capture/30`](capture/30-dockerfile-native-http-status.txt)).

Verdict **NO** stands.

**The hazard: §2.1 recommends the one substitute whose Quarkus version most likely cannot load our
extension.** The report treats 3.15.3-vs-3.37.3 as a generic "real gap" about the *target*. It is
sharper than that — it is a gap between the target and **`basquin-quarkus` itself**, which is compiled
against Quarkus 3.37.3 and consumes deployment-module APIs (`FilterBuildItem`, `RouteBuildItem`,
`FeatureBuildItem`) plus an extension-metadata contract that are **not** stable across that many minor
versions. On 3.15.3 the plausible outcome is that augmentation fails outright, or — worse, and this is
DD-043 §1.1's relocated failure mode — that it succeeds and produces a silently uninstrumented binary.

So the cheapest-looking option carries an **unmeasured build-compatibility risk** the other two mostly
avoid: Debezium Server is on 3.33.1.1 and Hono on 3.27.4.1, both far closer to 3.37.3
([`capture/41`](capture/41-debezium-native-declarations.txt),
[`capture/40`](capture/40-hono-native-declarations.txt)).

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

---

## 5. Does the re-derived evidence still support §4's ranking? (2026-07-29)

Yes — and on two of three points more strongly than the 2026-07-26 draft could show.

### 5.1 The ranking's primary input is unchanged

§4 ranks by *Quarkus distance from `basquin-quarkus`'s 3.37.3*, and all three version figures
re-derived to the same values at pinned refs, not moving ones:

| target | Quarkus | source, now pinned |
|---|---|---|
| Debezium Server | **3.33.1.1** | `debezium/debezium@38e9e602 pom.xml:132` ([`capture/41`](capture/41-debezium-native-declarations.txt)) |
| Eclipse Hono | **3.27.4.1** | `eclipse-hono/hono@8a4c4370 bom/pom.xml:52` ([`capture/40`](capture/40-hono-native-declarations.txt)) |
| Apicurio Registry 2.6.x | **3.15.3** | `Apicurio/apicurio-registry@b7f3e291 pom.xml:154` ([`capture/11`](capture/11-apicurio-2.6.x-versions.txt)) |

Ordering by distance from 3.37.3 is therefore unchanged: Debezium, Hono, Apicurio-2.6.x.

### 5.2 The CI-health inputs moved, and moved *in favour of* §4's order

The draft's tallies (D2, D3) were workflow-level. Re-derived at the level that actually answers "can
this be built native today":

| target | native **build** step | evidence quality |
|---|---|---|
| Eclipse Hono | **15 / 15 success** (window 2026-07-24..29) | step-level, complete ([`capture/24`](capture/24-hono-native-build-step.txt)) |
| Debezium Server | **7 / 7 success** where it ran; **0 failures**; 4 of 12 runs `skipped` behind a failed upstream `build`, 1 job absent (window 2026-07-16..28) | step-level for the 7 ([`capture/25`](capture/25-debezium-native-build-step.txt)); skips attributed ([`capture/27`](capture/27-debezium-skip-attribution.txt)) |
| Apicurio Registry 2.6.x | **not retrievable** — job-level only (14 success / 2 failure / 4 cancelled across 20 native jobs), all 10 runs return `steps=0` | job-level only; steps pruned ([`capture/26`](capture/26-apicurio-2.6.x-native-build-step.txt)) |

Debezium's draft figure ("3 of the last 4") made it look like the weakest of the three on CI health;
at job level it has **zero** native-build failures in the window. That removes the one line of
argument that could have unseated it from rank 1. Hono at 15/15 remains the strongest signal but is
still rank 2 on Quarkus distance and infra cost — unchanged.

Apicurio 2.6.x's evidence got *worse*, not better: its newest CI exercise in the window is
**2025-08-06** against a capture date of **2026-07-29**, and GitHub has aged out the step detail, so
its "CI-green native" claim can no longer be resolved below job granularity. Both facts reinforce
rank 3.

### 5.3 What this section does **not** claim

- It does not re-test the §4 hazard. The compatibility of `basquin-quarkus` with 3.15.3 remains
  **unmeasured**; §4's rank-3 condition (spike first) stands as written.
- The Hono 15/15 is 15 cron runs against one commit (§2.2), not 15 commits.
- Debezium's "every PR" trigger is verified from the workflow file, but the job only *ran* in 7 of
  the 12 sampled runs.
- Windows differ per target because they are set by each project's own CI cadence (Apicurio 2.6.x is
  frozen; Hono runs 3×/day; Debezium runs per PR). Comparing the three pass-rates as if they were
  like-for-like samples would be wrong.
- **"Did the native build fail when it ran" is *not* uniformly "no".** Hono: no — 0 of 15 build-step
  failures. Debezium: no — 0 of 7 exercised native jobs failed. **Apicurio 2.6.x: yes — 2 of its 20
  native jobs concluded `failure`** ([`capture/20`](capture/20-apicurio-2.6.x-verify-runs.txt): run
  `16327722821` `Build and Test SQL native images => failure`, run `15740112725` `Build and Test In
  Memory native images => failure`), and because the step lists are pruned those 2 cannot be shown to
  be integration-leg failures rather than image-build failures. Apicurio is the only one of the three
  with unexplained native-job failures in its window.

### 5.4 Re-deriving this

```
bash capture/capture.sh     # read-only; rewrites every file in capture/
```

Figures will move as CI runs; the pinned-SHA facts (§1.1, §1.3 2.6.x, §1.4, §4 spot-check) will not.
If a re-run changes a tally, update the body **and** add a row to §0.2 rather than editing the number
silently.
