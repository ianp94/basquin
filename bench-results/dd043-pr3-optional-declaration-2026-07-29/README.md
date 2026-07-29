# `<optional>true</optional>` is not a silent bypass — measured, not argued

**The question.** `BasquinInjector.failOnUnusableDeclaration` hard-fails four shapes of pre-existing
`com.basquin:basquin-quarkus` declaration because each one would produce a build that **succeeds** while
shipping an uninstrumented application. It does **not** fail `<optional>true</optional>`, even though
`addDependency` sets no optional flag, so an accepted optional declaration is not identical to what the
injector would have added. PR #103's review asked the fair question: is `optional` a fifth silent-bypass
shape that the guard is letting through?

**Verdict: NOT A BYPASS — all six checks passed.** With `<optional>true</optional>` as the *only*
deviation from upstream and the real injector on `maven.ext.class.path`, the injector took its
**accept** path, both basquin jars reached `quarkus-app/lib/main/` in a file list identical to the
non-optional injected build's, the runtime banner listed `basquin`, and a driven request answered
`/__basquin/result` with a cost line rather than `miss`. **JVM mode, one target, one shape — see
"What this does not establish".**

## Result

| # | Check | Evidence |
|---|---|---|
| 1 | The optional declaration is the **only** deviation from the upstream pom | `pom-deviation.diff:7-12` — one added `<dependency>` block, `<optional>true</optional>` at `:10`; the file is 15 lines with a single `@@` hunk (`:3`), so there is no second change anywhere in the pom |
| 2 | The injector took the **accept** path — the guard did not fire on the optional shape | `build-optional-injector.log:2` = `[basquin-injector] rest-villains already declares basquin-quarkus:0.3.0; adding the repository only`, then `:3` `instrumented rest-villains (…)` |
| 3 | `BUILD SUCCESS` with the injector, augmentation ran | `build-optional-injector.log:108`; augmentation at `:106` (`completed in 2613ms`), `Total time: 8.396 s` at `:110` |
| 4 | Both basquin jars are in `quarkus-app/lib/main/`, and the whole listing matches the non-optional injected build | `libmain-diff.txt:4` (`diff` exit 0, no differences), `:7-8` (228 files each), `:12-13` (`com.basquin.basquin-core-0.3.0.jar`, `com.basquin.basquin-quarkus-0.3.0.jar`); raw listings in `libmain-optional-injector.txt:4-5` and `libmain-nonoptional-reference.txt` |
| 5 | Startup banner lists `basquin` under `Installed features` | `app-startup.log:25`, extracted to `banner.txt:1` |
| 6 | The boundary **works**, not just loads: a driven request returns a cost line, and an undriven id still returns `miss` | `result-poll.txt:8` = `176,-12207,10|0||` for `X-Basquin-Req: optional-rerun-1` (HTTP 200 at `:5`); control at `result-poll.txt:12` = `miss` |

Check 6's control is what makes check 6 mean something: the same endpoint, polled for an id nobody
drove, answers `miss` (`result-poll.txt:11-12`). So the cost line at `:8` is the filter having sat on
that request, not the endpoint answering anything to anyone.

**Do not read `176,-12207,10|0||` as a measurement.** It is a first-hit figure on a cold app, and its
negative heap component is the same PR-5-owned artefact `bench-results/dd043-pr3-restvillains-2026-07-26/README.md:36-41`
documents — a GC inside the window, which the in-flight counter structurally cannot detect. The only
load-bearing property here is the line's **shape** (`costCsv|invariantCount|detail|leak`, not `miss`).

## Provenance of the captures — and which one is a re-run

The build evidence (checks 1–4) is the **original** capture: `build-optional.log` and
`build-optional-injector.log` are that run's own stdout, and `libmain-optional-injector.txt` is a
listing of the `quarkus-app` those builds produced. (A working note describing this same run,
`.superpowers/sdd/pr103-fix-s1-report.md`, is not part of the committed record — `.superpowers/` is
gitignored and `git ls-files .superpowers/` returns 0 files — so it is not cited as evidence here.)

The runtime evidence (checks 5–6) is a **re-run**, on 2026-07-29T15:01–15:02Z. The original run's
containers had been torn down, so its banner and its cost line no longer existed as captured output
anywhere in the committed record. Rather than retype a figure from an uncommitted note, the
**same `target/quarkus-app/quarkus-run.jar` that Run B produced** was booted again against a fresh
`postgres:18` and re-polled. The re-run **agreed on every load-bearing property** — `basquin` in the
banner, a cost line rather than `miss` (`result-poll.txt:8` = `176,-12207,10|0||`). The per-request
numbers are wall-clock, heap and thread deltas and are not expected to repeat run-to-run; this
directory does not claim they did. Nothing in this directory was reconstructed from an uncommitted
note.

Verified in `provenance.txt`:

- `provenance.txt:5-7` — at capture time (2026-07-29T15:03:43Z), the injector jar Run B mounted
  md5-matched the jar then on disk at
  `basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar`. That match is **not
  re-derivable**: `build/` is gitignored and the jar is not byte-reproducible (a fresh
  `./gradlew :basquin-maven-injector:jar` today produces a different md5). The property that
  actually matters — that the mounted jar is *behaviourally* this branch's injector — is what
  `provenance.txt:9-15` establishes from source, not from bytes.
- `provenance.txt:9-15` — that jar was built at `a61a90c` with a working-tree delta on
  `BasquinInjector.java` of **0 non-comment changed lines** (javadoc only), so it is behaviourally the
  branch's injector.
- `provenance.txt:17-23` — build and re-run both used
  `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5`,
  byte-identical to the digest pinned at `bench-results/dd043-pr3-restvillains-2026-07-26/build.sh:30`.
- `provenance.txt:42-46` — both builds ran `-o` (offline) and fetched nothing: **zero**
  `Downloading from` / `Downloaded from` lines in either log. Offline resolution worked because the
  cached artifacts' `_remote.repositories` records repository id `basquin-injected`
  (`provenance.txt:33-34`), which is exactly `BasquinInjector.REPO_ID`.
- `provenance.txt:48-51` — Run A's log contains **zero** `basquin` lines of any kind: it is the
  no-injector control, and only Run B exercised the injector's accept path.
- `provenance.txt:36-40` — the non-optional reference listing comes from the `target/` the PR-3
  acceptance (`bench-results/dd043-pr3-restvillains-2026-07-26/`) left in the sibling clone on
  2026-07-26.

## The application clone was never touched

The optional declaration exists only in a **scratchpad copy** of `rest-villains`. `pristine-proof.txt`
captures the clone at `c9b46d7` with `git status --porcelain` returning **0 lines**
(`pristine-proof.txt:7-8`) and `grep -c basquin rest-villains/pom.xml` returning **0**
(`pristine-proof.txt:11-12`) — the clone's pom carries no Basquin declaration of any kind. Zero edits to
the target tree is a property PR-3 asserts, and it holds after this measurement as well.

## What this establishes, and what it does not

**Establishes:** on the pinned toolchain (the app's own Maven wrapper — `apache-maven-3.9.15`,
`provenance.txt:57` — inside the pinned Mandrel image, Quarkus 3.37.3
(`build-optional-injector.log:19`), JVM packaging, JDK 25), a pre-existing `<optional>true</optional>` declaration of
`com.basquin:basquin-quarkus` is accepted by the injector **and** the resulting application is
instrumented end to end. `<optional>` governs whether *downstream consumers* inherit the dependency; it
does not remove it from the declaring module's own classpath, which is the only classpath
instrumentation needs. Guarding this shape would reject a build that demonstrably works.

**Does not establish:**

- **Anything about transitive optionals.** The measured case is a **direct** optional on the module
  being built. `maven-resolver`'s `OptionalDependencySelector` is `depth < 2 || !isOptional()`, so an
  optional Basquin declaration inherited from a *parent or dependency* — depth ≥ 2 — is a different case
  and is **unmeasured** here.
- **Multi-module reactors.** One single-module Maven project. The injector's per-project loop ran over a
  one-project reactor, as in the PR-3 acceptance.
- **Native mode.** JVM packaging only.
- **That the two `lib/main` listings came from the same Maven invocation.** They are two builds
  (2026-07-26 non-optional in the clone, 2026-07-29 optional in the scratchpad copy) of the same commit
  of the same app; identical file lists is the claim, not identical bytes. `generated-bytecode.jar`
  byte-equality was checked in the original session but its capture is not in this directory, so it is
  **not** claimed here.
- **Run A's `lib/main`.** Run B's `clean package` deleted Run A's `target/`, so only the
  through-the-injector variant's listing survives. Run A's own log survives (`build-optional.log`,
  `BUILD SUCCESS` at `:105`) and contains **zero** `basquin` lines — it was built without the injector,
  with the dependency declared directly, which is why it is kept here as the no-injector control rather
  than as the decisive run.

## Files in this directory

| File | What it is |
|---|---|
| `build-optional.log` | Run A — the optional declaration built **without** the injector (control). `BUILD SUCCESS` at `:105` |
| `build-optional-injector.log` | Run B — the decisive build, optional declaration **through the real injector** on `maven.ext.class.path`. Accept-path line at `:2`, `BUILD SUCCESS` at `:108` |
| `pom-deviation.diff` | the only difference between the scratchpad copy and the pristine upstream pom: the `<optional>true</optional>` block |
| `libmain-optional-injector.txt` | sorted `quarkus-app/lib/main/` listing from Run B (228 entries) |
| `libmain-nonoptional-reference.txt` | sorted `quarkus-app/lib/main/` listing from the non-optional injected build the PR-3 acceptance left in the clone (228 entries) |
| `libmain-diff.txt` | the `diff` of the two, plus the `basquin` greps — check 4's transcript |
| `app-startup.log` | app container log from boot through the banner (re-run) |
| `banner.txt` | the extracted `Installed features` line (`app-startup.log:25`) |
| `result-poll.txt` | the driven request, the `/__basquin/result` poll, and the undriven-id control |
| `pristine-proof.txt` | the sibling clone's commit, empty `git status`, and `grep -c basquin` on its pom |
| `provenance.txt` | injector-jar md5 equality, image digest, offline/`basquin-injected` facts, reference-build timestamps |

## Reproduce

From the `closureJVM` repo root. Prerequisites: the sibling clone per
`bench-results/dd043-pr3-restvillains-2026-07-26/README.md`'s "Reproduce", Docker, port 8084/55432 free,
and `com.basquin:{basquin-core,basquin-quarkus,basquin-quarkus-deployment}:0.3.0` present in the host
`~/.m2` (`./gradlew publishToMavenLocal`) so the build can run offline.

```bash
CLONE=/abs/path/to/quarkus-super-heroes            # never edited
WORK=$(mktemp -d)                                  # the copy that gets the optional declaration

# 0. Property under test: the clone stays pristine. Copy, then edit only the copy.
(cd "$CLONE" && git status --porcelain)            # must print nothing
tar -C "$CLONE/rest-villains" --exclude=./target -cf - . | tar -C "$WORK" -xf -

# 1. Declare the shape under test as the FIRST entry of <dependencies> in $WORK/pom.xml:
#      <dependency><groupId>com.basquin</groupId><artifactId>basquin-quarkus</artifactId>
#        <version>0.3.0</version><optional>true</optional></dependency>
#    Nothing else — no <repositories>; the injector supplies that.
diff -u "$CLONE/rest-villains/pom.xml" "$WORK/pom.xml"     # must show exactly that one hunk

# 2. Build the injector jar from this branch and stage it outside the app tree
./gradlew :basquin-maven-injector:jar
STAGE=$(mktemp -d); cp basquin-maven-injector/build/libs/basquin-maven-injector-0.3.0.jar "$STAGE/injector.jar"

# 3. Run B — the decisive build: offline, through the real injector
IMAGE=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5
docker run --rm -v "$WORK":/w -w /w -v "$STAGE":/ext -v "$HOME/.m2":/home/x/.m2 \
  -u "$(id -u):$(id -g)" -e HOME=/home/x "$IMAGE" \
  ./mvnw -B -o -Dmaven.ext.class.path=/ext/injector.jar clean package -DskipTests \
  2>&1 | tee build-optional-injector.log
grep -n 'already declares basquin-quarkus' build-optional-injector.log   # absent => the guard fired; STOP

# 4. Check 4: both jars, and a listing identical to the non-optional build's
ls "$WORK/target/quarkus-app/lib/main" | LC_ALL=C sort > libmain-optional-injector.txt
ls "$CLONE/rest-villains/target/quarkus-app/lib/main" | LC_ALL=C sort > libmain-nonoptional-reference.txt
diff libmain-nonoptional-reference.txt libmain-optional-injector.txt     # must be empty

# 5. Checks 5-6: boot it and drive it (PR-2's harness, on the copy)
docker network create basquin-optional-net
docker run -d --name basquin-optional-db --network basquin-optional-net \
  -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
  -p 55432:5432 postgres:18
APP_DIR="$WORK" DB_CONTAINER=basquin-optional-db DB_NETWORK=basquin-optional-net \
  APP_CONTAINER=basquin-optional-app bench-results/dd043-pr2-restvillains-2026-07-26/run-app.sh
sleep 20 && docker logs basquin-optional-app 2>&1 | grep 'Installed features'   # must contain basquin
curl -s -H 'X-Basquin-Req: optional-rerun-1' http://localhost:8084/api/villains > /dev/null
curl -s 'http://localhost:8084/__basquin/result?id=optional-rerun-1'            # cost line, not "miss"
curl -s 'http://localhost:8084/__basquin/result?id=never-driven-control'        # must be "miss"

# 6. The claim itself: the clone is still pristine
(cd "$CLONE" && git status --porcelain)            # must print nothing

# 7. Clean up
docker rm -f basquin-optional-app basquin-optional-db && docker network rm basquin-optional-net
rm -rf "$WORK" "$STAGE"
```

## Where this is cited

`basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java`'s
`failOnUnusableDeclaration` javadoc records `optional` as the one deliberate exception to its usability
whitelist and cites this directory by `file:line` for every figure.
`BasquinInjectorGuardsTest#acceptsAnOptionalDeclarationBecauseADirectOptionalStillReachesTheClasspath`
pins the accept behaviour, so closing `optional` as a fifth shape fails a test that points here.
