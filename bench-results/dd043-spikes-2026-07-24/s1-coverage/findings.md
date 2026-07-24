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
