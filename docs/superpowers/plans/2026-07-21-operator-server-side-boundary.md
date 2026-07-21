# Agent-installed server-side request boundary (operator path) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the server-side availability oracle (and DD-029 lock-free load) to the operator path by having the injected Java agent install a request boundary via bytecode, instead of a mounted Tomcat valve.

**Architecture:** `Agent.premain` (today a no-op stub) installs a ByteBuddy transformer on `org.apache.catalina.core.StandardHostValve.invoke(Request,Response)`. A new Catalina-free `RequestBoundary` holds the three-state machine (control / load / explore) that `BasquinValve` uses today; both the valve and the inlined advice call it. The operator flips it on with a single `-Dbasquin.boundary=agent` flag.

**Tech Stack:** Java 17, ByteBuddy 1.14.12 (already a dependency), Gradle, JUnit 4; Go (operator, controller-runtime, Ginkgo/Gomega); bash (e2e in kind).

**Spec:** `docs/superpowers/specs/2026-07-21-operator-server-side-boundary-design.md`.

## Global Constraints

- Java target bytecode 17 (`sourceCompatibility/targetCompatibility = '17'`); CI builds on JDK 17 and 21.
- ByteBuddy pinned at `net.bytebuddy:byte-buddy:1.14.12` — no new bytecode dependency.
- `RequestBoundary` references **no** `org.apache.catalina.*` type (agent classes load on the boot loader via `-Xbootclasspath/a`, which can't see Catalina). Catalina refs live only in `TomcatBoundaryAdvice` (inlined, never linked) and `BasquinValve` (its own module).
- Catalina is added `compileOnly` to the agent main source set; the resolved `basquin-agent.jar` must NOT bundle it.
- Namespace-free (DD-011): no `javax.servlet` / `jakarta.servlet` reference in the instrumented path.
- Best-effort: neither `premain` instrumentation nor per-request advice may ever fail an application request.
- The agent boundary is **opt-in, default off** (`-Dbasquin.boundary=agent`); the bench path (valve + agent) must remain single-boundary.
- Depends on `agent.LoadMode` / `agent.LoadModeControl` — already on `main` (PR #70).
- Commits are bot-authored; the branch is `feat/operator-server-boundary` (already created off `main`).

## File Structure

- `agent/RequestBoundary.java` (new) — Catalina-free three-state boundary; owns `ITERATION_LOCK`, control dispatch, begin/end, header computation.
- `agent/TomcatBoundaryAdvice.java` (new) — ByteBuddy `@Advice` glue; the only Basquin class naming Catalina connector types; delegates to `RequestBoundary`.
- `agent/Agent.java` (modify) — implement `premain` to install the transformer, gated on the opt-in flag.
- `tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java` (modify) — become a thin adapter over `RequestBoundary`.
- `build.gradle` (modify) — add `compileOnly` Catalina to the agent main source set.
- `test/RequestBoundaryTest.java` (new) — unit tests for the state machine (no Catalina).
- `operator/internal/controller/injection.go` (modify) — append `-Dbasquin.boundary=agent`; fix the stale comment.
- `operator/internal/controller/basquintarget_controller_test.go` (modify) — assert the flag is injected.
- `deploy/e2e/e2e.sh` (modify) — assert the `/__basquin` surface + drift are live in-cluster.
- Docs/CHANGELOGs (modify) — record the operator now has the server-side oracle.

---

### Task 1: `RequestBoundary` — the shared, Catalina-free state machine

**Files:**
- Create: `agent/RequestBoundary.java`
- Test: `test/RequestBoundaryTest.java`

**Interfaces:**
- Consumes: `agent.Agent.beginIteration()/endIteration()/getLastInvariantViolations()`, `agent.LoadMode.isLoad(long)`, `agent.LoadModeControl.handle(String,String)` (all on `main`).
- Produces: `RequestBoundary.onEnter(String uri, String query) → Decision{Phase phase, String controlBody, boolean skipApp()}`; `RequestBoundary.onExit(Throwable appError) → ExitResult{Map<String,String> headers, Throwable toThrow}`; `enum Phase{CONTROL_HANDLED, LOAD_PASSTHROUGH, EXPLORE_BEGAN}`. Used by Task 2 (valve) and Task 3 (advice).

- [ ] **Step 1: Write the failing test** — `test/RequestBoundaryTest.java`

```java
package test;

import agent.LoadMode;
import agent.RequestBoundary;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class RequestBoundaryTest {

    @After
    public void reset() {
        LoadMode.setExplore(); // leave the shared flag clean for the next test
    }

    @Test
    public void controlToggleToLoadIsHandledAndSkipsApp() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/mode", "to=load");
        Assert.assertEquals(RequestBoundary.Phase.CONTROL_HANDLED, d.phase);
        Assert.assertTrue(d.skipApp());
        Assert.assertEquals("ok:load", d.controlBody);
        Assert.assertTrue(LoadMode.isLoad(System.currentTimeMillis()));
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null); // no-op close for a skipped request
        Assert.assertTrue(r.headers.isEmpty());
        Assert.assertNull(r.toThrow);
    }

    @Test
    public void driftReturnsThreeFieldCsv() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/drift", null);
        Assert.assertTrue(d.skipApp());
        Assert.assertTrue("drift body was: " + d.controlBody, d.controlBody.matches("^\\d+,\\d+,\\d+$"));
        RequestBoundary.onExit(null);
    }

    @Test
    public void loadModePassesThroughWithoutBoundary() {
        LoadMode.setLoad(60_000L);
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        Assert.assertEquals(RequestBoundary.Phase.LOAD_PASSTHROUGH, d.phase);
        Assert.assertFalse(d.skipApp());
        RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
        Assert.assertTrue(r.headers.isEmpty());
        Assert.assertNull(r.toThrow);
    }

    @Test
    public void exploreBeginsAndExitReleasesLock() {
        LoadMode.setExplore();
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        Assert.assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
        Assert.assertFalse(d.skipApp());
        Assert.assertNull(RequestBoundary.onExit(null).toThrow);

        // The lock must be released: a full cycle on ANOTHER thread must not deadlock.
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            RequestBoundary.Decision d2 = RequestBoundary.onEnter("/again", null);
            RequestBoundary.onExit(null);
            ok[0] = d2.phase == RequestBoundary.Phase.EXPLORE_BEGAN;
        });
        t.start();
        try { t.join(5_000); } catch (InterruptedException ignored) { }
        Assert.assertTrue("second explore cycle on another thread must not deadlock", ok[0]);
    }

    @Test
    public void appErrorPropagatesThroughExit() {
        LoadMode.setExplore();
        RequestBoundary.onEnter("/app", null);
        RuntimeException boom = new RuntimeException("app blew up");
        Assert.assertSame(boom, RequestBoundary.onExit(boom).toThrow);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'test.RequestBoundaryTest'`
Expected: FAIL — `RequestBoundary` does not exist (compile error).

- [ ] **Step 3: Write the minimal implementation** — `agent/RequestBoundary.java`

```java
package agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The shared three-state request boundary (control / load / explore), extracted from BasquinValve so
 * the valve AND the agent's bytecode boundary (TomcatBoundaryAdvice) run identical logic
 * (DD-005/DD-010/DD-029).
 *
 * <p><b>Catalina-free by design.</b> Agent classes load on the boot loader (the operator injects
 * {@code -Xbootclasspath/a:basquin-agent.jar}), which cannot see {@code org.apache.catalina.*} — a
 * child loader. So this class names no Catalina type: it takes the request URI/query as strings and
 * returns a {@link Decision}/{@link ExitResult}; each caller performs its own Catalina response I/O.
 */
public final class RequestBoundary {

    public enum Phase { CONTROL_HANDLED, LOAD_PASSTHROUGH, EXPLORE_BEGAN }

    /** onEnter's result: the phase and, for control requests, the plaintext body to write. */
    public static final class Decision {
        public final Phase phase;
        public final String controlBody; // non-null iff phase == CONTROL_HANDLED
        Decision(Phase phase, String controlBody) { this.phase = phase; this.controlBody = controlBody; }
        /** True iff the app body must be skipped (a /__basquin control request handled here). */
        public boolean skipApp() { return phase == Phase.CONTROL_HANDLED; }
    }

    /** onExit's result: invariant headers to set (never null; may be empty) + throwable to propagate. */
    public static final class ExitResult {
        public final Map<String, String> headers;
        public final Throwable toThrow; // null if clean
        ExitResult(Map<String, String> headers, Throwable toThrow) { this.headers = headers; this.toThrow = toThrow; }
    }

    private static final ReentrantLock ITERATION_LOCK = new ReentrantLock(true);
    private static final ThreadLocal<Phase> PHASE = new ThreadLocal<>();
    private static final Map<String, String> NO_HEADERS = Collections.emptyMap();

    private RequestBoundary() { }

    /** Before the wrapped invoke. Never throws. Stashes the phase in a thread-local for {@link #onExit}. */
    public static Decision onEnter(String uri, String query) {
        // Control surface: /__basquin/* handled here, never reaches the app.
        String control = LoadModeControl.handle(uri, query);
        if (control != null) {
            PHASE.set(Phase.CONTROL_HANDLED);
            return new Decision(Phase.CONTROL_HANDLED, control);
        }
        // Load mode (DD-029): passthrough — no lock, no begin, run concurrently.
        if (LoadMode.isLoad(System.currentTimeMillis())) {
            PHASE.set(Phase.LOAD_PASSTHROUGH);
            return new Decision(Phase.LOAD_PASSTHROUGH, null);
        }
        // Explore (default): serialize + begin. Lock BEFORE begin; if begin throws, unlock and degrade
        // this one request to passthrough rather than stranding the fair lock forever.
        ITERATION_LOCK.lock();
        try {
            Agent.beginIteration();
        } catch (Throwable t) {
            ITERATION_LOCK.unlock();
            PHASE.set(Phase.LOAD_PASSTHROUGH);
            return new Decision(Phase.LOAD_PASSTHROUGH, null);
        }
        PHASE.set(Phase.EXPLORE_BEGAN);
        return new Decision(Phase.EXPLORE_BEGAN, null);
    }

    /**
     * After the wrapped invoke. {@code appError} is what the app threw (or null). Runs even when the app
     * was skipped. Never throws. Preserves the valve's exact exception semantics: the app's exception
     * wins and an endIteration error is attached as suppressed; an endIteration error on a clean request
     * surfaces as the returned throwable.
     */
    public static ExitResult onExit(Throwable appError) {
        Phase phase = PHASE.get();
        PHASE.remove();
        if (phase != Phase.EXPLORE_BEGAN) {
            return new ExitResult(NO_HEADERS, appError); // control / load: nothing to close
        }
        Throwable pending = appError;
        Map<String, String> headers = NO_HEADERS;
        try {
            Agent.endIteration();
        } catch (Throwable endError) {
            if (pending != null) pending.addSuppressed(endError);
            else pending = endError;
        } finally {
            try {
                headers = invariantHeaders();
            } finally {
                ITERATION_LOCK.unlock();
            }
        }
        return new ExitResult(headers, pending);
    }

    /** The invariant headers to set on the response (empty if none). Caller gates on !isCommitted(). */
    private static Map<String, String> invariantHeaders() {
        try {
            List<String> violations = Agent.getLastInvariantViolations();
            if (violations != null && !violations.isEmpty()) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("X-Basquin-Invariant-Count", String.valueOf(violations.size()));
                String first = violations.get(0);
                if (first != null) {
                    if (first.length() > 200) first = first.substring(0, 200);
                    h.put("X-Basquin-Invariant-Detail", first);
                }
                return h;
            }
        } catch (Throwable ignored) {
            // header reporting is best-effort
        }
        return NO_HEADERS;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'test.RequestBoundaryTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/RequestBoundary.java test/RequestBoundaryTest.java
git commit -m "feat(agent): extract Catalina-free RequestBoundary state machine"
```

---

### Task 2: Refactor `BasquinValve` to delegate to `RequestBoundary`

**Files:**
- Modify: `tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java`

**Interfaces:**
- Consumes: `RequestBoundary.onEnter/onExit` (Task 1).
- Produces: unchanged public behavior (a Catalina `ValveBase`); no new API.

- [ ] **Step 1: Replace `invoke` and delete the now-shared internals**

Replace the whole `invoke` method, the `ITERATION_LOCK` field, and the `writeInvariantHeaders` method with the adapter below. Keep `sneakyThrow`, the constructor, and the class javadoc (update the "Iterations are SERIALIZED" paragraph to note the boundary now lives in `RequestBoundary`, shared with the agent). Update imports: remove `agent.Agent`, `agent.LoadMode`, `agent.LoadModeControl`, `java.util.List`, `java.util.concurrent.locks.ReentrantLock`; add `agent.RequestBoundary`, `java.util.Map`.

```java
    // Narrowed throws (no ServletException): a legal override that keeps the servlet namespace out of
    // this method's signature and bytecode (DD-011). All boundary logic lives in RequestBoundary, shared
    // with the agent-installed boundary (TomcatBoundaryAdvice); this method is only Catalina glue.
    @Override
    public void invoke(Request request, Response response) throws IOException {
        RequestBoundary.Decision decision =
                RequestBoundary.onEnter(request.getRequestURI(), request.getQueryString());
        if (decision.skipApp()) {
            // /__basquin control request: write the plaintext result, never reach the app.
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().print(decision.controlBody);
            return;
        }
        Throwable appError = null;
        try {
            getNext().invoke(request, response);
        } catch (Throwable t) {
            appError = t;
        }
        RequestBoundary.ExitResult r = RequestBoundary.onExit(appError);
        if (!r.headers.isEmpty() && !response.isCommitted()) {
            for (Map.Entry<String, String> h : r.headers.entrySet()) {
                response.setHeader(h.getKey(), h.getValue());
            }
        }
        if (r.toThrow != null) {
            if (r.toThrow instanceof IOException) throw (IOException) r.toThrow;
            if (r.toThrow instanceof RuntimeException) throw (RuntimeException) r.toThrow;
            if (r.toThrow instanceof Error) throw (Error) r.toThrow;
            // Checked ServletException (javax or jakarta) — re-raise without naming the type.
            sneakyThrow(r.toThrow);
        }
    }
```

- [ ] **Step 2: Build the valve to verify it compiles**

Run: `./gradlew :tomcat-valve:jar`
Expected: BUILD SUCCESSFUL. (The valve module has `compileOnly project(':')`, so `agent.RequestBoundary` resolves.)

- [ ] **Step 3: Verify the valve stayed namespace-free (DD-011)**

Run: `javap -c -p tomcat-valve/build/classes/java/main/com/basquin/valve/BasquinValve.class | grep -ic 'javax/servlet\|jakarta/servlet' || true`
Expected: `0`.

- [ ] **Step 4: Run the full JVM test suite (valve refactor must not regress the boundary)**

Run: `./gradlew test`
Expected: PASS (including `RequestBoundaryTest` and existing DD-029 tests).

- [ ] **Step 5: Commit**

```bash
git add tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java
git commit -m "refactor(valve): delegate the request boundary to RequestBoundary"
```

---

### Task 3: `TomcatBoundaryAdvice` + Catalina compile-only + implement `Agent.premain`

**Files:**
- Create: `agent/TomcatBoundaryAdvice.java`
- Modify: `build.gradle` (agent main source set gains `compileOnly` Catalina)
- Modify: `agent/Agent.java` (`premain`)

**Interfaces:**
- Consumes: `RequestBoundary` (Task 1), ByteBuddy `net.bytebuddy.*`, Catalina `org.apache.catalina.connector.Request/Response`.
- Produces: a `premain` that, when `-Dbasquin.boundary=agent`, instruments `StandardHostValve.invoke`. No Java API surface for other tasks; the operator (Task 4) supplies the flag.

- [ ] **Step 1: Add Catalina compile-only to the agent main source set** — `build.gradle`

In the first `dependencies { ... }` block (the one with `implementation 'net.bytebuddy:byte-buddy:1.14.12'`), add the compile-only Catalina line:

```groovy
dependencies {
    implementation 'net.bytebuddy:byte-buddy:1.14.12'
    // Catalina API to compile TomcatBoundaryAdvice's connector-type refs; provided by the Tomcat
    // runtime, NEVER bundled (the advice is inlined into StandardHostValve at instrumentation time).
    // Same coordinates the tomcat-valve module compiles against; DD-011 keeps the bytecode namespace-free.
    compileOnly 'org.apache.tomcat:tomcat-catalina:10.1.20'
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Write the advice** — `agent/TomcatBoundaryAdvice.java`

```java
package agent;

import net.bytebuddy.asm.Advice;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import java.util.Map;

/**
 * ByteBuddy advice inlined into {@code org.apache.catalina.core.StandardHostValve.invoke(Request,Response)}
 * by {@link Agent#premain} when {@code -Dbasquin.boundary=agent}. This is the ONLY Basquin class that
 * names Catalina connector types. It is never loaded/linked as a class — its body is copied into
 * StandardHostValve (where Catalina is visible), so its Catalina refs never trip the boot-loader
 * visibility limit that keeps {@link RequestBoundary} Catalina-free. All logic lives in RequestBoundary;
 * this is pure Catalina glue, mirroring {@code BasquinValve.invoke}.
 */
public final class TomcatBoundaryAdvice {

    private TomcatBoundaryAdvice() { }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) Request request,
                         @Advice.Argument(1) Response response) {
        RequestBoundary.Decision d =
                RequestBoundary.onEnter(request.getRequestURI(), request.getQueryString());
        if (d.skipApp()) {
            try {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getWriter().print(d.controlBody);
            } catch (Throwable ignored) {
                // best-effort control write; never fail the request
            }
        }
        return d.skipApp(); // non-default (true) → skip the StandardHostValve.invoke body
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(1) Response response,
                     @Advice.Thrown(readOnly = false) Throwable thrown) {
        RequestBoundary.ExitResult r = RequestBoundary.onExit(thrown);
        try {
            if (!r.headers.isEmpty() && !response.isCommitted()) {
                for (Map.Entry<String, String> h : r.headers.entrySet()) {
                    response.setHeader(h.getKey(), h.getValue());
                }
            }
        } catch (Throwable ignored) {
            // best-effort header write
        }
        thrown = r.toThrow; // app exception wins; endIteration error suppressed under it
    }
}
```

- [ ] **Step 3: Implement `premain`** — `agent/Agent.java`

Add imports near the top of `Agent.java`:

```java
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
```

Replace the `premain` body:

```java
    public static void premain(String agentArgs, java.lang.instrument.Instrumentation instrumentation) {
        System.out.println("Basquin Agent initialized");
        // Opt-in, default OFF: the bench path runs the valve AND this agent, and two boundaries would
        // double-count. The operator sets -Dbasquin.boundary=agent (it mounts no valve); the bench path
        // leaves it unset, so the valve stays the sole boundary there.
        if (!"agent".equals(System.getProperty("basquin.boundary"))) {
            return;
        }
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges() // advice-only; no class-schema changes
                    .type(ElementMatchers.named("org.apache.catalina.core.StandardHostValve"))
                    .transform((builder, type, cl, module, pd) -> builder.visit(
                            Advice.to(TomcatBoundaryAdvice.class).on(ElementMatchers.named("invoke"))))
                    .installOn(instrumentation);
            System.out.println("[Basquin] agent boundary installed on StandardHostValve");
        } catch (Throwable t) {
            // Degrade, don't break: an app whose Tomcat internals differ just runs uninstrumented.
            System.err.println("[Basquin] agent boundary NOT installed: " + t);
        }
    }
```

Implementation note: `premain` runs before Tomcat's bootstrap loads `StandardHostValve`, so transform-on-load suffices (no retransformation). If `Advice.to(TomcatBoundaryAdvice.class)` ever fails to locate the advice bytes at premain (class-file-locator on the boot loader), fall back to `Advice.to(new net.bytebuddy.description.type.TypeDescription.ForLoadedType(TomcatBoundaryAdvice.class), net.bytebuddy.dynamic.ClassFileLocator.ForClassLoader.ofSystemLoader())`.

- [ ] **Step 4: Build the agent jar and verify it compiles + Catalina is NOT bundled**

Run: `./gradlew jar`
Expected: BUILD SUCCESSFUL.

Run: `unzip -l build/libs/basquin-*.jar | grep -c 'org/apache/catalina' || true`
Expected: `0` (Catalina is compile-only; the fat jar must not contain it). ByteBuddy classes SHOULD be present: `unzip -l build/libs/basquin-*.jar | grep -c 'net/bytebuddy'` → non-zero.

- [ ] **Step 5: Verify premain is a safe no-op when the flag is unset**

Run: `./gradlew test` (the full suite still passes; nothing calls premain with the flag set, so no behavior change).
Expected: PASS. (Full instrumentation is proven in-cluster by Task 5's e2e — a unit test can't host a real Tomcat.)

- [ ] **Step 6: Commit**

```bash
git add agent/TomcatBoundaryAdvice.java agent/Agent.java build.gradle
git commit -m "feat(agent): install a server-side request boundary via ByteBuddy (opt-in)"
```

---

### Task 4: Operator opt-in flag

**Files:**
- Modify: `operator/internal/controller/injection.go`
- Test: `operator/internal/controller/basquintarget_controller_test.go`

**Interfaces:**
- Consumes: nothing new.
- Produces: injected JVM opts now include `-Dbasquin.boundary=agent` whenever the Java agent is injected.

- [ ] **Step 1: Add the failing assertion** — `basquintarget_controller_test.go`

After the existing injected-args assertions (the block asserting `-agentpath:` and `-javaagent:`, around line 148), add:

```go
			Expect(opts).To(ContainSubstring("-Dbasquin.boundary=agent"))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd operator && go test ./internal/controller/ -run TestControllers 2>&1 | tail -20`
Expected: FAIL — the injected opts do not yet contain `-Dbasquin.boundary=agent`.

- [ ] **Step 3: Append the flag + fix the stale comment** — `injection.go`

In `buildAgentArgs`, immediately after the `-javaagent` + `-Xbootclasspath/a` append (the `args = append(args, "-javaagent:"+... , "-Xbootclasspath/a:"+...)` call), add:

```go
	// Turn on the agent-installed server-side request boundary (RequestBoundary via ByteBuddy in
	// premain). Default-off in the agent, so the bench path (valve + agent) stays single-boundary; the
	// operator mounts no valve, so it opts in here. This is what gives the operator path its server-side
	// heap/thread/latency oracle + DD-029 /__basquin control surface.
	args = append(args, "-Dbasquin.boundary=agent")
```

Then update the comment above `buildAgentArgs` (the lines beginning "The valve is NOT a JVM flag ... handled separately (deferred ...)"). Replace with:

```go
// The Tomcat valve is not mounted by the operator; instead the injected -javaagent installs the
// server-side request boundary itself via bytecode (-Dbasquin.boundary=agent, see agent.Agent.premain
// / RequestBoundary), so the operator path gets the availability oracle on any Tomcat image with no
// context.xml / lib surgery.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd operator && go test ./internal/controller/ -run TestControllers 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 5: Run go vet + the full controller suite**

Run: `cd operator && go vet ./... && go test ./... 2>&1 | tail -20`
Expected: PASS. (The flag is part of `buildAgentArgs`, already hashed by `specHash`, so steady targets stay a no-op and only re-inject on the args change — no separate change needed.)

- [ ] **Step 6: Commit**

```bash
git add operator/internal/controller/injection.go operator/internal/controller/basquintarget_controller_test.go
git commit -m "feat(operator): opt the injected agent into the server-side boundary"
```

---

### Task 5: e2e — assert the server-side boundary is live in-cluster

**Files:**
- Modify: `deploy/e2e/e2e.sh`
- Modify: `docs/LOCKFREE-LOAD-DESIGN.md` (the "Where it activates" note — operator now activated)

**Interfaces:**
- Consumes: the running operator (Task 4) injecting `-Dbasquin.boundary=agent`; the `apod` variable (the JPetStore pod, defined ~line 294).
- Produces: e2e assertions proving `/__basquin` is served from the operator-injected target (no valve).

- [ ] **Step 1: Add the server-side assertions** — `deploy/e2e/e2e.sh`

After `apod` is resolved and the explore campaign has completed (before or alongside the existing load-campaign block), add:

```bash
  # DD-030: the agent-installed boundary (no valve mounted by the operator) intercepts requests on the
  # app's own port. Proof it's live in-cluster: the /__basquin control surface responds. A 3-field CSV
  # (heapKb,threads,epochMs) from /__basquin/drift is only possible if premain instrumented
  # StandardHostValve — i.e. the same code path that runs Agent.begin/end server-side in explore.
  sdrift=""; smode=""
  if [ -n "$apod" ]; then
    sdrift="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s http://localhost:8080/__basquin/drift" 2>/dev/null || true)"
    smode="$($K -n "$NS" exec "$apod" -c jpetstore -- sh -c "curl -s 'http://localhost:8080/__basquin/mode?to=explore'" 2>/dev/null || true)"
  fi
  check "DD-030: agent boundary serves /__basquin/drift in-cluster (CSV)" "echo '$sdrift' | grep -qE '^[0-9]+,[0-9]+,[0-9]+$'"
  check "DD-030: agent boundary serves /__basquin/mode control"          "[ '$smode' = 'ok:explore' ]"
  echo "  (server-side boundary: drift=${sdrift:-<none>}, mode-toggle=${smode:-<none>})"
```

- [ ] **Step 2: Update the load-campaign note that said /__basquin is absent**

In the load-campaign block, replace the `NOTE:` comment (added when the DD-029 assertion was removed — it claims "no /__basquin endpoint") with:

```bash
    # The agent-installed boundary (Task: DD-030) now serves /__basquin on the operator target — asserted
    # above. Here we only assert the load run's own client-side outputs.
```

- [ ] **Step 3: Update the design-doc scoping note** — `docs/LOCKFREE-LOAD-DESIGN.md`

In the "Where it activates" section, change the sentence stating the operator does not mount the valve so DD-029 is gated, to note it is now active via the agent-installed boundary:

```markdown
**Update (DD-030):** the operator now activates DD-029 in-cluster via the *agent-installed* boundary
(the injected `-javaagent` instruments `StandardHostValve` when `-Dbasquin.boundary=agent`) — no valve
mount required. `LoadRun`'s mode toggle + drift polling reach a live `/__basquin` surface on the
operator target; the e2e asserts it.
```

- [ ] **Step 4: Syntax-check the e2e**

Run: `bash -n deploy/e2e/e2e.sh && echo OK`
Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add deploy/e2e/e2e.sh docs/LOCKFREE-LOAD-DESIGN.md
git commit -m "test(e2e): assert the agent server-side boundary is live in-cluster"
```

*(The real run is CI on push — the in-cluster e2e is the integration proof that premain instruments a real Tomcat and serves `/__basquin` from a valve-less, operator-injected target.)*

---

### Task 6: Docs + CHANGELOGs

**Files:**
- Modify: `docs/DESIGN-DECISIONS.md` (add a DD-030 stub), `docs/OPERATOR-USAGE.md` (server-side oracle note), `agent/CHANGELOG.md`, `tomcat-valve/CHANGELOG.md`.

- [ ] **Step 1: Add a DD-030 entry** — `docs/DESIGN-DECISIONS.md`

Append a short DD-030 record: *"Operator server-side boundary via agent bytecode. The operator mounts no valve; the injected `-javaagent` installs the request boundary by instrumenting `StandardHostValve.invoke` (ByteBuddy) when `-Dbasquin.boundary=agent`. Brings the server-side availability oracle + DD-029 lock-free load to any Tomcat image in-cluster. Shared logic lives in `RequestBoundary`, used by both the valve and the advice. See `docs/superpowers/specs/2026-07-21-operator-server-side-boundary-design.md`."*

- [ ] **Step 2: Note the operator oracle** — `docs/OPERATOR-USAGE.md`

Add a short subsection noting that instrumented targets now capture **server-side** heap/thread/latency findings (previously the operator path was client-side only), and that `/__basquin` is served on the app port for in-cluster load control (same in-cluster-trust caveat already documented).

- [ ] **Step 3: CHANGELOG entries** — `agent/CHANGELOG.md` and `tomcat-valve/CHANGELOG.md`

Under each component's Unreleased section:
- agent: *"Added: server-side request boundary installed by `premain` via ByteBuddy (`-Dbasquin.boundary=agent`), sharing `RequestBoundary` with the valve; brings the availability oracle to the operator path."*
- tomcat-valve: *"Changed: `BasquinValve` now delegates its boundary to the shared `agent.RequestBoundary` (behavior unchanged)."*

- [ ] **Step 4: Commit**

```bash
git add docs/DESIGN-DECISIONS.md docs/OPERATOR-USAGE.md agent/CHANGELOG.md tomcat-valve/CHANGELOG.md
git commit -m "docs: record DD-030 operator server-side boundary"
```

---

## Final step: open the PR

- [ ] Push the branch and open a bot-authored PR titled *"feat: server-side availability oracle in the operator path (DD-030)"*, request review from @claude and the user, and link the spec + this plan. Body must explain the reframing (the operator path had no server-side oracle; this adds it) and that it depends on the merged DD-029 work.

## Self-Review notes (author)

- **Spec coverage:** RequestBoundary (Task 1), advice + premain (Task 3), valve refactor (Task 2), operator flag (Task 4), e2e (Task 5), docs (Task 6) — every spec component has a task.
- **Type consistency:** `Decision{phase, controlBody, skipApp()}` and `ExitResult{headers, toThrow}` are defined in Task 1 and consumed identically in Tasks 2 and 3. `Phase` enum values match across tasks. `-Dbasquin.boundary=agent` string is identical in Agent.premain (Task 3) and injection.go (Task 4).
- **No placeholders:** every code step shows complete code; every command has expected output.
