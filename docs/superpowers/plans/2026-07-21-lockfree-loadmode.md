# Lock-free load-mode (DD-029) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `mode: load` campaigns generate real concurrent load on an instrumented target while still
capturing the app's availability drift — by giving the valve a lock-free load state and serving mode
control + drift over the app port the valve already intercepts.

**Architecture:** A `volatile` mode flag in the agent. The valve intercepts `/__basquin/*` control
requests (toggle mode, read a drift snapshot) at the top of `invoke()` — no lock — and otherwise
branches: **explore** = today's serialized `begin/end`; **load** = passthrough. `LoadRun` toggles the
target into load mode over `baseURL`, polls the drift snapshot to build the series, adds 5xx detection,
and reverts on exit. The agent auto-reverts to explore after inactivity as a crash-safety.

**Tech Stack:** Java 17 (agent + Tomcat valve, namespace-free), Gradle, JUnit 4; Go operator (envtest)
only if a driver-arg change is needed (expected: none).

## Global Constraints

- **Explore mode is untouched** — the serialized `ITERATION_LOCK` → `Agent.begin/end` path must behave
  exactly as today; load mode is a separate branch (DD-029, lowest-regression requirement).
- **Namespace-free valve** — no `javax`/`jakarta` servlet types in the valve's bytecode (DD-011); use
  Catalina `Request`/`Response` only, as the existing valve does.
- **The lock is all-or-nothing** — mode is target-wide for the campaign, never per-request.
- **Drift is an absolute `Runtime` read**, never the lock-dependent `ctx.heapDeltaBytes` delta (DD-029 / #63).
- **Control surface is the app's HTTP port** via valve interception (resolves DD-029 open-Q #2) — no new
  listener, no agent HTTP server, no operator change. Path prefix `/__basquin/` (collision-unlikely).
- Branch-per-change, **bot-authored PRs, review from @claude + the user, user approval to merge**.

## Plan-review note (confirm before executing)

DD-029 §2/§5 described drift as "a pollable agent endpoint" with the operator "passing the drift
endpoint." This plan resolves the spec's own open question #2 by serving control+drift via valve
interception on the app port instead — strictly simpler (no agent HTTP server, no agent sampler thread,
no operator wiring), same behavior. If you'd rather have a dedicated agent endpoint + operator arg, say
so and I'll re-plan tasks 2–5.

---

## Task 1: Agent mode flag + drift snapshot + auto-revert (TDD)

**Files:**
- Create: `agent/LoadMode.java`
- Test: `test/LoadModeTest.java`

**Interfaces (Produces):**
- `LoadMode.setLoad(long ttlMillis)` — enter load mode until `now+ttl`; `LoadMode.setExplore()` — leave.
- `LoadMode.isLoad(long nowMillis)` — true iff load and not expired (auto-revert).
- `LoadMode.driftSnapshotCsv()` — `"<usedHeapKb>,<threadCount>,<epochMillis>"` from absolute
  `Runtime.getRuntime().totalMemory()-freeMemory()` and `Thread.activeCount()`.

- [ ] **Step 1: Write the failing test.**
```java
import org.junit.Test; import agent.LoadMode; import static org.junit.Assert.*;
public class LoadModeTest {
  @Test public void defaultsToExplore() { assertFalse(LoadMode.isLoad(1000)); }
  @Test public void loadUntilTtlThenAutoReverts() {
    LoadMode.setLoad(500); // ttl 500ms from an internal 'now'
    assertTrue(LoadMode.isLoad(LoadMode.enteredAt()+100));
    assertFalse("auto-revert after ttl", LoadMode.isLoad(LoadMode.enteredAt()+600));
  }
  @Test public void explicitExplore() { LoadMode.setLoad(10_000); LoadMode.setExplore(); assertFalse(LoadMode.isLoad(0)); }
  @Test public void driftCsvHasThreeNumericFields() {
    String[] f = LoadMode.driftSnapshotCsv().split(","); assertEquals(3, f.length);
    for (String x : f) Long.parseLong(x); // all numeric
  }
}
```
- [ ] **Step 2: Run to verify it fails.** `./gradlew test --tests LoadModeTest` → FAIL (class missing).
- [ ] **Step 3: Implement `agent/LoadMode.java`** — a final class, `private static volatile boolean load`,
  `private static volatile long expiresAt`, `enteredAt`. `setLoad(ttl)` sets `load=true`,
  `enteredAt=System.currentTimeMillis()`, `expiresAt=enteredAt+ttl`. `isLoad(now)` returns
  `load && now < expiresAt`. `setExplore()` sets `load=false`. `driftSnapshotCsv()` reads absolute
  Runtime heap (÷1024) + `Thread.activeCount()` + `System.currentTimeMillis()`. Expose `enteredAt()`.
- [ ] **Step 4: Run to verify pass + full suite.** `./gradlew test --tests LoadModeTest` then `./gradlew test`.
- [ ] **Step 5: Commit** (`feat(agent): load-mode flag + drift snapshot + auto-revert`), bot-authored PR,
  request @claude + ianp94 review, address findings.

## Task 2: Valve control-request interception + two-state branch (integration)

**Files:**
- Modify: `tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java`
- Test: `tomcat-valve/src/test/java/.../BasquinValveModeTest.java` (check the existing valve test harness;
  if none exercises `invoke()`, add a minimal fake `Request`/`Response` + a stub next valve).

**Interfaces (Consumes):** `agent.LoadMode` from Task 1. **Produces:** valve honors `/__basquin/mode?to=load|explore`
and `/__basquin/drift`, and branches explore/load for app traffic.

- [ ] **Step 1: Write the failing test** — with a fake next-valve that records whether it was invoked and
  a fake Request/Response:
```java
// control request: GET /__basquin/drift -> valve writes a CSV body, does NOT call getNext(), no lock
@Test public void driftControlRequestBypassesAppAndLock() throws Exception {
  FakeNext next = new FakeNext(); BasquinValve v = valveWith(next);
  FakeResp r = invoke(v, "/__basquin/drift");
  assertFalse("app not invoked for control req", next.invoked);
  assertEquals(3, r.body.split(",").length);
}
// mode toggle flips LoadMode
@Test public void modeToggleEntersLoad() throws Exception {
  invoke(valveWith(new FakeNext()), "/__basquin/mode?to=load");
  assertTrue(agent.LoadMode.isLoad(agent.LoadMode.enteredAt()+10));
}
// in load mode, app traffic passes through without the serialized begin/end
@Test public void loadModeAppTrafficIsPassthrough() throws Exception {
  agent.LoadMode.setLoad(10_000); FakeNext next = new FakeNext();
  invoke(valveWith(next), "/somepage"); assertTrue(next.invoked); // and (assert no lock contention via a probe)
}
```
- [ ] **Step 2: Run to verify it fails.** `./gradlew :tomcat-valve:test --tests BasquinValveModeTest` → FAIL.
- [ ] **Step 3: Implement** at the top of `invoke()`, BEFORE `ITERATION_LOCK.lock()`:
  read `request.getRequestURI()`; if it starts with `/__basquin/`, handle inline (parse `mode?to=` →
  `LoadMode.setLoad(ttl)`/`setExplore()`; `/__basquin/drift` → write `LoadMode.driftSnapshotCsv()` to the
  response, `text/plain`), then `return` (never lock, never call `getNext`). Otherwise: if
  `LoadMode.isLoad(System.currentTimeMillis())` → `getNext().invoke(request,response)` directly (no lock,
  no begin/end); else → the current serialized block unchanged. Keep it namespace-free (Catalina types only).
- [ ] **Step 4: Run to verify pass** + `./gradlew :tomcat-valve:test` + `./gradlew test`.
- [ ] **Step 5: Commit** (`feat(valve): /__basquin control requests + explore/load two-state invoke`),
  bot PR, reviews, address.

## Task 3: LoadRun — 5xx detection + target-side drift polling + mode toggle (TDD where pure)

**Files:**
- Modify: `runner/coverage/LoadRun.java`
- Test: `test/LoadRunDriftTest.java` (pure helpers only; the HTTP loop is covered by the e2e in Task 4)

**Interfaces (Produces):** helpers `LoadRun.parseDrift(String csv)` → `{heapKb, threads, ts}`;
`LoadRun.driftDelta(first, last)` → `{heapDriftKb, threadDrift}`; `fire()` records 5xx.

- [ ] **Step 1: Write the failing test** for the pure helpers:
```java
@Test public void parseDriftReadsCsv() { var d = LoadRun.parseDrift("2048,37,1000"); assertEquals(2048, d.heapKb); assertEquals(37, d.threads); }
@Test public void driftDeltaIsLastMinusFirst() {
  var a = LoadRun.parseDrift("1000,10,0"); var b = LoadRun.parseDrift("1600,14,5000");
  var delta = LoadRun.driftDelta(a,b); assertEquals(600, delta.heapDriftKb); assertEquals(4, delta.threadDrift);
}
```
- [ ] **Step 2: Run to verify fail.** `./gradlew test --tests LoadRunDriftTest`.
- [ ] **Step 3: Implement** `parseDrift`/`driftDelta`; in `fire()` capture `conn.getResponseCode()` and
  increment a `serverError` counter when `>= 500` (the #63/#DD-029 gap — today it swallows); at load start
  `POST baseURL/__basquin/mode?to=load` (ttl = duration + slack), sample `GET baseURL/__basquin/drift`
  periodically into a list, `POST .../mode?to=explore` at end; replace the driver-JVM `usedHeapKb()` drift
  in the summary with the **target-side** first→last drift, and add `serverErrors` to the JSON.
- [ ] **Step 4: Run to verify pass** + `./gradlew test`.
- [ ] **Step 5: Commit** (`feat(runner): load mode drives concurrency + target drift + 5xx`), bot PR, reviews.

## Task 4: e2e — a load campaign is actually concurrent

**Files:**
- Modify: `deploy/e2e/e2e.sh` (the existing load-campaign assertion block)

- [ ] **Step 1:** After the load campaign completes, assert the run was **concurrent, not serialized**:
  with `driver.concurrency=N`, aggregate throughput should exceed the single-flight rate (e.g. observed
  RPS ≥ ~1.5× the serialized baseline), and `status.load` carries a non-zero target-side heap/thread drift
  and a `serverErrors` field. Add a check that `X-Basquin`-free `/__basquin/drift` returns a 3-field CSV.
- [ ] **Step 2: Verify** the e2e passes in kind (`INSTALL=helm bash deploy/e2e/e2e.sh`).
- [ ] **Step 3: Commit** (`test(e2e): assert load mode runs concurrently + reports target drift`), bot PR, reviews.

## Task 5: Docs — flip DD-029 to implemented, update OPERATOR-USAGE load section

**Files:** Modify `docs/DESIGN-DECISIONS.md` (DD-029 stub → implemented), `docs/LOCKFREE-LOAD-DESIGN.md`
(status), `docs/OPERATOR-USAGE.md` (load-mode section: it now really loads; note the `/__basquin/` control
surface + its in-cluster-trust caveat), component `CHANGELOG`s.

- [ ] **Step 1:** Update the four docs + changelogs to match what shipped, incl. the security note that
  `/__basquin/mode` is unauthenticated on the app port (in-cluster trust, same model as the JaCoCo port —
  DD-022); flag hardening as a follow-up.
- [ ] **Step 2: Commit** (`docs: DD-029 implemented — lock-free load mode`), bot PR, reviews.

## Self-review notes
- **Spec coverage:** valve two-state (T2), drift via absolute reads (T1/T2), latency+5xx client-side (T3),
  driver-owned toggle over the app port (T2/T3), auto-revert (T1/T2), no operator change (resolved open-Q #2).
  Every DD-029 §-decision maps to a task; deferred items (clustered coordination, back-pressure, server-side
  per-request findings) stay deferred.
- **Type consistency:** `LoadMode.isLoad(now)` / `setLoad(ttl)` / `driftSnapshotCsv()` and
  `LoadRun.parseDrift`/`driftDelta` names are used identically across tasks.
- **Risk:** the valve test harness may not currently exercise `invoke()` with fakes — Task 2 Step 1 builds
  the minimal fakes if absent (check `tomcat-valve/src/test` first).
