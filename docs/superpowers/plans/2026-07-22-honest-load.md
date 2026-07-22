# Honest Load (DD-035) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Hardened against `main`** (line numbers/signatures verified). Where a step says "verify against the file," the implementer reads the current code — do not trust remembered line numbers blindly.

**Goal:** Make load-mode replay faithful (method-, session-, sequence-aware) and its drift measurement honest (no silent `heapDriftKb:0`), so replaying a fuzz-found corpus stops manufacturing 5xx/timeouts and captures the heap-fatigue curve.

**Architecture:** A new `RequestLine` **record** carries `method/path/body` and hosts static sequence helpers; a corpus line is a TAB-separated ordered sequence of steps (a bare `/…` line = a 1-step GET sequence, backward-compatible). Explore emits whole sequences; each `LoadRun` worker replays a random sequence in order with its own cookie jar. Drift failure becomes a first-class `driftUnavailable` signal instead of a fake `0`.

**Tech Stack:** Java 17, JUnit4, plain `HttpURLConnection` + JDK `com.sun.net.httpserver` for tests (no new deps). Ships in ONE PR, task-by-task commits, on branch `feat/honest-load-dd035`.

## Global Constraints

- **Corpus line format v2:** `STEP( TAB STEP )*`, `STEP = METHOD? path( SP body )?`; a step with no method token = `GET`; bodies are `application/x-www-form-urlencoded` (no literal SP/TAB). A bare `/…` line = 1-step GET sequence (**backward-compatible** with all existing corpora + seed files).
- **No `Sequence` class** (collides with `RequestGrammar.Sequence`). Sequence helpers are STATIC on `RequestLine`: `parseSequence(String)→List<RequestLine>`, `formatSequence(List<RequestLine>)→String`, `firstPath(String)→String`.
- The line filter that excludes grammar value files (`values/*.txt`) must still work: a corpus line is valid iff `RequestLine.firstPath(line).startsWith("/")`. This REPLACES the current `t.startsWith("/")` checks at `LoadRun.readCorpus` and `CoverageGuidedRun.loadSeeds:75` (the latter silently drops `POST /…` lines today — that is the bug).
- Keep fields package-private; **in-package tests live under `test/runner/coverage/`** (`package runner.coverage`), matching `CostRankedReplayTest`. Root `test/*.java` are default-package/`package test` and can only reach `public` members — do not put package-private tests there.
- No operator/Go changes (`ReplayCorpus []string` + `\n`-join is format-agnostic; `LoadStatus.HeapDriftKb` is `omitempty`; Go ignores unknown JSON keys). No CRD changes.
- Byte budget: `REPLAY_CORPUS_MAX_BYTES=3000` inside `TERMINATION_MSG_BUDGET=3900`. `replayCorpusJson` ALREADY truncates by whole entries and appends cost-descending — once a sequence is one TAB-joined string, whole-sequence truncation is free (Task 6 = a pinning test only). Note `\t` escapes to `\\t` (2 budget bytes).
- Drift honesty: assert *sampled-ness*, never `drift>0` (GC can make drift ≤0; a clean run showed −753MB).

---

### Task 1: `RequestLine` record + sequence statics

**Files:** Create `runner/coverage/RequestLine.java`; Test `test/runner/coverage/RequestLineTest.java` (`package runner.coverage`).

**Produces:** `record RequestLine(String method, String path, String body)` with:
- `static RequestLine parse(String step)` — split step on first SP into head + body(nullable); if head's first token ∈ `{GET,POST,PUT,DELETE,PATCH,HEAD}` take it as method else `GET`; path = head remainder.
- `String format()` — `(method.equals("GET")?"":method+" ") + path + (body==null?"":" "+body)`.
- `static List<RequestLine> parseSequence(String line)` — split on `\t`, each field via `parse`.
- `static String formatSequence(List<RequestLine> seq)` — `\t`-join of `format()`.
- `static String firstPath(String line)` — `parse(firstTabField).path()` if it starts with `/`, else `""`.

- [ ] **Step 1: Failing tests** — use this exact body:
```java
package runner.coverage;
import org.junit.Test; import java.util.List; import static org.junit.Assert.*;
public class RequestLineTest {
  @Test public void barePathIsGetWithNoBody() {
    RequestLine r = RequestLine.parse("/actions/Catalog.action");
    assertEquals("GET", r.method()); assertEquals("/actions/Catalog.action", r.path()); assertNull(r.body());
  }
  @Test public void methodPrefixAndBodyAreSplit() {
    RequestLine r = RequestLine.parse("POST /actions/Account.action?signon= username=j2ee&password=j2ee");
    assertEquals("POST", r.method()); assertEquals("/actions/Account.action?signon=", r.path());
    assertEquals("username=j2ee&password=j2ee", r.body());
  }
  @Test public void parseFormatRoundTrips() {
    for (String s : new String[]{"/x","GET /x?a=b","POST /y z=1&w=2",
        "POST /actions/Account.action?signon= username=j2ee&password=j2ee"})
      assertEquals(RequestLine.parse(s), RequestLine.parse(RequestLine.parse(s).format()));
    assertEquals("/x?a=b", RequestLine.parse("GET /x?a=b").format()); // GET canonicalizes bare
  }
  @Test public void sequenceSplitsOnTabAndJoinsBack() {
    List<RequestLine> seq = RequestLine.parseSequence("POST /a b=1\t/c\tGET /d");
    assertEquals(3, seq.size()); assertEquals("b=1", seq.get(0).body()); assertEquals("GET", seq.get(1).method());
    assertEquals("POST /a b=1\t/c\t/d", RequestLine.formatSequence(seq));
  }
  @Test public void firstPathDrivesTheCorpusLineFilter() {
    assertEquals("/a", RequestLine.firstPath("POST /a b=1\tGET /c"));
    assertEquals("/x", RequestLine.firstPath("/x"));
    assertEquals("", RequestLine.firstPath("cat"));
  }
}
```
- [ ] **Step 2: Run → FAIL** (class missing). **Step 3: Implement** the record + statics. **Step 4: Run → PASS.** **Step 5: Commit** `feat(runner): RequestLine record + sequence helpers for v2 corpus (DD-035)`.

---

### Task 2: `LoadRun.readCorpus` → sequences (+ GET-only worker shim so the commit stays green)

**Files:** Modify `runner/coverage/LoadRun.java` (`readCorpus` ~251-267; decl/fallback ~33-37; log ~44-45; worker pick ~91-93). Test `test/runner/coverage/LoadCorpusV2Test.java`.

**Change:** `readCorpus` returns `List<List<RequestLine>>`: walk files, per non-blank line keep iff `RequestLine.firstPath(line).startsWith("/")`, then `RequestLine.parseSequence(line)`. In `run()` fix the fallback + effective-finality (the worker lambda captures `corpus`): `List<List<RequestLine>> read = readCorpus(dir); final List<List<RequestLine>> corpus = read.isEmpty() ? List.of(List.of(new RequestLine("GET","/",null))) : read;`. Update the `"%d route(s)"` log to sequence count. **Worker shim for THIS commit only:** pick `corpus.get(rnd.nextInt(...))` and `for (RequestLine s : seq) fire(baseUrl, s.path());` against the still-`GET` `fire` — keeps the build green until Task 3.

- [ ] **Step 1: Failing test** (`LoadCorpusV2Test`): write a `corpus.txt` with a bare GET line, a 2-step `POST…\tPOST…` line, a single-step `POST /actions/Account.action?signon= u=j2ee` line (old filter would DROP it), and value lines `cat`/`dog`; assert `readCorpus` returns 3 sequences with the right step counts/methods.
- [ ] **Step 2: FAIL.** **Step 3: Implement** the sequence reader + fallback + shim. **Step 4: PASS.** **Step 5: Commit** `feat(load): readCorpus parses v2 sequences; keep value-file exclusion`.

---

### Task 3: `LoadRun.fire` (method+body+session) + sequence-in-order worker loop

**Files:** Modify `runner/coverage/LoadRun.java` (`fire` 168-191 — drop `private`→ package-private `static int fire(String base, RequestLine step, Map<String,String> jar)`; worker loop 88-108). Test `test/runner/coverage/LoadFireTest.java`.

**Change:** `fire` issues `step.method()`; if `body!=null`: `setDoOutput(true)`, `Content-Type: application/x-www-form-urlencoded`, write body; send `Cookie` from `jar`; capture `Set-Cookie: JSESSIONID=…` into `jar` (strip attributes at first `;`); drain; return status or -1. Worker: each worker owns `Map<String,String> jar = new HashMap<>()`; pick a random sequence, fire its steps **in order** through `jar`; **check the deadline between steps** (a long sequence must not overshoot the window); metrics (`hist`, `requests`, `serverError`, baseline CAS, warmup gate) record **per step** exactly as today.

- [ ] **Step 1: Failing tests** — use a JDK `com.sun.net.httpserver.HttpServer` on port 0 (`@Before` start / `@After` stop): (a) `fire(base, RequestLine.parse("POST /signon u=j2ee&p=j2ee"), new HashMap<>())` → handler sees method=POST, `Content-Type: application/x-www-form-urlencoded`, body `u=j2ee&p=j2ee`; (b) step1 sets `Set-Cookie: JSESSIONID=abc123; Path=/`, step2 asserts `Cookie: JSESSIONID=abc123` from the same `jar`. (Full bodies per the design review's LoadFireTest.)
- [ ] **Step 2: FAIL.** **Step 3: Implement.** **Step 4: PASS.** **Step 5: Commit** `feat(load): method+body+session replay; sequences run in order per worker`.

---

### Task 4: Explore issuer honors method + body

**Files:** Modify `runner/coverage/CoverageGuidedRun.java` (`request` 478-530 — make package-private `static CostSample request(String base, String step)`, keep the `(base, String)` signature so call sites at ~:237/:438/:464 don't churn; hardcoded `setRequestMethod("GET")` at ~:482). Test `test/runner/coverage/ExploreRequestTest.java`.

**Change:** inside `request`, `RequestLine r = RequestLine.parse(step)`; issue `r.method()`+`r.body()` (like Task-3 `fire`); preserve JSESSIONID handling (:484-497) and cost/invariant header parsing. `runSequence` then becomes method-aware for free.

- [ ] Step 1: failing HttpServer test (POST+body reaches stub via `request(base,"POST /signon u=1")`). Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(explore): issue method+body (not GET-only) for corpus entries`.

---

### Task 5: Grammar accepts an optional method prefix

**Files:** Modify `runner/coverage/RequestGrammar.java` (sequence-step gate :82, route gate :105, `templateFor` :210-225). Test `test/runner/coverage/GrammarMethodTest.java`.

**Change:** both gates recognize an optional `METHOD ` before `/` (indented `@sequence` steps AND top-level routes). `templateFor` needs no logic change if templates + concretes both carry the prefix (`Pattern.quote` handles SP/body) — pin with the test. `mutate`/`replaceParam` untouched here.

- [ ] Step 1: failing test — grammar with `POST /actions/Cart.action?addItemToCart=&workingItemId={item}` yields a POST request filling the template; `templateFor` maps it back. Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(grammar): optional METHOD prefix on routes/sequence steps`.

---

### Task 6: Emission — whole-sequence + method-aware + multi-step seeds + mutation guard

**Files:** Modify `runner/coverage/CoverageGuidedRun.java` (sequence emit :199; `writeSummary` 328-363; `replayCorpusJson` 370-391; `loadSeeds` 64-90 incl. filter :75; the seed/fresh feed at ~:211-212/:225 and the `pendingSequences` init ~:171-172; `mutate` :624). Tests extend `test/runner/coverage/CostRankedReplayTest.java` / `ReplayCorpusJsonTest.java`.

**Three coupled changes:**
1. **Whole-sequence emit:** at :199 replace `corpus.consider(sequence.get(sequence.size()-1), …)` with `corpus.consider(RequestLine.formatSequence(sequence), …)` (whole ordered sequence, still `coverageFind=true`).
2. **Multi-step seeds must not hit `request(base, input)`** (a TAB-joined line → garbage URL). In `loadSeeds`/`main`: change the :75 filter to `RequestLine.firstPath`, then split each seed line — single-step → the `seeds` list (fired via `request`); multi-step (`line.indexOf('\t')>=0`) → `RequestLine.parseSequence` → merged into `pendingSequences` and run by the existing sequence branch (:186-201). **`pendingSequences` init must stop being gated on `grammar != null`** so a grammar-less run can replay seeded sequences.
3. **Mutation guard (replay-only sequences):** at `mutate` (:624) add `if (input.indexOf('\t') >= 0) return input;` — matches the method's existing "un-mutatable input returned as-is" contract and prevents `replaceParam` (:638-643) from scanning past `\t` and splicing/deleting steps. `RequestGrammar.mutate` needs no change (its `templateFor` fallback already yields a fresh single request for a multi-step string). `CostCorpus` dedup (LinkedHashSet, string-equality) + eviction (cost/pheromone, coverageFind-immune) are already sequence-safe — no change.

`replayCorpusJson` truncation is **already whole-entry** → no code change; add a pinning test only.

- [ ] Step 1: failing tests — (a) a kept 3-step sequence emits as a 3-field TAB line (not the bare tail); (b) a multi-step entry that doesn't fit the byte budget is dropped whole, array stays valid JSON; (c) round-trip: emitted corpus re-parses via `loadSeeds` into the same sequences and a multi-step line lands in `pendingSequences`; (d) `mutate("POST /a x=1\tPOST /b", rnd)` returns the input unchanged. Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(explore): emit whole cost-ranked sequences; seeds route through pendingSequences; mutation is replay-only for sequences (DD-035)`.

---

### Task 7: `driftUnavailable` hardening

**Files:** Modify `runner/coverage/LoadRun.java` (`setTargetMode` 237-248 → package-private `boolean`, compare body to `"ok:load"`; baseline path 95-101 + `pollDrift` 224-235; extract a package-private `static String summaryJson(long total,double rps,int p50,int p90,int p99,int max,DriftDelta drift,long serverErr,long latViol,boolean driftUnavailable)` from :142-152; snapshotter 65-83; terminal 120-152). Modify `runner/util/StatusReporter.java` (`recordLoad` :132-137 + `loadBlockJson` :142-149, mode gate kept). Tests: new `test/runner/coverage/LoadDriftUnavailableTest.java`; UPDATE `test/StatusReporterLoadTest.java` (`package test`, the 9-arg `recordLoad` call at :23) for the new arg + assert the key.

**Change:** track `driftUnavailable = (baseline poll never succeeded) || (toggle != ok:load)`. When true, `summaryJson` and `status.load` emit `"driftUnavailable":true` and OMIT `heapDriftKb`/`threadDrift`; else emit heap/thread (may be ≤0), no flag. `setTargetMode` logs one clear line on mismatch/failure (not silent). Thread the flag through `recordLoad`'s two call sites (:78-79 snapshotter, :138-139 terminal).

- [ ] Step 1: failing tests — `LoadDriftUnavailableTest`: `summaryJson(...,driftUnavailable=true)` contains `"driftUnavailable":true` and NOT `"heapDriftKb":0`; `...=false` has heap present, no flag. Update `StatusReporterLoadTest` for the new signature + assert the key appears iff set. Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(load): surface driftUnavailable instead of a fake heapDriftKb:0 (DD-035)`.

---

### Task 8: Seed corpus + grammar data (JPetStore)

**Files:** Modify `examples/grammar/jpetstore.grammar` (mark POST handlers: cart mutations, signon, newAccount/editAccount, newOrder), `examples/corpus/jpetstore/*.txt` (prefix POST routes; add one realistic `signon→addItemToCart→newOrder` TAB sequence file).

- [ ] Step 1: update grammar + seed files (catalog reads stay GET). Step 2: `./gradlew test` green (v2 parses). Step 3: Commit `feat(examples): mark JPetStore POST handlers + a stateful seed sequence`.

---

### Task 9: e2e drift assertions + docs + DD-035 record

**Files:** Modify `deploy/e2e/e2e.sh` (route-count grep :385; load asserts 455-457), `docs/LOAD-MODE-DESIGN.md`, `docs/OPERATOR-USAGE.md`, `docs/DESIGN-DECISIONS.md` (DD-035 record).

**Change:**
- Grep `grep -c '^/'` → `grep -cE '^([A-Z]+ )?/'` (counts v1, method-prefixed, and sequence lines).
- After the load campaign assert: (a) driver log has NO `mode load toggle failed`; (b) the driver log's `[Basquin] load done: {…}` JSON does NOT contain `"driftUnavailable":true` (grep the Job log — there is NO `status.load.driftUnavailable` CRD field, so do not use `kubectl jsonpath`); (c) `curl http://jpetstore-app.<ns>.svc:8080/__basquin/drift` over Service DNS returns 200 **while load mode is active**. Assert sampled-ness, not `>0`.
- Docs: format v2, whole-sequence replay, per-worker session, `driftUnavailable`. DD-035 record with rejected alternatives (JSON-lines corpus; blank-line sequence blocks; dedicated drift port).

- [ ] Step 1: update `e2e.sh`. Step 2: docs + DD-035. Step 3: Commit `test(e2e): assert drift sampled over Service DNS in load mode; docs + DD-035 for format v2`.

---

## Notes for the executor

- **Task ordering:** 1→2→3 must be consecutive (Task 2's shim keeps the build green; Task 3 removes it). 4,5 independent. 6 depends on 1 (+ benefits from 5). 7 independent of the corpus tasks. 8 after 4-6. 9 last.
- **Whole-branch final review must check:** (1) backward-compat — an old bare-path corpus replays as GET 1-step sequences; (2) value-file exclusion holds via `firstPath`; (3) no sequence corruption — `mutate` returns TAB inputs unchanged AND multi-step seeds never reach `request(base,line)`; (4) drift honesty — no path emits a fake `0`; (5) `pendingSequences` init is no longer gated on `grammar != null`.
