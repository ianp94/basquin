# S1 — offline JaCoCo correctness under AOT

Question: does offline JaCoCo instrumentation, read via `RuntimeData` directly (§6.4's
mechanism, not the agent-boot/file-output path), produce *correct* coverage once the app is
compiled through `native-image`? Three distinct failure signatures were tested, not just
"coverage must increase" — see the brief and spec §7.1 for why that check alone is
insufficient (it cannot detect an inflated baseline).

**Headline result: REFUTED.** `/coverage` throws in every native-mode request — `t0`, `t1`
and `t2` all come back as HTTP 500, never real JaCoCo binary data. The three failure
signatures could not be evaluated on live native data at all, because the mechanism §6.4
specifies (`Class.forName(...).getMethod(...).invoke(...)` against JaCoCo's runtime agent)
throws `NoSuchMethodException` under `native-image`'s default reflection policy, before any
class-id matching or baseline question is even reachable. This is exactly the scenario the
brief's ambiguity #3 anticipated: *"If `/coverage` returns empty or errors in native, that is
signature (iii)/the `RuntimeData` path not surviving AOT — a real finding."* Per the top-level
instructions, this is reported as-is rather than routed around.

## Method

1. **`fixture/pom.xml`** — added the `jacoco-maven-plugin:0.8.15` `instrument` execution
   (bound to `process-classes`, backs originals up to `target/generated-classes/jacoco`) and
   the `org.jacoco.agent:runtime:0.8.15` dependency, both verbatim from the brief.
2. **`CoverageProbe.java`** (new, verbatim from the brief) — `GET /coverage` reflectively
   calls `RT.getAgent()` then `agent.getClass().getMethod("getExecutionData", boolean.class)`,
   i.e. reads `RuntimeData` directly rather than through JaCoCo's agent-boot/file-output path.
3. **Native build**: `env/build.sh package -DskipTests -Dnative
   -Dquarkus.native.additional-build-args=-H:+PrintClassInitialization`. Ran alone (no
   concurrent native build, per §7.2's mutex). `BUILD SUCCESS`, total Maven wall time 2:54,
   `native-image` proper 41.0s. Full transcript: `build-native-full.log`.
4. **Coverage dump at three points**, per the brief's Step 4 exactly: started
   `fixture-1.0.0-SNAPSHOT-runner` directly on the host (established loader-safe per Task 3),
   `sleep 3`, `GET /coverage` → `t0-startup.exec` (before any route), `GET /ok`, `GET
   /coverage` → `t1-after-ok.exec`, `GET /alloc` + `GET /redirect`, `GET /coverage` →
   `t2-after-more.exec`. `pkill`ed afterward; `ss -ltnp | grep 8080` and `ps aux | grep
   fixture-1.0.0-SNAPSHOT-runner` both confirmed clean shutdown, port free. Raw app output:
   `app.log`.
5. **Analysis against the preserved originals**: `target/generated-classes/jacoco` (JaCoCo's
   own backup of the pre-instrumentation classes, per ambiguity #1 — never the instrumented
   copies), via `org.jacoco.cli-0.8.15-nodeps.jar` `report` in the
   `maven:3.9-eclipse-temurin-17` container, exactly as the brief's Step 5 specifies. Full
   transcript: `analysis.txt`.

### Deviation from the brief: the CLI jar's first `dependency:get` silently missed the mounted cache

The brief's exact `mvn dependency:get -Dartifact=org.jacoco:org.jacoco.cli:0.8.15:jar:nodeps`
reported `BUILD SUCCESS` and showed the jar downloading, but it was not on the host afterward.
Root cause: the official `maven:3.9-eclipse-temurin-17` image's entrypoint script tries
`mkdir $HOME/.m2` before running Maven; with `$HOME` unset and `-u host-uid:gid` (no
`/etc/passwd` entry for that uid, the same class of problem `env/build.sh` already documents
for the Mandrel image), the entrypoint's own `$HOME/.m2/settings.xml` bootstrapping resolved
the local repo somewhere other than the mounted `/m2` — `-Duser.home=/m2` via `MAVEN_OPTS`
alone was not sufficient to override it. Fix: re-ran with `-Dmaven.repo.local=/m2/.m2/repository`
passed explicitly, which is unambiguous regardless of `$HOME`/`settings.xml` resolution. Not a
finding about JaCoCo or native-image — recorded here only because it cost a fetch attempt and
because analysts on the next spike should pass `-Dmaven.repo.local` explicitly rather than rely
on `-Duser.home` for this particular image family.

## Step 3 — native build result

`BUILD SUCCESS` (`build-native-full.log`, `[INFO] BUILD SUCCESS`), `--no-fallback` was in
effect (confirmed in the logged `native-image` invocation, `build-native-full.log:80`) — this
is a genuine ahead-of-time image, not a JVM-mode fallback masking the result.
`-H:+PrintClassInitialization` produced both a configuration and a report CSV, copied into
this directory as `class_initialization_configuration.csv` (1,041 rows) and
`class_initialization_report.csv` (12,094 rows). `artifacts.txt` records the `find` output at
build time.

**All application classes present in the report are `BUILD_TIME` initialized**, matching the
spec's own stated default (§7.1: "Quarkus registers application classes for build-time
initialization by default"):

```
com.basquin.spike.BoundaryProbe,   BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
com.basquin.spike.CoverageProbe,   BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
com.basquin.spike.MemProbe,        BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
com.basquin.spike.Probe,           BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
org.jacoco.agent.rt.IAgent,        BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
org.jacoco.agent.rt.RT,            BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
org.jacoco.agent.rt.internal_bac9136.Agent, BUILD_TIME, from feature io.quarkus.runner.Feature.duringSetup
```

This confirms the spec's stated hypothesis half-way: JaCoCo's `RuntimeData`-holding `Agent`
singleton *is* built at image-build time and *is* captured into the image heap, exactly as
§7.1 describes. (Whether that also means its probe arrays read as pre-populated is a separate
question — see signature (ii) below; it could not be settled on live data.)

**`com.basquin.spike.NeverCalled` does not appear anywhere in either CSV**, and does not
appear in the compiled binary at all: `strings fixture-1.0.0-SNAPSHOT-runner | grep -c
NeverCalled` → `0`, despite `NeverCalled.class` being present in the
`native-image-source-jar` that `native-image` builds from (confirmed via `unzip -l`). This
means `native-image`'s closed-world reachability analysis determined `NeverCalled` is
provably dead code — nothing in the reachable call graph ever constructs or references it —
and **eliminated it from the image entirely**, before the class-initialization question is
even asked. This is a genuinely clean instrument for signature (ii) in a very literal sense
(the class isn't in the running binary, so it structurally cannot have executed at build
time), but it also means a class-elimination-based "zero" is not the same evidence as a
runtime-data-based "zero" — see signature (ii) below.

**Which classes Quarkus/SVM shifted to `RUN_TIME` initialization** (the brief's Step 3
requirement, since this sets the expected coverage floor): **183 classes**, entirely outside
`com.basquin.spike` and `org.jacoco`
(`grep ", RUN_TIME," class_initialization_report.csv | grep -iE "basquin|jacoco"` → zero
matches). By package prefix: 82 `io.netty` (buffer/channel implementation classes — pooled
allocators, composite/derived buffers, `DefaultChannelId`), 25 `sun.nio`, 15 `sun.security`
(includes `SunJCE$SecureRandomHolder`), 10 `io.vertx`, 8 `java.util`, 6 each of `java.lang`,
`java.io`, `io.quarkus`, plus smaller counts of `java.net`/`jdk.net`/`sun.net`/`jdk.internal`/
`io.smallrye`/others. None of these are coverage-instrumented application or JaCoCo classes,
so **the expected floor for signature (ii), as far as class-initialization timing alone can
tell, is that every `com.basquin.spike` and `org.jacoco.*` class initializes at build time —
there is no runtime-init class to serve as an alternative control.**

## Step 4 — coverage dump: `/coverage` fails, deterministically, in native

All three requests returned **HTTP 500**, all three 125-byte bodies are Quarkus's standard
error page (not JaCoCo binary data), and all three logged the identical exception in
`app.log`:

```
java.lang.NoSuchMethodException: org.jacoco.agent.rt.internal_bac9136.Agent.getExecutionData(boolean)
	at java.base@25.0.3/java.lang.Class.checkMethod(DynamicHub.java:1343)
	at java.base@25.0.3/java.lang.Class.getMethod(DynamicHub.java:1337)
	at com.basquin.spike.CoverageProbe.dump(CoverageProbe.java:17)
	...
```

`/ok` (200), `/alloc` (200) and `/redirect` (302) all worked correctly in the same run — the
binary is healthy; only the reflective `RuntimeData` retrieval fails. Reproduced identically
three times (once per dump point, distinct error ids `...-1`/`...-2`/`...-3`, otherwise
byte-identical bodies apart from the id).

### Root-causing the failure, without working around it

Confirmed via `javap` against the actual `org.jacoco.agent-0.8.15-runtime.jar` (the exact jar
the pom pulls) that `getExecutionData(boolean)` genuinely exists — it's declared on the public
`IAgent` interface and implemented by the concrete `internal_bac9136.Agent` class:

```
public interface org.jacoco.agent.rt.IAgent {
  ...
  public abstract byte[] getExecutionData(boolean);
  ...
}
public class org.jacoco.agent.rt.internal_bac9136.Agent implements org.jacoco.agent.rt.IAgent {
  ...
  public byte[] getExecutionData(boolean);
  ...
}
```

This rules out a version mismatch or a typo in the brief's code — the method is real, on the
real class, in the exact jar version pinned. The `class_initialization_report.csv` evidence
above additionally shows `internal_bac9136.Agent` was reached and `BUILD_TIME`-initialized by
Quarkus's own feature, so the class genuinely exists in the image. The failure is
`native-image`'s default reflection-metadata policy: reflective lookups
(`Class.getMethod`) only succeed at runtime for methods `native-image`'s closed-world
analysis explicitly registered ahead of time (via `@RegisterForReflection`, a
`reflect-config.json`, or a narrow set of literal-argument heuristics). The brief's
`CoverageProbe.dump()` reaches the concrete `internal_bac9136.Agent` class only through a
two-hop indirection (`Class.forName("...RT").getMethod("getAgent").invoke(null)` returns a
bare `Object`, then `agent.getClass().getMethod(...)` reflects on whatever concrete type that
`Object` turns out to be at runtime) — exactly the shape of reflective call that
`native-image`'s automatic-registration heuristics are known not to cover, and no explicit
`reflect-config.json` was supplied for JaCoCo's agent runtime by the pom, the fixture, or
Quarkus itself.

**This was not fixed.** Per the top-level instructions and the brief's own ambiguity #3, this
is recorded as the result, not routed around with `@RegisterForReflection` or a
`reflect-config.json` addition — that would be curing the exact failure this spike exists to
surface.

### Isolating native as the cause: a JVM-mode diagnostic (not part of the required steps, run for rigor before declaring a spec-voiding verdict)

To rule out a fixture bug, a JaCoCo/Quarkus version incompatibility, or a mistake in
`CoverageProbe.java` (any of which would produce this same exception in *any* mode, native or
not), the identical unmodified code was run in **JVM mode**, same JDK 25, same Quarkus 3.37.3,
same JaCoCo 0.8.15, same `org.jacoco.agent:runtime` dependency, same offline instrumentation —
`env/build.sh clean package -DskipTests` (no `-Dnative`), then `docker run --entrypoint java
-jar quarkus-run.jar` against the same pinned Mandrel-builder image (JDK 25; the host's JDK 17
cannot run bytecode compiled with `--release 25`). This is not part of the brief's required
steps and does not count toward its three signatures — it is scoping evidence for the verdict.

**In JVM mode, `/coverage` works.** `t0`: `HTTP 200`, 134 bytes of real JaCoCo binary data
(magic header `01 C0 C0 10`, matches JaCoCo's execution-data block format). `t1`/`t2`: `HTTP
200`, 170 bytes each. This conclusively isolates the failure to native/AOT compilation
specifically: identical reflective code, identical JaCoCo version, identical Quarkus version —
the only variable is `native-image` vs. HotSpot. (Raw: `jvm-diag-t0.exec`, `jvm-diag-t1.exec`,
`jvm-diag-t2.exec`, `build-jvm-diagnostic.log`.)

Because the JVM-mode path actually produced usable data, it was also fed through the same
`jacoco-cli report` pipeline against the same preserved originals (`jvm-diag-analysis.txt`,
`jvm-diag-t{0,1,2}.csv`) — **purely as supplementary diagnostic evidence that the
instrumentation/analysis design is otherwise sound**, not as a substitute for the native
result the brief actually asks for:

| Class | t0 (missed,covered) | t1 | t2 |
|---|---|---|---|
| `Probe` | 40, 0 | 35, 5 | 9, 31 |
| `MemProbe` | 35, 0 | 35, 0 | 35, 0 |
| `BoundaryProbe` | 57, 40 | 3, 94 | 3, 94 |
| `CoverageProbe` | 18, 27 | 0, 45 | 0, 45 |
| `NeverCalled` | 11, 0 | 11, 0 | 11, 0 |

(Total instructions per class — `missed + covered` — is stable across all three rows for
every class, e.g. `Probe` = 40 throughout, confirming the analyzer is resolving the same class
consistently rather than drifting.) In JVM mode: `Probe`'s covered count rises strictly
`0 → 5 → 31` as `/ok` then `/alloc`+`/redirect` are hit (signature (i) holds); `NeverCalled`
reads exactly `0` covered at all three points (signature (ii) holds); all five classes resolve
by name with non-zero, stable totals (signature (iii) holds). `BoundaryProbe` and
`CoverageProbe` show pre-existing coverage before their own dedicated routes are hit, both for
reasons the brief already anticipates or S3 already established: `CoverageProbe` serves the
`/coverage` route itself (ambiguity #2's expected exception, legitimate); `BoundaryProbe` rises
between `t0` and `t1` even though no `/boundary` route was called, consistent with S3's finding
that it's a Vert.x-level filter firing on *every* request, not a route-scoped handler — so `/ok`
alone exercises it. Neither is evidence of pollution; both are legitimate, attributable
coverage from real traffic on the JVM-mode path.

**This JVM-mode result is explicitly not a substitute for the native result.** It shows the
JaCoCo-offline-instrumentation-plus-preserved-originals-plus-`RuntimeData` design is coherent
in principle, on this exact toolchain, when reflection isn't AOT-compiled. It does not show
that the native path works, and the native path is what §6.4 actually needs.

## The three signatures — as tested on the actual native artifact

| Signature | Assertion | Native result |
|---|---|---|
| (i) frozen probes | `t2` covered > `t1` > `t0` | **UNTESTABLE.** No valid `.exec` data was ever obtained from the native binary — all three dumps are identical-shaped HTTP 500 bodies, not JaCoCo execution data. |
| (ii) **inflated baseline** | `NeverCalled` reads **0** covered at `t0`, `t1` **and** `t2` | **UNTESTABLE via live `RuntimeData`**, for the same reason. Independently: `NeverCalled` was eliminated from the native image entirely by `native-image`'s dead-code analysis (absent from both class-init CSVs and from `strings` on the binary) — so it structurally cannot have executed at build time, but this is *static* evidence about class elimination, not a *runtime* reading of `RuntimeData`, and it says nothing about whether `Probe`/`MemProbe`/`BoundaryProbe`/`CoverageProbe` — which *are* `BUILD_TIME`-initialized and present in the image — had any probes pre-flipped during the build. That question, the one signature (ii) actually cares about, remains open. |
| (iii) class-id/augmentation mismatch | report resolves both classes by name with non-zero total instructions | **The report never got the chance to test this.** `org.jacoco.cli` rejected all three native `.exec` files outright — `java.io.IOException: Invalid execution data file` — because they are HTML error bodies, not JaCoCo's binary format, so class-id matching against `target/generated-classes/jacoco` was never reached at all (`analysis.txt`). This is a *prior* failure to what signature (iii) was written to catch — the mechanism didn't get far enough to produce a wrong-but-present number; it produced no number. |

Every `.exec`/`.csv` artifact these rows reference is committed alongside this file (see the
directory listing note at the end).

## Overall verdict: **REFUTED**

§6.4's mechanism — reading JaCoCo's `RuntimeData` directly via reflection, exactly as
specified — **does not survive `native-image` compilation** on this toolchain (Quarkus
3.37.3, JaCoCo 0.8.15, `maven.compiler.release=25`, Mandrel/GraalVM 25.0.3). The failure is
deterministic (reproduced identically three times), root-caused with high confidence (`javap`
confirms the target method exists in the exact pinned jar; `class_initialization_report.csv`
confirms the holding class is reachable and `BUILD_TIME`-initialized; the JVM-mode diagnostic
confirms the identical code works when the same classes aren't AOT-compiled) — not a fluke,
not an environment problem, and not a bug in the fixture's routing or the `pom.xml` wiring.

Per §7.1: **"If S1 fails, §6.4 is void and coverage-guided exploration on native needs
redesigning — which reopens the full-parity goal in §2, since coverage is what forces the
compile-in step."** That is the situation this spike found. None of the three signatures could
be evaluated on real native coverage data, because the prerequisite — getting *any* execution
data out of a running native image via this mechanism — already fails.

### What would need to change for this to be re-testable (not run here — speculative, explicitly unverified)

Two directions, neither attempted, both requiring a spec revision before a re-spike:

1. **Explicit `native-image` reflection registration** for
   `org.jacoco.agent.rt.internal_*.Agent#getExecutionData(boolean)` (a
   `reflect-config.json` entry or `@RegisterForReflection`, shipped by whatever injects the
   JaCoCo dependency). Untested here by design — the brief's exact code was to be used
   verbatim and the failure recorded, not patched.
2. **A non-reflective call.** `fixture/pom.xml` already declares `org.jacoco.agent:runtime` as
   a direct compile-time dependency (not merely a reflectively-discovered runtime jar), so
   `IAgent agent = RT.getAgent(); byte[] data = agent.getExecutionData(false);` — ordinary
   virtual dispatch against the public `IAgent` interface, no `Class.forName`/`getMethod` at
   all — is a plausible alternative that `native-image`'s closed-world analysis would very
   likely include automatically, since direct calls need no reflection metadata. **This is a
   hypothesis, not a result** — trying it would have meant deviating from the brief's verbatim
   code and would have changed what "REFUTED" means (fixing the artifact used to produce the
   verdict, rather than reporting the verdict for the artifact specified). It is recorded here
   only as the most direct lead for whoever re-opens §6.4.

Either direction, if it worked, would still need re-running all three signatures from scratch —
this spike establishes only that the exact mechanism specified in the brief/spec fails, not
that offline JaCoCo coverage on native is impossible in every form.

## Files in this directory

- `class_initialization_configuration.csv`, `class_initialization_report.csv` — copied from
  `fixture/target/fixture-1.0.0-SNAPSHOT-native-image-source-jar/reports/` before a later
  diagnostic `clean` rebuild removed the native artifacts; `artifacts.txt` records the `find`
  output that located them.
- `build-native-full.log` — the native build (Step 3), `BUILD SUCCESS`.
- `app.log` — the native binary's own stdout/stderr across all three dump attempts, including
  the full stack trace each time.
- `t0-startup.exec`, `t1-after-ok.exec`, `t2-after-more.exec` — the three native dump
  attempts. **These are Quarkus HTTP-500 error bodies, not JaCoCo execution data** — committed
  as-is because that emptiness/failure *is* the finding, per the instructions not to
  substitute a workaround to obtain numbers.
- `analysis.txt` — the three native analysis attempts, all `Invalid execution data file`.
- No `t0-startup.csv`/`t1-after-ok.csv`/`t2-after-more.csv` exist — `jacoco-cli` never
  produced them, because it never got past loading the (invalid) execution data. This is
  itself part of the REFUTED result, not an omission.
- `build-jvm-diagnostic.log`, `jvm-diag-t0.exec`, `jvm-diag-t1.exec`, `jvm-diag-t2.exec`,
  `jvm-diag-t0.csv`, `jvm-diag-t1.csv`, `jvm-diag-t2.csv`, `jvm-diag-analysis.txt` — the
  supplementary JVM-mode diagnostic (root-cause isolation only, not a substitute result).

---

## S1b: reading coverage without reflection

S1 (above) isolated the failure to the reflective two-hop lookup
(`Class.forName("...RT").getMethod("getAgent").invoke(null)` then
`agent.getClass().getMethod("getExecutionData", boolean.class)`), not to JaCoCo-under-AOT in
general — the identical mechanism worked in JVM mode, and `javap` confirmed the target method
genuinely exists in the pinned jar. S1b tests the fix S1's own "what would need to change"
section proposed but explicitly did not try: a compile-time-typed call against JaCoCo's public
`IAgent` API, with **no reflection at all**.

**Headline result: CONFIRMED.** Approach A — the direct typed call — compiles, builds natively,
and returns real JaCoCo execution data (magic header `01 C0 C0 10`) on every one of the three
dump points. Approach B (reflection registration) was not attempted; it wasn't needed. All
three signatures are now testable on live native data, and all three hold.

### Approach A: direct, compile-time-typed call

`javap` against `org.jacoco.agent-0.8.15-runtime.jar` confirmed the exact shape S1's own
speculative fix proposed is valid before writing any code:

```
public final class org.jacoco.agent.rt.RT {
  public static org.jacoco.agent.rt.IAgent getAgent() throws java.lang.IllegalStateException;
}
public interface org.jacoco.agent.rt.IAgent {
  public abstract byte[] getExecutionData(boolean);
  ...
}
```

`RT` and `IAgent` are ordinary public compiled types in `org.jacoco.agent:runtime`, which
`fixture/pom.xml` already declares as a compile-time dependency (added for S1, unchanged here).
`CoverageProbe.java` was rewritten to call them directly:

```java
@Path("/coverage")
public class CoverageProbe {
    @GET
    @Produces("application/octet-stream")
    public byte[] dump() throws Exception {
        IAgent agent = RT.getAgent();
        return agent.getExecutionData(false);
    }
}
```

No `Class.forName`, no `getMethod`, no `invoke` — ordinary virtual dispatch. This is visible to
`native-image`'s closed-world analysis the same way any other method call is, so it needs no
`@RegisterForReflection` and no `reflect-config.json`. Approach B (reflection registration,
keyed on the shaded `internal_bac9136` package name) was not attempted, per the brief's
ordering — Approach A worked, so it is the answer.

### Signature (ii)'s new instrument: `Probe.unused()`

S1 found `NeverCalled` eliminated entirely from the native image by reachability analysis
(absent from `strings` on the binary and from both class-initialization CSVs), so it cannot
serve as a live never-executed probe there. Per this spike's instructions, a new route was
added to `Probe.java` — `@GET @Path("unused") public String unused() { return "unused"; }` —
registered as an ordinary JAX-RS endpoint (so Quarkus's routing feature references it, keeping
it reachable and present in the image) but never requested by any dump step.
`s1b-class_initialization_report.csv` confirms it survived into this build: it has its own
`quarkusrestinvoker` class (`Probe$quarkusrestinvoker$unused_615ac64...`, `BUILD_TIME`), and
`strings` on the runner binary finds it, unlike `NeverCalled` (still `0` occurrences — its
elimination is unaffected by this spike's changes). The existing routes `/ok`, `/boom`,
`/redirect`, `/slow`, `/alloc` and the `NeverCalled` class itself were not touched.

### Step 3 (repeated) — native build result

`BUILD SUCCESS` (`s1b-build-native.log`), `--no-fallback` in effect
(`s1b-build-native.log:34`, the logged `native-image` invocation) — a genuine AOT image, same
as S1's. Total Maven wall time 2:49, `native-image` proper 41.6s. Class-initialization CSVs
copied to `s1b-class_initialization_configuration.csv` / `s1b-class_initialization_report.csv`
(`s1b-artifacts.txt` records the `find` output). All `com.basquin.spike` and `org.jacoco.*`
classes are `BUILD_TIME`-initialized, matching S1 exactly; `NeverCalled` is still absent from
both CSVs and from the binary.

### Step 4 (repeated) — coverage dump: `/coverage` now returns real data

Same sequence as S1's Step 4, run against the rebuilt binary (raw app output: `s1b-app.log`):

| Dump | Trigger | HTTP | Body size |
|---|---|---|---|
| `s1b-t0-startup.exec` | before any route | 200 | 134 bytes |
| `s1b-t1-after-ok.exec` | after `GET /ok` | 200 | 171 bytes |
| `s1b-t2-after-more.exec` | after `GET /alloc` + `GET /redirect` | 200 | 171 bytes |

All three bodies open with JaCoCo's execution-data magic header (`01 C0 C0 10`), not an error
page. `/unused` was never requested (`grep -c unused s1b-app.log` on the request-serving lines
→ `0`); `/ok`, `/alloc`, `/redirect` all returned their expected codes. Port and process
confirmed clean before and after (`ss -ltnp`, `ps aux`, matching S1's discipline).

**A first attempt at this step produced files that vanished between the `curl` write and the
following `ls`** (curl reported real HTTP 200 / non-zero byte counts, `app.log` showed a clean
full run through graceful shutdown, but the `.exec` files were not on disk afterward) — treated
as an environment flake (background-shell/DrvFs interaction) rather than a real result, and not
committed. The measurement was re-run with the app started as its own tracked background task
and each file's presence verified immediately after every `curl`, which is what produced the
committed `s1b-t{0,1,2}.exec` files above.

### Step 5 (repeated) — analysis against the preserved originals

Ran via the same containerized `jacoco-cli report`, against `target/generated-classes/jacoco`
(this build's preserved pre-instrumentation originals — regenerated in this run, so they now
include `Probe.unused()`). Full transcript: `s1b-analysis.txt`. CSV and, for method-level
detail, XML reports: `s1b-t0-startup.{csv,xml}`, `s1b-t1-after-ok.{csv,xml}`,
`s1b-t2-after-more.{csv,xml}`.

| Class | t0 (missed,covered) | t1 | t2 |
|---|---|---|---|
| `Probe` | 42, 0 | 37, 5 | 11, 31 |
| `MemProbe` | 35, 0 | 35, 0 | 35, 0 |
| `BoundaryProbe` | 57, 40 | 3, 94 | 3, 94 |
| `CoverageProbe` | 4, 5 | 0, 9 | 0, 9 |
| `NeverCalled` | 11, 0 | 11, 0 | 11, 0 |

(Total instructions per class is stable across all three rows, confirming consistent class-id
resolution: `Probe`=42, `MemProbe`=35, `BoundaryProbe`=97, `CoverageProbe`=9, `NeverCalled`=11.
`CoverageProbe`'s total dropped from 45 in S1's JVM-mode diagnostic to 9 here — the direct
typed call compiles to far less bytecode than the two-hop reflective lookup it replaced.)

### The three signatures — as tested on the actual native artifact, this time

| Signature | Assertion | Native result |
|---|---|---|
| (i) frozen probes | `t2` covered > `t1` > `t0` | **HOLDS.** `Probe`: `0 → 5 → 31`, strictly increasing. |
| (ii) inflated baseline | never-executed probe reads **0** covered at `t0`, `t1` **and** `t2` | **HOLDS**, on the new instrument. See below — this needed a more careful read than the headline number, because two different kinds of "zero" are present in this data and only one of them is the signature the brief means. |
| (iii) class-id/name mismatch | report resolves all classes by name with non-zero, stable total instructions | **HOLDS.** All five classes resolve by name every time, with the stable per-class totals shown above. |

### Signature (ii), read carefully: two different zeros

The CSV table shows `NeverCalled` at `0` covered throughout, same as S1. But S1 already flagged
why that specific zero is weak evidence: `NeverCalled` doesn't exist in the running binary at
all (reachability-eliminated), so `jacoco-cli` is matching zero probe data for it and falling
back to "all instructions missed" for the whole class — a *structural absence*, not a *live
reading of zero*. That ambiguity is exactly why the brief asked for a new instrument.

`Probe.unused()` is the real test, and the raw `.exec` bytes make the distinction visible.
`strings` on the three dumps shows which classes have *any* execution-data record present at
each point:

| Dump | Classes with real execution-data records |
|---|---|
| `s1b-t0-startup.exec` | `CoverageProbe`, `BoundaryProbe` |
| `s1b-t1-after-ok.exec` | `CoverageProbe`, `Probe`, `BoundaryProbe` |
| `s1b-t2-after-more.exec` | `CoverageProbe`, `Probe`, `BoundaryProbe` |

`Probe` itself is *absent* from the raw execution data at `t0` — consistent with JaCoCo's
per-method lazy registration (each instrumented method checks/populates its class's probe-array
field on first entry; a class whose methods have never run has no record yet, independent of
whether SVM ran that class's `<clinit>` at build time). `CoverageProbe` and `BoundaryProbe` are
present from `t0` because `/coverage` itself invokes `CoverageProbe.dump()`, and `BoundaryProbe`
is the Vert.x-level filter S3 already found fires on every request. `MemProbe` never appears at
all — none of its routes are hit in this sequence. `NeverCalled` never appears in any dump,
consistent with it not existing in the binary.

This means `unused()`'s `t0` reading (missed=2, covered=0, from the XML method breakdown) is
the same *structural-absence* zero as `NeverCalled`'s — `Probe`'s class record doesn't exist
yet at `t0`, so every one of its methods, including `unused()`, defaults to all-missed. That
part of signature (ii), on its own, would be exactly as weak as S1's original evidence.

**The `t1` and `t2` readings are not that.** By `t1`, `Probe` *does* have a real execution-data
record (its name is in the raw bytes; `<init>` and `ok()` both flip to covered). Against that
real, non-default record, `unused()` still reads missed=2, covered=0 — and stays there at `t2`,
even as `alloc()` and `redirect()` flip to covered alongside it in the same class:

| Method | t0 | t1 | t2 |
|---|---|---|---|
| `<init>` | 3 missed, 0 covered | 0, 3 | 0, 3 |
| `ok` | 2, 0 | 0, 2 | 0, 2 |
| `boom` | 5, 0 | 5, 0 | 5, 0 |
| `redirect` | 7, 0 | 7, 0 | 0, 7 |
| `slow` | 4, 0 | 4, 0 | 4, 0 |
| `alloc` | 19, 0 | 19, 0 | 0, 19 |
| `unused` | 2, 0 | 2, 0 | 2, 0 |

`unused` is the only method whose route was registered-but-never-called and it is the only
method (besides the equally-never-called `boom`/`slow`, which serve as an internal control)
that never flips, across a run where its sibling methods in the *same class*, backed by the
*same* live probe-array record, demonstrably do flip when their routes are hit. That is a live
reading of zero, not a structural-absence default, and it is direct evidence against the
inflated-baseline worry: **build-time class initialization did not pre-flip `unused()`'s
probe.**

### The denominator finding

`NeverCalled` still contributes `11` of the `194` total instructions (`42+35+97+9+11`) that
`jacoco-cli report` counts across these five classes, because `--classfiles` points at
`target/generated-classes/jacoco` — the offline-instrumentation backup made from the *compiled,
pre-native* classpath, which is unaffected by whatever `native-image`'s reachability analysis
later decides to keep or discard. Those 11 instructions cannot ever read as covered on this
native binary, no matter how thoroughly it's exercised — the class isn't in the image. A raw
"% covered" computed from this denominator therefore has a lower achievable ceiling on native
than on JVM, for a reason that has nothing to do with test thoroughness.

This is a general property, not specific to the one class this fixture deliberately made dead:
whatever `native-image` eliminates as unreachable — and S1's own build-output stats logged
"3,758 types ... registered for reflection" out of "11,223 types ... found reachable" against a
much larger universe of compiled classes — sits in the coverage denominator as permanently
uncoverable weight. Two consequences for the benchmark design:

1. **A native coverage percentage and a JVM coverage percentage, computed from the same
   preserved-originals classfiles, are not directly comparable.** They use the same formula and
   the same denominator source, but native's is capped below 100% by an amount that varies by
   build (whatever that build's whole-program analysis proves dead), while JVM's is not. An
   85% native reading is not evidence of "15% under-tested" the way an 85% JVM reading would be.
2. **Any coverage-guided stopping rule or cross-mode threshold in the spec needs to say which
   denominator it means** — either compute a native-specific ceiling (e.g., by intersecting the
   classfiles directory against what `native-image`'s own reachability/class-initialization
   reports say survived), or restrict claims to *trends within one mode* (native run N+1 covers
   more than native run N) rather than cross-mode percentage comparisons.

### Overall verdict: **CONFIRMED**

Approach A — reading `RuntimeData` via a direct, compile-time-typed call against JaCoCo's
public `IAgent` API, with no reflection — survives `native-image` compilation on this toolchain
(Quarkus 3.37.3, JaCoCo 0.8.15, `maven.compiler.release=25`, Mandrel/GraalVM 25.0.3) and
produces real, analyzable execution data at every dump point. All three of S1's failure
signatures are now testable on live native data and all three hold: probes are not frozen
(signature i), a genuinely never-executed route reads zero against a real, non-default probe
record (signature ii, using the new `unused()` instrument — `NeverCalled`'s zero remains
structurally weak evidence, unaffected by this result), and class-id resolution is stable and
correct (signature iii). Approach B (explicit reflection registration) was not attempted, since
Approach A succeeded first, per the brief's ordering; note for the record that had B been
needed, its registration key (`org.jacoco.agent.rt.internal_bac9136.Agent`) is a JaCoCo-shaded
package name that changes across JaCoCo versions, which would have made that path
version-coupled in a way Approach A is not.

**Spec impact: §6.4 is not void.** It needs revising to specify the direct-call form
(`RT.getAgent().getExecutionData(false)`) rather than the brief's original reflective form, and
it should record the denominator caveat above. Coverage-guided exploration on native does not
need the full redesign S1's REFUTED verdict would have triggered under §7.1.

### Files in this directory (S1b additions)

- `s1b-build-native.log` — the S1b native build (Approach A's `CoverageProbe`, plus the new
  `Probe.unused()` route), `BUILD SUCCESS`.
- `s1b-class_initialization_configuration.csv`, `s1b-class_initialization_report.csv` — copied
  from this build's `.../native-image-source-jar/reports/`; `s1b-artifacts.txt` records the
  `find` output.
- `s1b-app.log` — the rebuilt native binary's stdout/stderr across the three dump points, ending
  in a clean shutdown.
- `s1b-t0-startup.exec`, `s1b-t1-after-ok.exec`, `s1b-t2-after-more.exec` — the three native
  dump attempts, this time real JaCoCo execution data (magic header `01 C0 C0 10`), not HTTP
  error bodies.
- `s1b-t0-startup.csv`, `s1b-t1-after-ok.csv`, `s1b-t2-after-more.csv` — class-level
  `jacoco-cli report` output against `target/generated-classes/jacoco`.
- `s1b-t0-startup.xml`, `s1b-t1-after-ok.xml`, `s1b-t2-after-more.xml` — the same analysis at
  XML/method granularity, used to isolate `Probe.unused()`'s counters for signature (ii).
- `s1b-analysis.txt` — the three native analysis transcripts (CSV generation only; the XML runs
  were not tee'd separately but are reproducible from the same `.exec`/classfiles inputs).
