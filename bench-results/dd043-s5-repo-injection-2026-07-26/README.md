# S5 — does an injected `<repository>` reach Quarkus's bootstrap resolver?

**The question.** DD-043 PR-3's `basquin-maven-injector` must work against a target application
whose `pom.xml`/`settings.xml` are never touched and whose local Maven repository does not contain
`com.basquin` artifacts. S4 proved the injected **dependency** reaches `javac` and augmentation, but
only by pre-installing the artifact into the local repository. This spike asks the half S4 never
tested: can the same `afterProjectsRead` participant also inject a **repository**, such that
`com.basquin:basquin-quarkus`, `basquin-quarkus-deployment`, and `basquin-core` all resolve **from a
remote HTTP repository**, with the local repository purged of `com.basquin` at build start and zero
edits to any application-tree file?

**Verdict: CONFIRMED — all three artifacts, including the deployment artifact the Quarkus bootstrap
resolver fetches on its own.** The one implementation subtlety that matters is recorded below: a
model-level `<repository>` injection alone is a no-op at `afterProjectsRead` time; the participant
must also rebuild the project's effective repository lists.

## Result

The decisive build (`build-s5-injected.log`) ran with the local repo purged of `com.basquin`
(`purge-proof.txt`) and the three artifacts served only by `python3 -m http.server` on
`http://localhost:8000/` from `pages-repo/` (a fresh stand-in for the GitHub Pages repo, populated
from HEAD by `publish-s5-pages.log`):

```
[REPO-INJECT] added dependency com.basquin:basquin-quarkus:0.3.0 and repository basquin-injected=http://localhost:8000/ to fixture
```

| Artifact | Fetched from | Build stage (log lines) |
|---|---|---|
| `basquin-quarkus` (pom+jar) | `basquin-injected` | session dependency resolution (`build-s5-injected.log:8-17`) |
| `basquin-core` (pom+jar) | `basquin-injected` | session dependency resolution (`build-s5-injected.log:11-19`) |
| `basquin-quarkus-deployment` (pom+jar) | `basquin-injected` | **Quarkus bootstrap resolver**, during `quarkus:3.37.3:generate-code` (`build-s5-injected.log:27-33`) |

Every `Downloading from central:` attempt for `com/basquin/...` has no matching `Downloaded from
central:` line (Central 404s — `central-absence.txt` shows all three group paths absent), and every
artifact's `Downloaded from` line names `basquin-injected`. `BUILD SUCCESS`
(`build-s5-injected.log:55`), augmentation completed (`build-s5-injected.log:53`).

The built app was then run and served a request (`banner-s5-run.log`; `/ok` returned `200`):

```
Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]
```

`basquin` in the banner is emitted by `FeatureBuildItem("basquin")` in **the deployment module's**
`BasquinProcessor`, so the banner proves augmentation actually executed build steps from the
deployment jar that arrived over HTTP — not merely that the jar downloaded.

## Three independent provenance lines

1. **The HTTP server's access log** (`http-access.log`): 13 GETs, all `200`. The first
   (`03:25:08`) is a host-side pre-build server check; the remaining 12 are the containerized
   build — 3 artifacts x (pom, pom.sha1, jar, jar.sha1). The log's timestamps are server-local
   EDT (UTC-4); the build log is UTC, so `03:26:03`–`03:26:12` EDT aligns exactly with the build's
   download lines at `07:26` UTC.
2. **The build log's `Downloaded from basquin-injected:` lines** for all six files.
3. **Maven Resolver's own `_remote.repositories` tracking files** written into the local repo by the
   build (`local-repo-provenance.txt`): all six entries read `...>basquin-injected=`.

## The ambiguity control

Per the S4-addendum standard, before the decisive build the fixture's local repository
(`bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository`, the directory `env/build.sh` mounts at
`/m2`) was purged of the entire `com/basquin` tree — it still held the PR-2-spike publications —
and the purge was captured (`purge-proof.txt`: full before-listing, the `rm -rf`, and a failing
`find` after). `central-absence.txt` closes the only other route (HTTP 404 for all three artifact
paths on `repo1.maven.org`). With local repo empty and Central empty, the injected repository is the
only possible source, and all three provenance lines above agree that it was the actual one.

Side effect worth knowing: the purge also removed S4's `com.basquin.spike.localonly:local-probe-dep`
from that shared local repo, so re-running S4's addendum needs its `install:install-file` step again.
After this spike's build the local repo holds `com.basquin` again — fetched from `basquin-injected`,
as the `_remote.repositories` files record.

## The participant, and the subtlety PR-3 must carry

`probe-participant/src/main/java/com/basquin/spike/RepoInjectProbe.java` (S4's `InjectProbe`
descendant; per-project allocation discipline kept — see its class comment). Injecting the
repository takes **two** mutations:

- **Model level** — `p.getModel().getRepositories().add(repo)`. Alone, this is too late to do
  anything: the project's effective repository lists were computed at project-building time, before
  any participant runs.
- **Effective-list level** — append a `MavenArtifactRepository` and call
  `p.setRemoteArtifactRepositories(...)`. Verified against maven-core 3.9.16 bytecode: that setter
  also refreshes the Aether list (`remoteProjectRepositories = RepositoryUtils.toRepos(...)`), which
  is what Maven's dependency resolution and the quarkus-maven-plugin's
  `@Parameter("${project.remoteProjectRepositories}")` actually consume. This is how the injected
  repository reached the Quarkus bootstrap resolver.

## Transport note (HTTP, localhost, and the http-blocker)

The container reached the host server via `EXTRA_DOCKER_ARGS="--network host"` (native dockerd
29.1.3 on WSL2; verified with an in-container `curl` before the build), so the repo URL is
`http://localhost:8000/`. Localhost is deliberate twice over: Maven 3.9.16's default
`maven-default-http-blocker` mirror matches `external:http:*`, which **exempts localhost** — and the
production Pages URL is HTTPS, which no default mirror matches either. So the spike matches
production's "no default mirror interferes" condition. A consequence, stated plainly: this spike is
**insensitive** to whether any resolver re-applies settings mirrors to the injected repository
(injection happens after project-building-time mirror application, and localhost would be exempt
anyway), so behaviour under a user-defined `mirrorOf="*"` mirror is unmeasured.

## What this establishes, and what it does not

**Establishes:** on the pinned toolchain (containerised Maven 3.9.16 wrapper, Quarkus 3.37.3, JVM
packaging), a Maven core extension can inject a remote repository at `afterProjectsRead` — via the
two-level mutation above — such that the full `com.basquin` closure (runtime, deployment, core)
resolves over HTTP from that repository with the local repo empty of it and Central not carrying it,
and the resulting app's banner lists `basquin`. PR-3's injector can be self-contained: one
`-Dmaven.ext.class.path`, no pom/settings edit, no operator pre-populate step.

**Does not establish:**

- **Native mode.** Deliberately not run (brief constraint); PR-3's acceptance covers it separately.
- **HTTPS/TLS transport.** The spike served plain HTTP; Pages serves HTTPS. Certificate trust and
  TLS behaviour of the resolver were not exercised.
- **Mirror re-application** (see transport note): environments with custom catch-all mirrors are
  unmeasured.
- **The injected-dependency half.** The fixture `pom.xml` still declares
  `com.basquin:basquin-quarkus:0.3.0` (PR-2-spike leftover; unmodifiable here under the zero-edit
  rule), so the dependency was in the model from the pom *and* from injection — S5 cannot attribute
  the dependency's presence to injection, and does not: that claim is S4's. The **deployment**
  artifact has no such confound: its coordinate appears in no pom anywhere — only in the runtime
  jar's `META-INF/quarkus-extension.properties` — and the Quarkus bootstrap resolver fetched it from
  the injected repository on its own.
- **Fetch timing vs `quarkus:build`.** The deployment artifact was fetched during
  `quarkus:generate-code` (the first Quarkus mojo, which builds the `ApplicationModel`);
  `quarkus:build`'s augmentation then consumed it from the now-warm local repo. "Augmentation
  resolved it remotely at `quarkus:build` time" was not observed and is not claimed.
- **Multi-module reactors.** The fixture is single-module; the per-project allocation discipline is
  carried in code but not exercised.

## Files

| File | What it is |
|---|---|
| `probe-participant/` | participant source (`RepoInjectProbe.java`) + pom; jar builds to `target/`, which is git-ignored |
| `probe-build.log` | participant build (`maven:3.9-eclipse-temurin-17` container) |
| `s5-publish-init.gradle` | init script adding the `s5Pages` publish target (no build.gradle edits) |
| `publish-s5-pages.log` | Gradle publish of the three artifacts from HEAD into `pages-repo/` |
| `pages-repo/` | the served repository — stand-in for the Pages repo. **Not tracked in git** (it holds built jars, and `bench-results/` tracks none anywhere); regenerate it with the publish step in *Reproduce* below. That the artifacts were served is evidenced by `http-access.log`, not by these bytes |
| `central-absence.txt` | `repo1.maven.org` HTTP 404 for all three artifact paths |
| `purge-proof.txt` | local-repo `com/basquin` before-listing, purge, failing `find` after |
| `http-access.log` | the HTTP server's request log — every remote fetch, with status codes |
| `build-s5-injected.log` | full decisive build (`clean package -DskipTests`) |
| `banner-s5-run.log` / `banner-s5.txt` | app startup output / extracted `Installed features` line |
| `local-repo-provenance.txt` | post-build `_remote.repositories` files naming `basquin-injected` |

## Reproduce

From the repo root (server port 8000 and app port 8080 must be free):

```bash
# 1. Build the participant jar (Maven 3.9 / JDK 17 container — never the host JDK)
docker run --rm -v "$PWD/bench-results/dd043-s5-repo-injection-2026-07-26/probe-participant":/w -w /w \
  maven:3.9-eclipse-temurin-17 mvn -B package

# 2. Publish basquin-core + basquin-quarkus + deployment from HEAD into the Pages stand-in
./gradlew -I bench-results/dd043-s5-repo-injection-2026-07-26/s5-publish-init.gradle \
  :basquin-core:publishAllPublicationsToS5PagesRepository \
  :basquin-quarkus:runtime:publishAllPublicationsToS5PagesRepository \
  :basquin-quarkus:deployment:publishAllPublicationsToS5PagesRepository

# 3. Ambiguity control: purge com.basquin from the fixture's local repo; keep the proof
rm -rf bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin
find bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/com/basquin   # must error: No such file

# 4. Serve the stand-in over HTTP; the access log is evidence
python3 -u -m http.server 8000 --bind 127.0.0.1 \
  -d bench-results/dd043-s5-repo-injection-2026-07-26/pages-repo > http-access.log 2>&1 &

# 5. The decisive build. Must be `clean package` (stale-target trap, see the PR-2 spike README).
PROBE_ABS="$PWD/bench-results/dd043-s5-repo-injection-2026-07-26/probe-participant/target"
EXTRA_DOCKER_ARGS="--network host -v $PROBE_ABS:/probe" \
EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/probe/repo-inject-probe-1.0.jar -Dbasquin.inject.repo.url=http://localhost:8000/" \
  bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests

# 6. Run the artifact, confirm it serves, read the banner
docker run --rm -d --name s5-verify -p 8080:8080 --entrypoint java \
  -v "$PWD/bench-results/dd043-spikes-2026-07-24/fixture/target/quarkus-app":/app \
  quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:c1d52b8ac781c2b7cf6f0cb3ed366ee3ea5ea4e5e34014eaf060ca6841afd6e5 \
  -jar /app/quarkus-run.jar
sleep 12 && curl -s -o /dev/null -w '/ok -> %{http_code}\n' http://localhost:8080/ok \
  && docker logs s5-verify | grep "Installed features"
docker rm -f s5-verify; kill %1   # stop the app container and the HTTP server
```
