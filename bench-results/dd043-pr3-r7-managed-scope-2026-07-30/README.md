# DD-043 PR-3 — round-7 managed-scope measurement: the eighth silent bypass, reproduced and closed

This directory settles, by measurement, PR #103 round-6 finding 1: a `dependencyManagement` entry for
`com.basquin:basquin-core` at the **agreeing** version carrying `<scope>provided</scope>` or
`<scope>test</scope>` builds GREEN under the reviewed injector (commit `865ba35`) while `basquin-core`
is off the runtime classpath — a silent bypass. It also settles the boundary the previous "refutation"
missed, and verifies the fix.

**Every figure below is a `file:line` into this directory.** `citations.txt` is the machine-generated
index of every cited line (39 entries, each verified to resolve exactly); `cell-results.txt` is the
machine-generated summary table. Both are regenerated from `logs/` and `cells/` by the python in the
appendix, not typed.

---

## The history this exists to correct

An earlier approver reported the managed-`<scope>` gap. It was "refuted" by
`bench-results/dd043-pr3-r4-guard-measurement-2026-07-29/` cells (`msc`/`mty`/`mcl`/`mopt`) whose
managed entries all named `com.basquin:basquin-quarkus` — the **depth-1** artifact the injector adds,
which Maven 3.9's `ClassicDependencyManager` does not manage (it applies managed
version/scope/optional only from depth 2 down; exclusions have no depth gate). Those cells answered
the depth-1 question only. `basquin-core` is a **depth-2** node, and this directory's cells vary the
managed entry on *it*. The earlier approver was right.

## Method

Same harness shape as the r4 directory: a minimal jar project per cell (`cells/*.pom.xml`), the
injector on `-Dmaven.ext.class.path`, `com/basquin/*:0.3.0` served from a `file://` repository, an
isolated initially-empty local repository, Maven 3.9.15 / JDK 17 (`provenance.txt`). Two readouts per
stock cell:

- `maven-dependency-plugin:3.6.1:list` — what Maven itself resolved, at what scope;
- `dependency:build-classpath -DincludeScope=runtime` — whether `basquin-core` actually reaches the
  **runtime classpath** (`logs/*-stock-runtime-cp.txt`, split one-entry-per-line into
  `logs/*-stock-runtime-cp-entries.txt` so entries are line-citable).

Two injector jars (`provenance.txt` for md5s, `injector-guard-added.diff` for their full source diff):
**stock** = source exactly as committed at `865ba35`, the reviewed head, compiled from a clean tree
(porcelain 0 at build time); **guarded** = stock + the round-7 scope branch. Every managed entry in
every cell pins version `0.3.0` — the agreeing version (`cells/mscore.pom.xml:8`,
`cells/mtcore.pom.xml:8`, `cells/moptcore.pom.xml:8`) — so the managed-*version* branch provably
cannot be what fires or stays silent; only the attribute under test varies
(`cells/mscore.pom.xml:9`, `cells/mtcore.pom.xml:9`, `cells/moptcore.pom.xml:9`).

## The checks

1. **Control (`ctl`) — no management.** `basquin-quarkus:jar:0.3.0:compile` and
   `basquin-core:jar:0.3.0:runtime` resolve (`logs/ctl-stock-list.log:12-13`), the build succeeds
   (`:109`), and the runtime classpath holds 95 entries with both basquin jars
   (`logs/ctl-stock-runtime-cp-entries.txt:1-2`). The injector instrumented the project
   (`logs/ctl-stock-list.log:2`). (`logs/ctl-stock-coldcache-list.log` is the same cell's first,
   cold-repository run — same resolution at `:1632-1633`, success at `:1729`; the warm rerun exists so
   line numbers align across cells.)

2. **`mscore` — managed `<scope>provided</scope>` on `basquin-core`, stock injector: the bypass.**
   `basquin-quarkus` still resolves `:compile` (`logs/mscore-stock-list.log:12`) but `basquin-core`
   resolves `:provided` (`:13`). BUILD SUCCESS (`:109`). The injector printed only its `instrumented`
   line (`:2`; it is the log's only `[basquin-injector]` line — count derived in `cell-results.txt`),
   i.e. **no guard fired**. The runtime classpath has 94 entries; `basquin-quarkus-0.3.0.jar` is there
   (`logs/mscore-stock-runtime-cp-entries.txt:1`) and **no `basquin-core` entry exists in the file**
   (`cell-results.txt`, "core on rt-cp: NO" — derived by grep, not asserted). The extension jar still
   loads, so `Installed features` would still list `basquin` and §5.2's banner acceptance cannot see
   this; the application ships without its core at runtime, silently.

3. **`mtcore` — managed `<scope>test</scope>`: same bypass, second scope.**
   `basquin-core:jar:0.3.0:test` (`logs/mtcore-stock-list.log:13`), quarkus `:compile` (`:12`), BUILD
   SUCCESS (`:109`), injector silent but for `instrumented` (`:2`), 94 runtime entries with no
   basquin-core (`logs/mtcore-stock-runtime-cp-entries.txt:1`, `cell-results.txt`).

4. **`moptcore` — managed `<optional>true</optional>`: measured NOT a hazard.** The core resolves
   `runtime (optional)` (`logs/moptcore-stock-list.log:13`) and **stays on the runtime classpath** —
   95 entries, core present (`logs/moptcore-stock-runtime-cp-entries.txt:2`), build success
   (`logs/moptcore-stock-list.log:109`). A guard here would hard-fail a build that demonstrably
   works, so the fix deliberately does not guard managed `optional` — the same doctrine as
   `optional=true` on the declared path.

5. **The fix fires on exactly the two hazard cells and nothing else.** With the guarded injector:
   `mscore` aborts at `Scanning for projects` with the scope branch's message naming the scope, the
   artifact, and the UNINSTRUMENTED consequence (`logs/mscore-guarded-list.log:1-2`; the log is 8
   lines, no BUILD SUCCESS anywhere in it); `mtcore` likewise (`logs/mtcore-guarded-list.log:2`).
   `ctl` still succeeds (`logs/ctl-guarded-list.log:109`, resolution unchanged at `:12-13`) and
   `moptcore` still succeeds with the core still `runtime (optional)`
   (`logs/moptcore-guarded-list.log:13`, success at `:109`).

6. **The guard's unit test and mutation row bind.** Outside this directory, recorded in the round-7
   report: neutering the branch's input fails
   `failsLoudlyWhenDependencyManagementPinsOurGroupToAnUnusableScope` and only it (30 module tests, 1
   failure); deleting the branch entirely turns the `guards:managed-scope` row of
   `scripts/verify-dd043-pr3.sh` into FAIL ("mutation target not found") and `guards:restored` red.

## What this run does NOT establish

- **`system` and `import` managed scopes are unmeasured.** They ride the same compile/runtime
  whitelist as `test`/`provided`, for the reasons already recorded on the declared-sibling path. (A
  successfully imported BOM never survives into the effective model this method reads, so an `import`
  entry visible there is malformed anyway — reasoning, not measurement.)
- **Only depth 2 via `basquin-quarkus` is measured.** Deeper chains (a depth-3 basquin artifact, if
  one ever exists) are inferred from the same `ClassicDependencyManager` mechanism, not measured; and
  the depth-1 statements this directory relies on are the r4 directory's, not re-measured here.
- **No application was built or started.** The cells are minimal jar projects; "off the runtime
  classpath" is read from Maven's own resolver via dependency-plugin, not from a Quarkus
  `quarkus-app/lib/main/` listing, and "Installed features would still list basquin" is the mechanism
  established by the r4/optional-declaration runs (the extension jar itself still resolves at
  `:compile` in every cell here), not a banner captured in this run.
- **Maven 3.9.15 only.** Maven 4 replaces `ClassicDependencyManager` and re-opens every depth claim.
- **A transitive/managed `optional` at other depths** is unmeasured; check 4 covers this graph only.

## Appendix — regenerating the derived files

`bash rerun.sh` regenerates `logs/` into a scratch dir (never the tree; stock source read via
`git show 865ba35:…`, read-only). `python3 regen-derived.py` (in this directory, run from it) rebuilds
`cell-results.txt` and `citations.txt` from `logs/` and `cells/`, then re-reads every citation from
its file and exits nonzero on any mismatch. 39 entries, 0 mismatches at capture.
