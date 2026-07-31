# PR-3 acceptance — `rest-villains` instrumented with **zero pom edits**

**The question.** PR-2's rest-villains acceptance (`bench-results/dd043-pr2-restvillains-2026-07-26/`)
reached the `basquin` banner via **a manual pom edit** (`pom-deviation.patch` there), and its README
explicitly hands PR-3 the job of proving the same result with no edit at all. Does
`basquin-maven-injector` — one `-Dmaven.ext.class.path` flag, nothing else — instrument a real
third-party Quarkus application whose tree is never touched and whose local Maven repository starts
empty of `com.basquin`?

**Verdict: CONFIRMED — all five acceptance checks passed.** This directory's own artifact
(`pristine-proof.txt:6,11`) is a single post-hoc `git status --porcelain` check, empty, taken after
the build and the app run had already happened. The two-sided before-and-after property check 5 names
is established separately, by `scripts/verify-dd043-pr3.sh`'s `jvm` stage — dirty-tree refusal before
the build (the preflight `git -C "$app" status --porcelain -- .`, whose non-zero `rc` `skip`s the whole
stage as UNMEASURED) and `jvm:zero-edits` after it
(`git status --porcelain=v2 --branch -- .` into `jvm-target-status-after.txt`), graded in
`bench-results/RUN-OF-RECORD/RESULTS.md`'s `jvm:zero-edits` row. All three are cited by
command text and row key, not by line: the script's line numbers went stale twice while this very
citation was being fixed, and any added guard row renumbers that table. The run *directory* is the part
that still has to be re-checked by hand — the previous name here was superseded and deleted while this
line kept citing it. Not by this directory. The
local repo was purged of `com.basquin` first so resolution could not succeed for the wrong reason;
and the **deployment** artifact — named by no pom anywhere — was fetched from the injected repository
by the Quarkus bootstrap resolver on its own. **JVM mode only — the native cell is separate evidence
(spec §5.2's other half).**

## Result

| # | Check | Evidence |
|---|---|---|
| 1 | `BUILD SUCCESS` **and** the injector announced itself | `build.log:125`; `build.log:2` = `[basquin-injector] instrumented rest-villains (com.basquin:basquin-quarkus:0.3.0 from http://localhost:8000/)` |
| 2 | Startup banner lists `basquin` under `Installed features` | `app-startup.log:25`, extracted to `banner.txt` |
| 3 | The boundary works, not just loads: driven request returns a cost line, not `miss` | `result-poll.txt`: `X-Basquin-Req: pr3-accept-1` → `/__basquin/result?id=pr3-accept-1` → `787,-684,9|0||` |
| 4 | `basquin-quarkus-deployment` fetched from the injected repo | `http-access.log` (4 GETs for it, all `200`); `build.log:31-36` (`Downloaded from basquin-injected`) |
| 5 | App tree pristine, checked once, post-hoc | `pristine-proof.txt:6,11`: a single `git status --porcelain`, empty, taken after `clean package` and after the app container ran. The before-**and**-after property (not merely after) is established by the verify run, not this artifact — in `scripts/verify-dd043-pr3.sh`, the preflight `git -C "$app" status --porcelain -- .` (before; a non-zero `rc` `skip`s the stage as UNMEASURED) and `git status --porcelain=v2 --branch -- .` into `jvm-target-status-after.txt` (after). Each is that file's only occurrence, so it is cited by command text, not by a line number that edits keep invalidating. Graded `PASS` at `bench-results/RUN-OF-RECORD/RESULTS.md`'s `jvm:zero-edits` row (row key, not line — an added guard row renumbers the table), against the same clone commit `c9b46d74…` this directory used (that run's `jvm-target-status-after.txt:1`) |

Build wall time 01:03 min (`build.log`, `Total time`). On check 3's numbers: 787 ms elapsed is a
first-hit (Hibernate/Agroal warmup) figure, the −684 KB heap delta is the reactive-path GC-in-window
behaviour PR-2's README already documents under "On `villains-4`'s negative delta", and +9 threads is
pool warmup. The point here is the line's *shape* — `costCsv|invariantCount|detail|leak`, not `miss` —
proving the filter sat on the request path.

**Do not read the −684 KB as a measurement.** The spec assigns negative heap deltas to **PR-5** as an
`UNMEASURED` producer (the `| **PR-5** |` row of §9's delivery table in
`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md` — cited by row key, not line: that
table's rows are edited in place and every edit above PR-5 renumbers it), because the in-flight counter
structurally cannot detect them — a GC is not a request. This run is the second independent sighting on
a different code path (PR-2 measured −16,456 KB on this same target), which makes the gap systematic
rather than incidental, and is a data point for PR-5 rather than a defect in this acceptance. Until
PR-5's disposition lands, the heap column of any row derived from this channel is not publishable.

## Why check 4 is the unconfounded one

`basquin-quarkus` could in principle appear in a model some other way; `basquin-quarkus-deployment`
cannot. Its coordinate appears in **no pom anywhere** — only in the runtime jar's
`META-INF/quarkus-extension.properties` — and the Quarkus bootstrap resolver fetches it on its own
during `quarkus:generate-code`. That fetch succeeded **only** because the injector's repository
injection reached the resolver's effective repository list (S5's two-level mutation, now production
code in `BasquinInjector`). `build.log:31/34` show Central being *attempted* for the deployment pom
and jar; the log contains **zero** `Downloaded from central:` lines for any `com/basquin` path
(Central does not carry the artifacts — `bench-results/dd043-s5-repo-injection-2026-07-26/central-absence.txt`),
and every `Downloaded from` line for all three artifacts names `basquin-injected` (`build.log:10,13,17,19,33,36` —
six lines: 3 artifacts x pom+jar).

## The ambiguity control

Before the build, the **host's real `~/.m2`** (the repository `build.sh` bind-mounts into the
container) was purged of the entire `com/basquin` tree — it held `basquin-core`, `basquin-quarkus`,
and `basquin-quarkus-deployment` from PR-2's `publishToMavenLocal`. `purge-proof.txt` captures the
before-listing, the `rm -rf`, and the failing `find` after. With the local repo empty of
`com.basquin` and Central not carrying it, the injected repository is the only possible source. The
HTTP access log agrees: 13 GETs, all `200` — the first is the host-side pre-build server check, the
remaining 12 are the containerized build (3 artifacts x pom, pom.sha1, jar, jar.sha1). Timestamp
note, same as S5: `http-access.log` is server-local EDT (UTC−4), `build.log` is UTC. The build's
download lines carry no timestamp of their own, so the alignment is derived, not read directly:
`build.log`'s `Finished at: 2026-07-26T11:48:22Z` (`:128`) minus `Total time:  01:03 min` (`:127`)
puts the build's start at ~11:47:19Z, and `http-access.log`'s GETs for the served artifacts run
`07:47:21`-`07:47:37` EDT (`:3-14`) — i.e. `11:47:21`-`11:47:37` UTC — inside that window.

## Provenance

- Target: `quarkusio/quarkus-super-heroes` at `c9b46d745620708e1519859bb4775469114381e5` — the same
  sibling clone PR-2 used (`../quarkus-super-heroes/rest-villains`, never committed here — not in
  `git ls-files`). PR-2's pom deviation was reverted
  (`git checkout -- pom.xml`) and the tree confirmed pristine before the run.
- Injector: `basquin-maven-injector-0.3.0.jar`, built from this branch at `58eb601` (working tree
  clean apart from this evidence directory) via `./gradlew :basquin-maven-injector:jar`. Sisu index
  confirmed to name `com.basquin.maven.BasquinInjector` — the guard against Maven silently
  tolerating a broken `maven.ext.class.path` and completing an uninstrumented build.
- Served repository: `build/tmp/pr3-pages`, populated from the same HEAD by the Pages publish tasks
  (`-PbasquinPagesDir=…`), served by `python3 -m http.server` on `127.0.0.1:8000`. Not tracked in
  git (`bench-results/` tracks no built jars); the access log, not those bytes, is the evidence.
- Build/run environment: PR-2's harness verbatim — same pinned Mandrel image digest, same
  `run-app.sh`, same standalone `postgres:18` with the app's own credentials — except for the one
  `build.sh` change documented in its header (`${EXTRA_DOCKER_ARGS:-}`).

## Transport note (HTTP, localhost, and the http-blocker)

The build container ran with `--network host` and the repo URL `http://localhost:8000/` — spike S5's
**measured** channel, not a convenience. Maven 3.9.15's default `maven-default-http-blocker` mirror
matches `external:http:*` and exempts localhost; a docker-gateway URL (`http://172.17.x.x:8000/`) is
precisely the class that mirror matches, and whether mirrors are re-applied to an *injected*
repository is unmeasured (S5's README, "Transport note"). The fetch that would be blocked is the
deployment artifact's — check 4, the decisive evidence — so an unmeasured transport would have
confounded exactly the check that matters.

## What this establishes, and what it does not

**Establishes:** on the pinned toolchain (the app's own Maven wrapper, `apache-maven-3.9.15` —
`bench-results/dd043-pr3-optional-declaration-2026-07-29/provenance.txt:57` = the wrapper's
`distributionUrl=…/apache-maven/3.9.15/apache-maven-3.9.15-bin.zip`, restated in
`BasquinInjector.java`'s `failOnUnusableDeclaration` javadoc — containerised, Quarkus 3.37.3, JVM
packaging, JDK 25 Mandrel image), `basquin-maven-injector` instruments a real third-party
application — datasource, Hibernate, contract-first generated resources — with zero edits to any
file in the application's tree, no settings.xml, and no operator pre-populate step: one system
property naming the extension jar, one naming the repository URL. The full `com.basquin` closure
resolved over HTTP from the injected repository, augmentation ran the deployment module, and the
boundary filter answered on a real route.

**Does not establish:**

- **Native mode.** JVM packaging only; spec §5.2's native half is separate evidence
  (`bench-results/dd043-pr3-native-2026-07-26/`).
- **Generality.** One target. rest-villains is real and was not written for this project, but it is
  a single Maven single-module app; multi-module reactors are unexercised here (the injector's
  per-project loop runs, but over a one-project reactor).
- **HTTPS/TLS transport.** Localhost plain HTTP stood in for the real GitHub Pages HTTPS endpoint;
  certificate trust and resolver TLS behaviour were not exercised (same scope limit S5 recorded).
- **Mirror re-application.** Environments with a user-defined catch-all mirror remain unmeasured —
  localhost would be exempt from the default blocker either way.

## Files in this directory

| File | What it is |
|---|---|
| `build.sh` | PR-2's containerized build entrypoint plus the one documented change: `${EXTRA_DOCKER_ARGS:-}` before `"$IMAGE"` |
| `build.log` | full decisive build (`clean package -DskipTests`) — injector line at :2, `BUILD SUCCESS` at :125 |
| `purge-proof.txt` | host `~/.m2` `com/basquin` before-listing, purge, failing `find` after |
| `pristine-proof.txt` | check 5's artifact: the clone's commit + empty `git status`, plus the build-log argument that the model Maven built for `rest-villains` carried no `com.basquin:basquin-quarkus` dependency when the injector read it. The discriminator is the **absence** of the `already declares` line (`grep -c` = 0), not the presence of `instrumented` — that line prints on both paths (`BasquinInjector.java:108-109`). `instrumented` establishes only that the loop body ran, which is what makes the absence non-vacuous; see that file's "Amended 2026-07-30" section |
| `http-access.log` | the HTTP server's request log — every remote fetch with status codes; the deployment artifact's 4 GETs are check 4 |
| `app-startup.log` | app container log from boot through the banner |
| `banner.txt` | the extracted `Installed features` line |
| `result-poll.txt` | the driven request and the `/__basquin/result` poll transcript |

## Reproduce

From the `closureJVM` repo root. Prerequisites: the sibling clone per PR-2's README (do **not**
apply its `pom-deviation.patch` — an unmodified tree is the property under test), Docker, ports
8000/8084/55432 free.

```bash
APP_DIR=/abs/path/to/quarkus-super-heroes/rest-villains   # sibling clone, pristine
export APP_DIR

# 0. Property under test: the tree is pristine (revert PR-2's patch if present)
(cd "$APP_DIR" && git status --porcelain)                 # must print nothing

# 1. Ambiguity control: purge com.basquin from the HOST's ~/.m2; keep the proof
ls -d ~/.m2/repository/com/basquin/*                      # capture: before
rm -rf ~/.m2/repository/com/basquin
find ~/.m2/repository/com/basquin -type f                 # must error: No such file

# 2. Build the injector jar and publish the chain to a scratch Pages repo
./gradlew :basquin-maven-injector:jar
rm -rf build/tmp/pr3-pages
./gradlew -PbasquinPagesDir=build/tmp/pr3-pages \
  :basquin-core:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
  :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository

# 3. Serve it on 127.0.0.1 (S5's measured transport; see the transport note)
python3 -u -m http.server 8000 --bind 127.0.0.1 -d build/tmp/pr3-pages \
  > bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log 2>&1 &
echo $! > /tmp/pr3-http-server.pid
curl -sf -o /dev/null -w 'server up: %{http_code}\n' \
  http://localhost:8000/com/basquin/basquin-quarkus/0.3.0/basquin-quarkus-0.3.0.pom

# 4. Stage the jar OUTSIDE the app clone ($APP_DIR/.. is the clone root), then build
STAGE="$PWD/build/tmp/pr3-inj"; mkdir -p "$STAGE"
cp basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar "$STAGE/"
EXTRA_DOCKER_ARGS="--network host -v $STAGE:/inj" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/basquin-maven-injector-0.3.0.jar -Dbasquin.inject.repo.url=http://localhost:8000/" \
  bench-results/dd043-pr3-restvillains-2026-07-26/build.sh clean package -DskipTests \
  2>&1 | tee bench-results/dd043-pr3-restvillains-2026-07-26/build.log
grep '\[basquin-injector\] instrumented rest-villains' \
  bench-results/dd043-pr3-restvillains-2026-07-26/build.log   # absent => STOP, uninstrumented build
# The pristine discriminator (see pristine-proof.txt): the injector must NOT have found a
# declaration. `instrumented` alone does not show this — it prints on both paths.
grep -c 'already declares' \
  bench-results/dd043-pr3-restvillains-2026-07-26/build.log   # must be 0 => model had no declaration

# 5. The claim itself: still pristine AFTER the build
(cd "$APP_DIR" && git status --porcelain)                 # must print nothing

# 6. Run it (PR-2's harness verbatim) and read the banner
docker network create basquin-restvillains-net
docker run -d --name basquin-restvillains-db --network basquin-restvillains-net \
  -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
  -p 55432:5432 postgres:18
DB_CONTAINER=basquin-restvillains-db DB_NETWORK=basquin-restvillains-net \
  bench-results/dd043-pr2-restvillains-2026-07-26/run-app.sh
sleep 15 && docker logs basquin-restvillains-app 2>&1 | grep 'Installed features'

# 7. The boundary, not just the banner
curl -s -H 'X-Basquin-Req: pr3-accept-1' http://localhost:8084/api/villains > /dev/null
curl -s 'http://localhost:8084/__basquin/result?id=pr3-accept-1'   # CSV cost line, not "miss"

# 8. Check 4: the deployment artifact came over the injected repo
grep basquin-quarkus-deployment bench-results/dd043-pr3-restvillains-2026-07-26/http-access.log

# 9. Clean up (restore ~/.m2 any time with ./gradlew publishToMavenLocal)
docker rm -f basquin-restvillains-app basquin-restvillains-db
docker network rm basquin-restvillains-net
kill "$(cat /tmp/pr3-http-server.pid)" && rm -f /tmp/pr3-http-server.pid
```

## Global constraints honoured

No file under `agent/`, `runner/`, `tomcat-valve/`, `basquin-core/`, `basquin-quarkus/`, or
`basquin-maven-injector/` was modified for this acceptance — the only actions against those modules
were `:basquin-maven-injector:jar` and the three Pages publish tasks, which package
already-committed source. The only file ever changed in the app clone was reverting PR-2's pom
deviation back to upstream bytes; nothing was created there, and the injector jar was staged under
this repo's `build/tmp/`, not anywhere in the clone.
