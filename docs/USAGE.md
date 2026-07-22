# Basquin Usage Reference

The command catalog and flag reference. For what the tool is and why, see the
[README](../README.md); for design rationale see [DESIGN-DECISIONS](DESIGN-DECISIONS.md).

## Prereqs & build

- Java 17+ (tested on 17 and 21), Gradle wrapper included.
- Build: `./gradlew build`
- Fat jar (agent + runner + examples): `./gradlew jar` → `build/libs/basquin-0.3.0.jar`

## Flags

| Flag | Meaning |
|------|---------|
| `-Dbasquin.target=<FQCN>` | Target class for the `runner.Runner` forwarder |
| `-Dbasquin.forceExitOnLeak=true` | Exit the process immediately on a leak/hard invariant (demos/CI) |
| `-Dbasquin.status=true` | Live AFL-style status screen (suppresses per-iteration log spam) |
| `-Dbasquin.status.intervalMs=<n>` | Status redraw interval (default 1000) |
| `-Dbasquin.status.forceTty=true` | Render the status box even when output is piped |
| `-Dbasquin.heap.gcBeforeMeasure=true` | GC before heap baseline/measure so heap delta = retention, not allocation noise |
| `-Dbasquin.native.lib=<abs path>` | Load the JVMTI native agent (same path as `-agentpath`) |
| `-Dbasquin.triage.queueCapacity=<n>` | Triage handoff queue capacity (default 256) |
| `-Dexamples.mode=leak\|proper` | Example behavior selector |
| `-Dexamples.sleepMs=<n>` / `-Dexamples.threads=<n>` | Example work duration / worker count |
| `-Dexamples.http.baseUrl=<url>` / `-Dexamples.http.routes=<csv>` | HTTP driver target config |

### Exploration surface (v0.10)

| Flag | Meaning |
|------|---------|
| `-Dbasquin.grammar=<file>` | Request grammar: route templates + parameter value space (takes precedence over a plain corpus) |
| `-Dbasquin.corpusDir=<dir>` | Seed corpus of routes, one per line per file (used when no grammar is given) |
| `-Dbasquin.sequencePercent=<n>` | Chance (%) an iteration runs a multi-step `@sequence` instead of a single request (default 25) |
| `-Dbasquin.session=false` | Disable session handling (default on) |
| `-Dbasquin.session.epoch=<n>` | Iterations per session epoch; alternates signed-on and anonymous (default 40) |

### Coverage of the app under test (v0.10)

| Flag | Meaning |
|------|---------|
| `-Dbasquin.coverage.jacoco=host:port[,host:port...]` | The target's JaCoCo tcpserver(s), default `localhost:6300`. Accepts a comma-separated list; a host that resolves to several addresses (a headless Service name → all pod IPs) is expanded automatically. Coverage is union-merged across every replica that responds (DD-023). |
| `-Dbasquin.coverage.classes=<dir>` | Directory of the app's `.class` files, for computing covered/total |
| `-Dbasquin.coverage.intervalMs=<n>` | Poll interval for the non-guided coverage driver (default 1000) |
| `-Dbasquin.run.duration=<10m\|30s\|500ms\|45>` | Time-box the coverage-guided run: stop the loop and exit cleanly at the deadline (bare number = seconds). When set without an iteration count, the deadline governs. **Best-effort** — the deadline is checked between iterations, so a slow in-flight request (or a multi-step sequence) can overrun it by up to one iteration's wall time; pick an `activeDeadlineSeconds` backstop accordingly. Used by operator campaigns (DD-025) so a run ends cleanly instead of being SIGKILLed. |
| `-Dbasquin.summary.out=<path>` | Write an end-of-run metrics summary (the `StatusReporter` snapshot JSON) to this path on shutdown, for a supervisor (the operator) to read. **Requires `-Dbasquin.status=true`** — the metrics are gated behind it, so without it the summary is valid JSON but all zeros (the runner warns loudly). |

### Dashboard (v0.10)

The dashboard is a **separate process**; drivers push to it and it never drives anything itself.

| Flag | Meaning |
|------|---------|
| `-Dbasquin.dashboard.push=host:port` | Driver side: push status/findings/config to this dashboard |
| `-Dbasquin.dashboard.id=<name>` | Campaign id (defaults to `HOSTNAME`, i.e. the pod name in Kubernetes) |
| `-Dbasquin.dashboard.server.port=<n>` | Dashboard side: listen port (default 7070) |
| `-Dbasquin.dashboard.bind=<addr>` | Dashboard side: bind address. **Defaults to `127.0.0.1`** — this process exposes an endpoint that spends API credit, so network exposure is opt-in |
| `-Dbasquin.dashboard.token=<secret>` | Shared secret required alongside the `X-Basquin-Dashboard` header. Required for `/api/analyze` on a non-loopback bind |
| `ANTHROPIC_API_KEY` / `-Dbasquin.claude.apiKey` | Dashboard side only: enables the "Analyze with Claude" button |
| `-Dbasquin.claude.model` / `-Dbasquin.claude.maxTokens` | Model (default `claude-sonnet-5`) and output ceiling (default 2000) |

### Invariants (v0.2)

Disabled unless set. Modes: `hard` (fail fast) or `soft` (record + continue).

- `-Dbasquin.invariant.latency.maxMs=100`
- `-Dbasquin.invariant.heapDelta.maxKb=256`
- `-Dbasquin.invariant.threadDelta.max=2`
- `-Dbasquin.invariant.mode=hard|soft` (per-invariant override: `...latency.mode`, etc.)
- Evidence: `-Dbasquin.invariant.stack=current|all|off` (default `current`),
  `-Dbasquin.invariant.stack.maxFrames=15`,
  `-Dbasquin.invariant.latency.sample=true` (sample the execution stack at the latency threshold)

### Reset (v0.2, preview)

- `-Dbasquin.reset=classloader` — child-first classloader swap of the target's package
- `-Dbasquin.reset.onFailure=true` — reset after a hard invariant/leak
- `-Dbasquin.reset.maxResets=3`

## Running

```
# Examples
./gradlew runExampleProper          # clean example
./gradlew runRunnerLeak             # thread-leak demo (fails; use forceExitOnLeak in CI)
./gradlew runRunnerJavalinLeak      # Javalin leak demo (downloads Javalin for this run)

# Generic runner against any IterationTarget
java -cp build/libs/basquin-0.3.0.jar runner.GenericRunner 100 your.pkg.YourTarget

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

Run: `java -cp build/libs/basquin-0.3.0.jar:<your-cp> runner.GenericRunner 100 your.pkg.YourTarget`

## Native agent (JVMTI)

Event-driven thread tracking (counts + leak set) with no polling or safepoint stack walks;
the harness falls back to `ThreadMXBean` when it is not loaded.

```
./gradlew buildNativeAgent          # -> build/native/libbasquinjvmti.so (needs cc + JDK headers)
LIB=$PWD/build/native/libbasquinjvmti.so
java -agentpath:$LIB -Dbasquin.native.lib=$LIB -cp ... runner.GenericRunner ...
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
./gradlew minimizeInput -Dbasquin.target=<FQCN> \
  -Dbasquin.min.input=fuzz-results/<t>/input-<ts>.bin \
  -Dbasquin.min.output=fuzz-results/<t>/minimized.bin
```

Saved inputs land under `fuzz-results/<target>/` as a `.bin` plus a `.meta.txt` triage report
(classification, timestamp, violation, stacks). Configure with `-Dbasquin.fuzz.resultsDir=...`.

## HTTP driver (client-side)

Drive a running app over HTTP — real end-to-end latency + 5xx-as-crash, with the live status
screen. Harvests server-side `X-Basquin-Invariant-*` headers when the app has the valve/filter.

```
./gradlew runHttpDrive -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.invariant.latency.maxMs=50 -Dbasquin.invariant.mode=soft -Ddrive.iterations=200
```

## Coverage-guided exploration (v0.10)

Point it at a running app that has the JaCoCo agent and the Basquin valve injected
(`docker-compose.coverage.yml`, or the kind demo in [deploy/k8s](../deploy/k8s/README.md)).

```bash
./gradlew stageAgents            # agent + valve + jacocoagent at stable paths

./gradlew runDashboard &         # standalone dashboard on 127.0.0.1:7070

./gradlew runCoverageGuided \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.coverage.jacoco=localhost:6300 \
  -Dbasquin.coverage.classes=/path/to/WEB-INF/classes \
  -Dbasquin.grammar=examples/grammar/jpetstore.grammar \
  -Dbasquin.invariant.latency.maxMs=25 -Dbasquin.invariant.mode=soft \
  -Dbasquin.dashboard.push=localhost:7070
```

Related tasks: `runHttpDriveCoverage` (coverage % without guided mutation), `runHttpDrive`
(no coverage at all), `stageAgents`, `copyJacocoAgent`, `buildNativeAgent`.

### Multiple replicas

When one driver drives a target Service backed by several replicas, name every JaCoCo endpoint so
coverage reflects the whole fleet, not the one pod your reader connected to (DD-023):

```bash
# explicit list of pod endpoints...
-Dbasquin.coverage.jacoco=10.0.1.4:6300,10.0.1.5:6300,10.0.1.6:6300
# ...or a headless Service name, which resolves to all pod IPs on its own
-Dbasquin.coverage.jacoco=jpetstore-jacoco.default.svc.cluster.local:6300
```

Coverage is union-merged across replicas. The status panel shows `[N/M pods]` whenever a replica
is unreachable or more than one is expected, so an under-count from a restarting pod is visible
rather than being mistaken for genuinely low coverage.

## Kubernetes: instrument any app with the operator

Instead of baking the agents into a custom image (the manual path in `deploy/k8s/`), the operator
instruments an **unmodified** app Deployment at deploy time and reverses it cleanly when you're done.
Design: [OPERATOR-DESIGN.md](OPERATOR-DESIGN.md). It's namespaced — it only touches Deployments you
name, in its own namespace.

**Build + install** (into a kind cluster, for the local demo):

The fastest way to see all of this is **`deploy/e2e/e2e.sh`**, which builds every image, deploys the
operator, applies a target against a raw app, and asserts the result. To do it by hand:

```bash
# 1. Build the agents image the operator injects, and load it into your cluster.
deploy/agents-image/build.sh 0.3.0 <kind-cluster>            # => basquin/agents:0.3.0

# 2. Build + load the operator image (a fixed tag => IfNotPresent, so kind uses the loaded image
#    instead of trying to pull the manifest's default controller:latest).
docker build -t basquin/operator:0.3.0 operator/
kind load docker-image basquin/operator:0.3.0 --name <kind-cluster>

# 3. Install the CRD and deploy the operator (namespaced RBAC), pinning that image.
kubectl apply -f operator/config/crd/bases/basquin.dev_basquintargets.yaml
kubectl kustomize operator/config/default \
  | sed 's#image: controller:latest#image: basquin/operator:0.3.0#' \
  | kubectl apply -f -                                       # operator + Role/RoleBinding/SA

# 4. Tell the operator which agents image to inject.
kubectl -n basquin-system patch deploy basquin-controller-manager --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--agents-image=basquin/agents:0.3.0"}]'
```

**Instrument an app** — apply a `BasquinTarget` naming its Deployment:

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata: { name: myapp, namespace: basquin-system }
spec:
  deploymentRef: { name: myapp }
  container: myapp                 # optional if the pod has one container
  jvmOptsVar: CATALINA_OPTS        # or JAVA_TOOL_OPTIONS (non-Tomcat); the agents are APPENDED
  agents:
    threadTracker: true
    coverage: { enabled: true, port: 6300, includes: "com.yourco.yourapp.*" }   # includes REQUIRED
  invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }
  coverageService: true            # operator creates a headless Service for DD-023 union coverage
```

The operator patches the Deployment (initContainer copies the agents into a shared volume, appends
the agent flags to `jvmOptsVar`, exposes the coverage port), rolls it out, and reports:

```bash
kubectl -n basquin-system get basquintargets
# NAME    DEPLOYMENT   PHASE      INSTRUMENTED   AGE
# myapp   myapp        Injected   1              30s

# the coverage endpoint to point the DD-023 flag at:
kubectl -n basquin-system get basquintarget myapp -o jsonpath='{.status.coverageEndpoint}'
# myapp-basquin-jacoco.basquin-system.svc.cluster.local:6300
```

**Uninstall / revert** — delete the target; the operator restores the Deployment to exactly its
pre-injection state (a finalizer guarantees it) and garbage-collects the coverage Service:

```bash
kubectl -n basquin-system delete basquintarget myapp
```

> Status: injection (P2) + coverage Service (P3) are implemented and validated in-cluster by
> `deploy/e2e/e2e.sh`. The Tomcat **valve** is deferred (it needs a `context.xml` entry, not a JVM
> flag), and launching the *driver + dashboard* from the operator (`BasquinCampaign`) is roadmap.

## Writing a request grammar

The grammar decides what the fuzzer can ever reach, so it is **data, not code**. Two concerns are
deliberately split: the **corpus supplies values**, the **grammar supplies structure**.

```
# a rule: alternatives may be literals, a corpus file, a structure, or a generator
$itemId     = @../corpus/jpetstore/values/itemId.txt | ~EST-[0-9]{1,4} | <empty> | <string>
$categoryId = FISH | DOGS | ~[A-Z]{4,8}

# a route template; ${name} expands to one alternative of that rule
/actions/Catalog.action?viewItem=&itemId=${itemId}
```

| Form | Meaning |
|------|---------|
| `@file` | Load values from a corpus file, one per line (`#` comments allowed). Relative to the grammar file |
| `~pattern` | **Structural** generation: literals, `[A-Z]`/`[a-z]`/`[0-9]`/`[abc]` classes, `{n}` and `{n,m}` repetition |
| `<int>` | Boundary-biased integer (0, 1, -1, MAX_VALUE, …) |
| `<string>` | Short random string, sometimes containing metacharacters |
| `<long>` | Long string, for length/overflow probing |
| `<empty>` | The empty string |

Why both `@file` and `~pattern`: real values reach happy paths and deep code; **structurally valid
but nonexistent** values (`EST-847`) get past parsing and format checks into the lookup and
dereference code, which is where the interesting failures are; purely random junk usually gets
rejected at the first validation. Using only one of the three finds noticeably less.

### Multi-step transactions

Some code is only reachable after an ordered sequence against one session — order placement needs
a *populated cart*, not just a login. Indented steps belong to the sequence; a blank line ends it.

```
@sequence signon_browse_checkout
  /actions/Account.action?signon=&username=j2ee&password=j2ee
  /actions/Catalog.action?viewItem=&itemId=${itemId}
  /actions/Cart.action?addItemToCart=&workingItemId=${itemId}
  /actions/Cart.action?checkOut=
```

**Placeholders bind once per sequence execution**, so `${itemId}` above is the same item in every
step. If it re-randomised per step the transaction would add one item and remove a different one —
still plausible-looking in a dashboard, while never reaching checkout code.

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
# status UI: http://localhost:8080/basquin/index.html
```

Third-party apps (unmodified WARs, via the Tomcat valve) — including the JPetStore walkthrough
and the javax/jakarta namespace table — are covered in [THIRD-PARTY-APPS](THIRD-PARTY-APPS.md).
