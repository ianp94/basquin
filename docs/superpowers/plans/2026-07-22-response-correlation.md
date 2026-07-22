# Response Correlation (DD-036) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> **Hardened against `main`** (line numbers/signatures verified; the spec/plan branch touches no code, so `main` IS the current code). Spec: `docs/superpowers/specs/2026-07-22-response-correlation-design.md`.

**Goal:** Capture a dynamic value (CSRF token, `_sourcePage`) from one response and substitute it into a later request in a replay sequence, so fuzz/replay reaches write paths guarded by response-derived tokens.

**Architecture:** A step gains an optional trailing `<<name=kind:arg` capture suffix; a later step's body references `${{name}}`. `Capture` extracts (response header or HTML hidden-input regex) into a per-sequence-execution `Map<String,String> bindings`; the body is substituted (URL-encoded) at fire time. Corpus v3, backward-compatible; the corpus stores the recipe, never the token.

**Tech Stack:** Java 17, JUnit4, `HttpURLConnection` + JDK `com.sun.net.httpserver` for tests. One PR, task-by-task commits, on `feat/response-correlation-dd036`.

## Global Constraints

- **Corpus v3 step:** `METHOD? path( SP body )?( SP <<name=kind:arg )?`; reference `${{name}}` in a later step's body. `kind:arg` ∈ `header:Header-Name` | `input:FIELD_NAME`. One capture/step; body-only substitution (MVP).
- **Backward-compatible:** no `<<`/`${{` → byte-for-byte today's behavior; the no-capture runtime path is the existing code.
- **`RequestLine.format()` MUST re-emit the capture suffix.** `CoverageGuidedRun.splitSeeds:130` round-trips via `parseSequence→format`, so a dropped suffix silently strips the recipe on the *second* onboarding. This is the DD-035-class round-trip trap — Task 2 owns it, with an explicit `formatSequence(parseSequence(line))==line` test.
- **The corpus stores the recipe, never the captured value.** Captured values are **never mutated** (pass-through); the payload stays fuzzable.
- Bindings scope = one sequence execution (declared inside the worker lambda per outer-while iteration in load; per-`runSequence` in explore). Never shared, never cached across executions. **Uncorrelated sequences allocate nothing** — precompute a `correlated` flag at `readCorpus` time and a `needsSubstitution` field on `RequestLine` (not a live `body.contains` in the hot loop).
- **Keep `fire`/`request` existing arities as delegating overloads** (null bindings) so `LoadFireTest`/`ExploreRequestTest`/`login()`/main-loop are untouched.
- **DD-036 is terminal-summary-only.** Do NOT touch `StatusReporter.recordLoad`/`loadBlockJson` — that changes the 10-arg signature and breaks both LoadRun call sites + `StatusReporterLoadTest` (the DD-035 breakage pattern). Only `LoadRun.summaryJson` gains fields.
- Keep fields package-private; in-package tests in `package runner.coverage` under `test/runner/coverage/`. No operator/CRD changes. Next DD = DD-036.

---

### Task 1: `Capture` model + extractors

**Files:** Create `runner/coverage/Capture.java`; modify `runner/coverage/CoverageGuidedRun.java` (relax `unescapeHtml` 679-695 from `private` → package-private `static` so `Capture` can reuse it); Test `test/runner/coverage/CaptureTest.java`.

**Produces:** `record Capture(String name, Kind kind, String arg)` (`enum Kind { HEADER, INPUT }`), `static Capture parse(String)` (null on malformed), `String format()`, `String extract(java.util.function.Function<String,String> headerLookup, String body)` (HEADER→`headerLookup.apply(arg)`; INPUT→regex `<input …>` where `name`==arg → `value`, unescaped via `CoverageGuidedRun.unescapeHtml`; null on miss).

- [ ] **Step 1: Failing tests** — parse/format round-trip both kinds; parse null on `"/x"`,`"<<bad"`,`"<<n=nope:x"`; INPUT extract from `<input type="hidden" name="X-XSRF-TOKEN" value="abc+/=">`→`abc+/=`, value-before-name order, single/double quotes, entity unescape `value="a&amp;b"`→`a&b`, miss when absent; HEADER via a stub `Function`.
- [ ] **Step 2: FAIL. Step 3: Implement** (two precompiled regexes for INPUT; relax `unescapeHtml` visibility). **Step 4: PASS** (+ existing suite green — visibility relax is safe). **Step 5: Commit** `feat(runner): Capture model — header + hidden-input extractors (DD-036)`.

---

### Task 2: `RequestLine` v3 — optional capture component + round-trip

**Files:** Modify `runner/coverage/RequestLine.java` (record at **line 8**; `parse` 11-57, `format` 63-65). Test `test/runner/coverage/RequestLineV3Test.java`.

**Change:** add 4th record component `Capture capture` (nullable); **add a 3-arg convenience ctor `RequestLine(m,p,b){ this(m,p,b,null); }`** so all 8 three-arg sites (7 in RequestLine + `LoadRun.java:38`) compile untouched. Extract current body logic into `private static RequestLine parseCore(String)` (capture=null); `parse` peels the capture first: `int ls=step.lastIndexOf(' '); if(ls>=0 && step.startsWith("<<",ls+1)){ cap=Capture.parse(step.substring(ls+1)); step=step.substring(0,ls); }` then `RequestLine r=parseCore(step); return new RequestLine(r.method(),r.path(),r.body(),cap);` (malformed cap→null, log once). `format`: append ` `+`capture.format()` when present. Add `boolean needsSubstitution` (precomputed `body!=null && body.contains("${{")`) + accessor. `firstPath` still works (peels the suffix). Space-in-body collision is safe (urlencoded bodies can't contain literal ` <<`).

- [ ] **Step 1: Failing tests** — parse a POST step with `${{csrf}}` body + `<<csrf=input:X-XSRF-TOKEN` suffix → correct method/path/body/capture; **round-trip `formatSequence(parseSequence(line))==line`** for a line carrying both `<<…` and `${{…}}` (the recipe-preservation guard); a v2 line → capture null, byte-identical format; `needsSubstitution` iff body has `${{`; backward-compat bare `/x` / `GET /x?a=b`.
- [ ] Step 2: FAIL. Step 3: Implement. Step 4: PASS (+ `RequestLineTest` green — 4-arg record with capture=null compares equal). Step 5: Commit `feat(runner): RequestLine v3 capture suffix; format re-emits it (DD-036)`.

---

### Task 3: `RequestGrammar.expand` preserves `${{…}}` (MVP-CRITICAL)

**Files:** Modify `runner/coverage/RequestGrammar.java` (`expand` 236-250). Test `test/runner/coverage/GrammarCorrelationTest.java`.

**Change:** insert immediately after the `close < 0` guard (~line 243), before `out.append(template,i,open)`:
```java
if (open + 2 < template.length() && template.charAt(open + 2) == '{') {
    int dbl = template.indexOf("}}", open + 2);
    if (dbl < 0) { out.append(template, i, template.length()); break; }
    out.append(template, i, dbl + 2);   // copy pre-text AND ${{name}} verbatim
    i = dbl + 2; continue;
}
```
`templateFor` (214-229) + `mutate` (204-207, TAB-guard at 702) need **no** change (verified). `expand` is private — the test exercises it via `expandAll`/`randomSequence`/`expandAllSequences` on a loaded grammar (like `RequestGrammarTest`).

- [ ] Step 1: failing test — a grammar step body `X-XSRF-TOKEN=${{csrf}}&page=${page}` after expansion still contains `${{csrf}}` verbatim AND `${page}` resolved. Step 2: FAIL (`${{csrf}}`→empty). Step 3: implement. Step 4: PASS (+ `RequestGrammarTest`/`GrammarMethodTest` green). Step 5: Commit `fix(grammar): expand preserves ${{name}} correlation refs (DD-036)`.

---

### Task 4: substitute helper + capture + counters in `LoadRun`

**Files:** Modify `runner/coverage/LoadRun.java` (counters 52-55; worker loop 93-128; `fire` 218-268; `summaryJson` 192-205 + call sites 169-172). Test `test/runner/coverage/LoadCorrelationTest.java` (JDK `HttpServer`, per `LoadFireTest`).

**Changes:**
- `static String substitute(String body, Map<String,String> bindings)` — replace each `${{name}}` with `URLEncoder.encode(bindings.get(name), UTF_8)`; missing binding → return null. Unit-tested directly (`+`/`=` encoding).
- Declare `final AtomicLong captureMisses` + `final AtomicLong clientErrors` next to `serverError` (52-55).
- Worker loop: `Map<String,String> bindings = seq.correlated ? new HashMap<>(4) : null` inside the lambda, per outer-while iteration. Per step, after the deadline break (107) and before `t0` (108): if `step.needsSubstitution()`, `String b=substitute(step.body(),bindings); if(b==null){ if(nanoTime>=measureFromNanos) captureMisses.incrementAndGet(); continue; }` and fire with `b`. Warmup gate/CAS/recording unchanged. In the record block (~120): `else if (code>=400 && code<500) clientErrors.incrementAndGet();`.
- **New `fire` overload** `fire(base, RequestLine step, Map jar, Map bindings, AtomicLong captureMisses)`; keep 3-arg `fire(base,step,jar)` delegating (null,null). After `getResponseCode()`+`captureSessionCookie()`: no capture → today's drain (252-255) unchanged; capture present → read body retaining ≤256KB **but drain to EOF** (keep-alive), `Capture.extract(c::getHeaderField, body)`, `bindings.put` or `captureMisses.incrementAndGet()`.
- `summaryJson`: +2 params (`long captureMisses,long clientErrors`) + 2 JSON keys; update both call sites (169-172) and the test caller `LoadDriftUnavailableTest.java:20,35`.
- **Ordering lint** (fold in here): at `readCorpus`/wrapper build, scan each sequence once, log-once if a `${{name}}` has no preceding `<<name=`.

- [ ] Step 1: failing tests (HttpServer) — (a) `substitute` URL-encodes; (b) GET returns `<input name="X-XSRF-TOKEN" value="tok123">`, POST asserts body got `X-XSRF-TOKEN=tok123`, run a 2-step correlated sequence through one worker's bindings; (c) miss (field absent) → step not fired, `captureMisses`++; (d) **uncorrelated 2-step sequence takes the today-identical path (no alloc)**. Step 2: FAIL. Step 3: Implement. Step 4: PASS (+ full suite; `LoadDriftUnavailableTest` updated). Step 5: Commit `feat(load): response correlation — capture+substitute; captureMisses/clientErrors (DD-036)`.

---

### Task 5: capture in `CoverageGuidedRun` (explore)

**Files:** Modify `runner/coverage/CoverageGuidedRun.java` (`runSequence` 492-504; `request` 536-603, body block 588-601). Test `test/runner/coverage/ExploreCorrelationTest.java`.

**Change:** `runSequence` creates a per-run `bindings` map, parses each step to `RequestLine` once, substitutes before firing (skip-and-count unresolvable), passes the map into `request`. **New overload `request(base, step, bindings)`; keep 2-arg `request(base,step)` delegating null** (so callers 295/496/522/523 + `ExploreRequestTest:55` untouched). `request` already drains the whole body via the readLine loop (588-598) and appends only for 5xx — extend to also append when `capture!=null && code<500 && len<262144`, then `Capture.extract`; the 5xx throw path (599-601) unchanged.

- [ ] Step 1: failing HttpServer test — `runSequence` over GET-form→POST-save captures the token and the POST receives it; a single-step `request(base,step)` with a capture parses but is inert. Step 2: FAIL. Step 3: Implement. Step 4: PASS. Step 5: Commit `feat(explore): response correlation in runSequence/request (DD-036)`.

---

### Task 6: grammar data — correlated write sequences

**Files:** Modify `examples/grammar/jspwiki.grammar` (has `$page`/`$query` only, **no `$pageName`/`$wikitext`, no `@sequence` yet**); `examples/grammar/jpetstore.grammar`.

**Change (data):**
- jspwiki: add a `$wikitext = ~[a-z ]{20,120} | <string>` rule (reuse existing `$page` for page names — do NOT invent `$pageName`), then `@sequence edit_save`: `/Edit.jsp?page=${page} <<csrf=input:X-XSRF-TOKEN` → `POST /Edit.jsp page=${page}&action=save&X-XSRF-TOKEN=${{csrf}}&_editedtext=${wikitext}` → `/Wiki.jsp?page=${page}`.
- jpetstore `@sequence order_with_sourcepage`: signon → addItemToCart → checkOut → `POST /actions/Order.action?newOrderForm= <<src=input:_sourcePage` → `POST /actions/Order.action?newOrder= _sourcePage=${{src}}`.

- [ ] Step 1: author the sequences (verify placeholder names resolve against defined rules). Step 2: `./gradlew test` green. Step 3: Commit `feat(examples): correlated write sequences (JSPWiki edit-save, JPetStore _sourcePage) (DD-036)`.

---

### Task 7: DD-036 record + docs

**Files:** `docs/DESIGN-DECISIONS.md` (DD-036 record — latest is DD-035, confirmed); `docs/LOAD-MODE-DESIGN.md` (corpus v3 note); `runner/CHANGELOG.md`.

- [ ] Step 1: DD-036 record (problem, v3 syntax, extractors, recipe-not-token, `captureMisses`/`clientErrors` honesty, rejected alternatives: HTML parser/CSS, JSON-lines, recording live tokens, auto-CSRF-forward, capture-once caching). Step 2: Commit `docs: DD-036 response correlation record + corpus v3 note`.

---

## Notes for the executor

- **Ordering:** 1→2 (model+parse). 3 independent. 4 depends on 1,2. 5 depends on 1,2. 6 after 3,4,5. 7 last.
- **captureMisses warmup gate:** increment only when `nanoTime>=measureFromNanos` (parity with `requests`/`serverError`).
- **Whole-branch final review must check:** (1) backward-compat — v1/v2 corpus/grammar byte-for-byte unchanged; (2) `format()` re-emits the capture suffix (round-trip); (3) captured values never reach mutation; (4) `expand` preserves `${{}}`; (5) uncorrelated fast path allocates/scans nothing; (6) `+`/`=` URL-encoded in substitutions; (7) `clientErrors` counts 4xx; (8) StatusReporter untouched.
- **Proof (post-merge):** re-onboard JSPWiki with `edit_save`; arm B (reads + correlated writes) → save POSTs 2xx/3xx, `captureMisses≈0`/`clientErrors≈0`, write-path cost vs arm A's cached reads.
