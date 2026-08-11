# DD-043 PR-4 Task 5 — the §7.3 coverage control (withheld application route)

**Verdict: the control is VALID and PASSED — all three of §7.1's obligations are met, and the
withheld route's method reads a live zero.** On the reused Task-4 `rest-villains` **native**
binary, the pre-declared withheld route `GET /api/villains/{id}` (`VillainResource.getVillain`)
reads **`covered=0 missed=12`** at `t1` (`flip-control.out`, derived from `t1.xml`) while BOTH
declared sibling methods of the same class, driven in the same run, flipped:
`getRandomVillain` **`covered=10 missed=0`** and `getAllVillains` **`covered=15 missed=5`**
(`flip-control.out`). The zero is measured, not structural: the withheld method's Quarkus REST
invoker is present in the committed build's class-initialization report
(`class-init-excerpt.txt`), and the class's own execution-data record is live at the dump point
(`execinfo-t1.out`). This is the §7.3 table's coverage row, run as its own control run, distinct
from the published explore run that produced
`bench-results/dd043-pr4-2x2-2026-08-10/`'s numbers.

What this certifies: the 2×2's published per-method coverage numbers come from a read that
reports a genuinely-unexercised route-method as **zero for the right reason** — a measured miss
against live execution data — and not from a reader that would zero anything (the sibling in the
same record flipped), nor from a class that reachability analysis silently deleted (the invoker
is in the image's class-init report). A control that reads zero for the wrong reason would be
void; each obligation below exists to close one wrong-reason path, and each was checked.

## The three §7.1 obligations, each with its evidence line

| # | Obligation | Evidence |
|---|---|---|
| 1 | **Pre-registration, not post-hoc selection** — the withheld route is fixed before the run | `MANIFEST.md`, first declared at `2026-08-11T00:46:55Z` (this directory's name carries the same stamp in compact form); its sha256 `a9fa74b0ebc9151531f1dbe29e7ad8d4b402852fd0395e862a2a5ff69b723761` is the FIRST line of `drive.log`, recorded at `2026-08-11T01:01:09Z` — before the app process started at `01:01:18.787Z` and the first request of the run. The committed manifest blob reproduces that hash (LF form; see CRLF note below) |
| 2 | **Reachability precondition** — the withheld method's invoker class must be present in the build's `-H:+PrintClassInitialization` report, else the zero is `NeverCalled`'s structural absence | `class-init-excerpt.txt` (produced by `precheck.sh` at `2026-08-11T01:01:09Z`, BEFORE the run): the row `io.quarkus.sample.superheroes.villain.api.resources.VillainsResource$quarkusrestinvoker$getVillain_a9655097f1412421e65ffbe969b515b826bd3fe7, BUILD_TIME, ...` is in the committed `bench-results/dd043-pr4-2x2-2026-08-10/villains-native/class_initialization_report.csv` — the report of the exact build whose binary this run reused (binary sha256 `876ef6b6f5b66a6626a698fefa9f0ca99f2af46f816c4e36ac8df1cd4f5b188a`, logged in `drive.log` and pre-declared in `MANIFEST.md`) |
| 3 | **The zero is read against a live record** — meaningful only at a dump point where a sibling method of the same class has already flipped | `execinfo-t1.out`: `t1.exec` carries the execution-data record `7e364ffb48647e7a` for `io/quarkus/sample/superheroes/villain/rest/VillainResource` with 10 of 53 probes hit; `flip-control.out` shows both declared siblings covered (>0) and the withheld method at 0 in that same class's record at `t1` |

Obligation 3's counterfactual is also in evidence: at `t0` — before the sibling drive —
`execinfo-t0.out` shows **no** `VillainResource` record at all (only
`io/quarkus/sample/superheroes/villain/VillainApplicationLifeCycle` and
`io/quarkus/sample/superheroes/villain/Villain`, from startup). A `t0` "zero" on `getVillain`
would have measured nothing, exactly as §7.1 warns; the control read is taken at `t1`, where the
class record exists and is provably live.

## The run (all timestamps from `drive.log`)

- `01:01:09Z` — manifest hash frozen into `drive.log` (`declare.sh`); obligation-2 precheck
  (`precheck.sh` → `class-init-excerpt.txt`).
- `01:01:17.075Z` — postgres container up (`postgres:18`, published `55432`); `01:01:18.787Z` —
  the reused native binary started as a fresh process (pid `900646`), Task-4's exact host-run
  recipe (`drive.sh`, mirroring the committed
  `bench-results/dd043-pr4-2x2-2026-08-10/villains-native/run-native.sh`).
- `01:01:19.819Z` — **t0 dump**: `/__basquin/coverage` → `HTTP/1.1 200 OK`, `176` bytes,
  first-4-bytes `01 c0 c0 10` (`drive.log`, `t0-headers.txt`, `t0.exec`).
- `01:01:19.846Z` — **REQUEST 1** (declared sibling) `GET /api/villains/random` → HTTP `200`,
  body `417` bytes (`req-random.txt`).
- `01:01:19.903Z` — **REQUEST 2** (declared sibling) `GET /api/villains` → HTTP `200`, body
  `102011` bytes (`req-all.txt`).
- `01:01:19.931Z` — **t1 dump**: → `HTTP/1.1 200 OK`, `661` bytes, `01 c0 c0 10`
  (`t1-headers.txt`, `t1.exec`).
- Teardown: app stopped, DB container removed (`drive.log`); the target clone's `git status
  --porcelain` stayed empty (no build was run, nothing created).
- **No withheld-shaped request was sent**: `withheld-check.txt` (produced by
  `withheld-check.sh`) enumerates every application-route URL the run requested —
  `/api/villains` and `/api/villains/random` only — and finds no `/api/villains/<id>`-shaped
  request. `drive.sh` is the run's only driver and logs every request it sends.

## Analysis (jacoco-cli 0.8.15, per-method XML on the dumped exec bytes)

`analyze-control.sh` ran `org.jacoco.cli-0.8.15-nodeps.jar` — sha1
`1da22eb914b9176037589aaaac612b3f9b65f7ea`, matching its pinned `.sha1` file (`cli-pin.txt`; the
jar itself lives in the Phase-0 spike's gitignored `.m2` mirror, so `cli-pin.txt` is the
committed record of what ran) — over `t0.exec` and `t1.exec` against the reused build's
preserved pre-instrumentation originals (the untracked clone's `target/generated-classes/jacoco`),
producing `t0.xml`/`t1.xml`. Both dump points analyze as 15 classes with zero id-mismatch
warnings (`cli-t0.out`, `cli-t1.out`) — matching probes-to-classfiles identity, which is what
makes the per-method counters meaningful against this binary. `extract.sh` derives the
per-method table (`flip-control.out`) from the XML with the 2×2's committed
`bench-results/dd043-pr4-2x2-2026-08-10/flip.py`:

- `t0`: `getRandomVillain` `covered=0 missed=10`; `getAllVillains` `covered=0 missed=20`;
  `getVillain` `covered=0 missed=12` (`flip-control.out`, from `t0.xml`).
- `t1`: `getRandomVillain` `covered=10 missed=0`; `getAllVillains` `covered=15 missed=5`;
  **`getVillain` `covered=0 missed=12`** (`flip-control.out`, from `t1.xml`).

Each driven sibling flipped in this run; the withheld method's counters did not move. The
`missed=5` remainder on `getAllVillains` is its undriven `name_filter` branch — the driver sent
`GET /api/villains` without the query parameter — which is itself a small demonstration that the
counters track exactly what was exercised.

## Honest disclosures

- **Two prior control runs were discarded and fully re-run.** Per `MANIFEST.md`'s
  declaration-history note: revision 1's run (executed `2026-08-11T00:48:50Z-00:48:54Z`) was
  discarded because `scripts/check-citations.py` flagged citation-format defects in the manifest
  text; revision 2's run (`2026-08-11T00:57:35Z-00:57:39Z`) was discarded because revision 2's
  own description of that fix re-introduced the bare contract-filename token it was describing.
  The manifest is hash-frozen into the drive log, so it cannot be edited after its run — each
  wording fix therefore meant a full fresh run. The declaration's substance (withheld route,
  siblings, plan, pass criteria) is unchanged across all three revisions; the withheld route was
  fixed at `2026-08-11T00:46:55Z`, before any run. Everything committed here is from the final
  run under revision 3; both discarded runs had produced the identical per-method outcome
  (withheld `0` covered, siblings `10` and `15` covered), so the re-runs changed timestamps,
  not the result.
- **The exec session's START timestamp predates this run.** `execinfo-t1.out` shows the session
  `Billy-b1da7556` starting `Mon Aug 10 20:12:10 EDT 2026` — the native BUILD's time, not this
  run's. That is the documented SubstrateVM behavior §7.1 describes: JaCoCo's `RuntimeData` is
  captured into the image heap during build-time class initialization, session stamp included.
  The record CONTENT is this run's: the session end stamp is this run's dump instant
  (`Mon Aug 10 21:01:19 EDT 2026`), `t0` taken by this same process shows `VillainResource`
  with no record at all, and the flip to `10 of  53` probes happened between this run's `t0`
  and `t1`. Also visible at `t0`: the startup-only baseline (`176` bytes, two app-class
  records) — the t0-pollution diagnostic; none of the three declared methods reads covered
  at `t0` (`flip-control.out`).
- **CRLF checkout translation.** This checkout materializes committed text files with CRLF
  (observed on the committed 2×2 CSV; MANIFEST.md gets the same treatment on a fresh checkout).
  The manifest hash in `drive.log` is over the LF form, which is exactly what git stores: `git
  cat-file blob` of the committed `MANIFEST.md` reproduces
  `a9fa74b0ebc9151531f1dbe29e7ad8d4b402852fd0395e862a2a5ff69b723761` (verified before commit).
  The binary artifacts (`t0.exec`, `t1.exec`) are committed byte-identical (no text translation;
  blob sha256s verified against the working files before commit).
- **Tool gotcha, kept visible in the scripts.** `flip.py` matches the FIRST class whose name
  contains the given substring, and `HelloVillainResource` contains `VillainResource` as a
  substring — so the bare class name silently prints no method rows for this class;
  `extract.sh` documents this and uses `rest/VillainResource`. Similarly, `withheld-check.sh`'s
  first version grepped the whole log for URL-shaped tokens and false-positived on the log line
  that *mentions* the withheld template while stating it was never sent; the committed version
  keys on actual request lines and documents the earlier defect.
- **Scope.** This control ran on ONE cell — `rest-villains` native, the mode §7.1's obligations
  are written for (reachability deletion only exists under AOT). It certifies the read
  mechanism the 2×2's four cells share (offline-instrumented classes, `/__basquin/coverage`
  typed read, jacoco-cli per-method analysis against preserved originals); it does not re-run
  the other three cells, and no cross-mode or cross-cell number is computed here.

## Files

- `MANIFEST.md` — the pre-declared bench manifest (obligation 1); hash-frozen into `drive.log`.
- `declare.sh`, `precheck.sh`, `drive.sh`, `analyze-control.sh`, `extract.sh`,
  `withheld-check.sh`, `cli-pin.sh` — the complete, ordered tooling that produced everything
  below (committed so the exact commands are reproducible).
- `drive.log` — the run record: manifest hash first, then every request with timestamps.
- `class-init-excerpt.txt` — obligation-2 excerpt from the committed class-init report.
- `t0.exec`, `t1.exec`, `t0-headers.txt`, `t1-headers.txt` — the dumped coverage bytes and
  their HTTP reads.
- `t0.xml`, `t1.xml`, `cli-t0.out`, `cli-t1.out` — jacoco-cli 0.8.15 per-method reports.
- `execinfo-t0.out`, `execinfo-t1.out` — raw execution-data records (obligation 3).
- `flip-control.out` — the derived per-method counter table quoted above.
- `withheld-check.txt` — the no-withheld-request verification.
- `req-random.txt`, `req-all.txt` — the two sibling responses.
- `app.log` — the application's own log for the run.
- `cli-pin.txt` — identity of the jacoco-cli jar that ran.
