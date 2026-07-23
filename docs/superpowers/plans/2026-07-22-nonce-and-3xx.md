# DD-038: `<nonce>` generator + Location-classified 3xx counters — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every replayed write body unique (a `<nonce>` grammar generator → per-fire token) so change-detecting apps don't no-op it, and make redirect outcomes visible in the load summary.

**Architecture:** `<nonce>` is a grammar generator emitting a collision-proof fire-time marker `${{@nonce}}` that `LoadRun.substitute` fills per-fire with `<millis>x<pid>-<counter>`; substitution widens to the full request line (path+body) at every fire site. `LoadRun.fire` stops following redirects and returns a `FireResult(code, location)` so the load worker can classify `3xx` targets.

**Tech Stack:** Java 17, JUnit 4, Gradle. Package `runner.coverage`. No new dependencies.

## Global Constraints

- **Marker/name discipline:** `<nonce>` is a generator primitive; its wire marker is `${{@nonce}}`. `@` is not in `Capture.NAME_PATTERN` (`[A-Za-z0-9_]+`) → collision-proof; it reserves nothing in the `${{}}` correlation namespace.
- **`RUN_SALT` = `currentTimeMillis()` + `"x"` + `ProcessHandle.current().pid()`** (JDK 17). Token `<RUN_SALT>-<counter>` (a shared `AtomicLong` from 0). Unique within a run (counter) and across runs/parallel JVMs (salt). URL-safe (unreserved chars), spliced verbatim (model A).
- **Substitution is null-safe:** the path is never null; the body can be null. Substitute the path unconditionally; guard the body. Skip+captureMiss only on a genuine unbound `${{ref}}`.
- **`fire` no-follow is load-only:** `CoverageGuidedRun` (explore) keeps following redirects.
- **`LoadFireTest` calls the `int fire(...)` overload and must stay untouched** — add a *distinct method* `fireR` (Java can't overload on return type).
- **`summaryJson` must stay valid, bounded JSON** — the operator drops the whole summary on invalid/over-4 KB JSON. `redirectTargets`: ≤12 keys, top-N emitted, each key JSON-escaped/charset-restricted.
- Build/test: `./gradlew test --tests runner.coverage.<Test>` for one class, `./gradlew test` for the full suite. All existing tests stay green at every task boundary.

---

### Task 1: `<nonce>` generator + `substitute` `@nonce` branch + `RUN_SALT`

Makes `${{@nonce}}` in a **body** fill with a unique per-fire token. Path substitution is Task 2.

**Files:**
- Modify: `runner/coverage/RequestGrammar.java` (`generator()` switch, ~line 337) + its class Javadoc generator list (~lines 31-34)
- Modify: `runner/coverage/LoadRun.java` (`substitute`, lines 345-359; add `RUN_SALT`/`NONCE` statics near `CORRELATION_REF_PATTERN`, line 337)
- Test: `test/runner/coverage/LoadCorrelationTest.java` (substitute nonce), `test/runner/coverage/GrammarCorrelationTest.java` (generator emits marker; it has the temp-grammar helpers)

**Interfaces:**
- Produces: grammar generator `<nonce>` → the literal string `${{@nonce}}`. `LoadRun.substitute(body, bindings)`: a `${{@nonce}}` ref is replaced with `RUN_SALT + "-" + NONCE.getAndIncrement()` (never returns null for it); other refs unchanged.

- [ ] **Step 1: Write the failing tests**

Add to `test/runner/coverage/LoadCorrelationTest.java`:
```java
@Test public void substituteFillsNonceUniquelyAndNeedsNoBinding() {
    String a = LoadRun.substitute("x=${{@nonce}}", new java.util.HashMap<>());
    String b = LoadRun.substitute("x=${{@nonce}}", new java.util.HashMap<>());
    assertNotNull(a); assertNotNull(b);
    assertTrue(a.startsWith("x=")); assertFalse(a.contains("${{"));   // marker gone
    assertNotEquals("each fire's nonce differs", a, b);
}
@Test public void nonceDoesNotMaskAnUnboundRealRef() {
    // @nonce fills, but an unbound ${{csrf}} still makes the step skip (null)
    assertNull(LoadRun.substitute("a=${{@nonce}}&t=${{csrf}}", new java.util.HashMap<>()));
}
```
Add to `test/runner/coverage/GrammarCorrelationTest.java` (package `runner.coverage`) — it already has the private `write(String)` / `load(String)` temp-grammar helpers this needs:
```java
@Test public void nonceGeneratorEmitsTheFireTimeMarker() throws IOException {
    RequestGrammar g = load("$rev = <nonce>\n/edit?r=${rev}\n");
    assertEquals("<nonce> defers to the fire-time marker, not a baked value",
            "/edit?r=${{@nonce}}", g.randomRequest());
}
```
**Do NOT put this in `RequestGrammarTest`** — that file is `test/RequestGrammarTest.java` in package `test` (FQN `test.RequestGrammarTest`) with different helpers, so `--tests runner.coverage.RequestGrammarTest` would match nothing and `writeGrammar(...)` wouldn't compile.

- [ ] **Step 2: Run — must FAIL**

Run: `./gradlew test --tests runner.coverage.LoadCorrelationTest --tests runner.coverage.GrammarCorrelationTest`
Expected: FAIL — `${{@nonce}}` returns null / stays literal; generator returns `""` (unknown token) or the literal `<nonce>`.

- [ ] **Step 3: Add the `<nonce>` generator**

In `runner/coverage/RequestGrammar.java` `generator()`'s `switch`, add a case (next to `<empty>`):
```java
case "<nonce>":
    // A per-FIRE unique value, evaluated at replay time — NOT frozen here. Emit the fire-time
    // marker LoadRun.substitute fills. `@` can't be a Capture name, so it can't collide with a
    // ${{name}} capture ref. (DD-038)
    return "${{@nonce}}";
```
Add `<nonce>` to the class Javadoc generator list (~line 33): `<li>{@code <nonce>} — a per-fire-unique token (for non-idempotent write payloads)</li>`.

- [ ] **Step 4: Add the `@nonce` branch + salt to `substitute`**

In `runner/coverage/LoadRun.java`, near line 337 add the statics:
```java
// DD-038: a per-fire unique payload token. RUN_SALT is per-process (millis + pid, so two driver
// JVMs starting in the same millisecond still differ); NONCE is the within-run counter.
static final String RUN_SALT =
        Long.toString(System.currentTimeMillis()) + "x" + Long.toString(ProcessHandle.current().pid());
static final java.util.concurrent.atomic.AtomicLong NONCE = new java.util.concurrent.atomic.AtomicLong();
```
Rewrite the `substitute` loop body so `@nonce` is generated (never a null-return):
```java
while (m.find()) {
    String name = m.group(1);
    out.append(body, last, m.start());
    if (name.equals("@nonce")) {
        out.append(RUN_SALT).append('-').append(NONCE.getAndIncrement());
    } else {
        String value = bindings == null ? null : bindings.get(name);
        if (value == null) return null;
        out.append(value);
    }
    last = m.end();
}
```
Update the `substitute` Javadoc: note `${{@nonce}}` is generated per-fire and never causes a null return.

- [ ] **Step 5: Run — must PASS**

Run: `./gradlew test --tests runner.coverage.LoadCorrelationTest --tests runner.coverage.GrammarCorrelationTest`
Then: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add runner/coverage/RequestGrammar.java runner/coverage/LoadRun.java test/runner/coverage/
git commit -m "feat(correlation): <nonce> generator + substitute @nonce branch + RUN_SALT (DD-038)"
```

---

### Task 2: path+body substitution (null-safe) + lint exemption

Widen substitution to the full request line at every fire site, null-safely, and stop the lint from false-warning on `@nonce`.

**Files:**
- Modify: `runner/coverage/RequestLine.java` (`needsSubstitution()`, lines 15-17)
- Modify: `runner/coverage/LoadRun.java` (worker substitution block, lines 124-131; `lintCorrelationOrdering`, lines 513-528)
- Modify: `runner/coverage/CoverageGuidedRun.java` (`runSequence`, lines 496-504; single-step `request(base, String)`, line 554)
- Test: `test/runner/coverage/RequestLineV3Test.java`, `LoadCorrelationTest.java`, `ExploreCorrelationTest.java`

**Interfaces:**
- Consumes: `substitute` + the `@nonce` marker (Task 1).
- Produces: `RequestLine.needsSubstitution()` true when the marker is in **path OR body**. Every fire site substitutes path (always) + body (if non-null) and skips only on a genuine unbound-ref null.

- [ ] **Step 1: Write the failing tests**

`test/runner/coverage/RequestLineV3Test.java`:
```java
@Test public void needsSubstitutionDetectsAMarkerInThePath() {
    assertTrue(RequestLine.parse("GET /Wiki.jsp?rev=${{@nonce}}").needsSubstitution());   // body is null
    assertFalse(RequestLine.parse("GET /Wiki.jsp?rev=x").needsSubstitution());
}
```
`test/runner/coverage/LoadCorrelationTest.java` (path substitution end-to-end via the JDK HttpServer harness already in this file — mirror its existing sequence tests): drive a 1-step sequence `GET /rec?rev=${{@nonce}}` (empty body) through the worker/`fire` path and assert the server received a path whose `rev=` is a non-empty `<salt>-<n>` value, NOT the literal `${{@nonce}}`, and that firing it did **not** throw (null body must not NPE in substitute).

- [ ] **Step 2: Run — must FAIL**

Run: `./gradlew test --tests runner.coverage.RequestLineV3Test --tests runner.coverage.LoadCorrelationTest`
Expected: FAIL — `needsSubstitution()` is body-only so a path marker returns false; the path-only case fires the literal marker (or an NPE if an implementer already tried body-guarding).

- [ ] **Step 3: Extend `needsSubstitution` to path**

In `runner/coverage/RequestLine.java`:
```java
public boolean needsSubstitution() {
    return (body != null && body.contains("${{")) || (path != null && path.contains("${{"));
}
```

- [ ] **Step 4: Null-safe path+body substitution in the LoadRun worker**

In `runner/coverage/LoadRun.java`, replace the worker substitution block (lines 125-131) with:
```java
if (correlated && step.needsSubstitution()) {
    String p = substitute(step.path(), bindings);                       // path is never null
    String b = (step.body() == null) ? null : substitute(step.body(), bindings);
    if (p == null || (step.body() != null && b == null)) {              // a genuine unbound ${{ref}}
        if (System.nanoTime() >= measureFromNanos) captureMisses.incrementAndGet();
        continue;
    }
    toFire = new RequestLine(step.method(), p, b, step.captures());
}
```

- [ ] **Step 5: Same in `CoverageGuidedRun.runSequence` + the single-step path**

In `runner/coverage/CoverageGuidedRun.java` `runSequence` (lines 496-504), replace the single-body substitute with the path+body form:
```java
if (r.needsSubstitution()) {
    String p = LoadRun.substitute(r.path(), bindings);
    String b = (r.body() == null) ? null : LoadRun.substitute(r.body(), bindings);
    if (p == null || (r.body() != null && b == null)) continue;   // unbound ref: skip this step
    r = new RequestLine(r.method(), p, b, r.captures());
}
```
And in the single-step `request(String base, String step)` (line 554) — today it delegates without substituting — parse, substitute path+body with an **empty** bindings map (a nonce needs none), and skip (return `CostSample.EMPTY`) on a null:
```java
static CostSample request(String base, String step) throws Exception {
    RequestLine r = RequestLine.parse(step);
    if (r.needsSubstitution()) {
        java.util.Map<String,String> none = java.util.Map.of();
        String p = LoadRun.substitute(r.path(), none);
        String b = (r.body() == null) ? null : LoadRun.substitute(r.body(), none);
        if (p == null || (r.body() != null && b == null)) return CostSample.EMPTY;  // unbound ref: don't fire literal
        r = new RequestLine(r.method(), p, b, r.captures());
    }
    return request(base, r, null, step);   // label MUST stay the raw `step` (see below)
}
```
**CRITICAL — the label stays the raw `step`.** The 4-arg delegation's last arg is the recorded-finding label, and DD-036's token-leak invariant requires it be the **raw `step`, byte-for-byte** (`CoverageGuidedRun.java:554-558` javadoc; `runSequence` at :514 fires the substituted `r` but records raw `step`). Do **NOT** pass `r.format()` — `format()` canonicalizes away a `GET ` prefix, so every explore single-step finding would be silently rewritten (`GET /foo` → `/foo`). Keep `step`.
(Confirm `CostSample.EMPTY` exists; if the constant is named differently, use the file's existing "no-op sample" value.)

- [ ] **Step 6: Lint — exempt `@nonce` and scan the path**

In `runner/coverage/LoadRun.java` `lintCorrelationOrdering` (513-528): (a) when iterating a step's `${{name}}` refs, `if (name.equals("@nonce")) continue;` before the `capturedSoFar.contains` check; (b) scan the step's **path** as well as its body for refs (currently body-only). Add a `lintCorrelationOrderingIgnoresNonce` test asserting a nonce-only corpus logs no warning (drive `lintCorrelationOrdering` via the existing entry point).

- [ ] **Step 7: Run touched tests + full suite**

Run: `./gradlew test --tests runner.coverage.RequestLineV3Test --tests runner.coverage.LoadCorrelationTest --tests runner.coverage.ExploreCorrelationTest`
Then: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add runner/coverage/RequestLine.java runner/coverage/LoadRun.java runner/coverage/CoverageGuidedRun.java test/runner/coverage/
git commit -m "feat(correlation): substitute the full request line (path+body), null-safe; lint exempts @nonce (DD-038)"
```

---

### Task 3: `fire` no-follow + `FireResult`/`fireR` + `redirects`/`redirectTargets` + `summaryJson`

**Files:**
- Modify: `runner/coverage/LoadRun.java` (`fire` 245/257 + line 264; add `FireResult` + `fireR` + `normalizeLocation`; counters near 63-65; worker recording block 145-146; `summaryJson` 219-231 + call site 195-199)
- Modify: `test/runner/coverage/LoadDriftUnavailableTest.java` (the two `summaryJson` call sites — add the new params)
- Test: `test/runner/coverage/LoadFireTest.java` (new redirect + Set-Cookie tests), a `normalizeLocation` unit test

**Interfaces:**
- Produces: `record FireResult(int code, String location)`; `fireR(base, step, jar, bindings)` returns it; the `int fire(...)` overloads delegate. `static String normalizeLocation(String location, String requestPage)`. `summaryJson(...)` gains `long redirects` + `Map<String,Long> redirectTargets` (or a pre-serialized JSON fragment).

- [ ] **Step 1: Write the failing tests**

`test/runner/coverage/LoadFireTest.java` — it has ONE `server`/`base`; register these as three additional **contexts on that same server** (`/Edit.jsp` → 302 to `/Wiki.jsp?page=SessionExpired`, `/ok` → 200, `/login` → 302 + `Set-Cookie: JSESSIONID=abc`). Do not invent `base200`/`baseRedirectWithCookie` fields:
```java
@Test public void fireRDoesNotFollowAndReportsLocation() throws Exception {
    // handler returns 302 Location: /Wiki.jsp?page=SessionExpired
    LoadRun.FireResult r = LoadRun.fireR(base, new RequestLine("POST","/Edit.jsp","page=X&action=save"),
            new java.util.HashMap<>(), null);
    assertEquals(302, r.code());
    assertEquals("/Wiki.jsp?page=SessionExpired", r.location());
}
@Test public void fireRHasNullLocationForA200() throws Exception {
    LoadRun.FireResult r = LoadRun.fireR(base, new RequestLine("GET","/ok",null), new java.util.HashMap<>(), null);
    assertEquals(200, r.code()); assertNull(r.location());
}
@Test public void a302SetCookieStillPopulatesTheJar() throws Exception {
    // 302 carrying Set-Cookie: JSESSIONID=abc — jar must capture it (DD-035, no-follow)
    java.util.Map<String,String> jar = new java.util.HashMap<>();
    LoadRun.fireR(base, new RequestLine("GET","/login",null), jar, null);
    assertEquals("abc", jar.get("JSESSIONID"));
}
```
A `normalizeLocation` unit test (in `LoadFireTest` or a new `LoadRedirectTest`):
```java
@Test public void normalizeLocationClassifies() {
    assertEquals("self", LoadRun.normalizeLocation("/Wiki.jsp?page=Main", "Main"));
    assertEquals("SessionExpired", LoadRun.normalizeLocation("/Wiki.jsp?page=SessionExpired", "Main"));
    assertEquals("Wiki.jsp", LoadRun.normalizeLocation("/Wiki.jsp?tab=view", null));
    assertEquals("X", LoadRun.normalizeLocation("http://h/Wiki.jsp?page=X", null));
    // requestPage from a body-leading page= must resolve (N2): request "page=Main&action=save" -> "Main"
    // (test the requestPage helper if it's a separate static, else fold into the above self-case)
}
```

- [ ] **Step 2: Run — must FAIL**

Run: `./gradlew test --tests runner.coverage.LoadFireTest`
Expected: FAIL — `FireResult`/`fireR`/`normalizeLocation` don't exist.

- [ ] **Step 3: `FireResult` + `fireR` + no-follow**

In `runner/coverage/LoadRun.java`: add `record FireResult(int code, String location) {}`. Rename the body of the current 4-arg `fire(base, step, jar, bindings)` (returns int) into `fireR(...)` returning `FireResult`: set `c.setInstanceFollowRedirects(false)` (was line 264 `true`); after `int code = c.getResponseCode()` and the existing `captureSessionCookie(c, jar)`, read `String loc = (code >= 300 && code < 400) ? c.getHeaderField("Location") : null;`; keep the existing drain/`-1` paths (on the `-1` transport path, `location` is null); `return new FireResult(code, loc)`. Make BOTH existing `fire(...)` overloads (the 3-arg 245 and 4-arg 257) delegate: `return fireR(...).code();` (the 3-arg `fire` passes a single `null` for bindings, as it does today).

**RENAME, do NOT rewrite.** The 4-arg `fire` body (lines ~258-334) also holds the **DD-037 capture-into-bindings branch** (`step.captures()` loop → `Capture.extract` → `bindings.put`, ~292-322), the retain-≤256KB **drain-to-EOF**, `captureSessionCookie`, and the `catch` → `-1` path. Move that body WHOLESALE into `fireR` and change only: the follow flag, the added `loc` read, and the **two** `return code;` sites — the success return (~line 323) becomes `return new FireResult(code, loc);` and the `catch` return (~line 333) becomes `return new FireResult(code, null);`. Dropping or re-deriving the capture branch is a regression — the whole-branch review checks for it.

- [ ] **Step 4: `normalizeLocation` (pure static)**

```java
private static final int LOC_KEY_MAX = 64;
static String normalizeLocation(String location, String requestPage) {
    if (location == null || location.isEmpty()) return "?";
    String page = paramValue(location, "page");                    // (^|[?&])page= match
    String key;
    if (page != null) {
        key = (requestPage != null && requestPage.equals(page)) ? "self" : page;   // F1 self-fold
    } else {
        int q = location.indexOf('?'); int h = location.indexOf('#');
        String noQuery = location.substring(0, minPos(location.length(), q, h));
        int slash = noQuery.lastIndexOf('/');
        key = slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
        if (key.isEmpty()) key = "?";
    }
    if (key.length() > LOC_KEY_MAX) key = key.substring(0, LOC_KEY_MAX);
    return safeKey(key);                                            // charset-restrict for JSON (N3)
}
/** (^|[?&])<name>=<value up to & or #>, exact-boundary so frompage= doesn't false-match. */
static String paramValue(String s, String name) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(^|[?&])" + java.util.regex.Pattern.quote(name) + "=([^&#]*)").matcher(s);
    return m.find() ? m.group(2) : null;
}
private static int minPos(int len, int... ps) { int r = len; for (int p : ps) if (p >= 0 && p < r) r = p; return r; }
/** Restrict to a JSON-safe, budget-safe charset so a fuzzed Location can't void the summary (N3). */
static String safeKey(String k) {
    StringBuilder b = new StringBuilder(k.length());
    for (int i = 0; i < k.length(); i++) { char c = k.charAt(i);
        b.append((c=='.'||c=='_'||c=='-'|| (c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')) ? c : '_'); }
    return b.length() == 0 ? "?" : b.toString();
}
```
The worker's `requestPage` is `paramValue(stepBodyOrPath, "page")` from the fired step.

- [ ] **Step 5: Counters + worker recording + `summaryJson`**

Add the import `java.util.concurrent.atomic.LongAdder` at the top of `LoadRun.java` (lines 11-12 currently import only `AtomicLong`/`AtomicLongArray`; the map field below is fully-qualified but the **local** `LongAdder a` is not, so without the import it will NOT compile). Then add near lines 63-65: `final AtomicLong redirects = new AtomicLong();` and `final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.LongAdder> redirectTargets = new java.util.concurrent.ConcurrentHashMap<>();`. Change the worker fire call to `FireResult fr = fireR(baseUrl, toFire, jar, bindings); int code = fr.code();` and, in the post-warmup recording block (after the `else if (code>=400&&code<500)` at line 146), add:
```java
else if (code >= 300 && code < 400) {
    redirects.incrementAndGet();
    String reqPage = paramValue(toFire.body() != null ? toFire.body() : toFire.path(), "page");
    String key = normalizeLocation(fr.location(), reqPage);
    LongAdder a = redirectTargets.get(key);
    if (a == null) { if (redirectTargets.size() >= 12) key = "other";
        a = redirectTargets.computeIfAbsent(key, k -> new LongAdder()); }
    a.increment();
}
```
`summaryJson`: add params `long redirects, Map<String,Long> redirectTargets` (convert the LongAdder map to a top-12-by-count `LinkedHashMap<String,Long>` at the call site), emit `"redirects":%d,"redirectTargets":{...}` after `clientErrors` — build the inner object by joining `"escapedKey":count` (keys are already `safeKey`-restricted, so no escaper needed, but assert it in a test). Update the run() call site (195-199) and both `LoadDriftUnavailableTest` call sites with the two new args (e.g. `0, java.util.Map.of()`).

- [ ] **Step 6: Run — must PASS + full suite**

Run: `./gradlew test --tests runner.coverage.LoadFireTest --tests runner.coverage.LoadDriftUnavailableTest`
Then: `./gradlew test`
Expected: BUILD SUCCESSFUL (LoadFireTest's existing 200-handler tests still pass — they call the `int fire`).

- [ ] **Step 7: Commit**

```bash
git add runner/coverage/LoadRun.java test/runner/coverage/
git commit -m "feat(load): no-follow + FireResult/fireR + Location-classified 3xx counters (DD-038)"
```

---

### Task 4: JSPWiki `edit_save` uses `<nonce>`

**Files:** Modify `examples/grammar/jspwiki.grammar` (the `$…` rules + the `edit_save` POST).

- [ ] **Step 1: Add the rule + reference**

Add a rule near the other `$` rules: `$rev = <nonce>`. In `edit_save`'s POST body, append `${rev}` to `_editedtext` so each save differs, e.g.:
```
POST /Edit.jsp page=${page}&action=save&X-XSRF-TOKEN=${{csrf}}&${{spam}}&_editedtext=${wikitext} ${rev}&ok=Save
```

- [ ] **Step 2: Validate expansion (throwaway, like DD-037 Task 4)**

Compile coverage classes (`./gradlew compileCoverageJava -q`), then load the grammar and assert the expanded `edit_save` POST contains the literal `${{@nonce}}` marker (proof `<nonce>` expanded to the fire-time token):
Run a small `javac`+`java` check against `build/classes/java/coverage:build/classes/java/main` that `RequestGrammar.load(...).expandAllSequences()` yields an `action=save` line containing `${{@nonce}}` and `${{csrf}}`/`${{spam}}`.
Expected: prints the marker present.

- [ ] **Step 3: Full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add examples/grammar/jspwiki.grammar
git commit -m "feat(examples): JSPWiki edit_save uses <nonce> so each replayed save is a real change (DD-038)"
```

---

### Task 5: DD-038 record + changelog

**Files:** Modify `docs/DESIGN-DECISIONS.md`, `docs/LOAD-MODE-DESIGN.md`, `runner/CHANGELOG.md`.

- [ ] **Step 1: DD-038 record**

Append `## DD-038: <nonce> generator (unique per-fire payload) + Location-classified 3xx counters (2026-07-22)` after DD-037, mirroring DD-037's `**Context.**/**Decision.**/**Verified.**/**Rejected alternatives.**` shape. Content from `docs/superpowers/specs/2026-07-22-nonce-and-3xx-design.md`: the idempotent-write-replay no-op (JSPWiki `saveText`), the invisible-redirect problem; the `<nonce>` generator + `${{@nonce}}` marker + `RUN_SALT`-`counter` uniqueness (why a bare seeded counter fails cross-run); path+body substitution; no-follow + `FireResult`/`normalizeLocation` self-fold + hard-bounded/JSON-safe `redirectTargets`; that no-follow is a session-carry *fix*. Rejected: single-seeded counter, packed integer, reserving a `${{}}` name, keep-follow-and-infer-via-getURL.

- [ ] **Step 2: LOAD-MODE note + CHANGELOG**

`docs/LOAD-MODE-DESIGN.md`: a short `## 12.` note that load mode no longer follows redirects and classifies 3xx targets, and that `<nonce>` makes write replays non-idempotent; cross-reference DD-038. `runner/CHANGELOG.md`: under `## [Unreleased]`, one bullet — the `<nonce>` generator + per-fire token, full-request-line substitution, and the no-follow/3xx-classification (rejects like `SessionExpired`/`PageModified` now visible; existing captures/behavior otherwise unchanged).

- [ ] **Step 3: Commit**

```bash
git add docs/DESIGN-DECISIONS.md docs/LOAD-MODE-DESIGN.md runner/CHANGELOG.md
git commit -m "docs: DD-038 <nonce> + 3xx-classification record + changelog"
```

---

## Notes for the executor

- **Order:** 1 (generator+substitute) → 2 (path+body+lint) → 3 (3xx counters) → 4 (grammar) → 5 (docs). Each leaves a green build.
- **Whole-branch review must check:** (1) `${{@nonce}}` never returns null / never NPEs on a null body; (2) `RUN_SALT` folds in the pid and the token is URL-safe; (3) path+body substitution's skip guard is exactly `p==null || (body!=null && b==null)` at all three fire sites; (4) `fireR` is a distinct method and the `int fire` overloads delegate so `LoadFireTest` is untouched; (5) no-follow doesn't leak into `CoverageGuidedRun`; (6) `redirectTargets` is hard-bounded (≤12) and every key passes `safeKey` so `summaryJson` stays valid JSON under a fuzzed Location; (7) F1 self-fold: a body-leading `page=` resolves `requestPage` (the `(^|[?&])page=` rule), so a JSPWiki success 302 folds to `"self"` and doesn't evict rejects; (8) `lintCorrelationOrdering` exempts `@nonce` and scans path+body.
- **Proof (post-merge, in the benchmark campaign):** re-run JSPWiki `edit_save` load with the new corpus; the save POSTs persist a *new version every fire* (`OLD/<page>/page.properties` version count climbs), `redirects` counts self-redirects under `"self"` with `captureMisses≈0`, and the write-path latency/heap cost is now non-idempotent and measurable.
