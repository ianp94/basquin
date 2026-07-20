# ClosureJVM

**Find the availability bugs that crash-only testing misses — in real JVM web apps, unmodified.**

Most JVM web apps don't fall over because of a `NullPointerException`. They degrade: a
pathological input takes 500ms instead of 5ms, a request leaks a thread or holds an executor,
memory creeps across requests until a GC storm. These failures are input- and state-dependent,
hard to reproduce, and invisible to tests that only check for exceptions. ClosureJVM runs an app
across thousands of clean iterations and treats **availability invariants** — latency, heap
retention, thread/executor leaks — as first-class bug oracles, not just crashes.

[![CI](https://github.com/ianp94/closureJVM/actions/workflows/ci.yml/badge.svg)](https://github.com/ianp94/closureJVM/actions/workflows/ci.yml)

📖 **[Documentation, guides & demos → ianp94.github.io/closureJVM](https://ianp94.github.io/closureJVM/)** — how it works, getting started, and running against your own Tomcat app in Kubernetes.

![ClosureJVM live status driving JPetStore](docs/demo.svg)

*Above: the live status screen during a 200-iteration HTTP drive against an unmodified
[JPetStore](https://github.com/mybatis/jpetstore-6), flagging the app's real per-request latency.*

## Built with AI assistance

Much of ClosureJVM's code and documentation was written by Anthropic's Claude (via Claude Code),
with the author directing the design, making the architecture and semantic decisions, and reviewing
the work. This is noted for transparency — the project is not presented as entirely hand-written.

## Why it exists

Fuzzers like Jazzer and JQF are excellent, but their oracle is "did it throw / crash." The gap
ClosureJVM fills:

- **Availability is the oracle.** An iteration is *interesting* if it exceeds a latency budget,
  grows the heap, or leaks a thread/executor — not only if it throws.
- **Iteration cleanliness is enforced, not assumed.** Each iteration runs inside strict
  begin/end boundaries; leaked non-daemon threads and un-shut executors are detected and reported
  with stacks. Contamination becomes obvious instead of mysterious.
- **It works on apps you can't change.** A Tomcat valve wraps every request of an *unmodified*
  third-party WAR — no code edits, no repackaging — and a single namespace-free jar runs on both
  Tomcat 9 (`javax`) and Tomcat 10+ (`jakarta`).

## What it found in JPetStore (unmodified)

Running inside JPetStore's JVM via the valve, with no changes to the app:

| Signal | Example |
|--------|---------|
| Latency spike | cold first-catalog request **531ms** (budget 20ms) |
| Heap growth | up to **44MB** allocated on a single request |
| Per-request cost | steady multi-hundred-KB retention across catalog routes |

These are exactly the input/state-dependent availability pathologies the project targets.
Full walkthrough: [THIRD-PARTY-APPS](docs/THIRD-PARTY-APPS.md).

## Quick start

```bash
./gradlew build

# See a deliberate thread leak get caught (fails on purpose):
./gradlew runRunnerLeak

# Prove stability over 10,000 clean iterations:
./gradlew runSoakProper

# Drive a running web app and watch the live status screen:
./gradlew runHttpDrive -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dclosurejvm.invariant.latency.maxMs=50 -Dclosurejvm.invariant.mode=soft
```

Point it at **your** app by implementing a three-method `IterationTarget` — see
[USAGE](docs/USAGE.md#use-with-your-app).

## How it works

```
Inputs → Runner (begin/end iteration) → App entry → Metrics & invariants → Reset → repeat
```

- **Runner** executes iterations within boundaries and orchestrates the checks.
- **Agent** measures each iteration (latency, heap delta, thread/executor leaks) and evaluates
  invariants; an optional **JVMTI native agent** tracks thread lifecycle via events (no polling,
  no safepoint stack walks), with a `ThreadMXBean` fallback.
- **Reset** prefers enforced cleanliness, falling back to a classloader swap.
- **Triage** saves interesting inputs with classification, stacks, and metrics — off the hot path.

Details and the "why" behind each choice: [ARCHITECTURE](docs/ARCHITECTURE.md) ·
[DESIGN-DECISIONS](docs/DESIGN-DECISIONS.md).

## Exploration

Exploration (fuzzing / HTTP driving) runs each input through the same iteration boundaries and
invariants, and the live status screen grows an **exploration panel**: execs/sec, corpus size,
finds by classification (crash / invariant), **rejected** (expected input validations, not
crashes), time-since-last-find, and a **coverage %** row that lights up when a coverage source
reports.

![ClosureJVM exploration panel driving JPetStore](docs/demo-explore.svg)

*Above: exploring an unmodified JPetStore over HTTP — 220 requests, **0 crashes** (it's robust),
**48 invariant finds** harvested server-side via the valve. Point it at a running app:*

```bash
./gradlew runHttpDrive -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dclosurejvm.invariant.latency.maxMs=25 -Dclosurejvm.invariant.mode=soft
```

**Crashes are real crashes.** A target declares its expected input rejections via
`CrashClassifier`, so a parser throwing `IllegalArgumentException("bad char")` is counted as
*rejected*, not a crash — only genuine faults (an unhandled `NoSuchElementException`, an `NPE`, a
5xx) count. The top `crashes` figure and the exploration `finds crash` count come from that same
signal, so they always agree.

**Coverage comes from the app under test.** A JaCoCo agent runs in the target's JVM and the driver
reads it over the wire, so the coverage % is a real "% of code explored" rather than anything
measured in the harness. Coverage-*guided* mutation then keeps the inputs that reach new code:

```bash
./gradlew runCoverageGuided \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dclosurejvm.coverage.jacoco=localhost:6300 \
  -Dclosurejvm.coverage.classes=<dir>/WEB-INF/classes \
  -Dclosurejvm.grammar=examples/grammar/jpetstore.grammar
```

The reachable surface is **data, not code**: a [request grammar](docs/USAGE.md#writing-a-request-grammar)
supplies route templates, the corpus supplies parameter values, and `~EST-[0-9]{1,4}`-style
structural generation invents ids that *parse but don't exist* — which is how the deepest crashes
were found. `@sequence` blocks run ordered, session-carrying transactions (sign on → add to cart →
check out) to reach code that a single request never can.

## Kubernetes demo (kind)

One command runs the whole stack in a local Kubernetes cluster: JPetStore as a **pod** with the
valve, the ClosureJVM agent, and the JaCoCo coverage agent baked in — then drive it from outside
and watch every feature work in-cluster.

![ClosureJVM full stack in a kind cluster](docs/demo-k8s.svg)

*Above: 250 requests against the JPetStore pod — live **coverage %** (281/6368 edges of the pod's
own code, pulled from its JaCoCo agent), **96 invariant finds** harvested server-side through the
valve, 0 crashes.*

```bash
JPETSTORE_WAR=/abs/jpetstore.war deploy/k8s/up.sh
```

Details: [deploy/k8s/README.md](deploy/k8s/README.md).

## Web dashboard

A **standalone** dashboard process — never embedded in a driver, and never anywhere near the app
under test. Run it once; any number of drivers (one per campaign, one per pod) push their status
and findings to it, keyed by campaign id (defaults to `HOSTNAME`, a pod's name in Kubernetes). The
page shows a fleet view of every reporting campaign, with drill-down into metric cards, a coverage
bar, and a findings table (route/detail/classification, not just counts). This is the aggregation
point the auto-injection operator (see [TODO](TODO.md)) would point every instrumented pod at.

```bash
./gradlew runDashboard &   # standalone, its own process/port (7070 by default)

./gradlew runCoverageGuided -Dclosurejvm.dashboard.push=localhost:7070 \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dclosurejvm.coverage.jacoco=localhost:6300 -Dclosurejvm.coverage.classes=<dir>/WEB-INF/classes
```

No extra dependency (JDK `httpserver`) on either side. It binds **127.0.0.1** by default and
guards its ingest/analyze endpoints, since the analysis endpoint spends API credit — see
[DD-013](docs/DESIGN-DECISIONS.md) for the decoupling and [DD-022](docs/DESIGN-DECISIONS.md) for
the trust boundary.

Grammar, sequences, and every v0.10 flag are documented in [USAGE](docs/USAGE.md).

## Features

- Availability invariants (latency / heap / thread-delta) with hard-fail or soft-signal modes
- Thread, executor, and timer leak detection with stack evidence
- Event-driven thread tracking via a JVMTI native agent (one jar, JDK 17 & 21)
- **Coverage of the app under test**, read from its JaCoCo agent over the wire — and used to guide
  input mutation
- **Grammar-driven exploration**: routes and value structure as data, with multi-step
  session-carrying transactions
- Crash findings carry the **app's** stack, not the harness's, so they're triageable as-is
- Findings **clustered** by fingerprint (and across targets), so a systemic issue is one row
- Tomcat valve for unmodified third-party WARs — one jar for `javax` and `jakarta`
- Standalone **web dashboard**: fleet view, run configuration, findings with the inputs that
  produced them, optional Claude-backed analysis
- Live AFL-style status screen for long runs
- JQF fuzzing, corpus replay, and ddmin minimization

## Docs

- [USAGE](docs/USAGE.md) — commands, flags, and every runnable task
- [OPERATOR-USAGE](docs/OPERATOR-USAGE.md) — driving the Kubernetes operator (instrument an app, run a campaign, read results)
- [ARCHITECTURE](docs/ARCHITECTURE.md) — how and why
- [DESIGN-DECISIONS](docs/DESIGN-DECISIONS.md) — decision log with rejected alternatives
- [THIRD-PARTY-APPS](docs/THIRD-PARTY-APPS.md) — running against unmodified WARs (JPetStore)
- [deploy/k8s](deploy/k8s/README.md) — the kind cluster demo
- [TODO](TODO.md) — roadmap and milestones · [agents.md](agents.md) — maintainer guardrails
