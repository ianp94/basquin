# Load as a first-class citizen (DD-033) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make load campaigns first-class — the dashboard updates live during a soak (throughput/percentiles/drift/5xx), the campaign list and CLI show mode-aware metrics — via the existing push path, with metrics typed for a future OTLP export.

**Architecture:** `StatusReporter` gains a `mode` + a load block; `LoadRun` feeds it live through a snapshotter that reads the live histogram + one drift poller, so the existing `DashboardClient` loop carries it. The dashboard UI and CLI select their rendering by `mode`.

**Tech Stack:** Java 17 / JUnit 4 / Gradle (runner); Go / controller-runtime (CLI); HTML/JS (dashboard); bash (e2e).

## Global Constraints

- **Explore is byte-for-byte in behavior.** `mode` defaults to `explore`; the explore JSON gains only a `"mode":"explore"` field (additive — the UI ignores unknown fields), and the explore UI/CLI paths render exactly as today.
- **One push path.** Load flows `LoadRun` → `StatusReporter` → the existing `DashboardClient` loop. No second pusher, no new endpoint.
- **Live, not terminal-only** — mid-run snapshots. **Live percentiles are approximate (torn `AtomicLongArray` reads); the terminal numbers / `status.load` are authoritative. Never add a hot-path lock to reconcile them** (would defeat DD-029 lock-free load).
- **Live throughput uses the same post-warmup window** (`measureFromNanos`) as the terminal number, so it converges to it.
- **One drift poller** — the snapshotter owns `/__basquin/drift` polling; terminal drift reuses its last poll. (Verified: `/__basquin/drift` is control-handled in the boundary — no lock, never reaches the app — so a 2s poll is negligible.)
- **Metrics OTel-typed** (spec table): request latency = histogram `basquin.load.request.duration`, unit `ms` (UCUM field), exact 1 ms × 30 000 bucket boundaries; throughput/5xx/iterations/findings = counters; drift/coverage = gauges; `campaign.id` = resource attribute, `mode` = metric attribute. **No OTel dependency added.**
- Server stays schema-agnostic — the `mode` scrape is a light regex like the existing `iterations`/`crashes`/`pct` ones.
- Bot-authored branch `feat/load-first-class` (created). `gradlew` has CRLF — normalize a copy to run builds, never stage it.

## File Structure

- `runner/util/StatusReporter.java` (modify) — `mode` + load fields; `setMode`/`recordLoad`; `snapshotJson` splices `"mode"` + a `"load"` block.
- `runner/coverage/LoadRun.java` (modify) — extract `computeLoadSnapshot(...)`; add a snapshotter thread; `setMode("load")`; one drift poller; terminal reuses it.
- `test/` (new) — `StatusReporterLoadTest`, `LoadSnapshotTest`.
- `resources/dashboard.html` (modify) — `tick()` renders a load card set when `st.mode==='load'`; `tickFleet` shows mode.
- `runner/util/DashboardServer.java` (modify) — `listCampaigns` scrapes `mode`.
- `operator/cmd/basquin/status.go` (modify) — `MODE` column + mode-aware metrics from `cp.Status.Load`.
- `operator/cmd/basquin/status_test.go` (new/modify) — CLI rendering test.
- `deploy/e2e/e2e.sh`, `docs/DESIGN-DECISIONS.md`, `TODO.md`, `runner/CHANGELOG.md` (modify).

---

### Task 1: `StatusReporter` — `mode` + load block

**Files:** Modify `runner/util/StatusReporter.java`; Test `test/StatusReporterLoadTest.java`.

**Interfaces:**
- Produces: `StatusReporter.setMode(String)`, `StatusReporter.recordLoad(double throughputRps, int p50, int p90, int p99, int max, long heapDriftKb, int threadDrift, long serverErrors, long requests)`; `snapshotJson()` now emits `"mode"` and (in load) a `"load"` block. Used by Task 2 (LoadRun) and Task 3/4 (readers).

- [ ] **Step 1: Write the failing test** — `test/StatusReporterLoadTest.java`

```java
package test;

import runner.util.StatusReporter;
import org.junit.Assert;
import org.junit.Test;

public class StatusReporterLoadTest {

    @Test public void defaultModeIsExploreAndCarriesNoLoadBlock() {
        // A fresh reporter (explore) must carry mode=explore and no "load" block.
        String j = StatusReporter.snapshotJson();
        Assert.assertTrue("explore mode tag", j.contains("\"mode\":\"explore\""));
        Assert.assertFalse("no load block in explore", j.contains("\"load\":"));
    }

    @Test public void recordLoadEmitsModeLoadAndAWellFormedLoadBlock() {
        StatusReporter.setMode("load");
        StatusReporter.recordLoad(2139.5, 12, 40, 76, 256, 15600, 3, 5, 96294);
        String j = StatusReporter.snapshotJson();
        Assert.assertTrue(j.contains("\"mode\":\"load\""));
        Assert.assertTrue(j.contains("\"throughputRps\":\"2139.5\""));
        Assert.assertTrue(j.contains("\"latencyMs\":{\"p50\":12,\"p90\":40,\"p99\":76,\"max\":256}"));
        Assert.assertTrue(j.contains("\"heapDriftKb\":15600"));
        Assert.assertTrue(j.contains("\"threadDrift\":3"));
        Assert.assertTrue(j.contains("\"serverErrors\":5"));
        Assert.assertTrue(j.contains("\"requests\":96294"));
        // reset for other tests in the JVM
        StatusReporter.setMode("explore");
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew test --tests 'test.StatusReporterLoadTest'` → FAIL (no `setMode`/`recordLoad`, no mode tag).

- [ ] **Step 3: Implement** — `runner/util/StatusReporter.java`

Add static fields (near the other statics):
```java
    // DD-033: mode + load-block state (load campaigns feed these via recordLoad).
    private static volatile String mode = "explore";
    private static double loadThroughputRps;
    private static int loadP50, loadP90, loadP99, loadMax, loadThreadDrift;
    private static long loadHeapDriftKb, loadServerErrors, loadRequests;
    private static boolean loadRecorded;
```

Add the API:
```java
    /** DD-033: campaign mode ("explore"|"load"); tags snapshotJson so the dashboard/CLI pick a view. */
    public static synchronized void setMode(String m) { mode = (m == null ? "explore" : m); }

    /** DD-033: publish the current load snapshot for the dashboard (fed live by LoadRun's snapshotter). */
    public static synchronized void recordLoad(double throughputRps, int p50, int p90, int p99, int max,
            long heapDriftKb, int threadDrift, long serverErrors, long requests) {
        loadThroughputRps = throughputRps; loadP50 = p50; loadP90 = p90; loadP99 = p99; loadMax = max;
        loadHeapDriftKb = heapDriftKb; loadThreadDrift = threadDrift; loadServerErrors = serverErrors;
        loadRequests = requests; loadRecorded = true;
    }

    /** The load block, or "" when no load snapshot has been recorded. */
    private static String loadBlockJson() {
        if (!loadRecorded) return "";
        return String.format(java.util.Locale.ROOT,
            ",\"load\":{\"throughputRps\":\"%.1f\",\"latencyMs\":{\"p50\":%d,\"p90\":%d,\"p99\":%d,\"max\":%d},"
          + "\"heapDriftKb\":%d,\"threadDrift\":%d,\"serverErrors\":%d,\"requests\":%d}",
            loadThroughputRps, loadP50, loadP90, loadP99, loadMax,
            loadHeapDriftKb, loadThreadDrift, loadServerErrors, loadRequests);
    }
```

Splice `mode` + the load block into `snapshotJson()`'s return (it currently `return String.format(... "}...}")`). Capture that into a local and splice before the final `}`:
```java
        String explore = String.format(java.util.Locale.ROOT,
            /* ...the existing format string and args, unchanged... */ );
        String extra = ",\"mode\":\"" + mode + "\"" + loadBlockJson();
        return explore.substring(0, explore.length() - 1) + extra + "}";
```
(Only the tail changes; the explore body/args are untouched — explore JSON just gains a `"mode"` field.)

- [ ] **Step 4: Run tests, verify pass** — `./gradlew test --tests 'test.StatusReporterLoadTest'` → PASS. Then `./gradlew test` (full suite green — the existing dashboard/status tests must still pass with the added `"mode"` field).

- [ ] **Step 5: Commit** — `git add runner/util/StatusReporter.java test/StatusReporterLoadTest.java && git commit -m "feat(runner): StatusReporter mode + load block (DD-033)"`

---

### Task 2: `LoadRun` feeds `StatusReporter` live (snapshotter, one drift poller)

**Files:** Modify `runner/coverage/LoadRun.java`; Test `test/LoadSnapshotTest.java`.

**Interfaces:** Consumes `StatusReporter.setMode/recordLoad` (Task 1). Produces a package-private `computeLoadSnapshot(...)` for testing.

- [ ] **Step 1: Write the failing test** — `test/LoadSnapshotTest.java`

```java
package test;

import runner.coverage.LoadRun;
import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLongArray;

public class LoadSnapshotTest {

    @Test public void snapshotMatchesTerminalComputationForTheSameHistogramState() {
        AtomicLongArray hist = new AtomicLongArray(30_002);
        // 100 requests: 50 at 10ms, 40 at 40ms, 10 at 200ms
        for (int i = 0; i < 50; i++) hist.incrementAndGet(10);
        for (int i = 0; i < 40; i++) hist.incrementAndGet(40);
        for (int i = 0; i < 10; i++) hist.incrementAndGet(200);
        long total = 100, serverErrors = 3;
        double windowSec = 2.0; // 100 req / 2s = 50 rps
        LoadRun.LoadSnapshot s = LoadRun.computeLoadSnapshot(hist, total, serverErrors, windowSec, 1500, 4);
        Assert.assertEquals(50.0, s.throughputRps, 0.001);
        Assert.assertEquals(10, s.p50);   // median at 10ms
        Assert.assertEquals(40, s.p90);
        Assert.assertEquals(200, s.p99);  // p99 lands in the 200ms bucket
        Assert.assertEquals(200, s.max);
        Assert.assertEquals(1500, s.heapDriftKb);
        Assert.assertEquals(4, s.threadDrift);
        Assert.assertEquals(3, s.serverErrors);
        Assert.assertEquals(100, s.requests);
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew test --tests 'test.LoadSnapshotTest'` → FAIL (no `LoadSnapshot`/`computeLoadSnapshot`).

- [ ] **Step 3: Implement** — `runner/coverage/LoadRun.java`

Add the snapshot value type + the pure computation (reusing the existing `percentile`/`maxBucket`):
```java
    /** A single load snapshot (DD-033) — the same fields live and terminal, so they converge. */
    public static final class LoadSnapshot {
        public final double throughputRps; public final int p50, p90, p99, max;
        public final long heapDriftKb; public final int threadDrift; public final long serverErrors, requests;
        LoadSnapshot(double t, int p50, int p90, int p99, int max, long h, int td, long se, long req) {
            this.throughputRps = t; this.p50 = p50; this.p90 = p90; this.p99 = p99; this.max = max;
            this.heapDriftKb = h; this.threadDrift = td; this.serverErrors = se; this.requests = req;
        }
    }

    /** Compute a snapshot from the LIVE histogram + counters + a drift delta. Torn reads are acceptable
     *  (approximate live percentiles); the terminal call uses the same code so live converges to terminal.
     *  Package-private for testing (no server needed). */
    static LoadSnapshot computeLoadSnapshot(AtomicLongArray hist, long total, long serverErrors,
            double windowSec, long heapDriftKb, int threadDrift) {
        double rps = total / Math.max(0.001, windowSec);
        return new LoadSnapshot(rps,
            percentile(hist, total, 0.50), percentile(hist, total, 0.90),
            percentile(hist, total, 0.99), maxBucket(hist),
            heapDriftKb, threadDrift, serverErrors, total);
    }
```

In `run()`: after `setTargetMode(baseUrl, "load", ...)` and before starting workers, `StatusReporter.setMode("load");` and add a `final java.util.concurrent.atomic.AtomicReference<Drift> lastDrift = new java.util.concurrent.atomic.AtomicReference<>();`. Start a snapshotter thread that pushes live snapshots on the **post-warmup window**, owning the drift poll:
```java
        Thread snapshotter = new Thread(() -> {
            long intervalMs = Long.getLong("basquin.dashboard.pushIntervalMs", 2000L);
            while (System.nanoTime() < deadlineNanos) {
                try { Thread.sleep(intervalMs); } catch (InterruptedException e) { break; }
                if (System.nanoTime() < measureFromNanos || !baselined.get()) continue; // not measuring yet
                long total = requests.get();
                double windowSec = (System.nanoTime() - measureFromNanos) / 1e9; // SAME window as terminal
                Drift cur = pollDrift(baseUrl);                                   // the ONE poller
                if (cur != null) lastDrift.set(cur);
                DriftDelta d = driftDelta(baselineDrift.get(), cur);
                LoadSnapshot s = computeLoadSnapshot(hist, total, serverError.get(), windowSec,
                        d.heapDriftKb, d.threadDrift);
                StatusReporter.recordLoad(s.throughputRps, s.p50, s.p90, s.p99, s.max,
                        s.heapDriftKb, s.threadDrift, s.serverErrors, s.requests);
            }
        }, "Basquin-Load-Snapshot");
        snapshotter.setDaemon(true);
        snapshotter.start();
```
After the worker `join()` loop: stop the snapshotter and compute the **terminal** drift from its last poll (one source of truth) instead of a fresh `pollDrift`:
```java
        snapshotter.interrupt();
        try { snapshotter.join(3000); } catch (InterruptedException ignored) { }
        // Terminal drift reuses the snapshotter's last poll; fall back to one poll only if it never ran.
        Drift terminalDrift = lastDrift.get();
        if (terminalDrift == null) terminalDrift = pollDrift(baseUrl);
        DriftDelta drift = driftDelta(baselineDrift.get(), terminalDrift);
```
Replace the existing `DriftDelta drift = driftDelta(baselineDrift.get(), pollDrift(baseUrl));` line with the above (the terminal JSON's `heapDrift`/`threadDrift` now come from `drift`, unchanged downstream). Push one final snapshot before `setTargetMode(..,"explore",0)` so the terminal number lands on the dashboard too:
```java
        LoadSnapshot fin = computeLoadSnapshot(hist, total, serverError.get(), measuredSec, drift.heapDriftKb, drift.threadDrift);
        StatusReporter.recordLoad(fin.throughputRps, fin.p50, fin.p90, fin.p99, fin.max, fin.heapDriftKb, fin.threadDrift, fin.serverErrors, fin.requests);
```
(Compute `measuredSec` as today; the terminal JSON build is otherwise unchanged.)

- [ ] **Step 4: Run tests, verify pass** — `./gradlew test --tests 'test.LoadSnapshotTest'` then `./gradlew test` (full suite green).

- [ ] **Step 5: Commit** — `git add runner/coverage/LoadRun.java test/LoadSnapshotTest.java && git commit -m "feat(runner): LoadRun feeds StatusReporter live via a snapshotter, one drift poller (DD-033)"`

---

### Task 3: Dashboard UI load card + campaign-list mode

**Files:** Modify `resources/dashboard.html`, `runner/util/DashboardServer.java`.

- [ ] **Step 1: `DashboardServer.listCampaigns` scrapes `mode`** — add to the per-campaign JSON in `listCampaigns` (mirroring the existing `numField` scrapes; add a string scrape helper if none exists):
```java
                    + ",\"mode\":\"" + strField(c.statusJson, "mode") + "\""
```
Add a minimal `strField(json, "mode")` returning the quoted value or `"explore"` when absent (regex `"mode"\s*:\s*"([^"]*)"`), matching the light-scrape style of `numField`.

- [ ] **Step 2: `tick()` renders a load card set when `st.mode==='load'`** — in `resources/dashboard.html`, in `tick()` where `document.getElementById('cards').innerHTML = card(...)...` is built, branch on mode:
```javascript
    const ld = st.load;
    if (st.mode === 'load' && ld) {
      const lm = ld.latencyMs || {};
      document.getElementById('cards').innerHTML =
        card('throughput', (ld.throughputRps||'0')+'/s', ld.requests+' reqs') +
        card('p50 / p90 / p99', (lm.p50||0)+' / '+(lm.p90||0)+' / '+(lm.p99||0)+' ms', 'max '+(lm.max||0)+'ms') +
        card('heap drift', (ld.heapDriftKb||0)+' KiB', 'over the soak') +
        card('thread drift', ld.threadDrift||0) +
        card('5xx', ld.serverErrors||0, 'server errors');
      document.getElementById('sub').textContent = 'campaign: '+current+' — load · '+(ld.throughputRps||0)+'/s';
    } else {
      /* ...the existing explore card('iterations'...) block, unchanged... */
    }
```
Keep the explore branch exactly as today. (The coverage card below the grid can stay; it just shows "no coverage source" for load, which is accurate — or gate it on `st.mode!=='load'` if you prefer it hidden. Gate it — cleaner.)

- [ ] **Step 3: `tickFleet` shows mode** — in the fleet list item, prefix the summary line with the mode (e.g. `c.mode==='load' ? 'load' : 'explore'`), and for load campaigns show `iters`/`crashes`/`cov` are `—` or omitted (load has no coverage). Minimal: append `' · '+(c.mode||'explore')` to the existing line so the mode is visible even before per-campaign metrics are load-aware.

- [ ] **Step 4: Manual sanity** — `./gradlew jar` builds (HTML is a resource, no compile). There's no JS unit harness in-repo; correctness is verified by the e2e (Task 5) + visual. Note this in the report.

- [ ] **Step 5: Commit** — `git add resources/dashboard.html runner/util/DashboardServer.java && git commit -m "feat(dashboard): mode-aware load card + campaign-list mode (DD-033)"`

---

### Task 4: CLI `status` — MODE column + mode-aware metrics

**Files:** Modify `operator/cmd/basquin/status.go`; Test `operator/cmd/basquin/status_test.go`.

- [ ] **Step 1: Add the failing Go test** — assert a load campaign renders `load` + rps/p99 and an explore campaign renders `explore` + cov/finds. Read `LoadStatus`'s actual field names first (`operator/api/v1alpha1/basquincampaign_types.go`, `type LoadStatus`) and use them. Sketch:
```go
func TestStatusRendersModeAwareMetrics(t *testing.T) {
    // build two BasquinCampaign objects: one explore (CoveragePct set), one load (Status.Load set),
    // render the table to a buffer, assert the load row shows "load" + the throughput/p99,
    // and the explore row shows "explore" + the coverage%/findings.
}
```
(If the file has no existing table-render seam, extract the row-formatting into a helper `campaignRow(cp) string` so it's unit-testable without a live cluster, and test that helper.)

- [ ] **Step 2: Run it, verify it fails** — `cd operator && go test ./cmd/basquin/ -run TestStatusRendersModeAware` → FAIL.

- [ ] **Step 3: Implement** — in `status.go`, change the campaign header + row. Add a `MODE` column, and make the metrics mode-aware (explore: `cov% · N finds`; load: `rps · p99ms` from `cp.Status.Load`):
```go
	fmt.Fprintln(tw, "CAMPAIGN\tTARGET\tMODE\tPHASE\tMETRICS\tDASHBOARD")
	// ...
	for i := range campaigns.Items {
		cp := &campaigns.Items[i]
		mode := cp.Spec.Mode
		if mode == "" { mode = "explore" }
		metrics := fmt.Sprintf("%s · %d finds", orNone(cp.Status.CoveragePct), cp.Status.Findings)
		if mode == "load" && cp.Status.Load != nil {
			metrics = fmt.Sprintf("%s rps · p99 %dms", cp.Status.Load.ThroughputRps, cp.Status.Load.LatencyMs.P99) // use real field names
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n", cp.Name, cp.Spec.TargetRef.Name, mode,
			orNone(string(cp.Status.Phase)), metrics, orNone(cp.Status.DashboardURL))
	}
```
Adjust the field accesses to `LoadStatus`'s real names.

- [ ] **Step 4: Run test + vet** — `cd operator && go test ./cmd/basquin/ -run TestStatusRendersModeAware && go vet ./...` → PASS.

- [ ] **Step 5: Commit** — `git add operator/cmd/basquin/status.go operator/cmd/basquin/status_test.go && git commit -m "feat(cli): mode-aware status table (MODE column + load metrics) (DD-033)"`

---

### Task 5: e2e + docs + OTel roadmap

**Files:** Modify `deploy/e2e/e2e.sh`, `docs/DESIGN-DECISIONS.md`, `TODO.md`, `runner/CHANGELOG.md`.

- [ ] **Step 1: e2e — the load campaign's dashboard received load data** — the e2e already runs a load campaign + a per-campaign dashboard and checks it's reachable. Add: query the dashboard's `/api/campaign/{id}/status` (the `campaignDetail` endpoint) for the load campaign and assert `mode=load` + a non-empty `load` block. Use the load campaign's id and the dashboard Service/pod already referenced. Example:
```bash
  dstatus="$($K -n "$NS" exec "$dashpod" -- sh -c "wget -qO- http://localhost:7070/api/campaign/$LOAD_CAMPAIGN_ID/status" 2>/dev/null || true)"
  check "DD-033: dashboard received load-mode status" "echo '$dstatus' | grep -q '\"mode\":\"load\"'"
  check "DD-033: dashboard has a non-empty load block"  "echo '$dstatus' | grep -qE '\"load\":\\{\"throughputRps\"'"
  echo "  (dashboard load status: $(echo "$dstatus" | head -c 120))"
```
Use the actual dashboard-pod/id vars the e2e already has (grep for how it reaches the dashboard for the existing "dashboard reachable" check); adapt the port/id accordingly.

- [ ] **Step 2: Syntax check** — `bash -n deploy/e2e/e2e.sh && echo OK`.

- [ ] **Step 3: Docs + OTel roadmap** — DD-033 record in `docs/DESIGN-DECISIONS.md` (mode-aware dashboard/CLI; the live-snapshotter semantics — torn reads / terminal authoritative / warmup window / one drift poller; the OTel-typed metric table incl. `basquin.load.request.duration` + unit/bucket/attribute contract; references the spec). Add the **OTel-export roadmap item** to `TODO.md` in the `### … *(roadmap)*` format: an optional OTLP metrics export (off by default), alongside — never replacing — the bespoke dashboard, tied to clustered-runners histogram merge. `runner/CHANGELOG.md`: "Added (DD-033): live load-mode dashboard (throughput/percentiles/drift/5xx), mode-aware CLI status; metrics typed for a future OTLP export."

- [ ] **Step 4: Commit** — `git add deploy/e2e/e2e.sh docs/DESIGN-DECISIONS.md TODO.md runner/CHANGELOG.md && git commit -m "test(e2e)+docs: assert load reaches the dashboard; record DD-033 + OTel roadmap"`

---

## Final step: open the PR

- [ ] Push, open a bot-authored PR "feat: load as a first-class citizen (DD-033)"; request review from @claude + the user; body: mode-aware dashboard (live during the soak, one push path) + CLI polish; the live-vs-terminal semantics (approximate live / authoritative terminal, no hot-path lock); OTel-shaped metrics with the export deferred to its own DD.

## Self-Review notes (author)

- **Spec coverage:** StatusReporter mode+load (T1), LoadRun live snapshotter + one poller + warmup window (T2), dashboard load card + list mode (T3), CLI mode-aware (T4), e2e + docs + OTel roadmap (T5). All components tasked.
- **Constraint coverage:** explore additive-only (`"mode":"explore"` field, T1); one push path (T2 feeds StatusReporter, no new pusher); torn-reads/terminal-authoritative + warmup window + one drift poller (T2); OTel-typed names/units/buckets/attributes documented (T5 DD record); server schema-agnostic scrape (T3 `strField`); CLI MODE column + `status.Load` (T4).
- **Type consistency:** `recordLoad(double,int,int,int,int,long,int,long,long)` and `computeLoadSnapshot(hist,total,serverErrors,windowSec,heapDriftKb,threadDrift)→LoadSnapshot{throughputRps,p50,p90,p99,max,heapDriftKb,threadDrift,serverErrors,requests}` consistent T1↔T2; the load JSON field names (`throughputRps`,`latencyMs.{p50,p90,p99,max}`,`heapDriftKb`,`threadDrift`,`serverErrors`,`requests`) match the dashboard reads (T3) and the e2e grep (T5).
