# DD-043 target toolchain pins — committed evidence

**Why this exists.** The DD-043 spec (§3.1) states the target services' toolchain pins and labels them
"Verified 2026-07-24". The approver correctly rejected that: the claim had no committed evidence, and
the same PR commits an adversarial review (`docs/superpowers/specs/reviews/2026-07-24-dd043-fable-review.md`,
NIT 19) which says the Java-release claim *could not* be verified from the repository page it fetched.
A PR asserting a fact and also committing a document saying that fact is unverified is exactly the
"claims must trace to committed evidence" failure the project's invariants exist to catch.

This directory is the evidence. The spec now cites it instead of asserting verification.

## Provenance

Fetched 2026-07-24 from `main` of [`quarkusio/quarkus-super-heroes`](https://github.com/quarkusio/quarkus-super-heroes):

| File | Source URL |
|---|---|
| `rest-heroes-pom.xml` | `https://raw.githubusercontent.com/quarkusio/quarkus-super-heroes/main/rest-heroes/pom.xml` |
| `rest-villains-pom.xml` | `https://raw.githubusercontent.com/quarkusio/quarkus-super-heroes/main/rest-villains/pom.xml` |

Both files are the unmodified upstream bytes.

## Extracted pins

Both services pin identically — which matters, because the 2×2 in spec §7.2 requires the blocking
control (`rest-villains`) and the reactive target (`rest-heroes`) to differ *only* in request model. A
toolchain difference between them would have confounded every cell.

| Property | `rest-heroes` | `rest-villains` |
|---|---|---|
| `maven.compiler.release` | `25` | `25` |
| `quarkus.platform.version` | `3.37.3` | `3.37.3` |
| `quarkus.platform.artifact-id` | `quarkus-bom` | `quarkus-bom` |
| `jacoco.version` | `0.8.15` | `0.8.15` |

Reproduce:

```bash
grep -oE "<(maven\.compiler\.release|quarkus\.platform\.version|jacoco\.version)>[^<]*<" \
  bench-results/dd043-target-pins-2026-07-24/rest-heroes-pom.xml
```

## What this does and does not establish

**Establishes:** the pins above, as of `main` on 2026-07-24. `release=25` against a JDK-17 host is what
forces the containerized build in §3.1, and `jacoco.version=0.8.15` is why the spike pins that exact
JaCoCo version rather than a current one.

**Does not establish:** that these values are stable. `main` moves. Phase 1 builds a specific commit;
when it does, that SHA belongs here alongside these files, and any drift from the table above is a
finding rather than a routine update.
