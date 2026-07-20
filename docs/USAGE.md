# ClosureJVM Usage Reference

The command catalog and flag reference. For what the tool is and why, see the
[README](../README.md); for design rationale see [DESIGN-DECISIONS](DESIGN-DECISIONS.md).

## Prereqs & build

- Java 17+ (tested on 17 and 21), Gradle wrapper included.
- Build: `./gradlew build`
- Fat jar (agent + runner + examples): `./gradlew jar` → `build/libs/closurejvm-0.2.0.jar`

## Flags

| Flag | Meaning |
|------|---------|
| `-Dclosurejvm.target=<FQCN>` | Target class for the `runner.Runner` forwarder |
| `-Dclosurejvm.forceExitOnLeak=true` | Exit the process immediately on a leak/hard invariant (demos/CI) |
| `-Dclosurejvm.status=true` | Live AFL-style status screen (suppresses per-iteration log spam) |
| `-Dclosurejvm.status.intervalMs=<n>` | Status redraw interval (default 1000) |
| `-Dclosurejvm.status.forceTty=true` | Render the status box even when output is piped |
| `-Dclosurejvm.heap.gcBeforeMeasure=true` | GC before heap baseline/measure so heap delta = retention, not allocation noise |
| `-Dclosurejvm.native.lib=<abs path>` | Load the JVMTI native agent (same path as `-agentpath`) |
| `-Dclosurejvm.triage.queueCapacity=<n>` | Triage handoff queue capacity (default 256) |
| `-Dexamples.mode=leak\|proper` | Example behavior selector |
| `-Dexamples.sleepMs=<n>` / `-Dexamples.threads=<n>` | Example work duration / worker count |
| `-Dexamples.http.baseUrl=<url>` / `-Dexamples.http.routes=<csv>` | HTTP driver target config |

### Invariants (v0.2)

Disabled unless set. Modes: `hard` (fail fast) or `soft` (record + continue).

- `-Dclosurejvm.invariant.latency.maxMs=100`
- `-Dclosurejvm.invariant.heapDelta.maxKb=256`
- `-Dclosurejvm.invariant.threadDelta.max=2`
- `-Dclosurejvm.invariant.mode=hard|soft` (per-invariant override: `...latency.mode`, etc.)
- Evidence: `-Dclosurejvm.invariant.stack=current|all|off` (default `current`),
  `-Dclosurejvm.invariant.stack.maxFrames=15`,
  `-Dclosurejvm.invariant.latency.sample=true` (sample the execution stack at the latency threshold)

### Reset (v0.2, preview)

- `-Dclosurejvm.reset=classloader` — child-first classloader swap of the target's package
- `-Dclosurejvm.reset.onFailure=true` — reset after a hard invariant/leak
- `-Dclosurejvm.reset.maxResets=3`

## Running

```
# Examples
./gradlew runExampleProper          # clean example
./gradlew runRunnerLeak             # thread-leak demo (fails; use forceExitOnLeak in CI)
./gradlew runRunnerJavalinLeak      # Javalin leak demo (downloads Javalin for this run)

# Generic runner against any IterationTarget
java -cp build/libs/closurejvm-0.2.0.jar runner.GenericRunner 100 your.pkg.YourTarget

# Soak / stability
./gradlew runSoakProper             # 10,000 iterations, prints metrics

# Invariant / reset demos
./gradlew runLatencyInvariantDemo   # trips a 1ms latency threshold (non-zero exit)
./gradlew runResetClassloaderDemo   # fail, reset via classloader, then succeed
```

## Use with your app

Implement `runner.api.IterationTarget` (optionally `runner.api.InputReceiver` for fuzz input):

```java
package your.pkg;
import runner.api.IterationTarget;

public class YourTarget implements IterationTarget {
  public void initialize() throws Exception { /* start app/server */ }
  public void executeIteration() throws Exception { /* one request/unit of work */ }
  public void close() throws Exception { /* cleanup */ }
}
```

Run: `java -cp build/libs/closurejvm-0.2.0.jar:<your-cp> runner.GenericRunner 100 your.pkg.YourTarget`

## Native agent (JVMTI)

Event-driven thread tracking (counts + leak set) with no polling or safepoint stack walks;
the harness falls back to `ThreadMXBean` when it is not loaded.

```
./gradlew buildNativeAgent          # -> build/native/libclosurejvmti.so (needs cc + JDK headers)
LIB=$PWD/build/native/libclosurejvmti.so
java -agentpath:$LIB -Dclosurejvm.native.lib=$LIB -cp ... runner.GenericRunner ...
./gradlew runGenericNativeProper    # builds + injects + short demo
```

## Exploration (JQF, v0.3 preview)

Opt-in, not in CI. Enable with `-DenableJQF=true`.

```
./gradlew runFuzzCalculatorJQF -DenableJQF=true      # coverage-guided, seeds included
./gradlew runFuzzHttpJQF -DenableJQF=true
./gradlew runFuzzJsonJQF -DenableJQF=true
./gradlew runFuzzLatencyJQF -DenableJQF=true         # soft latency invariant
./gradlew runFuzzHeapJQF -DenableJQF=true            # soft heap invariant
```

Corpus replay & minimization (no JQF required):

```
./gradlew runCalculatorCorpus   # or runHttpCorpus / runJsonCorpus / runLatencyCorpus / runHeapCorpus
./gradlew minimizeInput -Dclosurejvm.target=<FQCN> \
  -Dclosurejvm.min.input=fuzz-results/<t>/input-<ts>.bin \
  -Dclosurejvm.min.output=fuzz-results/<t>/minimized.bin
```

Saved inputs land under `fuzz-results/<target>/` as a `.bin` plus a `.meta.txt` triage report
(classification, timestamp, violation, stacks). Configure with `-Dclosurejvm.fuzz.resultsDir=...`.

## HTTP driver (client-side)

Drive a running app over HTTP — real end-to-end latency + 5xx-as-crash, with the live status
screen. Harvests server-side `X-ClosureJVM-Invariant-*` headers when the app has the valve/filter.

```
./gradlew runHttpDrive -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dclosurejvm.invariant.latency.maxMs=50 -Dclosurejvm.invariant.mode=soft -Ddrive.iterations=200
```

## Tomcat demo (embedded + Docker)

Embedded (opt-in, local):

```
./gradlew runFuzzTomcatJQF -DenableJQF=true    # routes /crash /latency /heap; results in fuzz-results/tomcat
./gradlew runTomcatCorpus                      # deterministic replay
```

WAR + Docker (our demo WAR, jakarta / Tomcat 10):

```
./gradlew :tomcat-war:build jar
docker compose up tomcat                       # agent injected via CATALINA_OPTS
# http://localhost:8080/crash?type=NPE  /latency?ms=250  /heap?kb=512
# status UI: http://localhost:8080/closurejvm/index.html
```

Third-party apps (unmodified WARs, via the Tomcat valve) — including the JPetStore walkthrough
and the javax/jakarta namespace table — are covered in [THIRD-PARTY-APPS](THIRD-PARTY-APPS.md).
