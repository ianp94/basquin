# DD-038: `<nonce>` generator (unique per-fire payload) + Location-classified 3xx counters

**Status:** design approved (Component 1 as a `<nonce>` generator + Component 2 Option A), 2026-07-22.
**Builds on:** DD-036/037 response correlation (`LoadRun.substitute`, `${{name}}` refs, encoding model A).
**Motivation source:** `.superpowers/sdd/jspwiki-runner-save-rootcause.md`.

## Problem

Two findings from validating DD-037's correlated JSPWiki writes end-to-end:

1. **Idempotent write replays are invisible.** A load corpus replays a **fixed** request body per line. Change-detecting apps no-op an identical write: JSPWiki's `DefaultPageManager.saveText` returns *before writing* when `oldText.equals(proposedText)`, yet still issues the same `302 → success` redirect. So after the first replay a page never changes again, and the "save" costs nothing — the write-path cost the benchmark wants to measure disappears. This is a general CMS pattern, not JSPWiki-specific.
2. **Redirect outcomes are invisible in load metrics.** `LoadRun.fire` sets `setInstanceFollowRedirects(true)`, so a save's `302` is auto-followed to a final `200`. A *rejected* save (`302 → SessionExpired`/`Forbidden`/`PageModified`) is therefore counted as a normal request — not a 4xx (`clientErrors`) or 5xx (`serverError`), and capture succeeded so not a `captureMiss`. Rejections are silent.

## Goal

(1) Make every replayed write body unique so each save is a *real* change. (2) Make redirect outcomes visible in the load terminal summary.

## Component 1 — `<nonce>` generator (unique per-fire payload)

A new grammar **generator primitive** `<nonce>`, in the same `<…>` namespace as `<int>`/`<string>`/`<long>`/`<empty>` (`RequestGrammar.generator()`). Like those it **produces** a value and is used as a rule alternative under an **author-chosen** rule name — it reserves **nothing** in the `${{}}` correlation namespace (which stays for response captures the author names). This is the durable pattern: *producers* are `<…>` generators, *extractors* are `<<…=kind:…>` capture kinds, and author identifiers stay in `$rules`/`${{refs}}`.

The one twist: a nonce must vary at **replay (fire) time**, not be frozen at grammar-expansion time — the corpus is replayed many times, and a frozen value would be idempotent (the no-op bug). So `<nonce>` does not emit a concrete value at expansion; it emits an internal **fire-time marker** that the runner fills per-fire:

- `RequestGrammar.generator()`: `case "<nonce>": return "${{@nonce}}";` — a deferral marker. The `@` is NOT in `Capture`'s `NAME_PATTERN` (`[A-Za-z0-9_]+`), so `@nonce` can never be a valid author-named capture → the marker is **collision-proof**. `expand` does not re-scan generator output (and its existing `${{…}}` guard would preserve it anyway), so the marker survives verbatim into the emitted corpus.
- `LoadRun.substitute` (shared by both engines): its `${{name}}` scan yields `name = "@nonce"`; handle it **before** the bindings lookup:

```java
// RUN_SALT is per-process and collision-resistant even for two driver JVMs starting in the same
// millisecond (parallel campaigns): fold in the pid so their token streams differ.
// ProcessHandle.current().pid() is available on the runner's JDK 17.
static final String RUN_SALT =
        Long.toString(System.currentTimeMillis()) + "x" + Long.toString(ProcessHandle.current().pid());
static final java.util.concurrent.atomic.AtomicLong NONCE =
        new java.util.concurrent.atomic.AtomicLong();        // within-run counter, from 0
// inside substitute()'s while loop, name = m.group(1):
if (name.equals("@nonce")) {
    out.append(RUN_SALT).append('-').append(NONCE.getAndIncrement());  // "<millis>x<pid>-<n>", URL-safe (unreserved chars)
} else {
    String value = bindings == null ? null : bindings.get(name);
    if (value == null) return null;
    out.append(value);
}
```

**Substitution scope — the full request line (path + body), at every fire site.** DD-036/037 substituted only the body; generators are usually used in URL queries (`?rev=<nonce>`), so body-only would bake the literal marker into a fired URL and silently no-op. So this feature widens substitution:
- `RequestLine.needsSubstitution()` → `(body != null && body.contains("${{")) || (path != null && path.contains("${{"))`.
- **Null-safe substitution (N1 — `substitute` matches on the string and NPEs on null).** The path is never null (worst case `""`, `RequestLine.parseCore`), the body can be null. So substitute the path **unconditionally** and guard the body:
  ```java
  String p = substitute(path, bindings);                       // path never null
  String b = (body == null) ? null : substitute(body, bindings);
  if (p == null || (body != null && b == null)) { /* genuine unbound ${{ref}} */ skip + captureMiss; continue; }
  toFire = new RequestLine(method, p, b, captures);
  ```
  A path-only nonce `GET /Wiki.jsp?rev=${{@nonce}}` (`body==null`) must NOT crash — substitute the path, leave the null body null.
- Every fire site does the above and rebuilds the `RequestLine`: the **LoadRun worker loop**, `CoverageGuidedRun.runSequence`, AND the single-step `CoverageGuidedRun.request(base, String)` (the plain-explore path — today it never substitutes, so a nonce in a bare explore route would fire literally). The single-step path substitutes with an **empty/null bindings map** (a nonce needs none); if substitute returns null there (a `${{ref}}` with no capture on a bare route — a mis-authored grammar), **skip the request** (do not fire the literal marker).
- **Lint exemption + path scan (`lintCorrelationOrdering`, LoadRun.java:513-528):** (a) `@nonce` is *generated*, not captured — skip it when checking that a `${{name}}` ref has a preceding `<<name=` capture, or every nonce corpus trips a false "will never bind" warning (it's log-once, so the false positive masks real ordering bugs). (b) Since paths are now substituted, the lint must scan the **path as well as the body** for `${{ref}}` (else a path ref escapes the ordering check).

- **Uniqueness (two fields — this is load-bearing):** a per-process `RUN_SALT` (`currentTimeMillis()` at class-load) **plus** a within-run `AtomicLong` counter, emitted as `<RUN_SALT>-<counter>`. The counter guarantees uniqueness *within* a run (monotonic, thread-safe across workers); the salt guarantees uniqueness *across* runs (a different process gets a different salt, **regardless of counter magnitude**). A single `AtomicLong` *seeded* with millis is NOT enough: the seed advances in wall-clock millis but the counter advances by number-of-saves (millions per run under load), so a later run's millis seed can land deep inside a prior run's emitted counter range and re-emit values still saved on pages — reintroducing the exact `oldText.equals(proposedText)` no-op this feature exists to kill (bounded to ~one transient no-op per page, but the invariant is false and it matters for re-runs against a persistent store). The hyphen is an RFC 3986 §2.3 *unreserved* character, so the token stays URL-safe and is spliced verbatim under model A (no encoding).
- **`@nonce` never triggers the skip-and-count path:** it is generated, not looked up, so it never returns `null`. A body with both `${{@nonce}}` and an unbound `${{csrf}}` still returns `null` (the real capture miss) — nonce does not mask it.
- **A nonce-only body needs no capture:** `needsSubstitution()` (body contains `${{`) is already true, so `seq.correlated()` is true and the worker runs `substitute` with an allocated (possibly empty) bindings map. Works unchanged in both `LoadRun` (worker loop) and `CoverageGuidedRun` (`runSequence`), which share `substitute`.
- **Grammar authoring:** the writer names their own rule and uses it like any generator, e.g. `$rev = <nonce>` then `…&_editedtext=${wikitext} ${rev}`. The JSPWiki `edit_save` grammar adds `$rev = <nonce>` and appends `${rev}` to `_editedtext`, so every replayed save body differs and JSPWiki's no-op guard never fires. (Multiple `${rev}` occurrences each expand to the marker and each take the next counter value at fire time → all unique.)

## Component 2 — Location-classified 3xx counters (load mode only)

`LoadRun.fire` stops auto-following redirects; the load driver observes the direct response and classifies its `Location`.

- `c.setInstanceFollowRedirects(false)` in `fire`.
- **`fire` must return the `Location`, not just the code (F2 contract).** `fire` currently returns a bare `int` and the connection is method-local, so the worker can't read the header. Add `record FireResult(int code, String location)` and a **distinct method** `fireR(base, step, jar, bindings)` returning `FireResult` (reading `c.getHeaderField("Location")`, null for non-3xx and on the `-1` transport-error path) — **not** a `fire` overload (Java can't overload on return type). The existing `int`-returning `fire(...)` **delegates** (`return fireR(...).code()`), so `LoadFireTest` and every other caller are untouched. The worker calls `fireR`.
- New counters alongside `serverError`/`clientErrors`: a `redirects` `AtomicLong` (any `3xx`), and a `redirectTargets` `ConcurrentHashMap<String,LongAdder>` keyed by a normalized label.
- **`normalizeLocation(location, requestPage)` — a pure static, so it's unit-testable and its edge cases are pinned:**
  - **Self-redirect folding (F1 — critical):** a *successful* JSPWiki save also `302`s to `/Wiki.jsp?page=<savedPage>`, so under a naive `page=` rule the 66 real page names would fill the cap first-come and evict the actual rejects (`SessionExpired`/`PageModified`) into `"other"` — the exact invisibility this feature kills. So a **self-redirect** (Location's `page` equals the request's own `page`) folds to the single reserved key `"self"`; only *cross-target* redirects (the rejects) consume distinct slots. The worker extracts the request's `page` from the fired step (`page=` in its body/query) and passes it in.
  - **Page-param parse — `(^|[?&])page=` (N2).** The `requestPage` comes from the fired *step body* where `page=…` is often at **offset 0** (`page=${page}&action=save&…`, `jspwiki.grammar` `edit_save`), so a `[?&]page=` rule (fine for a Location, which always has `?page=`) would MISS the leading `page=` → `requestPage==null` → self-fold never fires → success crowd-out returns. Use `(^|[?&])page=` for **both** `requestPage` extraction and `normalizeLocation` (matches a leading `page=` AND `?page=`/`&page=`, still rejects `frompage=`).
  - Else use the last path segment **with any query/fragment stripped** (`/Wiki.jsp?tab=view` → `Wiki.jsp`); handle absolute `Location`s (`http://…/Wiki.jsp?page=X`); a request with **no** `page` param → `requestPage==null` (never self-folds — correct, only page-bearing writes self-redirect); empty/unparseable Location → `"?"`. **Truncate the key to 64 chars, then JSON-escape it (N3, below).**
- **Hard budget bound + JSON validity (F6 + N3):** the ~4 KB cap is the **kubelet termination-message limit** — if `summaryJson` overflows OR emits invalid JSON, the operator's `json.Unmarshal` (`basquincampaign_controller.go:~331`) silently drops the **entire** summary. `summaryJson` builds JSON via raw `String.format` (no escaping, unlike explore's `jsonString()`), so a fuzzed/reflected `Location` page containing `"`, `\`, or a control char would produce an **invalid JSON key** and void the whole summary. So `redirectTargets` is (a) **hard-bounded** — at most `CAP=12` keys (best-effort admission below; a rare overshoot ≤ worker-count is fine in-memory), JSON emits only the **top-N by count** (N≤12); and (b) each key is **JSON-escaped** on emit (reuse a `jsonString()`-style escaper) OR `normalizeLocation` restricts its output to a safe charset (`[A-Za-z0-9._-]`, others → `_`). Together the summary can never blow the budget or the parser.
  ```java
  LongAdder a = redirectTargets.get(key);          // best-effort admission (benign overshoot ≤ workers)
  if (a == null) {
      if (redirectTargets.size() >= CAP) key = "other";
      a = redirectTargets.computeIfAbsent(key, k -> new LongAdder());
  }
  a.increment();
  ```
- Worker recording block (post-warmup): `else if (code >= 300 && code < 400) { redirects.incrementAndGet(); bump redirectTargets with normalizeLocation(result.location(), requestPage); }` — mutually exclusive with the existing `code>=500`/`code>=400&&<500` branches; a `2xx` and a `-1` transport error count as neither.
- `summaryJson` gains `"redirects":N` and `"redirectTargets":{...}` (top-N) after `clientErrors`. `StatusReporter` is **untouched** (terminal-summary-only, per DD-036).
- **Behavior change (documented):** load mode no longer follows redirects — it measures the server's direct response to the exact request (arguably more correct for a load test) and surfaces rejects. Latency drops the follow hop for a redirecting request. `CoverageGuidedRun` (explore) keeps its follow behavior (coverage, not redirect metrics). **DD-035 session interaction — no-follow is a FIX, not a regression (F7):** with follow ON, a `302` from an authenticated action was re-issued as a *cookieless* GET whose *anonymous* `Set-Cookie` **overwrote the jar's valid `JSESSIONID`** on the way to the redirect target (root-cause §5.3) — silently dropping the session mid-sequence. `captureSessionCookie(c, jar)` runs on the direct `3xx` response *before* the (now-absent) follow, so the real session cookie is kept and the anonymous overwrite never happens. Session carry strictly improves (jpetstore's signon→authenticated-GETs sequence included).

## Data flow (JSPWiki honest write)

Grammar `$rev = <nonce>`; `edit_save` POST body `…&_editedtext=${wikitext} ${rev}&…`. At expansion `${rev}` → the `<nonce>` generator → the marker `${{@nonce}}` (baked into the corpus alongside the frozen `${wikitext}`). At replay, `substitute` splices the next `<RUN_SALT>-<counter>` value → each fire's body is unique → `saveText` writes a new version every time (real write cost in latency/heap). A rejected save now shows as a `redirects` bump keyed `SessionExpired`/`PageModified` instead of vanishing.

## Error handling / edge cases

- **No reserved *correlation* name.** The nonce lives in the `<…>` generator namespace, and its wire marker `@nonce` uses a `@` that `Capture.NAME_PATTERN` (`[A-Za-z0-9_]+`) can't produce — so it can never collide with an author-named capture, and the `${{}}` namespace stays purely author-controlled. (Reserving one keyword `<nonce>` in the generator namespace is like `<int>`/`<string>` — the correct home for value primitives; note it in the grammar-authoring docs. A future generated token (`<uuid>`, `<threadid>`) adds one more generator + one more `substitute` branch, same pattern.)
- **Capture from a 3xx under no-follow (new reachable state):** `getInputStream()` does not throw on `3xx` (only `≥400`), so a capture-bearing step that redirects reads a usually-empty body → `Capture.extract` returns `null` → surfaces downstream as a normal `captureMiss`. Benign and correct; noted because no-follow makes it reachable.
- `${{@nonce}}` with `bindings == null` (an uncorrelated-but-nonce body): the branch runs before any bindings deref, so no NPE.
- Multiple `${{@nonce}}` in one body: each occurrence takes the next counter value (all unique) — fine for uniqueness.
- `fire`'s existing drain-to-EOF + `-1`-on-transport-error paths unchanged; a `3xx` has a small/empty body.

## Testing

- `substitute` nonce: `substitute("x=${{@nonce}}", empty-map)` yields `x=<salt>-<n>`; two calls yield **different** values; a body with `${{@nonce}}` + an unbound `${{csrf}}` still returns `null`; `${{@nonce}}` needs no binding and never NPEs on a null bindings map. Two `RUN_SALT`s constructed in the same millisecond but different pids differ.
- **Path substitution (F4/N1):** `needsSubstitution()` is true when the marker is in the **path/query** with a **null body** (`GET /x?rev=${{@nonce}}` → `body==null`); substituting must **not NPE** (path substituted, null body left null) and the fired URL carries `<salt>-<n>`, not the literal marker. Cover it for the load worker, `runSequence`, and the single-step `request(base, String)` explore path (which fires literal-free or skips on a genuine unbound ref).
- `RequestGrammar`: `$rev = <nonce>` in a route → `expandAll`/`randomRequest` yields the marker `${{@nonce}}` verbatim (the generator output isn't re-scanned), and `RequestLine.parse(...).needsSubstitution()` is true for that line.
- **`lintCorrelationOrdering`:** a nonce-only corpus (`${{@nonce}}`, no capture) triggers **no** "will never bind" warning.
- **`normalizeLocation` (pure static):** **request page from a body-leading `page=` (N2):** `requestPage("page=Main&action=save")` → `"Main"` (the `(^|[?&])page=` rule), and a self-redirect `Location=/Wiki.jsp?page=Main` → `"self"` (guards F1 on the real target); reject (`Location=/Wiki.jsp?page=SessionExpired`) → `"SessionExpired"`; `frompage=X` does NOT match; trailing query (`/Wiki.jsp?tab=view`) → `"Wiki.jsp"`; absolute `http://…/Wiki.jsp?page=X` → `"X"`; a request with no `page` param → `requestPage==null`, never self-folds; a 70-char page → truncated to 64. **JSON validity (N3):** a `Location` page containing `"`/`\`/control char → the emitted `redirectTargets` key is escaped/sanitized so `summaryJson` stays valid JSON (assert the summary parses).
- `fire` 3xx: `FireResult` overload returns `(302, "/Wiki.jsp?page=SessionExpired")` for a redirect handler and `(200, null)` for a plain handler; the `int` overload delegates (same code). Driving a redirect through the worker increments `redirects` and the right `redirectTargets` key. **Plus a `302`-carrying-`Set-Cookie` test** asserting the jar still captures it (pins the DD-035 fix).
- `summaryJson` includes `redirects`/`redirectTargets` (top-N, hard-bounded); update its **two** callers — `LoadRun.run` and `LoadDriftUnavailableTest` (the only ones) — for the new params.
- Full suite green. **`LoadFireTest` is NOT affected by the no-follow flip** (its handlers return `200` directly, never via a redirect — verified) and calls the `int` overload — no existing assertion needs repair; the 3xx coverage is a *new* redirect-handler test.

## Rejected alternatives

- **Random/UUID nonce:** works, but the `RUN_SALT + AtomicLong` token is smaller, allocation-lean, and equally unique; no need for randomness.
- **Single `AtomicLong` *seeded* with millis (no separate salt):** the seed and the counter advance in different units (wall-clock ms vs number-of-saves), so under load a later run's seed lands inside a prior run's emitted counter range and re-emits still-saved values → cross-run no-op collisions. Rejected for the two-field salt+counter (see Uniqueness).
- **Packing salt+counter into one integer's high/low bits:** caps a run's save count (bits spent on the salt) and collides when two runs start in the same millisecond. Rejected — the `<millis>-<counter>` string has neither limit and stays URL-safe.
- **Keep auto-follow, infer the target from `getURL()`:** `HttpURLConnection.getURL()` returns the *original* URL after following, not the final — so the redirect target isn't recoverable without disabling follow. Rejected.
- **3xx classification in explore too:** explore optimizes coverage, not load metrics; following redirects there reaches more code. Keep it load-only.

## Scope

One cohesive feature, one plan. Touch:
- `runner/coverage/RequestGrammar.java` — the `<nonce>` generator → `${{@nonce}}` marker.
- `runner/coverage/RequestLine.java` — `needsSubstitution()` now checks **path + body**.
- `runner/coverage/LoadRun.java` — `substitute` `@nonce` branch + `RUN_SALT`/`NONCE`; `lintCorrelationOrdering` exempts `@nonce`; `fire` no-follow + `FireResult(code, location)` overload (int overloads delegate) + `redirects`/`redirectTargets` + static `normalizeLocation`; the worker loop substitutes path+body and records the redirect; `summaryJson` (hard-bounded top-N).
- `runner/coverage/CoverageGuidedRun.java` — `runSequence` **and** the single-step `request(base, String)` substitute path+body (so `<nonce>` works in explore, not just load sequences).
- `examples/grammar/jspwiki.grammar` — `$rev = <nonce>` + `${rev}` in `edit_save`.
- tests + the DD-038 doc/CHANGELOG.

No new dependencies.
