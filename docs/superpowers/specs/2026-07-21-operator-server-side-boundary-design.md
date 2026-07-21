# Agent-installed server-side request boundary (operator path) — Design

**Status:** accepted (2026-07-21), pending implementation. Extends the injection model
([OPERATOR-DESIGN.md](../../OPERATOR-DESIGN.md), DD-024), the valve boundary (DD-009/DD-011), and the
lock-free load-mode profile ([LOCKFREE-LOAD-DESIGN.md](../../LOCKFREE-LOAD-DESIGN.md), DD-029).

## Context — the operator path has no server-side oracle

The operator instruments an unmodified Tomcat app by appending `-javaagent:basquin-agent.jar`
(+ `-Xbootclasspath/a`), a native `-agentpath`, and optional JaCoCo to the target's JVM opts
(`operator/internal/controller/injection.go`). But:

- **`Agent.premain` is a no-op stub** (`agent/Agent.java` — literally `// Agent initialization logic
  would go here`). It installs no `ClassFileTransformer` and does no instrumentation.
- **The Tomcat valve is never mounted** by the operator (a `<Valve>` needs a `context.xml` entry +
  the jar on Catalina's *common* classloader — image-specific filesystem/config surgery the operator
  deliberately defers).

Consequence: `Agent.beginIteration()/endIteration()` are **never called inside the target JVM** in the
operator path. In-cluster explore campaigns therefore get:

- ✅ **Real coverage** — the JaCoCo agent *is* injected; the driver reads it over TCP from the target.
- ❌ **No server-side heap/thread/latency oracle** — those findings come only from the **driver JVM**
  (client-side), which measures the runner process, not the app. The core availability thesis — heap
  retention and thread/executor leaks *inside the app under test* — is inert in Kubernetes.

The 2026-07-21 benchmark's real server-side numbers came from the **bench path** (docker-compose with
the valve mounted into Tomcat's `lib/`), which is unaffected. This design brings that same server-side
boundary to the **operator path**, and carries DD-029's lock-free load mode along with it.

## Decision — the agent installs the boundary via bytecode (ByteBuddy)

Rather than teach the operator to mount a `<Valve>` (image-specific: it must know the Tomcat `lib/`
layout and safely inject XML without clobbering the app's own `conf/`), **fulfill the no-op premain**:
`Agent.premain` installs a ByteBuddy transformer that wraps Tomcat's request pipeline at
`org.apache.catalina.core.StandardHostValve.invoke(Request, Response)`.

Why this join point and this mechanism:

- **Image-agnostic.** Works on *any* Tomcat image through the `-javaagent` the operator already
  injects — the operator change is a single opt-in flag (`-Dbasquin.boundary=agent`), zero
  `lib/`/`conf/` surgery.
- **Namespace-free (DD-011 preserved).** `StandardHostValve.invoke(Request, Response)` takes
  `org.apache.catalina.connector.Request`/`Response` — the exact Catalina types the valve already
  uses. No `javax`/`jakarta` servlet types in the instrumented signature, so one agent jar serves
  Tomcat 9 (javax) and 10+ (jakarta).
- **Invoked once per client request**, and `StandardHostValve` has been stable across Tomcat 7–11.
- **ByteBuddy `1.14.12` is already a dependency** (`build.gradle:19`); the premain is a stub waiting
  for exactly this.

The advice reproduces `BasquinValve.invoke`'s existing three-state behavior at the new join point.

### Coexistence with the valve — the agent boundary is opt-in (default OFF)

The valve and the agent boundary must **never both run in the same JVM** — that would double the
begin/end boundary and corrupt the per-request deltas. The two deployment paths differ:

- **Operator path:** agent only (no valve). Boundary must be **on**.
- **Bench/manual path:** the docker-compose files mount the valve into `lib/` *and* inject
  `-javaagent`. Boundary must be **off** (the valve owns it).

So the agent boundary is gated on a system property, **default off**: `Agent.premain` installs the
transformer only when `-Dbasquin.boundary=agent` is set. The **operator sets that flag** (one line in
`buildAgentArgs`); the bench path does not, so the valve remains the sole boundary there. Explicit and
race-free (an auto-detect — the valve marking itself present for the advice to defer to — is a possible
later refinement, but not this cut).

### Per-request behavior (identical to the valve)

For each `StandardHostValve.invoke(request, response)`:

1. **Control surface** — if the request URI starts with `/__basquin/`: compute
   `LoadModeControl.handle(uri, query)`, write the plaintext result to the response (status 200), and
   **skip** the original method body (ByteBuddy `@Advice.OnMethodEnter(skipOn = ...)`). Never reaches
   the app.
2. **Load mode** (`LoadMode.isLoad(now)` true, DD-029) — passthrough: no `beginIteration`, no lock,
   no deltas, no exit headers. Requests run concurrently at app speed.
3. **Explore mode** (default) — `Agent.beginIteration()` on enter; on exit `Agent.endIteration()` +
   write `X-Basquin-Invariant-*` headers. The per-request heap/thread deltas are meaningful because
   explore serializes (the valve's `ITERATION_LOCK` semantics live in the shared boundary).

Best-effort throughout: any error inside the advice is swallowed so a request is never failed by the
instrumentation, exactly as `BasquinValve` does today.

## Architecture & components

### New: `agent/RequestBoundary.java` — the shared, Catalina-free state machine

Extracts the three-state boundary that currently lives inline in `BasquinValve.invoke` so the valve
**and** the agent advice call one implementation (DRY). It owns: the `ITERATION_LOCK`, the
`/__basquin` control dispatch, the load/explore branch, `begin/end`, and the invariant-header
*computation*.

**Critical: `RequestBoundary` references NO Catalina type.** The operator injects
`-Xbootclasspath/a:basquin-agent.jar`, so agent classes load on the **boot** loader, which cannot see
`org.apache.catalina.*` (a child loader). A loaded class that both sits on boot and names Catalina
would `NoClassDefFoundError`. So the boundary takes **primitive/String inputs and returns a decision**;
each caller does its own Catalina response I/O. (This also means the unit test needs no Catalina and no
fakes.)

```java
package agent;

public final class RequestBoundary {

    public enum Phase { CONTROL_HANDLED, LOAD_PASSTHROUGH, EXPLORE_BEGAN }

    /** onEnter's result: the phase, and (for control) the plaintext body to write + skip the app. */
    public static final class Decision {
        public final Phase phase;
        public final String controlBody;   // non-null iff phase == CONTROL_HANDLED
        public boolean skipApp() { return phase == Phase.CONTROL_HANDLED; }
    }

    /** onExit's result: the invariant headers to set (empty if none) + the throwable to propagate. */
    public static final class ExitResult {
        public final java.util.Map<String,String> headers;   // e.g. X-Basquin-Invariant-Count → "3"
        public final Throwable toThrow;                        // null if clean
    }

    /** Before the wrapped invoke: control? → write body + skip; load → passthrough; explore → lock+begin.
     *  Stashes the Phase in a thread-local for onExit. */
    public static Decision onEnter(String uri, String query) { ... }

    /** After the wrapped invoke. appError = what the app threw (or null). Reads+clears the thread-local
     *  Phase. EXPLORE_BEGAN → endIteration (suppressing its error under appError), compute headers,
     *  unlock. Returns the headers to write + the throwable to propagate. Runs even when the app was
     *  skipped (a no-op for CONTROL_HANDLED / LOAD_PASSTHROUGH). */
    public static ExitResult onExit(Throwable appError) { ... }
}
```

The caller writes `controlBody` to the response and returns early when `skipApp()`; otherwise runs the
app, catches any throwable, calls `onExit(appError)`, sets the returned headers (guarded by
`!response.isCommitted()`), and propagates `toThrow`. This preserves the valve's exact exception
semantics: the app's exception wins, an `endIteration` error is attached as *suppressed*, and an
`endIteration` error on a clean request surfaces as the thrown invariant. `ITERATION_LOCK` is acquired
in `onEnter` (explore) and released in `onExit`; `beginIteration` runs in a guard so a throw still
unlocks and degrades that one request to passthrough rather than stranding the lock.

### New: `agent/TomcatBoundaryAdvice.java` — the ByteBuddy advice template

Two static methods, `@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)` and
`@Advice.OnMethodExit(onThrowable = Throwable.class)`, with the Catalina `Request`/`Response` bound via
`@Advice.Argument(0)`/`(1)`. This is the **only** Basquin class that names
`org.apache.catalina.connector.*`; it is compiled `compileOnly` against Catalina and its body is
**inlined** into `StandardHostValve` at instrumentation time (so it runs in the common loader, where
Catalina is visible, and is never linked as a boot-loaded class).

```java
@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
static boolean enter(@Advice.Argument(0) Request request, @Advice.Argument(1) Response response) {
    RequestBoundary.Decision d = RequestBoundary.onEnter(request.getRequestURI(), request.getQueryString());
    if (d.skipApp()) {
        try { response.setStatus(200); response.setContentType("text/plain");
              response.getWriter().print(d.controlBody); } catch (Throwable ignored) {}
    }
    return d.skipApp();                 // non-default (true) → skip StandardHostValve.invoke body
}

@Advice.OnMethodExit(onThrowable = Throwable.class)
static void exit(@Advice.Argument(1) Response response,
                 @Advice.Thrown(readOnly = false) Throwable thrown) {
    RequestBoundary.ExitResult r = RequestBoundary.onExit(thrown);
    try {
        if (!response.isCommitted()) {
            for (java.util.Map.Entry<String,String> h : r.headers.entrySet())
                response.setHeader(h.getKey(), h.getValue());
        }
    } catch (Throwable ignored) {}
    thrown = r.toThrow;                 // app exception wins; endIteration error suppressed under it
}
```

The enter method's `boolean` return drives `skipOn`; the exit method reassigns `@Advice.Thrown` to
propagate exactly what the boundary decided (matching the valve's `sneakyThrow` semantics without
naming `ServletException`, since `thrown` is already a `Throwable`).

### Modified: `agent/Agent.java` — implement `premain`

```java
public static void premain(String agentArgs, Instrumentation inst) {
    // Opt-in, default OFF, so the bench path (valve + agent both present) is never double-instrumented.
    if (!"agent".equals(System.getProperty("basquin.boundary"))) {
        return;
    }
    try {
        new AgentBuilder.Default()
            .disableClassFormatChanges()          // advice-only; no schema changes
            .type(named("org.apache.catalina.core.StandardHostValve"))
            .transform((b, type, cl, module, pd) ->
                b.visit(Advice.to(TomcatBoundaryAdvice.class).on(named("invoke"))))
            .installOn(inst);
        System.out.println("[Basquin] agent boundary installed on StandardHostValve");
    } catch (Throwable t) {
        // Degrade, don't break: the app runs uninstrumented if the Tomcat internals differ.
        System.err.println("[Basquin] agent boundary NOT installed: " + t);
    }
}
```

`premain` runs before Tomcat's bootstrap loads `StandardHostValve`, so transform-on-load suffices (no
retransformation needed). The manifest keeps `Premain-Class: agent.Agent`.

### Modified: `tomcat-valve/BasquinValve.java` — call the shared boundary

`invoke` becomes a thin adapter over the same `RequestBoundary` calls the advice uses: read
`getRequestURI()/getQueryString()`, `onEnter(uri, query)`; if `skipApp()` write `controlBody` and
return; else run `getNext().invoke`, catch any throwable, `onExit(appError)`, set the returned headers
(guarded by `!isCommitted()`), and re-raise `toThrow` via the existing `sneakyThrow` (so a checked
`ServletException` is re-raised without being named — namespace-free, DD-011). The valve keeps
`sneakyThrow`; its inline `ITERATION_LOCK` and `writeInvariantHeaders` are deleted (now in
`RequestBoundary`). Behavior is unchanged.

### Modified: `operator/internal/controller/injection.go` — add the opt-in flag

`buildAgentArgs` appends **`-Dbasquin.boundary=agent`** whenever the Java agent is injected (i.e.
always, since the agent carries the oracle). This turns the server-side boundary on in the operator
path. The stale "the valve is handled separately (deferred)" comment is corrected. The flag is part
of `specHash` (already hashing `buildAgentArgs`), so a steady target stays a no-op and only re-injects
when the args change. One line of real change; the `-javaagent` itself is already plumbed.

### Modified: `deploy/e2e/e2e.sh` — assert the server-side boundary in-cluster

Re-add server-side assertions that now *pass* on the operator-injected (valve-less) target:

- `/__basquin/drift` returns a 3-field CSV (`heapKb,threads,epochMs`) — proof the agent boundary
  intercepts and the drift snapshot is served in-cluster.
- A request to a known route returns `X-Basquin-Invariant-*` headers (or the explore campaign records
  ≥1 server-side finding) — proof `beginIteration/endIteration` run *inside the target JVM*.

This closes the loop the DD-029 e2e assertion was removed for.

## Data flow

```
client request
  → CoyoteAdapter → StandardEngineValve → StandardHostValve.invoke(req,resp)   [INSTRUMENTED]
      ├─ /__basquin/*  → LoadModeControl.handle → write resp → SKIP app
      ├─ load          → (no begin) → app → (no end)
      └─ explore       → RequestBoundary.onEnter: LOCK + Agent.beginIteration()
                          → app (StandardContext… → servlet)
                          → RequestBoundary.onExit: Agent.endIteration() + headers + UNLOCK
```

Drift: `LoadMode.driftSnapshotCsv()` served on `/__basquin/drift`. `LoadRun` (DD-029, already built)
toggles `mode` and polls `drift` — now reaching a live in-cluster boundary instead of 404-ing.

## Error handling

- **Instrumentation failure** (unknown Tomcat internals) → logged, app runs uninstrumented. Degraded,
  never broken. The e2e would catch a regression by the drift/finding assertions failing.
- **Advice-body failure** → swallowed per-request (best-effort), mirroring the valve. A leak/invariant
  thrown by `endIteration` is attached as suppressed, never masks the app's own exception.
- **Lock safety** → `beginIteration` runs inside the try so a throw still releases the lock in the
  `finally` (the existing valve invariant, preserved in `RequestBoundary`).
- **Load-mode crash safety** → `LoadMode` auto-reverts to explore after its TTL (DD-029), so a driver
  that dies mid-load doesn't strand the target in passthrough.

## Testing

- **Unit — `RequestBoundary`** (`test/RequestBoundaryTest.java`): pure string-in/decision-out, no
  Catalina, no fakes. Assert: `onEnter("/__basquin/mode","to=load")` → `Phase.CONTROL_HANDLED`,
  `controlBody == "ok:load"`, `skipApp()` true, and `LoadMode.isLoad(now)` flipped true;
  `onEnter("/__basquin/drift", null)` → `controlBody` matches `^\d+,\d+,\d+$`; while in load mode,
  `onEnter("/app", null)` → `Phase.LOAD_PASSTHROUGH` and `onExit(null)` returns empty headers +
  null; in explore, `onEnter("/app", null)` → `Phase.EXPLORE_BEGAN` and `onExit(null)` returns a
  (possibly empty) header map + null with the lock released (a second `onEnter` doesn't deadlock).
- **Unit — valve adapter**: existing valve tests keep passing (behavior unchanged), proving the
  refactor is faithful.
- **Integration — e2e**: the real proof. Operator injects `-javaagent` only → `/__basquin/drift` and
  server-side findings assertions pass in a kind cluster.

## Non-goals / deferred

- **Declarative `<Valve>` mounting via the operator** — explicitly rejected in favor of the
  agent-installed boundary; the valve remains for the bench/manual path only.
- **Instrumenting non-Tomcat servers** (Jetty, Undertow, Netty) — a later boundary-provider per
  server. This design covers Tomcat, which is the project's stated target.
- **Per-request server-side findings in load mode** — that's explore's job (DD-029 non-goal, unchanged).

## Global constraints (carried into the plan)

- ByteBuddy `1.14.12` (already pinned); no new bytecode dependency.
- Catalina is `compileOnly` for the agent module — never bundled into `basquin-agent.jar`.
- The agent must remain safe to load in a non-Tomcat JVM (Catalina refs isolated to
  `TomcatBoundaryAdvice`, loaded only when instrumentation applies).
- Namespace-free (DD-011): no `javax.servlet`/`jakarta.servlet` reference in the instrumented path.
- Best-effort: instrumentation and per-request advice never fail an application request.
- The agent boundary is **opt-in, default off** (`-Dbasquin.boundary=agent`), so the bench path
  (valve + agent) is never double-instrumented; the operator sets the flag.
- Catalina compile-only is added to the **agent main source set** (`build.gradle`); the resolved jar
  (a fat jar) must NOT bundle it — `compileOnly`, provided by Tomcat at runtime.
- Depends on the DD-029 classes (`LoadMode`, `LoadModeControl`) — now merged to main via #70.
