# Response Correlation (DD-036) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Spec: `docs/superpowers/specs/2026-07-22-response-correlation-design.md`. Line numbers are from the DD-035-era code — verify against the file before editing.

**Goal:** Capture a dynamic value (CSRF token, `_sourcePage`) from one response and substitute it into a later request in a replay sequence, so fuzz/replay can reach write paths guarded by response-derived tokens.

**Architecture:** A step gains an optional trailing capture suffix `<<name=kind:arg`; a later step's body references `${{name}}`. `Capture` extracts (response header or HTML hidden-input regex) into a per-sequence-execution `Map<String,String> bindings`; the body is substituted (URL-encoded) at fire time. Corpus v3, fully backward-compatible; the corpus stores the recipe, never the token.

**Tech Stack:** Java 17, JUnit4, `HttpURLConnection` + JDK `com.sun.net.httpserver` for tests. One PR, task-by-task commits, on branch `feat/response-correlation-dd036` (spec already committed there).

## Global Constraints

- **Corpus v3 step:** `METHOD? path( SP body )?( SP <<name=kind:arg )?`. Reference `${{name}}` in a later step's body. `kind:arg` ∈ `header:Header-Name` | `input:FIELD_NAME`. One capture per step; substitution in body only (MVP).
- **Backward-compatible:** a step with no `<<`/`${{` parses + fires byte-for-byte as today (v1/v2 corpora untouched); the no-capture runtime path is the existing code.
- **The corpus stores the recipe, never the captured value** (session-bound; also no credential-in-ConfigMap).
- Captured values are **never mutated** — pass-through only; the payload stays fuzzable.
- Bindings scope = one sequence execution (per-worker in load, per-`runSequence` in explore); never shared, never cached across executions (token-rotation correctness); never allocated for uncorrelated sequences.
- Keep fields package-private; tests in `package runner.coverage` under `test/runner/coverage/`.
- No operator/CRD changes. DD-036 is the next DD number.

---

### Task 1: `Capture` model + extractors

**Files:** Create `runner/coverage/Capture.java`; Test `test/runner/coverage/CaptureTest.java`.

**Produces:** `record Capture(String name, Kind kind, String arg)` (`enum Kind { HEADER, INPUT }`) with:
- `static Capture parse(String token)` — `"<<csrf=input:X-XSRF-TOKEN"` → Capture; returns null on malformed (no `<<`, bad name, unknown kind).
- `String format()` — inverse (`"<<csrf=input:X-XSRF-TOKEN"`).
- `String extract(java.util.function.Function<String,String> headerLookup, String body)` — HEADER: `headerLookup.apply(arg)`; INPUT: regex over `<input …>` where `name`==arg → its `value`, HTML-entity-unescaped (`&amp; &quot; &lt; &gt; &#39;`); null on miss.

- [ ] **Step 1: Failing tests** — `parse`/`format` round-trip for both kinds; `parse` returns null on `"/x"`, `"<<bad"`, `"<<n=nope:x"`; INPUT `extract` from `<input type="hidden" name="X-XSRF-TOKEN" value="abc+/=">` (→ `abc+/=`), value-before-name order, single vs double quotes, entity unescape (`value="a&amp;b"` → `a&b`), miss when field absent; HEADER `extract` via a stub `Function`.
- [ ] **Step 2: FAIL** (class missing). **Step 3: Implement** (two precompiled regexes for INPUT: one for the `<input …>` tag, one for the `name`/`value` attrs, tolerant of attr order + quote style). **Step 4: PASS.** **Step 5: Commit** `feat(runner): Capture model — header + hidden-input extractors (DD-036)`.

---

### Task 2: `RequestLine` v3 — optional capture component

**Files:** Modify `runner/coverage/RequestLine.java`; Test `test/runner/coverage/RequestLineV3Test.java`.

**Change:** add a 4th record component `Capture capture` (nullable); keep a 3-arg convenience constructor (`capture=null`) so all existing call sites/tests compile. `parse`: if the step's LAST space-separated token starts with `<<`, split it off → `Capture.parse` (malformed → logged once, treated as absent) before the existing method/path/body logic. `format`: append ` `+`capture.format()` when present. Add `boolean needsSubstitution` (precomputed: `body != null && body.contains("${{")`), exposed via accessor.

- [ ] **Step 1: Failing tests** — `parse("POST /Edit.jsp page=X&t=${{csrf}} <<csrf=input:X-XSRF-TOKEN")` → method POST, path `/Edit.jsp`, body `page=X&t=${{csrf}}`, capture name=csrf; `format()` round-trips; a v2 line (no capture) → capture null, byte-identical format; `needsSubstitution` true iff body has `${{`; backward-compat: bare `/x` and `GET /x?a=b` unchanged.
- [ ] **Step 2: FAIL.** **Step 3: Implement.** **Step 4: PASS** (+ existing `RequestLineTest` still green). **Step 5: Commit** `feat(runner): RequestLine v3 optional capture suffix (DD-036)`.

---

### Task 3: `RequestGrammar.expand` skips `${{…}}` (MVP-CRITICAL)

**Files:** Modify `runner/coverage/RequestGrammar.java` (`expand` ~236-250); Test `test/runner/coverage/GrammarCorrelationTest.java`.

**Change:** `expand` currently treats `${{csrf}}` as placeholder `{csrf` (no rule → expands to `""`), silently destroying the reference. Add a two-char lookahead: on seeing `${`, if the next char is also `{`, copy through the matching `}}` verbatim and continue (do NOT rule-substitute). `${param}` (single brace) is unchanged.

- [ ] **Step 1: Failing test** — a grammar route/sequence step body `X-XSRF-TOKEN=${{csrf}}&page=${pageName}` after `expand` still contains `${{csrf}}` verbatim AND `${pageName}` was rule-substituted. **Step 2: FAIL** (`${{csrf}}` becomes empty). **Step 3: Implement** the lookahead. **Step 4: PASS** (+ existing `RequestGrammarTest`/`GrammarMethodTest` green). **Step 5: Commit** `fix(grammar): expand preserves ${{name}} correlation refs verbatim (DD-036)`.

---

### Task 4: substitution helper + capture in `LoadRun`

**Files:** Modify `runner/coverage/LoadRun.java` (worker loop ~88-125; `fire` ~168-268; counters ~52-55; summary ~142-205). Test `test/runner/coverage/LoadCorrelationTest.java` (JDK `HttpServer`).

**Changes:**
- New package-private `static String substitute(String body, Map<String,String> bindings)` — replace each `${{name}}` with `URLEncoder.encode(bindings.get(name), UTF_8)`; a missing binding → return null (signal "unresolvable"). Unit-test it directly (URL-encoding of `+`/`=`).
- Worker loop: `Map<String,String> bindings = seq.correlated ? new HashMap<>(4) : null` (seq wrapper carries `correlated` = any step has a capture or a `${{`); per step, if `step.needsSubstitution()`, resolve the body first — unresolvable → **step not fired**, `captureMisses++`, continue. Warmup gate / baseline CAS / metric recording unchanged.
- `fire`: new signature carrying `bindings` (or a small context). After `getResponseCode()`+`captureSessionCookie()`: no capture → today's drain loop unchanged; capture present → read body into a 256KB-capped buffer, `Capture.extract(c::getHeaderField, body)`, `bindings.put` on success / `captureMisses++` on null.
- Counters: add `AtomicLong captureMisses` and `AtomicLong clientErrors` (increment when `code>=400 && code<500`); emit both in the summary JSON.

- [ ] **Step 1: Failing tests** (HttpServer): (a) `substitute` URL-encodes; (b) end-to-end — GET handler returns `<input name="X-XSRF-TOKEN" value="tok123">`, POST handler asserts it received `X-XSRF-TOKEN=tok123` in the body; run a 2-step correlated sequence through one worker's bindings and assert the POST got the captured token; (c) a capture miss (field absent) → step not fired, `captureMisses` incremented; (d) uncorrelated sequence unaffected (fast path).
- [ ] **Step 2: FAIL.** **Step 3: Implement.** **Step 4: PASS** (+ full suite). **Step 5: Commit** `feat(load): response correlation — capture + substitute in replay; captureMisses/clientErrors (DD-036)`.

---

### Task 5: capture in `CoverageGuidedRun` (explore)

**Files:** Modify `runner/coverage/CoverageGuidedRun.java` (`runSequence` ~492-504; `request` ~536-603). Test `test/runner/coverage/ExploreCorrelationTest.java`.

**Change:** `runSequence` creates a per-run `bindings` map, parses each step to `RequestLine` once, substitutes before firing (skip-and-count unresolvable), passes the map into `request`. New overload `request(base, step, bindings)`; existing `request(base, step)` delegates with `null` (so `login()` + the single-step main-loop call are unchanged). When the step carries a capture and `code<500`, keep the body (256KB cap) and `Capture.extract`. The 5xx path (crash finding) is unchanged.

- [ ] **Step 1: Failing test** (HttpServer): `runSequence` over a GET-form→POST-save sequence captures the token and the POST receives it; a single-step `request(base,step)` with a capture parses but is inert (nothing consumes it). **Step 2: FAIL.** **Step 3: Implement.** **Step 4: PASS.** **Step 5: Commit** `feat(explore): response correlation in runSequence/request (DD-036)`.

---

### Task 6: grammar data — correlated write sequences

**Files:** Modify `examples/grammar/jspwiki.grammar` (add the `edit_save` sequence); `examples/grammar/jpetstore.grammar` (add a `_sourcePage`-correlated order sequence).

**Change (data only):**
- jspwiki `@sequence edit_save`: `/Edit.jsp?page=${pageName} <<csrf=input:X-XSRF-TOKEN` → `POST /Edit.jsp page=${pageName}&action=save&X-XSRF-TOKEN=${{csrf}}&_editedtext=${wikitext}` → `/Wiki.jsp?page=${pageName}`. Add `$pageName`/`$wikitext` value rules if absent.
- jpetstore `@sequence order_with_sourcepage`: signon → add-to-cart → checkOut → `POST /actions/Order.action?newOrderForm= <<src=input:_sourcePage` → `POST /actions/Order.action?newOrder= _sourcePage=${{src}}`.

- [ ] **Step 1:** author the sequences. **Step 2:** `./gradlew test` green (grammar parses; `GrammarCorrelationTest` from Task 3 covers the ${{}} preservation). **Step 3: Commit** `feat(examples): correlated write sequences (JSPWiki edit-save, JPetStore _sourcePage) (DD-036)`.

---

### Task 7: DD-036 record + docs

**Files:** `docs/DESIGN-DECISIONS.md` (DD-036 record); `docs/LOAD-MODE-DESIGN.md` (corpus v3 note); `runner/CHANGELOG.md` (Unreleased entry).

**Change:** DD-036 record summarizing the problem (dynamic-token write paths unreachable), the corpus-v3 syntax, extractors, the recipe-not-token property, `captureMisses`/`clientErrors`, and rejected alternatives (HTML parser/CSS, JSON-lines, recording live tokens, auto-CSRF-forward, capture-once caching). Note `driftUnavailable`-style honesty: `clientErrors` surfaces correlation failure.

- [ ] Step 1: DD-036 record + doc notes + changelog. Step 2: Commit `docs: DD-036 response correlation record + corpus v3 note`.

---

## Notes for the executor

- **Ordering:** 1→2 first (model + parse). 3 independent (grammar guard). 4 depends on 1,2. 5 depends on 1,2 (+ mirrors 4's substitute helper). 6 after 3,4,5. 7 last.
- **Whole-branch final review must check:** (1) backward-compat — a v1/v2 corpus/grammar with no `<<`/`${{` behaves byte-for-byte as before; (2) the corpus emits the recipe, never a captured value (Task 5 emission path); (3) captured values are never fed to mutation; (4) `expand` preserves `${{}}` (Task 3); (5) no-capture fast path allocates nothing / scans nothing; (6) URL-encoding of `+`/`=` in substituted values; (7) `clientErrors` counts 4xx (correlation-failure visibility).
- **Proof (post-merge, separate):** re-onboard JSPWiki with the `edit_save` sequence; arm B (reads + correlated writes) should show save POSTs at 2xx/3xx with `captureMisses≈0`/`clientErrors≈0` and write-path availability cost vs arm A's cached reads.
