# DD-043 PR-3 verification — 20260730T215842Z

Commit: `b980e1f` on `dd043-pr3-maven-injector` — tree clean at run start (`git-status.txt`)
Stages run: unit jar guards jvm native

**27 passed, 0 failed, 0 skipped.**

| Result | Check | Detail (derived from this directory's artifacts) |
|---|---|---|
| PASS | `unit` | 390 tests, 0 failures |
| PASS | `jar:sisu-index` | com.basquin.maven.BasquinInjector (class present in jar) |
| PASS | `jar:baked-version` | 0.3.0 matches build.gradle |
| PASS | `jar:gradle-init-version` | verifyGradleInitScriptVersion: basquin-init.gradle default version '0.3.0' matches basquin-maven-injector '0.3.0'. |
| PASS | `guards:skip` | neutering it fails injectsNothingWhenSkipIsSet (per guard-skip-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:managed-version` | neutering it fails failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion (per guard-managed-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:managed-exclusions` | neutering it fails failsLoudlyWhenDependencyManagementCarriesExclusions (per guard-managed-exclusions-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:managed-scope` | neutering it fails failsLoudlyWhenDependencyManagementPinsOurGroupToAnUnusableScope (per guard-managed-scope-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:declared-version` | neutering it fails failsLoudlyWhenTheProjectDeclaresOurArtifactAtADifferentVersion (per guard-declared-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:declaration-usability` | neutering it fails failsLoudlyWhenOurArtifactIsDeclaredAtAnUnusableScope (per guard-declaration-usability-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:sibling-scope` | neutering it fails failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAnUnusableScope (per guard-sibling-scope-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:sibling-version` | neutering it fails failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAConflictingVersion (per guard-sibling-version-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive) |
| PASS | `guards:restored` | source restored, module suite green (31 tests, 0 failures per guard-restore-junit/) |
| PASS | `jvm:build` | BUILD SUCCESS |
| PASS | `jvm:participant-ran` | [basquin-injector] instrumented rest-villains (com.basquin:basquin-quarkus:0.3.0 from http://localhost:8000/) |
| PASS | `jvm:injected-not-predeclared` | injector ran and never took the already-declares path — the dependency came from injection, not the pom |
| PASS | `jvm:deployment-from-injected-repo` | 4 GET(s) in http-access-jvm.log |
| PASS | `jvm:not-from-central` | 6 com/basquin download(s), every one from basquin-injected; none from central or any mirror id |
| PASS | `jvm:banner` | Installed features: [agroal, basquin, cdi, hibernate-orm, hibernate-orm-panache, hibernate-validator, jdbc-postgresql, kubernetes, micrometer, narayana-jta, opentelemetry, qute, rest, rest-jackson, rest-qute, smallrye-context-propagation, smallrye-health, smallrye-openapi, swagger-ui, vertx] |
| PASS | `jvm:boundary` | poll returned 1 formatted hop line(s): 810,-519,9\|0\|\| |
| PASS | `jvm:zero-edits` | target tree still pristine after the build (jvm-target-status-after.txt: branch headers only) |
| PASS | `native:build` | BUILD SUCCESS |
| PASS | `native:injected-not-predeclared` | injector ran and never took the already-declares path — the dependency came from injection, not the fixture pom |
| PASS | `native:not-from-central` | 6 com/basquin download(s), every one from basquin-injected; none from central or any mirror id |
| PASS | `native:serves` | served /ok |
| PASS | `native:banner` | Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx] |
| PASS | `native:boundary` | poll returned 1 formatted hop line(s): 5,512,0\|0\|\| |

## What a pass here does and does not establish

- `jvm` proves the injector instruments a real third-party Quarkus application with zero edits
  to its source, **in JVM mode, on one target**, resolving over localhost HTTP.
- `native` proves the same mechanism survives AOT, **on the Phase-0 fixture** — not on a real
  product. Whether `rest-villains` builds native at all is still unmeasured. Native
  instrumentation is FUNCTIONALLY established only by a PASSing `native:boundary` row in this
  run's own table — `native:banner` is a LOAD check, blind to a stripped or classpath-evicted
  `basquin-core`. A table with no `native:boundary` PASS leaves the native half of §5.2
  functionally unmeasured.
- Neither exercises the real GitHub Pages HTTPS repository; both serve over localhost HTTP.
  Pages cannot be tested until the first `v*` tag populates `docs/maven/`.
- `guards` proves each fail-loudly guard's test can actually fail, not that the guards cover
  every way injection could be defeated.

## Note on the `jvm` boundary poll

Poll returned `810,-519,9|0||` (`jvm-result-poll.txt`; per hop line:
latencyMs, heapDeltaKb, threadDelta \| invariant count \| detail \| leak).
**This poll carries negative heap delta(s) (`-519`) — a known gap, not a bug in this run:**
the spec assigns negative heap deltas to PR-5 as an `UNMEASURED` producer (the PR-5 row of
`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`). Do not read the heap
figure as a clean measurement.

## Note on the `native` boundary poll

Poll returned `5,512,0|0||` (`native-result-poll.txt`; per hop line:
latencyMs, heapDeltaKb, threadDelta \| invariant count \| detail \| leak).
Every heap delta in this poll is non-negative (sign derived from `native-result-poll.txt`, not hand-typed).
Negative deltas remain a known gap the spec assigns to PR-5 as an `UNMEASURED` producer (the
PR-5 row of `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`) — the heap
figure is still not a clean measurement until PR-5's controls land.
