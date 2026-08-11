# §7.3 coverage-control bench manifest — PRE-DECLARED, written before the control run

First declared at: **2026-08-11T00:46:55Z** (UTC; this directory's stamp) — before any control-run
process was started and before any request was sent. **This file is revision 3 of that
declaration: the withheld route, driven siblings, run plan and pass criteria are unchanged from
the first writing; only citation formatting was revised** — revision 2 expanded a bare
OpenAPI-contract filename mention and an ambiguous report filename to full paths after
`scripts/check-citations.py` flagged them, and revision 3 rewords revision 2's own description
of that fix, which had re-introduced the bare contract-filename token it was describing.
Because this file's sha256 is frozen into the drive log before the run it governs, each revision
required discarding the prior control run and re-running the whole control from scratch —
revision 1's run executed 2026-08-11T00:48:50Z-00:48:54Z, revision 2's at
2026-08-11T00:57:35Z-00:57:39Z, both discarded whole. The run whose artifacts sit beside this
file ran under THIS revision, and its `drive.log` opens with THIS file's hash, recorded before
its first request. Nothing about the declaration's substance changed across revisions, and the
withheld route was fixed before any of the runs.

## Target

- Application: `rest-villains` (blocking cell of the PR-4 2×2), **native** mode.
- Binary: the Task-4 native binary, reused, NOT rebuilt — the local sibling clone's (untracked,
  outside this repo)
  `/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/quarkus-super-heroes/rest-villains/target/rest-villains-1.0-runner`,
  sha256 `876ef6b6f5b66a6626a698fefa9f0ca99f2af46f816c4e36ac8df1cd4f5b188a`.
- Build provenance: this binary is the build whose `-H:+PrintClassInitialization` report is
  committed at `bench-results/dd043-pr4-2x2-2026-08-10/villains-native/class_initialization_report.csv`
  (the committed copy is content-identical to the on-disk build report
  `target/rest-villains-1.0-native-image-source-jar/reports/class_initialization_report_20260811_001231.csv`
  in that same untracked clone, after CRLF normalization; both hash to
  `662c03408c1b0192ef6377fe11220901ad031d51cc926aa3bb76195eb28ee3f0`
  as LF text — the working-tree copy of the committed file differs only by CRLF checkout
  translation on this Windows-mounted filesystem).
- Target source: `quarkusio/quarkus-super-heroes` at `c9b46d745620708e1519859bb4775469114381e5`,
  clean working tree (same pinned commit as the 2×2).

## The withheld route (obligation 1 — fixed here, before the run)

- **Withheld route: `GET /api/villains/{id}`** — `operationId: getVillain` in the target's
  contract `src/main/resources/openapi/openapi.yml` (upstream repo path, in the untracked
  clone named above), implemented by method **`getVillain`** of class
  **`io.quarkus.sample.superheroes.villain.rest.VillainResource`**.
- The driver is **forbidden to send any request whose path matches `/api/villains/<id>`**
  (i.e. `/api/villains/` followed by anything other than the literal sibling paths below) for
  the entire duration of this run.
- This is a real, registered, contract-first application route: it is enumerated in the
  target's own OpenAPI contract named above, declared on the generated `VillainsResource`
  interface, and implemented in application code that is in the coverage denominator (the
  preserved original `target/generated-classes/jacoco/io/quarkus/sample/superheroes/villain/rest/VillainResource.class`
  in the untracked clone). Nothing is planted; no application source file is created or
  modified (§1.1 holds).

## Driven sibling routes (same class, so the class's execution-data record goes live)

- `GET /api/villains/random` — method `getRandomVillain`, **same class** `VillainResource`.
- `GET /api/villains` — method `getAllVillains`, **same class** `VillainResource`.

## Run plan (declared now; the drive log is the record of what actually ran)

1. Reachability precondition (obligation 2), checked BEFORE the run against committed build
   output: the withheld method's Quarkus REST invoker class
   (`...VillainsResource$quarkusrestinvoker$getVillain_<hash>`) must be present in the committed
   `bench-results/dd043-pr4-2x2-2026-08-10/villains-native/class_initialization_report.csv`.
   Absent → the control is VOID and stops here.
2. Start the postgres container and the native binary (Task-4's exact run recipe: host-run
   binary, containerized DB on port 55432, `QUARKUS_HTTP_PORT=8084`).
3. `t0`: dump `/__basquin/coverage` before any application route is driven.
4. Drive the two sibling routes above — and nothing else.
5. `t1`: dump `/__basquin/coverage` again.
6. Stop the app, tear down the DB container.
7. Analyze `t0.exec` and `t1.exec` with jacoco-cli **0.8.15**
   (`org.jacoco.cli-0.8.15-nodeps.jar`, sha1 `1da22eb914b9176037589aaaac612b3f9b65f7ea`) against
   the same build's preserved originals (the untracked clone's `target/generated-classes/jacoco`),
   producing per-method XML; run `execinfo` on both dumps to show the class's execution-data
   record directly.

## Pass criteria (declared now)

- **Obligation 2**: the `getVillain` invoker row exists in the committed class-init report.
- **Obligation 3 (live record)**: at `t1`, at least one declared sibling method of
  `VillainResource` reads INSTRUCTION covered > 0 (flipped in THIS run — t0 shows it at 0).
- **The control read**: at that same `t1`, `VillainResource.getVillain` reads
  INSTRUCTION covered = 0 (all its instructions missed), against the same execution-data record.
- The drive log contains no request to any `/api/villains/{id}`-shaped path.

Any other outcome — invoker absent, no sibling flip, a nonzero read on `getVillain`, or a
withheld-shaped request found in the drive log — and this control FAILS or is VOID; it is
reported as such, not adjusted.

This run is a **distinct run from the published explore run** (§7.1/§7.3): the published 2×2
coverage evidence in `bench-results/dd043-pr4-2x2-2026-08-10/` came from its own app processes;
this control run is a fresh process started after this manifest was written.
