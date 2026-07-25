# DD-043 — `basquin-core` Maven publication: resolution evidence

**Why this exists.** Spec §4.1 claims the Pages-served Maven layout is resolvable by a real Maven
build. An earlier draft asserted that with quoted build output and **nothing committed behind it** —
which the approver correctly rejected: this project's binding invariant is that claims trace to
committed evidence, and the S4 addendum in the same section already follows that pattern by citing
`bench-results/dd043-spikes-2026-07-24/`. A quoted `BUILD SUCCESS` in prose is worse than a dangling
pointer, because there is no pointer to notice is broken.

This directory is the evidence.

## What was tested, and why over HTTP rather than `file://`

The layout was generated exactly as the release workflow generates it:

```
./gradlew :basquin-core:publishAllPublicationsToPagesRepository
```

then served over **local HTTP** (`python3 -m http.server 8099 --bind 127.0.0.1`, document root
`docs/maven/`) and resolved by Maven from a **clean local repository**, so nothing could be satisfied
from cache.

The first version of this test used a `file://` URL. That was weaker and the difference is not
cosmetic: GitHub Pages serves over HTTPS with its own content types and no directory listing, and
`file://` exercises none of that transport behaviour. HTTP is the same transport *class* as Pages, so
it tests what the claim is actually about.

| File | What it is |
|---|---|
| `consumer-pom.xml` | the throwaway consumer: declares `com.basquin:basquin-core:0.3.0` against the served repo |
| `mvn-resolve.log` | full `mvn -B dependency:resolve` output, clean `-Dmaven.repo.local` |
| `http-server-requests.log` | the server's request log — what Maven actually asked for |

Run with **Apache Maven 3.6.3** on **OpenJDK 17.0.19** (the host toolchain). Recorded because a
resolution result without its resolver version is not reproducible — resolver behaviour is exactly the
kind of thing that differs across Maven versions.

## Result

```
[INFO] BUILD SUCCESS
[INFO]    com.basquin:basquin-core:jar:0.3.0:compile
```

**Every request Maven made:**

```
GET /com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.pom
GET /com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.pom.sha1
GET /com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.jar
GET /com/basquin/basquin-core/0.3.0/basquin-core-0.3.0.jar.sha1
```

Two things that request log settles, which reasoning alone had only argued:

1. **Checksum sidecars are fetched** (`.pom.sha1`, `.jar.sha1`) — they are part of the retrieval flow,
   so the checksums Gradle writes are reachable and correctly named. The log shows *retrieval only*: it
   does **not** show they are validated, and Maven's default `checksumPolicy` is `warn`, so a mismatched
   checksum would log a warning rather than fail the build. Do not read this as checksum enforcement.
2. **The Gradle Module Metadata file is never requested.** `basquin-core-0.3.0.module` is published
   alongside the POM (Gradle's default), and the concern was whether it could cause a variant mismatch
   for a Maven consumer. It cannot: Maven does not ask for it. That was previously an argument from
   documented behaviour; here it is an observation.

## What this does and does not establish

**Establishes:** the generated layout — standard `group/artifact/version` plus `maven-metadata.xml` and
checksums — is resolvable by Maven over HTTP from a cold cache, and Maven ignores the `.module` file.

**Does not establish:** that a real GitHub Pages deploy serves it correctly. That cannot be tested
before the first `v*` tag publishes into `docs/maven/`, because Pages serves what is committed. The
supporting argument is precedent rather than evidence: `docs/charts/` is published by the same `pages`
job and `https://ianp94.github.io/basquin/charts/index.yaml` serves chart 0.3.0 over HTTPS today, and
`docs/.nojekyll` disables Jekyll processing repo-wide. **The next release tag is the real test.**

## Reproduce

```bash
./gradlew :basquin-core:publishAllPublicationsToPagesRepository
( cd docs/maven && python3 -m http.server 8099 --bind 127.0.0.1 & )
W=$(mktemp -d); cp bench-results/dd043-publish-2026-07-25/consumer-pom.xml "$W/pom.xml"
( cd "$W" && mvn -B dependency:resolve -Dmaven.repo.local="$W/.m2" )
```

Note the generated artifacts under `docs/maven/` are **not** committed — the release job owns
publishing, so `docs/maven/` holds only `.gitattributes` until the next tag. Running the command above
regenerates them locally.
