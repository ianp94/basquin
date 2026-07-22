# Honest Load (DD-035) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make load-mode replay faithful (method-, session-, and sequence-aware) and its drift measurement honest (no silent `heapDriftKb:0`), so replaying a fuzz-found corpus stops manufacturing 5xx/timeouts and captures the heap-fatigue curve.

**Architecture:** A new `RequestLine` value type carries `method/path/body`; a corpus line is a TAB-separated ordered `Sequence` of `RequestLine`s (a bare `/…` line = a 1-step GET sequence, backward-compatible). Explore emits whole sequences; each `LoadRun` worker replays a random sequence in order with its own cookie jar. Drift failure becomes a first-class `driftUnavailable` signal instead of a fake `0`.

**Tech Stack:** Java 17, JUnit4, plain `HttpURLConnection` (no new deps). Ships in ONE PR, task-by-task commits, on branch `feat/honest-load-dd035`.

## Global Constraints

- Corpus line format v2: `STEP( TAB STEP )*`, `STEP = METHOD? path( SP body )?`; a step with no method token = `GET`; bodies are `application/x-www-form-urlencoded` (no literal SP/TAB). A bare `/…` line = 1-step GET sequence (**backward-compatible** with all existing corpora + seed files).
- The line filter that excludes grammar value files (`values/*.txt`) must still work: a corpus line is valid iff its first step's path (after an optional `METHOD ` prefix) starts with `/`.
- Keep fields package-private; tests live in `package runner.coverage` (matching `CostRankedReplayTest`/`LoadModeTest`). No visibility widening for tests.
- No operator/Go changes (the `ReplayCorpus []string` + `\n`-join is format-agnostic). No CRD changes.
- Byte budget: emitted replay corpus stays under `REPLAY_CORPUS_MAX_BYTES=3000` inside the `TERMINATION_MSG_BUDGET=3900` message; truncate by whole sequences (never emit a partial sequence).
- Drift honesty: assert *sampled-ness*, never `drift>0` (GC can legitimately make drift ≤0; a clean run showed −753MB).

---

### Task 1: `RequestLine` + `Sequence` model

**Files:**
- Create: `runner/coverage/RequestLine.java`
- Test: `test/RequestLineTest.java` (`package runner.coverage`)

**Interfaces (Produces):**
- `RequestLine.parse(String step) -> RequestLine` — `"POST /x?a=b username=j2ee"` → method=POST, path=`/x?a=b`, body=`username=j2ee`; `"/x"` → method=GET, path=`/x`, body=null.
- `RequestLine.format() -> String` — inverse; GET with null body formats as bare `path`.
- `RequestLine.method()/path()/body()` accessors.
- `Sequence.parse(String line) -> List<RequestLine>` — TAB-split, each field via `RequestLine.parse`.
- `Sequence.format(List<RequestLine>) -> String` — TAB-join.
- `Sequence.firstPath(String line) -> String` — first step's path (for the corpus line filter), or `""` if unparseable.

- [ ] **Step 1: Write the failing tests**

```java
package runner.coverage;
import org.junit.Assert; import org.junit.Test; import java.util.List;
public class RequestLineTest {
  @Test public void barePathIsGet() {
    RequestLine r = RequestLine.parse("/actions/Catalog.action");
    Assert.assertEquals("GET", r.method());
    Assert.assertEquals("/actions/Catalog.action", r.path());
    Assert.assertNull(r.body());
  }
  @Test public void methodAndBody() {
    RequestLine r = RequestLine.parse("POST /actions/Account.action?signon username=j2ee&password=j2ee");
    Assert.assertEquals("POST", r.method());
    Assert.assertEquals("/actions/Account.action?signon", r.path());
    Assert.assertEquals("username=j2ee&password=j2ee", r.body());
  }
  @Test public void formatRoundTrips() {
    for (String s : new String[]{"/x", "GET /x?a=b", "POST /y z=1&w=2"})
      Assert.assertEquals(s.startsWith("/") ? "/"+s.substring(1) : s,
          RequestLine.parse(s).format().replaceFirst("^GET ","").equals("/x") ? "/x" : RequestLine.parse(s).format());
  }
  @Test public void sequenceSplitsOnTab() {
    List<RequestLine> seq = Sequence.parse("POST /a b=1\tGET /c\t/d");
    Assert.assertEquals(3, seq.size());
    Assert.assertEquals("/a", seq.get(0).path());
    Assert.assertEquals("GET", seq.get(2).method());
    Assert.assertEquals("POST /a b=1\tGET /c\t/d".replace("GET /c","/c").replace("GET /d","/d"),
        Sequence.format(seq).replaceAll("GET (/[^\t]*)","$1"));
  }
  @Test public void firstPathForFilter() {
    Assert.assertEquals("/a", Sequence.firstPath("POST /a b=1\tGET /c"));
    Assert.assertEquals("/x", Sequence.firstPath("/x"));
    Assert.assertEquals("", Sequence.firstPath("cat"));   // grammar value line → excluded
  }
}
```

- [ ] **Step 2: Run to verify fail** — `RequestLine`/`Sequence` don't exist → compile FAIL.
- [ ] **Step 3: Implement `RequestLine` + `Sequence`** — parse rules: split step on first SP into (head, body?); if head's first token is an uppercase method (`GET|POST|PUT|DELETE|PATCH|HEAD`) split it off else method=GET; path is the remainder of head; body is the SP-tail or null. `format()`: `(method=="GET"?"":method+" ") + path + (body==null?"":" "+body)`. `Sequence` splits/joins on `\t`. `firstPath` returns `parse(firstField).path()` starting with `/` else `""`.
- [ ] **Step 4: Run tests → PASS.**
- [ ] **Step 5: Commit** — `feat(runner): RequestLine + Sequence model for method/sequence-aware corpus (DD-035)`.

---

### Task 2: `LoadRun.readCorpus` → sequences (v2, backward-compatible)

**Files:**
- Modify: `runner/coverage/LoadRun.java` (`readCorpus` ~251-267)
- Test: `test/LoadCorpusV2Test.java` (`package runner.coverage`)

**Interfaces:**
- Consumes: `Sequence.parse`, `Sequence.firstPath` (Task 1).
- Produces: `readCorpus(String dir) -> List<List<RequestLine>>` (a list of sequences). Empty → caller falls back to a single `[GET /]` sequence.

**Change:** replace the `List<String>` route reader with a `List<List<RequestLine>>` sequence reader. Keep the file-walk; per non-blank line, keep it iff `Sequence.firstPath(line).startsWith("/")` (this preserves the value-file exclusion), then `Sequence.parse(line)`.

- [ ] **Step 1: Failing test**

```java
package runner.coverage;
import org.junit.*; import java.util.*; import java.nio.file.*;
public class LoadCorpusV2Test {
  @Test public void parsesSequencesAndSkipsValueLines() throws Exception {
    Path d = Files.createTempDirectory("corpus");
    Files.write(d.resolve("corpus.txt"), String.join("\n",
      "/actions/Catalog.action",                                   // 1-step GET (back-compat)
      "POST /actions/Cart.action?addItemToCart=&workingItemId=EST-3\tPOST /actions/Order.action?newOrder=", // 2-step
      "cat", "dog"                                                  // grammar values → excluded
    ).getBytes());
    List<List<RequestLine>> seqs = LoadRun.readCorpus(d.toString());
    Assert.assertEquals(2, seqs.size());
    Assert.assertEquals(1, seqs.get(0).size());
    Assert.assertEquals("GET", seqs.get(0).get(0).method());
    Assert.assertEquals(2, seqs.get(1).size());
    Assert.assertEquals("POST", seqs.get(1).get(1).method());
  }
}
```

- [ ] **Step 2: Run → FAIL** (readCorpus returns `List<String>`; compile error).
- [ ] **Step 3: Implement** the sequence reader (signature change to `List<List<RequestLine>>`). Update `run()`'s empty-corpus fallback to `List.of(List.of(new RequestLine("GET","/",null)))`.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(load): readCorpus parses v2 sequences, keeps value-file exclusion`.

---

### Task 3: `LoadRun.fire` (method+body+session) & sequence-in-order worker loop

**Files:**
- Modify: `runner/coverage/LoadRun.java` (`fire` 168-191; worker loop 85-114)
- Test: `test/LoadFireTest.java` (`package runner.coverage`) using a `com.sun.net.httpserver.HttpServer` stub (Tomcat-free; matches how other server-touching tests stub HTTP).

**Interfaces:**
- Consumes: `RequestLine` (Task 1), sequences (Task 2).
- `fire(String base, RequestLine step, Map<String,String> jar) -> int` — issues `step.method()` to `base+step.path()`; if body != null: `setDoOutput(true)`, `Content-Type: application/x-www-form-urlencoded`, write body bytes; send `Cookie` from `jar`; capture `Set-Cookie` `JSESSIONID` into `jar`; drain body; return status (or -1).
- Worker loop: each worker owns `Map<String,String> jar = new HashMap<>()`; each iteration picks `seqs.get(rnd.nextInt(seqs.size()))` and fires each step **in order** through `jar`; metrics (`hist`, `requests`, `serverError`, baseline drift CAS) record **per step** exactly as today.

- [ ] **Step 1: Failing tests** — spin a local `HttpServer`: (a) a POST handler asserts it received method=POST + the body; (b) a handler sets `Set-Cookie: JSESSIONID=abc` on step 1 and asserts `Cookie: JSESSIONID=abc` on step 2 of the same jar. (Full test body written against the new `fire` signature.)
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `fire(base, RequestLine, jar)` + rework the worker loop to iterate a sequence's steps in order with a per-worker jar. Warmup gate, `measureFromNanos`, and the drift baseline CAS stay per-step.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** — `feat(load): method+body+session replay, sequences run in order per worker`.

---

### Task 4: Explore issuer honors method + body (`CoverageGuidedRun.request`)

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java` (`request` ~478-530)
- Test: extend/add `test/ExploreRequestTest.java` (`package runner.coverage`) with an `HttpServer` stub asserting POST+body issued.

**Change:** `request()` currently hardcodes `setRequestMethod("GET")` (~:483) and already carries JSESSIONID (~:484-497). Take a `RequestLine` (or method+path+body) and issue method+body like Task-3 `fire`. Preserve cost/invariant header parsing and the existing session handling.

- [ ] Step 1: failing test (POST+body reaches the stub). Step 2: FAIL. Step 3: implement method/body in `request()`. Step 4: PASS. Step 5: Commit `feat(explore): issue method+body (not GET-only) for corpus entries`.

---

### Task 5: Grammar accepts an optional method prefix

**Files:**
- Modify: `runner/coverage/RequestGrammar.java` (route/sequence-step recognition ~82,105)
- Test: `test/GrammarMethodTest.java` (`package runner.coverage`)

**Change:** route templates and sequence steps are recognized by `line.startsWith("/")`. Generalize to accept an optional leading `METHOD ` before the `/`. `templateFor(...)` matching must still resolve a request to its template regardless of the method prefix. Mutation (`mutate`/`replaceParam`) continues to treat the string opaquely (a method prefix rides along).

- [ ] Step 1: failing test — a grammar with `POST /actions/Cart.action?addItemToCart=&workingItemId={item}` produces a `POST` request whose body/path fills the template; `templateFor` maps it back. Step 2: FAIL. Step 3: implement prefix acceptance in `load()` + `templateFor`. Step 4: PASS. Step 5: Commit `feat(grammar): optional METHOD prefix on routes/sequence steps`.

---

### Task 6: Emission — whole-sequence, method-aware, byte-budgeted

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java` (sequence emit ~199; `writeSummary` ~328-363; `replayCorpusJson` ~370-391; `loadSeeds` ~64-90)
- Test: extend `test/CostRankedReplayTest.java` / `test/ReplayCorpusJsonTest.java`

**RISK to resolve in this task:** the corpus `input` becomes a TAB-joined multi-step sequence. Confirm that `CostCorpus.consider(...)` keying, dedup, and **explore mutation** treat a multi-step input safely (mutate a whole sequence string vs per-step). If mutation would corrupt a sequence, gate sequence inputs out of mutation (replay-only), matching the DD-031 "bias only on replay" precedent. The implementer MUST read the current mutation path and state which behavior it chose; the reviewer verifies no sequence corruption.

**Change:**
- Emit the WHOLE ordered sequence as one `Sequence.format(...)` line (not `sequence.get(size-1)` at ~:199).
- `loadSeeds` parses v2 (a seed line may be a multi-step sequence) so an emitted replay corpus round-trips as a seed corpus.
- `replayCorpusJson` truncates by whole sequences against `REPLAY_CORPUS_MAX_BYTES` (drop lowest-cost whole sequences; never a partial).

- [ ] Step 1: failing tests — (a) a kept 3-step sequence emits as a 3-field TAB line, not the bare tail; (b) truncation drops whole sequences, never partial; (c) round-trip: emitted corpus re-parses via `loadSeeds` into the same sequences. Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(explore): emit whole cost-ranked sequences to the replay corpus (DD-035)`.

---

### Task 7: `driftUnavailable` hardening (LoadRun + StatusReporter)

**Files:**
- Modify: `runner/coverage/LoadRun.java` (`setTargetMode` 238-248; `pollDrift`/baseline path 95-101,225-235; summary JSON 142-152; snapshotter 66-81)
- Modify: `runner/util/StatusReporter.java` (load block `recordLoad`/`loadBlockJson`)
- Test: extend `test/LoadRunDriftTest.java`

**Change:**
- `setTargetMode`: read the response body; return/record whether it equals `ok:load`; log a clear line on mismatch/failure (not silent).
- Track a `driftUnavailable` boolean: true iff the baseline drift poll never succeeded (or the toggle failed). When true, the summary JSON and `status.load` emit `"driftUnavailable":true` and OMIT (or null) `heapDriftKb`/`threadDrift` instead of `0`.
- `StatusReporter.recordLoad` gains a `driftUnavailable` flag threaded into `loadBlockJson()` (keep the existing mode-gate).

- [ ] Step 1: failing test — with a never-succeeding drift poll, the summary contains `"driftUnavailable":true` and NOT `"heapDriftKb":0`; with a succeeding poll, no `driftUnavailable` and heap present (may be ≤0). Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(load): surface driftUnavailable instead of a fake heapDriftKb:0 (DD-035)`.

---

### Task 8: Seed corpus + grammar data (JPetStore)

**Files:**
- Modify: `examples/grammar/jpetstore.grammar` (mark POST handlers: cart mutations, signon, newAccount/editAccount, newOrder)
- Modify: `examples/corpus/jpetstore/*.txt` (prefix POST routes; optionally add a realistic signon→addToCart→order sequence file)

**Change:** data only (verified by Tasks 4-6 tests + e2e). Mark exactly the Stripes form handlers as POST; leave catalog reads as GET.

- [ ] Step 1: update grammar + seed files. Step 2: `./gradlew test` still green (format changes parse). Step 3: Commit `feat(examples): mark JPetStore POST handlers + a stateful seed sequence`.

---

### Task 9: e2e drift assertions + docs

**Files:**
- Modify: `deploy/e2e/e2e.sh` (route-count grep ~:386; load-campaign asserts ~441-457)
- Modify: `docs/LOAD-MODE-DESIGN.md` (§ corpus format v2 + sequence replay), `docs/OPERATOR-USAGE.md` (replay note)

**Change:**
- Route-count grep tolerates the v2 format (count sequence lines, method-prefixed).
- After the load campaign, assert: (a) driver log has NO `mode load toggle failed`; (b) `driftUnavailable` is false / drift was sampled; (c) `curl http://jpetstore-app.<ns>.svc:8080/__basquin/drift` over Service DNS returns 200 **while load mode is active** (not idle, not localhost-only). Assert sampled-ness, not `>0`.
- Docs: describe format v2, whole-sequence replay, per-worker session, and the `driftUnavailable` signal.

- [ ] Step 1: update `e2e.sh` asserts + grep. Step 2: update docs. Step 3: Commit `test(e2e): assert drift is sampled over Service DNS in load mode; docs for format v2 (DD-035)`.

---

## Notes for the executor

- The DD-035 record in `docs/DESIGN-DECISIONS.md` (with rejected alternatives: JSON-lines corpus, blank-line sequence blocks, dedicated drift port) is added as part of Task 9's docs commit.
- Whole-branch final review must specifically check: (1) backward-compat — an old bare-path corpus still replays as GET 1-step sequences; (2) the value-file exclusion still holds; (3) no sequence corruption via mutation (Task 6 risk); (4) drift honesty — no path emits a fake `0`.
