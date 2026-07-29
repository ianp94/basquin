# PR-3 acceptance — **native image**, extension injected only

**Verdict: CONFIRMED — the native binary's banner lists `basquin`, and injection was the only
possible source of the extension.** This is the native half of spec §5.2's two-artifact bar (the JVM
half is Task 6, `bench-results/dd043-pr3-restvillains-2026-07-26/`). The fixture's pom was stripped
of the PR-2 leftover declaration first, its local Maven repository was purged of `com.basquin`, and
Central does not carry the artifacts — so the `basquin` feature compiled into the ELF binary can
only have come from the injector's repository injection.

**The question.** Spec §5.2 requires the `Installed features` banner listing `basquin` for **both** a
JVM jar and a native image. Task 6 delivered the JVM half. Does `basquin-maven-injector` — one
`-Dmaven.ext.class.path` flag, one repo-URL property — survive the AOT path too: augmentation under
the native profile, `FeatureBuildItem("basquin")` from a deployment jar fetched over HTTP, native-image
compilation, and a banner from the resulting host-run executable?

**Target choice.** The **Phase-0 fixture**, deliberately not rest-villains. Whether rest-villains
builds native at all is unmeasured — PR-2 validated it in JVM mode only — and gating PR-3 on an
unrelated unknown would gamble the gate. The fixture is already proven to build native under
injection (S4's `banner-native.txt`), so this run isolates *the injector* as the variable under AOT.

## Result

| # | Check | Evidence |
|---|---|---|
| 1 | Injector announced itself | `build-native.log:2` = `[basquin-injector] instrumented fixture (com.basquin:basquin-quarkus:0.3.0 from http://localhost:8000/)` |
| 2 | `BUILD SUCCESS`, native profile | `build-native.log:132`; Mandrel 25.0.3.0 JDK 25.0.3+9-LTS (`:55`), native-image proper 41.2s (`:128`), Maven total 03:11 min (`:134`) |
| 3 | All three chain artifacts fetched from the injected repo | `build-native.log:10,13,17,19` (runtime+core, pom+jar) and `:30,33` (deployment, pom+jar) — all `Downloaded from basquin-injected`; **zero** `Downloaded from central:` lines for any `com/basquin` path |
| 4 | Native binary runs on the host and serves | `fixture-1.0.0-SNAPSHOT-runner` (ELF 64-bit x86-64, dynamically linked, stripped, 48,655,416 bytes — `binary-stat.txt:14,18`) run directly on this WSL2 host: `started in 0.144s` (`banner-native.log:5`), `/ok` → `200` |
| 5 | Banner lists `basquin` | `banner-native.log:7` / `banner-native.txt`: `Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]` |

Baseline comparison: S4's native run of this same fixture reported
`[cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]` — `smallrye-openapi` there was
S4's injected probe marker, not a fixture default. Netting out each run's injected marker, the
feature set is identical; here the marker is `basquin`, arriving via the production injector instead
of a spike probe.

## The precondition: the pom no longer declares the extension

S5 recorded that the fixture's `pom.xml` still declared `com.basquin:basquin-quarkus:0.3.0` (a
PR-2-spike leftover), which made the *dependency's* presence unattributable to injection there. That
declaration and its explanatory comment are now **removed** (the fixture is our own test scaffolding,
so the edit is legitimate — committed together with this evidence). Verified with
`grep -c "artifactId>basquin-quarkus<" fixture/pom.xml` → `0` (deliberately not `grep -c basquin`,
which can never reach 0: the fixture's own groupId is `com.basquin.spike`). Removal also matters
operationally since commit `726eac3`: the injector now hard-fails on a *declared* conflicting
version, and a declaration at the matching version would make a pass prove nothing.

## The ambiguity control

Before the build, the fixture's local repository
(`bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository`, mounted at `/m2` by `env/build.sh`) was
purged of the entire `com/basquin` tree — it held `basquin-core`, `basquin-quarkus`, and
`basquin-quarkus-deployment` from the previous run. `purge-proof.txt` captures the before-listing,
the `rm -rf`, and the failing `find` after. With the local repo empty and Central not carrying the
artifacts (`bench-results/dd043-s5-repo-injection-2026-07-26/central-absence.txt`; this build's six
`Downloading from central:` attempts for `com/basquin` paths all have no matching `Downloaded from
central:` line), the injected repository is the only possible source. `http-access.log` agrees: 13
GETs, all `200` — 1 host-side pre-build server check plus 12 build fetches (3 artifacts x pom,
pom.sha1, jar, jar.sha1). The deployment artifact — named by **no pom anywhere**, only by the runtime
jar's `META-INF/quarkus-extension.properties` — was fetched by the Quarkus bootstrap resolver on its
own during `quarkus:3.37.3:generate-code` (`build-native.log:27-33`), the same unconfounded signal
Task 6 leaned on. Timestamp note: `http-access.log` is server-local EDT (UTC−4).

## Provenance

- Fixture: `bench-results/dd043-spikes-2026-07-24/fixture/` at this commit (pom edit included).
  Build entrypoint: `env/build.sh` **unmodified** — it already forwards `EXTRA_MAVEN_OPTS` (line 40)
  and expands `${EXTRA_DOCKER_ARGS:-}` (line 41); same pinned Mandrel image digest as all Phase-0
  spikes.
- Injector: `basquin-maven-injector-0.3.0.jar` built from this branch at `726eac3` via
  `./gradlew :basquin-maven-injector:jar`; Sisu index confirmed = `com.basquin.maven.BasquinInjector`.
  Staged at `build/tmp/pr3-inj`, mounted at `/inj`.
- Served repository: `build/tmp/pr3-pages`, populated from the same HEAD by the Pages publish tasks
  (`-PbasquinPagesDir=…`), served by `python3 -m http.server` on `127.0.0.1:8000`. Not tracked in git
  (`bench-results/` tracks no built jars or binaries); the access log, not those bytes, is the
  evidence.
- Build command: `env/build.sh clean package -DskipTests -Dnative`. `clean` is mandatory —
  `bench-results/dd043-pr2-spike-2026-07-25/README.md` records that a plain `package` fails on the
  fixture's leftover JaCoCo offline-instrument plugin *and* can still emit a correct-looking banner
  from the previous build's artifact. `-Dnative` activates the fixture's `native` profile
  (`fixture/pom.xml`, property-activated), S4's switch.
- Run method: the ELF executable ran **directly on the host**, S4's proven method for this artifact
  (`s4-injection/findings.md:147-151` — no loader or glibc incompatibility on this Ubuntu/WSL2 host).
  No container, no added variable.
- Native mutex honoured (spec §7.2): no other native build, benchmark, or CPU-heavy work ran
  alongside; the only running container was the untouched `basquin-control-plane` kind node.

## Transport note (HTTP, localhost, and the http-blocker)

Same measured channel as S5 and Task 6, not a convenience: `--network host` with
`-Dbasquin.inject.repo.url=http://localhost:8000/`. Maven 3.9.16's default
`maven-default-http-blocker` mirror matches `external:http:*` and exempts localhost; a docker-gateway
URL is exactly the class that mirror matches, and mirror re-application to an *injected* repository
is unmeasured. The fetch that would be blocked is the deployment artifact's — the decisive one.

## What this establishes, and what it does not

**Establishes:** on the pinned toolchain (containerised Maven 3.9.16 wrapper, Quarkus 3.37.3, Mandrel
25.0.3.0 / JDK 25), the injector's two-level repository injection survives the **native** packaging
path end to end: the deployment jar fetched over HTTP ran its build steps during augmentation under
`-Dnative`, `FeatureBuildItem("basquin")` was compiled into the AOT image, and the resulting
executable — run directly on the host — booted in 0.144s, served a request, and lists `basquin` in
its banner. Together with Task 6's JVM cell this completes spec §5.2's two-artifact requirement.

**Does not establish:**

- **A real product in native mode.** The target is the Phase-0 **fixture** — our own minimal
  scaffolding — not a third-party application. **rest-villains' native buildability remains
  unmeasured**, with or without the injector; that is a deliberately separate unknown, not part of
  this gate.
- **Generality.** One target, single-module; multi-module reactors are unexercised under AOT.
- **HTTPS/TLS transport.** Localhost plain HTTP stood in for the real GitHub Pages HTTPS endpoint;
  certificate trust and resolver TLS behaviour were not exercised (same scope limit as S5/Task 6).
- **Mirror re-application.** Environments with a user-defined catch-all mirror remain unmeasured —
  localhost would be exempt from the default blocker either way.

## Files in this directory

| File | What it is |
|---|---|
| `build-native.log` | full decisive build (`clean package -DskipTests -Dnative`) — injector line at :2, `BUILD SUCCESS` at :132 |
| `purge-proof.txt` | fixture local-repo `com/basquin` before-listing, purge, failing `find` after |
| `http-access.log` | the HTTP server's request log — 13 GETs, all `200`; the deployment artifact's 4 are the unconfounded fetch |
| `banner-native.log` | the native binary's startup output, boot through banner |
| `banner-native.txt` | the extracted `Installed features` line |
| `binary-stat.txt` | post-hoc `file`/`stat` on the still-present (gitignored) binary — backs check 4's size/format figure; mtime cross-checks against `build-native.log:135`'s finish timestamp |

The built binary (`fixture/target/fixture-1.0.0-SNAPSHOT-runner`) and the served jars
(`build/tmp/pr3-pages`) are **not** tracked — `bench-results/` tracks no built artifacts anywhere.

## Reproduce

From the `closureJVM` repo root. Prerequisites: Docker, ports 8000/8080 free, no other native build
running (spec §7.2's mutex), ~15 minutes of quiet CPU.

```bash
OUT=bench-results/dd043-pr3-native-2026-07-26

# 0. Precondition: the fixture pom must not declare the extension
grep -c "artifactId>basquin-quarkus<" bench-results/dd043-spikes-2026-07-24/fixture/pom.xml  # must be 0

# 1. Ambiguity control: purge com.basquin from the FIXTURE's local repo; keep the proof
ls -d bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin/*   # capture: before
rm -rf bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin
find bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin -type f  # must error

# 2. Build the injector jar and publish the chain to a scratch Pages repo
./gradlew :basquin-maven-injector:jar
rm -rf build/tmp/pr3-pages
./gradlew -PbasquinPagesDir=build/tmp/pr3-pages \
  :basquin-core:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository

# 3. Serve it on 127.0.0.1 (the measured transport; see the transport note)
python3 -u -m http.server 8000 --bind 127.0.0.1 -d build/tmp/pr3-pages \
  > "$OUT/http-access.log" 2>&1 &
echo $! > /tmp/pr3-native-http.pid
curl -sf -o /dev/null -w 'server up: %{http_code}\n' \
  http://localhost:8000/com/basquin/basquin-quarkus/0.3.0/basquin-quarkus-0.3.0.pom

# 4. Stage the jar and run the decisive native build (env/build.sh unmodified — it has both hooks)
STAGE="$PWD/build/tmp/pr3-inj"; mkdir -p "$STAGE"
cp basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar "$STAGE/"
EXTRA_DOCKER_ARGS="--network host -v $STAGE:/inj" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar -Dbasquin.inject.repo.url=http://localhost:8000/" \
  bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests -Dnative \
  2>&1 | tee "$OUT/build-native.log"
grep '\[basquin-injector\] instrumented fixture' "$OUT/build-native.log"  # absent => STOP

# 5. Run the binary DIRECTLY ON THE HOST (S4's proven method) and read the banner
BIN=bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner
test -x "$BIN" || { echo "native binary missing"; exit 1; }
"$BIN" > "$OUT/banner-native.log" 2>&1 & echo $! > /tmp/pr3-native-app.pid
sleep 5
curl -sf -o /dev/null -w "served /ok: %{http_code}\n" http://localhost:8080/ok
grep "Installed features" "$OUT/banner-native.log" | tee "$OUT/banner-native.txt"

# 6. Clean up (restore the local repo any time by just rebuilding — it re-fetches)
kill "$(cat /tmp/pr3-native-app.pid)"  && rm -f /tmp/pr3-native-app.pid
kill "$(cat /tmp/pr3-native-http.pid)" && rm -f /tmp/pr3-native-http.pid
```
