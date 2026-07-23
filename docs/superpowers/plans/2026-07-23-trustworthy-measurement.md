# DD-040 Trustworthy Measurement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reported zero means "checked and clean". Violations reach the driver even when the response committed; a lost measurement is reported as unmeasured rather than as zero; and soft mode never alters an application's response.

**Architecture:** A per-request-id result store inside the target JVM, written by the boundary under the lock it already holds and read by the driver over the `/__basquin/*` control surface the driver already talks to. The response header stays as a fast path; the poll is the reliable path. Nothing is added to the load path.

**Tech Stack:** Java 17 (agent, valve, runner), Go (operator), JUnit 4.

## Global Constraints

Copied from the spec (`docs/superpowers/specs/2026-07-23-trustworthy-measurement-design.md`); every task's requirements implicitly include these.

- **A miss is never a zero.** On a failed/missing poll the driver sets `measured=false`, does **not** call `CostModel.score`, and does **not** build a zero-filled `CostSample`. Reproducing the zero-fill is reproducing the defect under a new name.
- **Ids are salted** with DD-038's `RUN_SALT` (`LoadRun.java:518-525`): `<RUN_SALT>-<n>`. An unsalted counter returns another run's entry as a valid result.
- **The result handler acquires `ITERATION_LOCK` (bounded wait) before reading the store.** `Agent.end()` sleeps 25 ms *before* measuring (`Agent.java:116-129`), so the entry is written ~25 ms after the client sees EOF on a `Content-Length` or error-page response. Without the lock wait the poll misses on exactly the class this change exists to recover.
- **The store is internally synchronized.** `ITERATION_LOCK` provides *attribution*, not reader safety — the poll runs on a different connector thread that never touches that lock.
- **The entry is built from `IterationContext`**, never from `Agent`'s process-global `last*` statics.
- **The load path gains zero instructions.** Everything new is on the `EXPLORE_BEGAN` branch or in `CONTROL_HANDLED`.
- **The fast-path discriminator is `X-Basquin-Cost`** (always emitted on an explore exit), never `X-Basquin-Invariant-Count` (absent on every clean request).
- Boundary code must stay **namespace-free** (DD-011): use only concrete Catalina methods (`getHeader`, `setHeader`, `isCommitted`), never `javax`/`jakarta` types.
- Do not change what is measured, or the invariant definitions.
- **The entry must be written even when the iteration THROWS.** `Agent.endIteration()` throws on a
  hard-mode invariant violation (`Invariants.java:41,53,64,71`; global mode **defaults to hard**,
  `:82`) and on leak detection (`Agent.java:249`). Building the entry from `endIteration()`'s return
  value therefore skips the store write for **exactly the violating iterations** — making the new
  "reliable" channel weaker than the header it replaces. Capture the context *before* the call
  (`Agent.currentContext()`, reading the existing `CURRENT` thread-local at `Agent.java:54`) and
  build the entry in the `finally` unconditionally. `endIteration()`'s own `finally` does
  `CURRENT.remove()` (`:289`), so capturing after the call yields `null`.
- **A recovered violation must be SAVED AS A FINDING**, not merely fed to the cost model. Violations
  become findings only through `FuzzIO.saveWithMeta(label, "Invariant-Remote", …)`
  (`CoverageGuidedRun.java:629-635`) → `StatusReporter.recordSaved` → `findInvariant`, which is the
  number the acceptance test compares against the pod log. A poll that recovers a count and saves
  nothing leaves the reported count at ~0 — the defect surviving under a new name, with every unit
  test green.
- **The load path reads no header.** Do **not** widen `onEnter`: the glue cannot know the phase
  before `onEnter` returns, so reading `X-Basquin-Req` there would touch every load request too.
  Keep `onEnter(uri, query)` and have the glue call a new `RequestBoundary.stampRequestId(reqId)`
  **only when `Decision.phase == EXPLORE_BEGAN`**. The id is not needed until `onExit`.
- `costCsv` must be composed exactly as the header is (`Agent.lastCostCsv`, `:424-426`):
  `latencyMs + "," + heapDeltaBytes/1024 + "," + threadDelta`. Bytes-vs-KB here is silent garbage.
- **The poll GET is never stamped.** A stamped poll would leave a stale id on a pooled connector
  thread and publish a later probe's metrics under a driver id — stale data as fresh.

---

### Task 1: The result store + control endpoints

**Files:**
- Create: `agent/ResultStore.java`
- Modify: `agent/LoadModeControl.java`
- Test: `test/agent/ResultStoreTest.java` (create)

**Interfaces produced:**
- `ResultStore.Entry` — `record Entry(String costCsv, int invariantCount, String detail, boolean leakDetected)`
- `static void put(String id, Entry e)`
- `static Entry take(String id)` — returns and removes; `null` on miss
- `static String format(Entry e)` / `static final String MISS = "miss"`
- `static long totalViolations()` — for `/__basquin/violations`
- `static void clearForTest()`

- [ ] **Step 1: Write the failing test**

```java
package agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResultStoreTest {

    @Before public void reset() { ResultStore.clearForTest(); }

    @Test public void putThenTakeReturnsTheEntryExactlyOnce() {
        ResultStore.put("salt-1", new ResultStore.Entry("12,340,0", 2, "latency: 719ms > 250ms", false));
        ResultStore.Entry e = ResultStore.take("salt-1");
        assertNotNull(e);
        assertEquals(2, e.invariantCount());
        assertNull("remove-on-read: a second take must miss", ResultStore.take("salt-1"));
    }

    // DD-040: a foreign or stale id must MISS, never return another run's entry. An unsalted
    // counter collides across two drivers or two campaigns against one long-lived target, and
    // returning stale data as fresh is worse than returning nothing.
    @Test public void unknownIdMisses() {
        assertNull(ResultStore.take("other-salt-1"));
    }

    @Test public void evictsOldestBeyondCapacityAndStaysBounded() {
        for (int i = 0; i < ResultStore.CAPACITY + 50; i++) {
            ResultStore.put("s-" + i, new ResultStore.Entry("1,2,0", 0, null, false));
        }
        assertEquals(ResultStore.CAPACITY, ResultStore.size());
        assertNull("oldest evicted", ResultStore.take("s-0"));
        assertNotNull("newest retained", ResultStore.take("s-" + (ResultStore.CAPACITY + 49)));
    }

    // The store lives inside the JVM whose heap deltas this tool reports, so its footprint is
    // part of the measurement. detail is capped like the header path already caps it.
    @Test public void detailIsCappedSoRetentionIsBounded() {
        String huge = "x".repeat(5000);
        ResultStore.put("s-cap", new ResultStore.Entry("1,2,0", 1, huge, false));
        assertTrue(ResultStore.take("s-cap").detail().length() <= 200);
    }

    @Test public void concurrentPutAndTakeDoNotCorruptTheStore() throws Exception {
        // The poll runs on a different connector thread from the boundary write and never holds
        // ITERATION_LOCK, so the store itself must be safe.
        Thread w = new Thread(() -> { for (int i = 0; i < 2000; i++)
            ResultStore.put("c-" + i, new ResultStore.Entry("1,2,0", 0, null, false)); });
        Thread r = new Thread(() -> { for (int i = 0; i < 2000; i++) ResultStore.take("c-" + i); });
        w.start(); r.start(); w.join(); r.join();
        assertTrue(ResultStore.size() <= ResultStore.CAPACITY);
    }

    @Test public void violationsTotalAccumulatesAcrossEntries() {
        ResultStore.put("v-1", new ResultStore.Entry("1,2,0", 3, null, false));
        ResultStore.put("v-2", new ResultStore.Entry("1,2,0", 4, null, false));
        assertEquals(7, ResultStore.totalViolations());
    }
}
```

Add to the **existing** `test/LoadModeControlTest.java` (package `test`, NOT `agent` — it already
contains an `unknownControlPathIsHandledNotPassedThrough` case, so do not duplicate it and do not
create a second class of the same name):

```java
    @Test public void resultEndpointReturnsTheEntryThenMisses() {
        ResultStore.clearForTest();
        ResultStore.put("s-9", new ResultStore.Entry("5,10,0", 1, "latency: 300ms > 250ms", false));
        assertTrue(LoadModeControl.handle("/__basquin/result", "id=s-9").contains("5,10,0"));
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", "id=s-9"));
    }

    @Test public void resultWithNoIdIsAMissNotAnError() {
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", null));
    }

```

Plus the tests that pin `awaitQuiescence` — **without these the whole fix silently fails in-cluster**,
because a naive implementation passes every mocked test and then misses on every committed response:

```java
    // A writer holds ITERATION_LOCK and publishes LATE, exactly as onExit does after the 25ms
    // grace sleep. The handler must WAIT for it. Delete awaitQuiescence and this test fails.
    @Test public void resultWaitsForAnInFlightIterationToPublish() throws Exception {
        ResultStore.clearForTest();
        Thread writer = new Thread(() -> {
            RequestBoundary.lockForTest();
            try { Thread.sleep(100); ResultStore.put("s-w", new ResultStore.Entry("1,2,0", 1, "x", false)); }
            catch (InterruptedException ignored) { }
            finally { RequestBoundary.unlockForTest(); }
        });
        writer.start();
        Thread.sleep(10);                        // let the writer take the lock first
        String body = LoadModeControl.handle("/__basquin/result", "id=s-w");
        writer.join();
        assertTrue("the poll must observe the late write, not race it", body.contains("1,2,0"));
    }

    @Test public void resultTimesOutToAMissRatherThanHangingTheDriver() throws Exception {
        ResultStore.clearForTest();
        Thread holder = new Thread(() -> {
            RequestBoundary.lockForTest();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            finally { RequestBoundary.unlockForTest(); }
        });
        holder.start();
        Thread.sleep(10);
        long t0 = System.nanoTime();
        assertEquals(ResultStore.MISS, LoadModeControl.handle("/__basquin/result", "id=nope"));
        assertTrue("must return within the bound", (System.nanoTime() - t0) / 1_000_000 < 2600);
        holder.interrupt(); holder.join();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ResultStoreTest*' --tests '*LoadModeControlTest*'`
Expected: FAIL — `ResultStore` does not exist.

- [ ] **Step 3: Implement**

Create `agent/ResultStore.java`. Keep it dependency-free and namespace-free — it is loaded into the target JVM.

```java
package agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DD-040: per-request measurement results, keyed by the driver's salted request id.
 *
 * <p>Exists because the response header is not a reliable channel: the boundary can only attach
 * headers if the response has not committed, and on a real app most responses have (>8KB, an
 * explicit flush, or an error-page forward). The violations were always evaluated — they were
 * being thrown away at the last hop.
 *
 * <p>Lives inside the JVM whose heap deltas this tool reports, so it is deliberately tiny and
 * bounded: {@value #CAPACITY} entries, each capped, removed on read.
 *
 * <p>Thread-safety: written by the boundary thread (under ITERATION_LOCK, which gives the write
 * its attribution) and read by a connector thread serving a control request, which holds no such
 * lock. The map is therefore synchronized here — the iteration lock is NOT the reader's guard.
 */
public final class ResultStore {

    public static final int CAPACITY = 256;
    private static final int DETAIL_MAX = 200;   // same cap the header path applies
    public static final String MISS = "miss";

    public record Entry(String costCsv, int invariantCount, String detail, boolean leakDetected) {
        public Entry {
            if (detail != null && detail.length() > DETAIL_MAX) detail = detail.substring(0, DETAIL_MAX);
        }
    }

    private static final AtomicLong TOTAL_VIOLATIONS = new AtomicLong();

    private static final Map<String, Entry> MAP =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > CAPACITY;
                }
            });

    private ResultStore() {}

    public static void put(String id, Entry e) {
        if (id == null || e == null) return;
        TOTAL_VIOLATIONS.addAndGet(e.invariantCount());
        MAP.put(id, e);
    }

    /** Returns and REMOVES the entry, or null on a miss. Remove-on-read bounds retention for a
     *  driver that polls every request, and makes a duplicate poll honestly miss. */
    public static Entry take(String id) { return id == null ? null : MAP.remove(id); }

    public static int size() { return MAP.size(); }
    public static long totalViolations() { return TOTAL_VIOLATIONS.get(); }

    /** Wire format: {@code costCsv|invariantCount|detail|leak} — FOUR fields, plaintext. {@code
     *  detail} is app-derived and may contain '|', so it is sanitised here; the driver still parses
     *  with a 4-field limit rather than a naive split. */
    public static String format(Entry e) {
        if (e == null) return MISS;
        String d = e.detail() == null ? "" : e.detail().replace('|', '/');
        return (e.costCsv() == null ? "" : e.costCsv()) + "|" + e.invariantCount() + "|" + d
                + "|" + (e.leakDetected() ? "leak" : "");
    }

    public static void clearForTest() { MAP.clear(); TOTAL_VIOLATIONS.set(0); }
}
```

In `LoadModeControl.handle`, add two cases beside `mode` and `drift`:

```java
            case "result": {
                String id = param(query, "id");
                // Bounded wait on ITERATION_LOCK: Agent.end() sleeps 25ms BEFORE measuring, so on a
                // committed response the client reaches EOF while the entry is still ~25ms away.
                // Waiting here queues the poll behind the in-flight iteration instead of racing it.
                // A timeout (not an indefinite block) so a target wedged inside the app misses
                // rather than hanging the driver.
                RequestBoundary.awaitQuiescence(2000);
                return ResultStore.format(ResultStore.take(id));
            }
            case "violations":
                return Long.toString(ResultStore.totalViolations());
```

Add to `RequestBoundary`:

```java
    // Test hooks: quiescence is only assertable if a test can hold the same lock.
    static void lockForTest() { ITERATION_LOCK.lock(); }
    static void unlockForTest() { ITERATION_LOCK.unlock(); }

    /** DD-040: wait until no explore iteration is in flight, bounded. Used only by the control
     *  path so a result poll observes the entry the in-flight iteration is about to write. */
    public static boolean awaitQuiescence(long millis) {
        try {
            if (ITERATION_LOCK.tryLock(millis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                ITERATION_LOCK.unlock();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*ResultStoreTest*' --tests '*LoadModeControlTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/ResultStore.java agent/LoadModeControl.java agent/RequestBoundary.java test/agent/ResultStoreTest.java test/agent/LoadModeControlTest.java
git commit -m "feat(agent): bounded per-request result store + /__basquin/result,violations (DD-040)"
```

---

### Task 2: Thread the request id through the boundary

**Files:**
- Modify: `agent/RequestBoundary.java`, `agent/TomcatBoundaryAdvice.java`,
  `tomcat-valve/src/main/java/com/basquin/valve/BasquinValve.java`, `agent/Agent.java`
- Test: `test/agent/RequestBoundaryIdTest.java` (create)

**Interfaces:**
- Consumes: `ResultStore` from Task 1.
- Produces: `RequestBoundary.stampRequestId(String)`, `Agent.currentContext()` (package-private,
  returns the `CURRENT` thread-local without removing it). **`onEnter` keeps its two-arg signature**
  and `endIteration()` keeps returning `void` — see Global Constraints for why both of the obvious
  changes are wrong.

- [ ] **Step 1: Write the failing test**

```java
package agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestBoundaryIdTest {

    @Before public void reset() { ResultStore.clearForTest(); }

    // The whole point: an explore request that carries an id leaves its measurements retrievable
    // even though this test never looks at a response header.
    @Test public void exploreIterationPublishesItsResultUnderTheGivenId() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
        assertEquals(RequestBoundary.Phase.EXPLORE_BEGAN, d.phase);
        RequestBoundary.stampRequestId("salt-7");           // glue stamps only after EXPLORE_BEGAN
        RequestBoundary.onExit(null);

        ResultStore.Entry e = ResultStore.take("salt-7");
        assertNotNull("the boundary must publish a result for a stamped explore request", e);
        assertNotNull("cost is always available on an explore exit", e.costCsv());
    }

    @Test public void anUnstampedRequestPublishesNothingAndStillWorks() {
        RequestBoundary.onEnter("/app/page", null);          // never stamped
        RequestBoundary.onExit(null);
        assertEquals(0, ResultStore.size());
    }

    // THE case a naive implementation loses. endIteration() THROWS on a hard-mode violation and on
    // a leak, so an entry built from its return value is skipped for exactly the violating
    // iterations -- making the "reliable" channel weaker than the header it replaces.
    @Test public void aThrowingIterationStillPublishesItsResult() {
        String prior = System.getProperty("basquin.invariant.latency.maxMs");
        System.setProperty("basquin.invariant.latency.maxMs", "0");   // hard mode, always violated
        try {
            RequestBoundary.onEnter("/app/slow", null);
            RequestBoundary.stampRequestId("salt-throw");
            RequestBoundary.ExitResult r = RequestBoundary.onExit(null);
            assertNotNull("onExit must surface the violation", r.error);
            assertNotNull("...and STILL publish the entry", ResultStore.take("salt-throw"));
        } finally {
            if (prior == null) System.clearProperty("basquin.invariant.latency.maxMs");
            else System.setProperty("basquin.invariant.latency.maxMs", prior);
        }
    }

    // The load path must gain zero instructions: no store write at all.
    @Test public void loadPassthroughNeverWritesTheStore() {
        LoadMode.setLoad(60_000L);
        try {
            RequestBoundary.Decision d = RequestBoundary.onEnter("/app/page", null);
            assertEquals(RequestBoundary.Phase.LOAD_PASSTHROUGH, d.phase);
            RequestBoundary.onExit(null);
            assertEquals(0, ResultStore.size());
        } finally {
            LoadMode.setExplore();
        }
    }

    // A control request must not begin an iteration or publish anything.
    @Test public void controlRequestPublishesNothing() {
        RequestBoundary.Decision d = RequestBoundary.onEnter("/__basquin/drift", null);
        assertEquals(RequestBoundary.Phase.CONTROL_HANDLED, d.phase);
        assertTrue(d.skipApp());
        assertEquals(0, ResultStore.size());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*RequestBoundaryIdTest*'`
Expected: FAIL — `onEnter` takes two arguments.

- [ ] **Step 3: Implement**

1. Add `RequestBoundary.stampRequestId(String reqId)` storing it in a `ThreadLocal<String>` beside
   `PHASE`. Set it **unconditionally** (a null argument overwrites), and `remove()` it in `onExit`
   with the same discipline as `PHASE` — a stale id on a pooled connector thread would publish a
   later probe's metrics under a driver id. **`onEnter` keeps its two-arg signature.**
2. Add package-private `Agent.currentContext()` returning the `CURRENT` thread-local
   (`Agent.java:54`) **without removing it**.
3. In `onExit`, capture `IterationContext ctx = Agent.currentContext()` **before** calling
   `Agent.endIteration()` — its own `finally` does `CURRENT.remove()` (`:289`), and it throws on a
   hard-mode violation or a leak, so capturing afterwards yields `null` precisely when a finding
   exists.
4. In the existing `finally`, **before `ITERATION_LOCK.unlock()`**, build the entry from that `ctx`
   and `ResultStore.put(reqId, entry)` — unconditionally, whether or not `endIteration()` threw.
   The fields are all populated before any throw: latency/heap/thread at `:133-136`,
   `invariantViolations` via `recordInvariantEvidence`, `leakDetected` at `:240`. Compose `costCsv`
   exactly as `Agent.lastCostCsv` does (see Global Constraints).
5. In **both** glue layers, read the header and stamp **only when the decision is `EXPLORE_BEGAN`**:
   ```java
   RequestBoundary.Decision d = RequestBoundary.onEnter(uri, query);
   if (d.phase == RequestBoundary.Phase.EXPLORE_BEGAN) {
       RequestBoundary.stampRequestId(request.getHeader("X-Basquin-Req"));
   }
   ```
   `Request.getHeader` is concrete, so this stays namespace-free (DD-011), and the load path reads
   no header at all.

Leave `exitHeaders()` exactly as it is. The header remains the fast path.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add agent/ tomcat-valve/ test/agent/RequestBoundaryIdTest.java
git commit -m "feat(agent): publish per-request results from IterationContext under the iteration lock (DD-040)"
```

---

### Task 3: Driver — stamp, poll, and never turn a miss into a zero

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java`, `runner/util/StatusReporter.java`
- Test: `test/runner/coverage/ReportChannelTest.java` (create)

**Interfaces:**
- Consumes: `/__basquin/result` from Task 1, the stamping contract from Task 2.
- Produces: `CoverageGuidedRun.reportMisses` (`static volatile long`), and a `CostSample` carrying
  `measured`.

- [ ] **Step 1: Write the failing test**

Drive a local `HttpServer` that mimics the target: it serves a big committed response with **no**
cost header, and a `/__basquin/result` endpoint.

```java
    // The motivating case: a response that committed, so no cost header — the driver must poll
    // and must recover the violation rather than recording a clean zero.
    @Test public void aCommittedResponseIsRecoveredByThePoll() { /* assert invariantCount==2 */ }

    // The fast path is keyed on the COST header (always present on an explore exit), not the
    // invariant header (absent on every clean request) — otherwise the driver polls every request.
    @Test public void aResponseCarryingTheCostHeaderIsNotPolled() { /* assert 0 poll hits */ }

    // A miss must NOT become a zero-filled CostSample. That reproduces the exact degeneration
    // this change exists to fix, with reportMisses ticking cheerfully beside it.
    @Test public void aMissYieldsUnmeasuredAndIncrementsReportMisses() {
        /* assert !sample.measured() && reportMisses increased && CostModel.score not applied */
    }

    // 500s are the interesting requests; the poll must still happen when request() throws.
    @Test public void aServerErrorIsStillPolled() { /* assert the entry was consumed */ }

    // THE test that makes the acceptance criterion reachable. Feeding the cost model is not
    // reporting: a violation becomes a FINDING only via FuzzIO.saveWithMeta -> recordSaved ->
    // findInvariant, which is the number Task 7 compares against the pod log. Without this the
    // reported count stays ~0 and every other test still passes.
    @Test public void aRecoveredViolationIsSavedAsAFinding() {
        /* point basquin.fuzz.resultsDir at a temp dir; poll recovers invariantCount=2;
           assert an "Invariant-Remote" meta file exists, labelled with the RAW step (DD-036),
           and that StatusReporter's findInvariant increased */
    }

    @Test public void aRecoveredLeakIsSavedAsAFinding() {
        /* entry carries the leak flag -> a "Leak-Remote" finding is saved */
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ReportChannelTest*'`

- [ ] **Step 3: Implement**

- Stamp every explore request: `c.setRequestProperty("X-Basquin-Req", reqId)` where
  `reqId = LoadRun.RUN_SALT + "-" + REQ_SEQ.getAndIncrement()`. **Never stamp the poll GET itself.**
- After the response, if `X-Basquin-Cost` **is absent**, GET `<base>/__basquin/result?id=<reqId>`
  and parse with a 4-field limit — `body.split("\\|", 4)` → `costCsv|count|detail|leak` — because
  `detail` is app-derived.
- **Poll `readTimeout` must exceed the handler's quiescence bound** (~4 s against the 2 s wait), or a
  queued poll is systematically misclassified as a miss. Copy the connect/read-timeout style at
  `CoverageGuidedRun.java:596-597`.
- Put the poll in a **`finally`**, and **swallow every poll exception inside it** — an exception
  escaping a `finally` would *replace* the in-flight `serverError` and lose the 500 finding.
- **On a recovery with `invariantCount > 0`, save the finding** exactly as the header path does at
  `:633`: `FuzzIO.saveWithMeta(label, "Invariant-Remote", "route=" + label + "\ncount=" + n + …)`,
  with the **raw step label** (DD-036). Feeding the cost model is not reporting; this is the step
  that makes the campaign's number move. Likewise a `leak` flag saves a `"Leak-Remote"` finding.
- On `MISS`, an error, or a timeout: `reportMisses++`, `measured=false`. Do **not** score, do **not**
  synthesise a zero `CostSample`.
- Explore mode follows redirects (`:605`), so a followed hop re-sends the same id and the second
  `put` overwrites the first — **final-hop-wins, matching today's header behaviour**. DD-039 owns
  per-hop semantics; do not improvise them here.
- Add `reportMisses` and a `findingsLowerBound` boolean to the explore summary in
  `StatusReporter.snapshotJson()` (`StatusReporter.java:339`) — "lower bound" must be a field the
  operator and dashboard can read, not prose. If misses are the **majority** of iterations, fail the
  run loudly rather than completing "clean".
- Read `/__basquin/violations` at run start and end and report the delta in the summary; it is what
  gives Task 7 a target-side number to compare against.

- [ ] **Step 4: Full suite** — `./gradlew test`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(explore): recover measurements via the result channel; a miss is unmeasured, not zero (DD-040)"
```

---

### Task 4: Stop reporting structural zeros — through every consumer

**Files:**
- Modify: `runner/coverage/LoadRun.java` (`summaryJson`),
  `operator/api/v1alpha1/basquincampaign_types.go`,
  `operator/internal/controller/basquincampaign_controller.go`,
  `operator/internal/controller/campaign_resources.go`, `resources/dashboard.html`
- Test: existing `LoadDriftUnavailableTest` + operator Go tests

Omitting at the driver alone is **theater** — verified: `LoadViolations.{Latency,Heap,Thread}` are
non-pointer `int32` with `omitempty`, so an omitted field unmarshals to `0`; the Ready condition
prints `"%d latency violations"` unconditionally; and the dashboard renders `(ld.heapDriftKb||0)`.
The DD-035 `driftUnavailable` precedent has the same hole — it is parsed nowhere.

- [ ] **Step 1** Tests: an omitted `violations.latency` must NOT round-trip to `0`; the Ready
  condition message must not print a count it does not have; a summary with no threshold omits the
  field.
- [ ] **Step 2** Run; expect failure.
- [ ] **Step 3** Implement: `summaryJson` omits `latency` when no threshold is configured and omits
  `heap`/`thread` always (load mode never evaluates them); operator fields become pointers or gain
  explicit unavailable markers; the condition message adapts; the dashboard stops defaulting.
  **`latencyMaxMs` for the load driver inherits from the BasquinTarget** (the reconciler already
  resolves it), with `campaign.spec.driver.invariants` overriding — the existing campaign-only
  propagation at `campaign_resources.go:86-88` is the trap that produced the zero.
- [ ] **Step 4** `./gradlew test`, then in `operator/`: **`make generate` and `make manifests`**
  before `go test ./...`. A stale `zz_generated.deepcopy.go` still *compiles* with pointer fields and
  silently shallow-copies them. Also update `basquincampaign_controller_test.go:331`
  (`Equal(int32(3))`). Note the drift half needs `LoadStatus.HeapDriftKb`/`ThreadDrift` to become
  pointers too, or the dashboard's `||0` on `heapDriftKb` cannot be fixed — shipping only the
  violations pointers would satisfy the wording while leaving the lie in place. Target inheritance
  requires passing `BasquinTarget.Spec.Invariants` (`basquintarget_types.go:116`) into
  `buildDriverJob`, i.e. a signature change. `int32`→`*int32` keeps the identical OpenAPI integer
  schema, so stored CRs are unaffected; note in Task 6 that pre-existing statuses will now read as a
  measured `0`.
- [ ] **Step 5** Commit.

---

### Task 5: Soft mode must not alter the response — and must actually record the leak

**Files:** `agent/Agent.java`, `test/agent/LeakSoftModeTest.java` (create)

**Depends on Task 2's entry-building being throw-safe.** Until this task lands, a leak still throws
out of `endIteration()`, so a leak iteration only publishes its entry because Task 2 captures the
context *before* the call — if that was implemented from the return value instead, this task's tests
will fail for a reason that looks unrelated.

Both halves are required. Gating the throw alone trades a false 500 for a **silently lost finding**:
leak evidence is not in `lastInvariantViolations` (only `Invariants` violations reach there), so it
is not in headers, and `StatusReporter.recordIteration` is a no-op in a target JVM without
`-Dbasquin.status`. It is stderr only.

- [ ] **Step 1** Tests: soft mode records a leak the driver can retrieve (via the Task 1 entry) and
  **does not throw**; hard mode still throws. Note global mode defaults to **hard**
  (`Invariants.java:82`), so gating on the existing read cannot silently disable it.
- [ ] **Step 2** Run; expect failure.
- [ ] **Step 3** Implement: gate the throw at `Agent.java:242-250`; fold `ctx.leakDetected` into the
  `ResultStore.Entry`. Decide and document whether leak honours a per-invariant
  `basquin.invariant.leak.mode` or global mode only.
- [ ] **Step 4** `./gradlew test` — the leak demo and CI must still fail loudly in hard mode.
- [ ] **Step 5** Commit.

---

### Task 6: Record and document

**Files:** `docs/DESIGN-DECISIONS.md` (append DD-040 after DD-039 if present, else after DD-038),
`docs/how-it-works.html`, `runner/CHANGELOG.md`, `deploy/bench/ONBOARDING.md`

- [ ] The DD-040 record must carry the **quantified loss** (97.3% / 75% / 0%), the three commit
  triggers, the grace-sleep race and why the lock wait is required, why ids are salted, and the
  disposition of the already-published numbers (Roller/JSPWiki invalid and re-run; JPetStore stands).
- [ ] ONBOARDING gains a check: after a campaign, compare the reported violation count against the
  target pod's `[Basquin][Invariant]` lines. A large gap means the channel is broken.
- [ ] `./gradlew test -q`; commit.

---

### Task 7: Prove it against a real app

- [ ] **Step 1** Rebuild and load agent + runner images; point the controller at them.
- [ ] **Step 2** Run a Roller explore campaign (Roller is the worst case: 97.3% loss).
- [ ] **Step 3** **Acceptance:** the campaign's reported violation count is within a small tolerance
  of the `[Basquin][Invariant]` line count in the target pod's log **for the same window**. Those two
  numbers differing by 1,906 is the bug this plan fixes.
- [ ] **Step 4** Confirm `reportMisses` is low, and that the retained corpus is no longer degenerate
  (Roller previously collapsed to 2 entries because the cost model was fed zeros).
- [ ] **Step 5** These spec verifications have no unit-test home and are **only** demonstrated here —
  name them explicitly so partial acceptance cannot be claimed: a 552-byte explicitly-flushed
  response is reported (§2), a custom-error-page status is reported (§3), and `/__basquin/result`
  never appears in coverage (§9). Also quantify the **perturbation bound** (§15): measure heap-delta
  noise with and without polling on an idle target and record it, rather than asserting it is zero.
- [ ] **Step 6** Record the measured numbers in the DD-040 record. **If the counts still disagree
  materially, STOP and report** — the fix has not been demonstrated, whatever the unit tests say.
