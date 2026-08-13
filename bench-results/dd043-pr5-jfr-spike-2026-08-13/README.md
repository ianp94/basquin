# DD-043 PR-5 §6.2 entry-gate spike — native JFR streaming + the two SubstrateVM API probes

**Verdict: §6.2 CLEARED.** Native JFR event streaming works under SubstrateVM and carries full
application stack traces on this toolchain; native `ThreadMXBean` **is** a
`com.sun.management.ThreadMXBean` (M4 TRUE), so native gets the exact
`getThreadAllocatedBytes` thread-allocation cross-check, not merely the statistical sampler;
and `GarbageCollectorMXBean.getCollectionCount()` works under SubstrateVM. No fallback is
needed anywhere in §6.2: native heap positives are publishable, the JFR row is buildable on
native as specced, and §6.1's four-producer disposition design — including the
GC-contaminated-positive detector — is fully buildable on native.

This directory is the commit-1 evidence for DD-043 PR-5 (`docs/superpowers/plans/2026-08-13-dd043-pr5-reactive.md`),
carried over from the session-local, gitignored spike run
(`.superpowers/sdd/dd043-pr5-jfr-spike.md`, `.superpowers/sdd/dd043-pr5-jfr-spike-evidence/`,
excluded from this repo's history by `.superpowers/sdd/.gitignore`). Every number below was
re-derived directly from the files in this directory while carrying the evidence over, not
copied from the gitignored report's prose — one figure in that report's prose (a JVM-control
G1 Old Generation heap drop) did not match its own underlying probe file byte-for-byte; the
correct figure, read here from `jvm-run/03-probe-gc-garbage.txt`, is used throughout below.

## What's carried here vs. local-only

This directory carries every **text/log** artifact the spike produced: build logs, provenance
proofs, probe outputs, window-boundary snapshots, the derived window analyses, and the two
independent `jfr` CLI parses of the dumped recordings (one gzipped, since the deep per-event
dump is large). Per this repo's convention (`bench-results/` tracks no binaries anywhere), the
following were **excluded** and are local-only, not committed:

- the two raw `.jfr` recording binaries (`jvm-run/recording-jvm.jfr`,
  `native-run/recording-native.jfr`, and their `.gz` compressed forms) — the parsed **text**
  analyses derived from them (`jfr-cli-verify.txt`, `jfr-stack-inspection.txt`,
  `native-allocsamples-deep.txt.gz`, and the `windows-analysis.txt` files) are the committed
  evidence instead;
- the native binary itself (`fixture-1.0.0-SNAPSHOT-runner`, 54,860,856 B) — its size and file
  metadata are captured in `native-binary-stat.txt`;
- the `fixture-src/` fixture application (five Java sources, a Maven pom, and small build/drive/
  window-analysis helper scripts) — a throwaway Quarkus fixture built solely to host the probes,
  not a bench target; its shape is described below instead of copied.

## Environment and build

- Pinned Mandrel builder image, `MANDREL 25.0.3.0 JDK 25.0.3+9-LTS` (`build-native.log:38`).
  One native build total; §7.2's serialization requirement is trivially satisfied.
- Minimal Quarkus 3.37.3 fixture (a copy of the Phase-0 fixture minus its jacoco leftovers),
  **zero basquin/jacoco declarations in the pom**. Fixture code (local-only, not committed):
  `JfrAllocStream` (startup bean: `RecordingStream` + `jdk.ObjectAllocationSample` aggregation,
  per-class and per-thread), `JfrRoute` (`/jfr/stats`, `/jfr/dump`), `MgmtProbe` (`/probe/m4`,
  `/probe/gc`).
- Real extension injected via PR-3's exact recipe: `com.basquin:basquin-quarkus:0.3.0` built from
  `f80e127`, published to a local repo served on `http://localhost:8010/`, injector on
  `-Dmaven.ext.class.path`. The scratch `.m2` held stale `com.basquin` artifacts and was purged
  first (`m2-purge-proof.txt`); the first build then fetched all 6 artifacts, `Downloaded from
  basquin-injected` appearing 6 times in `build-jvm-first.log`, 13 GETs logged in
  `http-access.log`.
- **The one new flag: `-Dquarkus.native.monitoring=jfr`** — no prior DD-043 build ever passed it
  (PR-3/PR-4 binaries compiled with `--enable-monitoring=heapdump,threaddump` only). Here it
  reached the compiler as `--enable-monitoring=jfr,heapdump,threaddump` (`build-native.log:39`),
  `BUILD SUCCESS` (`build-native.log:115`), native-image proper 33.6s
  (`build-native.log:111`), binary 54,860,856 B (`native-binary-stat.txt`) vs 48,655,416 B for
  the JFR-less PR-3 binary of this same fixture shape
  (`bench-results/dd043-pr3-native-2026-07-26/binary-stat.txt:18`) — roughly 6 MB of JFR support.
- Native binary run directly on the host (PR-3/PR-4's proven method): `started in 0.018s`,
  banner `Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]`
  (`native-app.log:5,7`). Defect routes enabled at runtime. **No `-XX:+FlightRecorder` /
  `-XX:StartFlightRecording` flags were needed** — the purely programmatic in-process
  `RecordingStream` open worked with zero JFR runtime flags.

## Protocol

Probes first, then four back-to-back windows with a `/jfr/stats` snapshot at each boundary
(deltas derived by a small helper script, not by hand): IDLE-1 20s → TRIVIAL 50x `GET /ok` →
ALLOC 50x `GET /__basquin/control/defect/alloc?bytes=4194304` (all 50 verified
`defect:alloc bytes=4194304`, `allocFails=0` both runs — `native-run/w3-alloc-drive.txt`) →
IDLE-2 20s. Then `/jfr/dump` and independent parsing of the dumped file by the JDK 25 `jfr` CLI
inside the pinned container.

### JVM control run — validates the harness before native is judged

(`jvm-run/windows-analysis.txt`) IDLE-1 **116,816 B** · TRIVIAL **1,923,024 B** · ALLOC
**210,691,272 B** (executor-thread-1 alone +209,899,872 B, vs 209,715,200 B gross driven) ·
IDLE-2 **0 B exactly**. ALLOC ranks first at **109.5x** (app-thread-only basis). M4 true on
HotSpot: `impl=com.sun.management.internal.HotSpotThreadImpl`, `threadAllocatedDelta=8,388,784`
around a known 8,388,608 B allocation (`jvm-run/02-probe-m4.txt`). `System.gc()` moves `G1 Old
Generation` 0→1 with `heapUsedDelta=-91,939,576` B (`jvm-run/03-probe-gc-garbage.txt`). Dumped
recording: 194,022 B (`jvm-run/05-dump.txt`), 176 `jdk.ObjectAllocationSample` events
(`jfr-cli-verify.txt`).

Two harness defects were found by this control and fixed **before** the native run:

1. **Delivery latency.** `RecordingStream` delivers events more than 1s after they occur — an
   early run's short alloc window leaked samples into the following idle window. Fix: a 3s
   drain before each boundary snapshot.
2. **Self-observation.** The streaming consumer's own machinery allocates measurably. Fix +
   finding: `jdk.ObjectAllocationSample` carries `eventThread` even where stacks are absent, so
   per-thread aggregation splits app threads from `JFR *` threads; the harness reports both raw
   and app-thread-only rankings (both shown in `windows-analysis.txt`).

### Native run — the gate measurement

(`native-run/windows-analysis.txt`; `state=OPEN` at every one of the 5 snapshots)

| Window | deltaWeight | deltaSamples | dominant thread |
|---|---|---|---|
| IDLE-1 (20s) | 266,560 B | 128 | vert.x-eventloop-thread-7 +205,016 B |
| TRIVIAL (50x /ok) | 508,400 B | 216 | vert.x-eventloop-thread-6 +209,680 B |
| **ALLOC (50x 4 MiB)** | **160,768,048 B** | 372 | **executor-thread-1 +159,708,928 B** |
| IDLE-2 (20s) | 50,447,672 B | 47 | executor-thread-1 +50,407,808 B |

- **ALLOC ranks first** on both raw and app-thread-only bases: 3.2x the next window
  (IDLE-2), 316x the trivial-route window (`native-run/windows-analysis.txt`). IDLE-1 is 0.17%
  of ALLOC.
- **The counter moves for the right reason.** executor-thread-1's ALLOC + IDLE-2 movement,
  159,708,928 + 50,407,808 = **≈210,116,736 B** (summed here, not itself a quoted figure), vs the
  50 x 4,194,304 = **≈209,715,200 B** driven — agreement to 0.19%. IDLE-2's movement is the alloc
  window's own samples delivered late
  (native delivery latency exceeds the JVM's — the JVM control's IDLE-2 was exactly 0), not a
  counter moving spuriously: IDLE-1, before any alloc, shows executor-thread-1 at just
  +24,760 B.
- **The recording is genuinely populated.** Dump 325,269 B (`native-run/05-dump.txt`), parsed
  independently by the JDK 25 `jfr` CLI: **1,515 `jdk.ObjectAllocationSample` events**, 21,411 B
  of event data (`jfr-cli-verify.txt`), 146 naming `byte[]` (`jfr-stack-inspection.txt`), real
  weights/threads/timestamps.

## M4 and the GC-contamination detector

- **M4 TRUE — exact cross-check available on native.** `impl=com.oracle.svm.core.jdk.management.
  SubstrateThreadMXBean`, `instanceofComSunManagementThreadMXBean=true`, and
  `getThreadAllocatedBytes` measured **8,388,736 B** around a known **8,388,608 B** allocation —
  128 B error (`native-run/02-probe-m4.txt`).
- **`getCollectionCount()` works under SubstrateVM.** `complete scavenger` moved 1→2 across one
  `System.gc()` call while `heapUsedDelta=-7,864,432` B (a real collection —
  `native-run/03-probe-gc-garbage.txt`), then 2→5 across three successive calls, +1 per call
  (`native-run/04-probe-gc-bare-x3.txt`).
- **A detector watching a single bean is blind on native.** `System.gc()` moved only
  `complete scavenger` in every probe above — `young generation scavenger` stayed at 0 through
  every explicit call (`native-run/03-probe-gc-garbage.txt`, `native-run/04-probe-gc-bare-x3.txt`)
  — while natural window traffic alone moved it 0→2, visible as `bean.0.countBefore=2` in the
  post-windows probe against `bean.0.countAfter=0` in the pre-windows probe
  (`native-run/06-probe-gc-post-windows.txt` vs `native-run/04-probe-gc-bare-x3.txt`). §6.1's
  window check must sum `getCollectionCount()` across every bean
  `getGarbageCollectorMXBeans()` returns, not read one.

## Stack traces: the refuted premise

`spec:953-955` ("native JFR streaming events carry no stack traces") is measured **FALSE** on
Mandrel 25.0.3. `jfr-stack-inspection.txt`'s own shallow-depth pass shows 0 basquin-named
frames — the JDK `jfr` CLI's default disassembly truncates deep stacks — but a second,
explicit `--stack-depth 64` parse of the same dumped recording
(`native-allocsamples-deep.txt.gz`, gzipped because the full per-event dump is large) resolves
the deeper application frames: **810 lines name `com.basquin` classes; 78 of the 1,515 events
carry a `BasquinControlHandler` frame; 23 are MB-scale `byte[]` samples attributed to**
**`BasquinControlHandler.allocDefect(String) line: 311`** — one verbatim, on
`eventThread = "executor-thread-1"`, `weight = 4.0 MB`, `objectClass = byte[]`, full stack
through `dispatchDefect` → `handle` → the Vert.x worker-thread dispatch chain
(`native-allocsamples-deep.txt.gz`). Attribution finer than temporal/per-route ranking is
therefore available natively on this toolchain — the premise was toolchain-stale, not
toolchain-true. §6.2's *published form* stays whole-run per-route ranking regardless: DD-004's
statistical-behind-soft-signals ruling is about what the quantity *means*, not about whether
stacks are available.

## Findings beyond the gate (spec-amendment material)

1. §6.2's stack-trace premise, above — favorable, corrects the spec.
2. **M4 collapse.** The "JVM cells = exact, native cells = statistical" split is refuted in the
   favorable direction; `SubstrateThreadMXBean` implements the `com.sun.management` interface
   with working, enabled `getThreadAllocatedBytes`. PR-5 may budget the exact per-thread
   cross-check on both modes.
3. **`getCollectionCount()` sum-across-beans requirement**, above — a naive single-bean
   detector would be blind on native.
4. **Delivery latency bounds temporal attribution.** Even with a 3s drain, ~24% of the alloc
   window's sampled bytes arrived in the following 20s window on native
   (`native-run/windows-analysis.txt`'s IDLE-2 row: 50,447,672 B, essentially all of it
   executor-thread-1). Whole-run per-route aggregation (the form §6.2 already prescribes) is
   immune; any short-window use must bucket by event time (`RecordedEvent.getEndTime()`), not
   delivery time.
5. **The in-process consumer has a real allocation cost** — measured in the JVM control's
   self-observation finding above. PR-5's cross-check budget should measure it on the real
   targets; the per-thread split (`eventThread`) makes it identifiable and excludable rather
   than a contaminant.
6. Corroborations in passing: the 512 KiB quantum reappeared
   (`native-run/04-probe-gc-bare-x3.txt`, `heapUsedDelta=-524288`).

## Adversarial-control checklist

| Control | Result |
|---|---|
| Idle window really idle | IDLE-1 = 266,560 B = 0.17% of ALLOC; ranked below TRIVIAL (`native-run/windows-analysis.txt`) |
| Counter moves for the right reason | ALLOC+IDLE-2 executor-thread-1 sum agrees with driven gross to 0.19%; pre-alloc idle on that thread +24,760 B |
| Recording populated, not empty-stream-as-success | 325,269 B dump; 1,515 events parsed by the `jfr` CLI in the pinned container, verbatim event bodies committed |
| Fixture logic proven before native judged | Full JVM control run: same protocol, all three claims behave (M4 true, gc count moves, ALLOC 109.5x), so a native failure would have been a SubstrateVM fact, not a fixture bug |
| Failure captured, never masked | `state` field on the stats route; `OPEN` at all 10 snapshots across both runs (`jvm-run/windows-analysis.txt`, `native-run/windows-analysis.txt`) |
| Injection the only source of the extension | `.m2` purged with proof (`m2-purge-proof.txt`); 6 `Downloaded from basquin-injected`; 13 repo GETs logged (`http-access.log`) |

## What this changes for PR-5's plan

- **§6.2 entry gate: CLEARED** — budget the cross-check in its redefined form (whole-run
  per-route ranking; per-thread exact `getThreadAllocatedBytes` now additionally available on
  both modes per M4). §7.3's JFR row is runnable on native; this spike is its mechanism-level
  dry run, with the real-target Phase-2 run still owed by PR-5's Task 6.
- **§6.1's GC-contamination detector is buildable on native as specced** — with the
  sum-across-all-beans requirement above written into Task 2's design.
- The build recipe for any published JFR row: PR-4's exact vehicle + `-Dquarkus.native.monitoring=jfr`
  (this spike's `build-native.log` is the proof it composes with the injector + offline-jacoco
  instrument on the same image).

## Evidence index (this directory)

- `build-jvm-first.log` / `build-jvm.log` / `build-native.log` — the three containerized builds
  (first JVM build carries the injected-repo downloads; native carries the JFR monitoring flag)
- `m2-purge-proof.txt`, `http-access.log` — provenance controls
- `jvm-run/`, `native-run/` — per-run: probe outputs (`01`-`06`), window snapshots (`w0`-`w4`),
  `windows-analysis.txt`, drive integrity (`w3-alloc-drive.txt`)
- `jfr-cli-verify.txt`, `jfr-stack-inspection.txt`, `native-allocsamples-deep.txt.gz` — the
  independent recording parses (the last one gzipped: the full `--stack-depth 64` per-event dump
  is large as plain text)
- `native-app.log` — banner, startup, 114 per-request `[PROBE]` lines
- `native-binary-stat.txt` — the native binary's size/type metadata (not the binary itself)

Not carried (see "What's carried here vs. local-only" above): the two raw `.jfr` recording
binaries, the native binary, and the `fixture-src/` fixture application — all local-only,
verified present at capture time, not part of this repo's tracked history.
