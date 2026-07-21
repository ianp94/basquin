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
  injects — **zero operator plumbing change**, zero `lib/`/`conf/` surgery.
- **Namespace-free (DD-011 preserved).** `StandardHostValve.invoke(Request, Response)` takes
  `org.apache.catalina.connector.Request`/`Response` — the exact Catalina types the valve already
  uses. No `javax`/`jakarta` servlet types in the instrumented signature, so one agent jar serves
  Tomcat 9 (javax) and 10+ (jakarta).
- **Invoked once per client request**, and `StandardHostValve` has been stable across Tomcat 7–11.
- **ByteBuddy `1.14.12` is already a dependency** (`build.gradle:19`); the premain is a stub waiting
  for exactly this.

The advice reproduces `BasquinValve.invoke`'s existing three-state behavior at the new join point.

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

### New: `agent/RequestBoundary.java` — the shared enter/exit state machine

Extracts the two/three-state boundary that currently lives inline in `BasquinValve.invoke` so the
valve **and** the agent advice call one implementation (DRY). It owns: the `ITERATION_LOCK`, the
`/__basquin` control dispatch, the load/explore branch, and invariant-header writing.

To stay usable from both a Catalina `Valve` and inlined advice — and to keep Catalina types out of
the *unit tests* — it is written against **narrow interfaces**, not `Request`/`Response` directly:

```java
package agent;

public final class RequestBoundary {

    /** What the boundary needs to read from the request. */
    public interface Req { String uri(); String query(); }

    /** What the boundary needs to write to the response. */
    public interface Resp {
        void status(int code);
        void contentType(String type);
        void write(String body);      // control-response body
        void header(String name, String value);
        boolean committed();
    }

    /** State carried from onEnter to onExit for a single request, held in a thread-local. */
    public enum Phase { CONTROL_HANDLED, LOAD_PASSTHROUGH, EXPLORE_BEGAN }

    /**
     * Called before the wrapped invoke. Stashes the request's Phase in a thread-local and returns
     * whether the app body must be SKIPPED (true only for the /__basquin control case). The boolean
     * is the ByteBuddy skip signal (skipOn = OnNonDefaultValue); the Phase is retrieved by onExit.
     */
    public static boolean onEnter(Req req, Resp resp) { /* control→skip; load; explore→lock+begin */ }

    /**
     * Called after the wrapped invoke. Reads + clears the thread-local Phase: EXPLORE_BEGAN →
     * endIteration + headers + unlock; LOAD_PASSTHROUGH / CONTROL_HANDLED → nothing. Runs even when
     * onEnter skipped the app (ByteBuddy applies exit advice on skip), hence the no-op branches.
     */
    public static void onExit(Resp resp) { /* explore: end + headers + unlock; else nothing */ }
}
```

The enter→exit signal is split on purpose: **a boolean skip flag** (so `skipOn = OnNonDefaultValue`
skips the app *only* for control requests, never for load/explore) and **a thread-local `Phase`** for
the exit branch. The `ITERATION_LOCK` is acquired in `onEnter` for the explore phase and released in
`onExit`; `beginIteration()` runs inside a try so a throw still unlocks in the `finally` (the existing
valve invariant). Because ByteBuddy runs exit advice even when entry advice skips the method, `onExit`
is a safe no-op for the `CONTROL_HANDLED` / `LOAD_PASSTHROUGH` phases.

### New: `agent/TomcatBoundaryAdvice.java` — the ByteBuddy advice template

Two static methods annotated `@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)` and
`@Advice.OnMethodExit`. They adapt the Catalina `Request`/`Response` (bound via `@Advice.Argument`) to
`RequestBoundary.Req`/`Resp` and delegate. This is the **only** class that names
`org.apache.catalina.connector.*` — compiled `compileOnly` against Catalina, and its bytecode is
inlined into `StandardHostValve` (which has Catalina on its loader) at instrumentation time, so it is
never loaded in a non-Tomcat JVM.

The enter method returns the boolean skip flag from `RequestBoundary.onEnter` (drives
`skipOn = OnNonDefaultValue`); the exit method calls `RequestBoundary.onExit`, which reads the
thread-local `Phase`. No value needs threading via `@Advice.Enter`.

### Modified: `agent/Agent.java` — implement `premain`

```java
public static void premain(String agentArgs, Instrumentation inst) {
    try {
        new AgentBuilder.Default()
            .disableClassFormatChanges()          // advice-only; no schema changes
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
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

Manifest gains `Can-Retransform-Classes: true` (and keeps `Premain-Class`). A `-Dbasquin.boundary`
switch (default on; `off` to disable) lets an operator opt out.

### Modified: `tomcat-valve/BasquinValve.java` — call the shared boundary

`invoke` becomes a thin adapter: wrap `Request`/`Response` as `RequestBoundary.Req`/`Resp`, call
`onEnter`, run `getNext().invoke` unless the phase is `CONTROL_HANDLED`, call `onExit` in a `finally`.
The `sneakyThrow`/checked-exception handling and namespace-free properties are preserved. Behavior is
unchanged; the logic simply moves into `RequestBoundary`.

### Modified: `operator/internal/controller/injection.go` — comment only

No functional change. The stale "the valve is handled separately (deferred)" comment on
`buildAgentArgs` is corrected to note the server-side boundary is now installed by the injected
`-javaagent` itself (no valve mount required). Confirm `agents.valve`/boundary gating reads sensibly;
the `-javaagent` is already appended, so nothing new is plumbed.

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

- **Unit — `RequestBoundary`** (`test/RequestBoundaryTest.java`): drive `onEnter`/`onExit` with fake
  `Req`/`Resp` implementations. Assert: `/__basquin/mode?to=load` → `CONTROL_HANDLED` + body `ok:load`
  + status 200 + app skipped; load phase → `LOAD_PASSTHROUGH`, no begin/end, no headers; explore →
  `EXPLORE_BEGAN`, headers written on exit. No Catalina on the test classpath.
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
- Depends on the DD-029 classes (`LoadMode`, `LoadModeControl`) from PR #70 — that merges first.
