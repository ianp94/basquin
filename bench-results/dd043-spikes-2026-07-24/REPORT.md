# DD-043 Phase 0 — consolidated spike report

**Date:** 2026-07-24
**Spec under test:** `docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md`
**Plan:** `docs/superpowers/plans/2026-07-24-dd043-phase0-spikes.md`
**Toolchain (pinned, unchanged throughout):** Quarkus `3.37.3`, `maven.compiler.release=25`,
JaCoCo `0.8.15`, Mandrel/GraalVM 25.0.3 in `ubi9-quarkus-mandrel-builder-image:jdk-25` (digest-pinned
in `env/build.sh`). Host is **JDK 17** (`env/ENVIRONMENT.md:8,142`) and no Maven step ran on it — the
host's own Maven version is not quoted here because nothing committed pins it and nothing depends on
it.

**Gate outcome: PASSED.** All four spikes resolved. Neither of the two spec-voiding outcomes §7.1
named occurred: §6.4 is not void, and §5's no-source-modification mechanism holds **for the half S4
exercised** — injecting a *dependency* into the in-memory model. The offline-JaCoCo **plugin
execution** §5 also requires was never injected by any spike; that half is unmeasured and is carried
as spec §8.2, a PR-4 entry gate. See S4's scope block below. The design survives with **eight
amendments**, all made in the same commit as this report.

Every figure below is copied from a committed artifact in this directory, with the file (and, where
the artifact is a log, the line) named. Nothing is typed from memory. Two figures carry explicit
caveats about what they can and cannot support — see S2 for the idle-drift rate and S1b for the
coverage denominator.

---

## Verdict table

| Spike | Question | Verdict | Spec sections affected |
|---|---|---|---|
| **S1** | Does offline JaCoCo produce *correct* coverage under AOT, read via the reflective `RuntimeData` path §6.4 specifies? | **REFUTED** | §6.4, §7.1 |
| **S1b** | Same question, with the read path corrected to a direct compile-time-typed call | **CONFIRMED** | §6.4, §7.1, §7.4 |
| **S2** | Do `Runtime.totalMemory()/freeMemory()` and `System.gc()` behave under SubstrateVM; does `quarkus.native.monitoring` accept `nmt`? | **CONFIRMED** | §5, §6.1 |
| **S3** | Does `addEndHandler` fire on errors, 3xx and client disconnects; does `addHeadersEndHandler` survive to the client? | **CONFIRMED** | §4.3, §6, §6.5, §7.3 |
| **S4** | Does Quarkus augmentation honour a dependency injected into the in-memory Maven model — JVM **and** native? | **CONFIRMED** | §5, §5.1, §5.2 |

### Scope qualifiers (read with the strict verdicts above)

The plan's verdict enum is `CONFIRMED` / `REFUTED` / `INCONCLUSIVE`. S2 and S4 were originally tagged
`CONFIRMED (scoped)`, which is outside that enum. The strict value is carried in the table so the
verdict stays machine-readable; the scope it was carrying is stated here rather than discarded.

- **S1 — scope:** REFUTED applies to the *mechanism the spec prescribed* (a two-hop reflective lookup),
  not to offline JaCoCo under AOT in general. S1b establishes the general case. Reported as
  **REFUTED-as-specified, then CONFIRMED via S1b** — see the S1 section for why collapsing this into a
  bare CONFIRMED would lose a real finding about the method §6.4 named.
- **S1b — scope:** the three failure signatures hold on live native data. The *denominator* finding is
  separate and negative: native's achievable coverage ceiling is capped below 100% for reasons
  unrelated to test thoroughness.
- **S2 — scope:** CONFIRMED for the one allocation size tested (`/alloc`, observed delta 4,718,592 B).
  It is **not** an unscoped confirmation of §6.1's heap invariant for arbitrary request sizes: the
  instrument is quantized, not continuous. The Hibernate-Reactive post-response-quiescence half of the
  S2 question is **DEFERRED** — the fixture has no datasource, so it could not be asked.
- **S3 — scope:** JVM mode only. Native-mode `addEndHandler` behaviour was not tested here; S4's
  native run exercised the same fixture but not the four dispositions.
- **S4 — scope:** CONFIRMED for *resolution and augmentation* of an injected **dependency**, in JVM and
  native, for a Central artifact **and** for a local-repo-only artifact. Three things it does **not**
  establish:
  1. That a locally-installed *Quarkus extension* is discovered by augmentation — extension discovery
     scans the classpath for `META-INF/quarkus-extension.properties`, and the local-only probe jar
     carries no such marker. That combination is inferred from two separate experiments, not measured
     as one.
  2. **That an injected *plugin execution* survives into the per-project execution plan.**
     `probe-participant/src/main/java/com/basquin/spike/InjectProbe.java:26-43` adds a `Dependency` and
     nothing else; no spike injected a plugin execution at all. §5 requires both injections — the
     dependency *and* the offline-JaCoCo plugin execution (spec §6.4) — so this spike covers half of
     the mechanism. A dependency is consumed by resolution; a plugin execution has to reach the
     **execution plan**, which Maven computes at a different lifecycle point. Carried as spec §8.2 and
     a PR-4 entry gate, because PR-4 is the first PR that cannot proceed without it.
  3. That the fix round's multi-module aliasing hazard is absent in practice — the fixture is
     single-module, so the corrected code is reasoned, not measured (see the fix-round section).

### Amendments this report forces

| # | Spec section | Amendment | Forced by |
|---|---|---|---|
| 1 | §6.4 | Coverage read is a direct compile-time-typed call, never reflection | S1 / S1b |
| 2 | §6.4, §7.4 | Native coverage denominator differs from the JVM's; the two percentages are not like-for-like | S1b |
| 3 | §6, §7.3 | 5xx/crash signal gated on `ar.succeeded()`; disconnect is its own disposition | S3 |
| 4 | §6.1 | Measurement floor: 512 KiB instrument resolution, ~1 MiB practical per-request minimum | S2 |
| 5 | §6.1 | `System.gc()` works under SubstrateVM; `gcBeforeMeasure` recommended, not merely noted | S2 |
| 6 | §5 | Drop the `nmt` hedge — `-Dquarkus.native.monitoring=jfr,nmt` is accepted and compiles | S2 |
| 7 | §5 | Injector constructs a fresh `Dependency` per `MavenProject` | S4 (fix round) |
| 8 | §7.1 | Rewrite S1's failure signatures; `NeverCalled` cannot be the signature-(ii) instrument | S1 / S1b |

---

## S1 — offline JaCoCo under AOT, as §6.4 specified it: **REFUTED**

Evidence: `s1-coverage/findings.md`, `s1-coverage/app.log`, `s1-coverage/analysis.txt`,
`s1-coverage/build-native-full.log`, `s1-coverage/class_initialization_report.csv`.

The native build itself was healthy. `build-native-full.log:157,161,163`:

```
Finished generating 'fixture-1.0.0-SNAPSHOT-runner' in 41.0s.
[INFO] BUILD SUCCESS
[INFO] Total time:  02:54 min
```

`--no-fallback` was in the logged `native-image` invocation, so this is a genuine AOT image and not a
JVM-mode fallback. `/ok`, `/alloc` and `/redirect` all served correctly in the same run.

**`/coverage` did not.** All three dump points returned HTTP 500 with the identical exception —
`app.log:8`, `:31`, `:54`, distinct error-id suffixes `-1` / `-2` / `-3`:

```
java.lang.NoSuchMethodException: org.jacoco.agent.rt.internal_bac9136.Agent.getExecutionData(boolean)
```

Because the three `.exec` files are HTTP error bodies rather than JaCoCo binary data, `jacoco-cli`
rejected all three — `analysis.txt:5`, `:19`, `:33`:

```
Exception in thread "main" java.io.IOException: Invalid execution data file.
```

So signatures (i) and (iii) were never reachable, and (ii) was reachable only as static evidence.

**The root cause is the read path, not JaCoCo-under-AOT.** Three independent facts isolate it:

1. `javap` against the pinned `org.jacoco.agent-0.8.15-runtime.jar` shows `getExecutionData(boolean)`
   declared on the public `IAgent` interface and implemented on `internal_bac9136.Agent` — no version
   mismatch (`s1-coverage/findings.md`, "Root-causing the failure").
2. `class_initialization_report.csv` shows the holding class present and reachable in the image:
   `org.jacoco.agent.rt.internal_bac9136.Agent, BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup`.
3. The **identical unmodified code works in JVM mode** on the same JDK 25 / Quarkus 3.37.3 / JaCoCo
   0.8.15 (`build-jvm-diagnostic.log`, `jvm-diag-t{0,1,2}.exec` — 134 B at t0 and 170 B at t1/t2,
   opening with JaCoCo's `01 C0 C0 10` magic header).

What failed is `Class.forName(...).getMethod(...).invoke(...)` under native-image's **default
reflection policy**. §6.4 prescribed that shape, so the refutation is of the spec's stated method.

**This is reported as REFUTED rather than folded into S1b's CONFIRMED.** The spec named a mechanism;
the mechanism did not survive AOT. That is a finding about the design as written, and a report showing
only the corrected result would leave the next reader believing the original §6.4 text was fine.

### The second S1 finding, independent of the read path

`com.basquin.spike.NeverCalled` — §7.1's designated signature-(ii) instrument — **does not exist in
the native image at all.** It appears zero times in `class_initialization_report.csv` and zero times in
`s1b-class_initialization_report.csv` (`grep -c NeverCalled` → `0` on both), and `strings` on the
runner binary finds it zero times, despite `NeverCalled.class` being present in the
`native-image-source-jar` the build consumed. Closed-world reachability analysis deleted it.

Consequences, both of which survive S1b:

1. A class-elimination zero is a **structural absence**, not a live reading of zero. It cannot test the
   pollution hypothesis §7.1 cares about. (Amendment 8.)
2. The native coverage denominator is not the JVM's class set. (Amendment 2 — quantified below.)

---

## S1b — the corrected read path: **CONFIRMED**

Evidence: `s1-coverage/findings.md` (S1b section), `s1-coverage/s1b-build-native.log`,
`s1-coverage/s1b-app.log`, `s1-coverage/s1b-t{0,1,2}-*.{exec,csv,xml}`, `s1-coverage/s1b-analysis.txt`,
`s1-coverage/s1b-class_initialization_report.csv`.

Approach A — delete the reflection — worked on the first attempt. `RT.getAgent()` returns the public
`IAgent`, and `getExecutionData(boolean)` is an ordinary virtual call visible to the closed-world
analysis:

```java
IAgent agent = RT.getAgent();
return agent.getExecutionData(false);
```

Approach B (registering `org.jacoco.agent.rt.internal_bac9136.Agent` for reflection) was never
attempted, so the design does **not** have to name JaCoCo's shaded package — a name that changes
between JaCoCo versions. That fragility is avoided rather than managed. (Amendment 1.)

Build: `s1b-build-native.log:111,115,117` — `Finished generating ... in 41.6s.`, `[INFO] BUILD
SUCCESS`, `[INFO] Total time:  02:49 min`. All three dumps returned HTTP 200 with real JaCoCo data.

### The three signatures, on live native data

Class-level counters, copied from `s1b-t0-startup.csv` / `s1b-t1-after-ok.csv` /
`s1b-t2-after-more.csv` (`INSTRUCTION_MISSED,INSTRUCTION_COVERED`):

| Class | t0 | t1 | t2 | total |
|---|---|---|---|---|
| `Probe` | 42, 0 | 37, 5 | 11, 31 | 42 |
| `MemProbe` | 35, 0 | 35, 0 | 35, 0 | 35 |
| `BoundaryProbe` | 57, 40 | 3, 94 | 3, 94 | 97 |
| `CoverageProbe` | 4, 5 | 0, 9 | 0, 9 | 9 |
| `NeverCalled` | 11, 0 | 11, 0 | 11, 0 | 11 |

- **(i) frozen probes — HOLDS.** `Probe` covered climbs `0 → 5 → 31`, strictly.
- **(iii) class-id / name mismatch — HOLDS.** All five classes resolve by name at every dump point
  (`s1b-analysis.txt`: `[INFO] Analyzing 5 classes.` ×3, `exit=0` ×3), with per-class totals stable
  across t0/t1/t2.
- **(ii) inflated baseline — HOLDS**, on a new instrument. Method-level counters for `Probe`, from
  `s1b-t{0,1,2}-*.xml` (`INSTRUCTION` counter, missed/covered):

| Method | t0 | t1 | t2 |
|---|---|---|---|
| `<init>` | 3, 0 | 0, 3 | 0, 3 |
| `ok` | 2, 0 | 0, 2 | 0, 2 |
| `boom` | 5, 0 | 5, 0 | 5, 0 |
| `redirect` | 7, 0 | 7, 0 | 0, 7 |
| `slow` | 4, 0 | 4, 0 | 4, 0 |
| `alloc` | 19, 0 | 19, 0 | 0, 19 |
| `unused` | 2, 0 | 2, 0 | 2, 0 |

`unused()` is a **registered-but-never-called JAX-RS route** — reachable through JAX-RS registration so
it survives into the image (its `Probe$quarkusrestinvoker$unused_...` class is in
`s1b-class_initialization_report.csv`), never requested (`grep -c unused s1b-app.log` → `0`). At t1 and
t2 `Probe` has a real execution-data record — `<init>` and `ok` have flipped to covered, and by t2
`redirect` and `alloc` have too — and against that live record `unused` still reads `2 missed, 0
covered`. That is a live zero, not a structural-absence default. **Build-time class initialization did
not pre-flip probes.**

*How this instrument transfers:* in the fixture, `unused()` was added to the fixture's **own source**,
which a real target forbids (spec §1.1). The spec therefore transfers the *shape* — a method-level zero
read against a live record for its own class — onto a **withheld application route**, pre-registered
before the run, planting nothing. Spec §7.1 states it and the reachability precondition that keeps the
zero live.

`NeverCalled`'s zero is *not* this kind of evidence and is not used as such; see the S1 section.

### The denominator finding

`jacoco-cli` analyzes against `target/generated-classes/jacoco` — the **pre-native preserved
classfiles** made by offline instrumentation, which are unaffected by whatever reachability analysis
later discards. `NeverCalled` therefore contributes `11` of the `194` total instructions across these
five classes (`42 + 35 + 97 + 9 + 11`) while being **incapable of ever reading covered**, because the
class is not in the binary.

This is not a fixture artifact. The same build reports (`s1b-build-native.log:70-71`):

```
   11,223 types,  15,547 fields, and  55,725 methods found reachable
    3,758 types,      82 fields, and   4,391 methods registered for reflection
```

against a much larger universe of compiled classes. Whatever the whole-program analysis proves dead
sits in the denominator as permanently uncoverable weight, and how much varies per build.

**Consequence for the benchmark page:** native's achievable coverage ceiling is capped below 100% for
reasons unrelated to test thoroughness, so a native coverage percentage and a JVM coverage percentage
computed from the same source tree are **not like-for-like comparable**. The page must state this
rather than print both in one column. (Amendment 2.)

---

## S2 — memory, GC and monitoring under SubstrateVM: **CONFIRMED** (see scope)

Evidence: `s2-memory/findings.md`, `s2-memory/series.txt`, `s2-memory/app.log`,
`s2-memory/build-native-nmt.log`.

### The quantization finding — the one that changes §6.1

Every `used` value in `series.txt` across the whole run is an exact multiple of **524,288 bytes
(512 KiB)**:

```
idle    4194304 → 4718592 → 5242880 → 5767168      (8, 9, 10, 11 × 512 KiB)
alloc   5767168 → 10485760                          (11 → 20 × 512 KiB)
gc      10485760 → 3670016                          (20 → 7 × 512 KiB)
```

`totalMemory` and `maxMemory` both read `13,416,005,632` on **every** sample in the run — no heap
resizing, so the deltas are pure allocation and collection with no resize artifact.

`Runtime.freeMemory()` under SubstrateVM is therefore **not a byte-accurate counter**. Its resolution
is 512 KiB: a request allocating 100 KB reads as `0` or as `524,288`, never as its actual cost. §6.1 as
written implies a continuous instrument. (Amendment 4.)

The practical per-request minimum is **two quanta (~1,048,576 B / 1 MiB)**, not one — idle drift can
contribute a quantum step *inside* a measurement window, so a one-quantum threshold cannot survive
worst-case coincidental noise.

### Idle drift — what this number can and cannot support

Over the idle series, `used` rose `4,194,304 → 5,767,168` = **1,572,864 bytes**. `s2-memory/findings.md`
extrapolates that to **≈3.10 MiB/min**.

**That figure must not be read as a measured rate.** It rests on **n = 3 discrete quantum events in a
single run**: 29 gaps between 30 samples, of which exactly 3 are non-zero, and every one of those three
is exactly 524,288 bytes. There is no continuous slope in this data to fit. The honest statement is
*"three 512 KiB steps were observed over a ~29 s idle window in one run"*; the per-minute figure is one
observation of a step function, arithmetically extrapolated, and it is quoted here only because it
appears in the committed findings.

Window timing, derived from the `[PROBE]` ids in `app.log`: the 30 analyzed idle samples span
**29.185 s** at **1.006 s** spacing (29 gaps, min 1.00606 s, max 1.00684 s) — clean. The liveness check
preceding them sits **9.454 s** before the first idle sample. `s2-memory/findings.md:29-30` names that
same gap as "**3 seconds** before sample 1" — a specific figure, and the wrong one, against the
9.454 s the `[PROBE]` ids in `app.log` give. No published number depends on the gap; the exact
divergence is stated here rather than paraphrased so a re-runner can check the narration against the
`[PROBE]` ids directly. Committed evidence is left as-is.

Every sample in this series is itself an HTTP request, so part of what drifts is the cost of the poll.

### `/alloc` clears the floor

`before 5,767,168` → `after 10,485,760`, delta **4,718,592 bytes**. `/alloc` allocates
`64 × 64 KiB = 4,194,304` bytes; the observed delta is exactly that plus one 512 KiB quantum. It is
**9×** the largest single idle step (524,288) and **3×** the whole idle series' cumulative drift
(1,572,864). The invariant works — for allocations well above the quantum. This is the entire basis of
the CONFIRMED, and it is one allocation size.

### `System.gc()` performs a real collection

`before 10,485,760` → `after 3,670,016` — a reduction of 6,815,744 bytes (65%). The post-GC value is
**2,097,152 bytes below the pre-`/alloc` idle floor** (5,767,168, idle sample 30), so the collection
also reclaimed idle drift, not just the deliberate allocation. `System.gc()` is not a no-op under
SubstrateVM, which makes DD-002's `basquin.heap.gcBeforeMeasure` a usable mitigation that directly
lowers the floor. (Amendment 5.)

### `quarkus.native.monitoring=jfr,nmt` is accepted

`build-native-nmt.log:32` shows the flag both forwarded and translated by Quarkus into a real
`native-image` argument:

```
... -J-Dquarkus.native.monitoring=jfr,nmt ... --enable-monitoring=jfr,nmt,heapdump,threaddump ...
```

(`heapdump` and `threaddump` are Quarkus defaults riding along.) It carried through a full compile —
`build-native-nmt.log:104,108,110`: `Finished generating ... in 47.4s.`, `[INFO] BUILD SUCCESS`,
`[INFO] Total time:  02:55 min` — and the binary served every request in this task. So §5's
`additional-build-args` fallback is not required. (Amendment 6.) The fallback build was deliberately
not run; `build-native-nmt-fallback.log` does not exist for that reason.

### Deferred, not answered

Post-response quiescence read `10,485,760` on all 5 samples — perfectly flat. **This does not settle the
Hibernate Reactive question.** The fixture has no datasource, so connection-pool maintenance, the idle
reaper and deferred `Uni` continuations do not exist in it. §7.1's quiescence half remains DEFERRED to
the `rest-heroes` cell in PR-2, and this clean result must not be read as having answered it.

---

## S3 — request-boundary hook semantics: **CONFIRMED**

Evidence: `s3-boundary/findings.md`, `s3-boundary/probe.log`, `s3-boundary/curl.txt`,
`s3-boundary/startup.log`; round-1 evidence preserved as `probe-round1.log` / `curl-round1.txt`.

The four `[PROBE]` lines, verbatim from `probe.log`:

```
[PROBE] id=probe-8489103752045 path=/ok       status=200 succeeded=true  cause=- ms=77
[PROBE] id=probe-8489192943266 path=/boom     status=500 succeeded=true  cause=- ms=31
[PROBE] id=probe-8489236492382 path=/redirect status=302 succeeded=true  cause=- ms=22
[PROBE] id=probe-8489265923197 path=/slow     status=200 succeeded=false cause=io.vertx.core.http.HttpClosedException: Connection was closed ms=1006
```

`addEndHandler` fired on all four dispositions, including the error path and the client disconnect —
§4.3 and §6.5 are supported. `X-Basquin-Req` reached the client on all three completed responses,
including the 500 (`curl.txt`). Two independent runs agree on every disposition; only `ms` differs
(`/ok` 74 vs 77, `/slow` 1005 vs 1006).

### The finding that forces a spec change

**On the disconnected request, `getStatusCode()` read `200`.**

```
path=/slow status=200 succeeded=false
```

The client received nothing at all (`curl.txt`: `== /slow (disconnect) ==` / `client aborted`). `200`
is Vert.x's default on an `HttpServerResponse` never written to the wire — not a claim about what was
delivered.

§6's 5xx/crash signal reads exactly that field at the end handler. **As written, the spec would count a
request that delivered nothing as a clean 200.** That is the DD-040 defect shape — a number meaning
"not measured" presented as "fine" — reached by a new route. The discriminator is `ar.succeeded()`, not
the status code. (Amendment 3.)

A second, smaller point worth carrying: on disconnect the end handler fired ~1 s after the client gave
up, bounded by the server's own close detection rather than the client's `--max-time`. Latency keyed
off `addEndHandler` for a disconnected request measures **server-side detection latency**, not
client-observed latency. §6.5 already keeps disconnects out of the latency distribution, which covers
this; no amendment needed.

**Scope:** JVM mode only.

---

## S4 — injection honoured by augmentation: **CONFIRMED**, JVM and native

Evidence: `s4-injection/findings.md`, `banner-baseline.txt`, `banner-jvm-injected.txt`,
`banner-native.txt`, `build-jvm-injected.log`, `build-native-injected.log`, `addendum-build.log`,
`addendum-install.log`, `addendum-central-absence.txt`, `probe-participant/`.

Baseline, then both injected modes — the `Installed features` line from each of the three committed
captures:

```
banner-baseline.txt      Installed features: [cdi, rest, smallrye-context-propagation, vertx]
banner-jvm-injected.txt  Installed features: [cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]
banner-native.txt        Installed features: [cdi, rest, smallrye-context-propagation, smallrye-openapi, vertx]
```

The three files are **not** the same shape, and the quoted lines are extracts rather than whole files:
`banner-baseline.txt` and `banner-jvm-injected.txt` are one line each, while `banner-native.txt` is a
9-line run log (ASCII banner, startup line, profile, features, a `[PROBE]` line, shutdown) that is
**byte-identical to `banner-native-run.log`** — same `md5sum`. The `Installed features` line above is
its line 7.

`smallrye-openapi` is absent from the baseline and present in both injected artifacts. Signal 1 — the
participant ran — is at line 2 of both build logs:

```
build-jvm-injected.log:2     [INJECT-PROBE] added quarkus-smallrye-openapi to fixture
build-native-injected.log:2  [INJECT-PROBE] added quarkus-smallrye-openapi to fixture
```

Native build: `build-native-injected.log:104,108,110` — `Finished generating ... in 45.3s.`, `[INFO]
BUILD SUCCESS`, `[INFO] Total time:  02:52 min`. The native binary ran directly on this Ubuntu/WSL2
host with no loader or glibc problem, started in 0.168 s, and served `/ok` → 200
(`banner-native-run.log`).

**§5.1's disk-re-read hypothesis is refuted.** Quarkus's bootstrap resolver builds its
`ApplicationModel` from the in-memory `MavenProject`/`Model`, not from a fresh read of `pom.xml`. The
failure this spike existed to catch — signal 1 present, signal 2 absent, i.e. a successful build
producing a silently uninstrumented binary — was not observed in either mode. §5/§5.2's
no-source-modification design is unblocked for PR-3 **for dependency injection**. What this experiment
injected was a `Dependency`; §5's other injection, the offline-JaCoCo plugin execution, was not
attempted here and is not covered by this refutation (spec §8.2).

### Addendum — a local-repo-only artifact resolves

The main experiment injected `io.quarkus:quarkus-smallrye-openapi`, which is already on Central. The
real artifact (`com.basquin:basquin-quarkus`) will not be, so the addendum asked whether a dependency
present **only** in the build's local repository resolves — with no `<repository>` injected and no
change to `fixture/pom.xml`.

Precondition, captured in `addendum-central-absence.txt` (2026-07-24T22:04:18Z): two `curl` checks
against `repo1.maven.org` for `com.basquin.spike.localonly` — the specific artifact path and the
groupId directory — both `404`.

Result — `addendum-build.log:2,31,33`:

```
[INJECT-PROBE] added local-probe-dep to fixture
[INFO] BUILD SUCCESS
[INFO] Total time:  44.374 s
```

and the physical proof, the `find` output recorded in `s4-injection/findings.md` (the fixture's
`target/` is gitignored, so the artifact tree itself is not committed):

```
fixture/target/quarkus-app/lib/main/com.basquin.spike.localonly.local-probe-dep-1.0.jar
```

Installing `basquin-quarkus` into the build's local repository is therefore a sufficient deployment
path; §5 does **not** also need to inject a `<repository>`. Scoped to *resolution* — see the scope
block above.

*Provenance note:* `s4-injection/findings.md` quotes `Total time: 44.136 s` for this build. That is the
pre-fix run; the log was regenerated after the aliasing fix below and now reads `44.374 s`. The live
artifact is authoritative and is what this report quotes. Committed evidence is left as-is.

### The fix round that became a design requirement

Review found `InjectProbe.afterProjectsRead` constructing one `Dependency` **outside** the
`for (MavenProject p : session.getProjects())` loop and adding that same mutable instance to every
project. The fixture is single-module, so no committed number changed — and that is exactly the hazard:
**it passes every single-module test.** Maven's model objects are mutable, so a shared instance aliases
across the whole reactor, and the real targets are multi-module (Apicurio Registry certainly;
super-heroes is a multi-project repo).

Fixed in `probe-participant/src/main/java/com/basquin/spike/InjectProbe.java:37` (allocation moved
inside the loop, with a comment saying why it must not be hoisted back out), both injection scenarios
re-run in JVM mode, both verdicts unchanged. This is a **PR-3 design requirement**, not a spike
tidy-up. (Amendment 7.)

---

## What the gate means for delivery

§7.1 named two outcomes that would have voided design sections. Neither occurred:

- **S1 did not void §6.4.** It refuted the read path §6.4 named, and S1b established a working one.
  §2's full-parity goal does not reopen.
- **S4 did not force §5.1's degradation.** In-memory **dependency** injection is honoured by
  augmentation, in both modes, including for an artifact that exists only in the local repository.
  The plugin-execution injection §5 also requires was not exercised (spec §8.2).

**PR-1 (`basquin-core` extraction) is cleared to start.** PR-3 and PR-4 inherit hard requirements from
this report — amendments 1, 2 and 7 in particular — and PR-5's reporting work inherits amendments 2
and 4.

**Two entry gates leave Phase 0 unsettled**, and they are unsettled because no spike asked the
question, not because a spike answered it badly:

- **PR-4 — spec §8.2**: does an injected *plugin execution* reach the per-project execution plan?
  S4 measured only the dependency half of §5's mechanism.
- **PR-5 — spec §6.2**: does native JFR event streaming work on the pinned Mandrel 25.0.3 image, and
  is `com.sun.management` really unavailable there? Phase 0 ran **no** JFR analysis; S2 proved only
  that the `jfr,nmt` *build flag* is accepted and compiles.

Both are recorded in the spec rather than only here, since the spec is what PR-1…PR-5 are implemented
from. A whole-branch review found several such scopes present in this report and missing there; that
round of fixes is logged in the spec's amendment ledger under "Round 2", which records **each finding
and the sections it changed**. It does not carry the review's reasoning or its per-finding failure
scenarios: that report was session-local scratch and is not committed, so it is unavailable rather than
summarised. The ledger is the record *of what was found and fixed*, not of the review itself.
