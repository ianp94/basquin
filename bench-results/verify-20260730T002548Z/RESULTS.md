# DD-043 PR-3 verification — 20260730T002548Z

Commit: `6bbab5e` on `dd043-pr3-maven-injector` — tree clean at run start (`git-status.txt`)
Stages run: unit jar guards jvm native

**21 passed, 0 failed, 0 skipped.**

| Result | Check | Detail (derived from this directory's artifacts) |
|---|---|---|
| PASS | `unit` | 387 tests, 0 failures |
| PASS | `jar:sisu-index` | com.basquin.maven.BasquinInjector (class present in jar) |
| PASS | `jar:baked-version` | 0.3.0 matches build.gradle |
| PASS | `guards:skip` | neutering it fails injectsNothingWhenSkipIsSet (per guard-skip-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:managed-version` | neutering it fails failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion (per guard-managed-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:managed-exclusions` | neutering it fails failsLoudlyWhenDependencyManagementCarriesExclusions (per guard-managed-exclusions-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:declared-version` | neutering it fails failsLoudlyWhenTheProjectDeclaresOurArtifactAtADifferentVersion (per guard-declared-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:declaration-usability` | neutering it fails failsLoudlyWhenOurArtifactIsDeclaredAtAnUnusableScope (per guard-declaration-usability-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:sibling-scope` | neutering it fails failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAnUnusableScope (per guard-sibling-scope-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:sibling-version` | neutering it fails failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAConflictingVersion (per guard-sibling-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:restored` | source restored, module suite green (28 tests, 0 failures per guard-restore-junit/) |
| PASS | `jvm:build` | BUILD SUCCESS |
| PASS | `jvm:participant-ran` | [basquin-injector] instrumented rest-villains (com.basquin:basquin-quarkus:0.3.0 from http://localhost:8000/) |
| PASS | `jvm:deployment-from-injected-repo` | 4 GET(s) in http-access-jvm.log |
| PASS | `jvm:not-from-central` | 6 com/basquin download(s), every one from basquin-injected; none from central or any mirror id |
| PASS | `jvm:banner` | Installed features: [agroal, basquin, cdi, hibernate-orm, hibernate-orm-panache, hibernate-validator, jdbc-postgresql, kubernetes, micrometer, narayana-jta, opentelemetry, qute, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx] |
| PASS | `jvm:boundary` | poll returned 778,-934,9\|0\|\| |
| PASS | `jvm:zero-edits` | target tree still pristine after the build (jvm-target-status-after.txt: branch headers only) |
| PASS | `native:build` | BUILD SUCCESS |
| PASS | `native:serves` | served /ok |
| PASS | `native:banner` | Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx] |

## What a pass here does and does not establish

- `jvm` proves the injector instruments a real third-party Quarkus application with zero edits
  to its source, **in JVM mode, on one target**, resolving over localhost HTTP.
- `native` proves the same mechanism survives AOT, **on the Phase-0 fixture** — not on a real
  product. Whether `rest-villains` builds native at all is still unmeasured.
- Neither exercises the real GitHub Pages HTTPS repository; both serve over localhost HTTP.
  Pages cannot be tested until the first `v*` tag populates `docs/maven/`.
- `guards` proves each fail-loudly guard's test can actually fail, not that the guards cover
  every way injection could be defeated.

## Note on the boundary poll

Poll returned `778,-934,9|0||`. The fields are latency, heap delta, threads.
**A negative heap delta is expected to appear and is a known gap, not a bug in this run:** the
spec assigns negative deltas to PR-5 as an `UNMEASURED` producer, and they have now been
observed on more than one code path. Do not read the heap figure as a clean measurement.
