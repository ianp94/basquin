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
static final long RUN_SALT = System.currentTimeMillis();     // once per process (class-load)
static final java.util.concurrent.atomic.AtomicLong NONCE =
        new java.util.concurrent.atomic.AtomicLong();        // within-run counter, from 0
// inside substitute()'s while loop, name = m.group(1):
if (name.equals("@nonce")) {
    out.append(RUN_SALT).append('-').append(NONCE.getAndIncrement());  // "<millis>-<n>", URL-safe (hyphen unreserved)
} else {
    String value = bindings == null ? null : bindings.get(name);
    if (value == null) return null;
    out.append(value);
}
```

- **Uniqueness (two fields — this is load-bearing):** a per-process `RUN_SALT` (`currentTimeMillis()` at class-load) **plus** a within-run `AtomicLong` counter, emitted as `<RUN_SALT>-<counter>`. The counter guarantees uniqueness *within* a run (monotonic, thread-safe across workers); the salt guarantees uniqueness *across* runs (a different process gets a different salt, **regardless of counter magnitude**). A single `AtomicLong` *seeded* with millis is NOT enough: the seed advances in wall-clock millis but the counter advances by number-of-saves (millions per run under load), so a later run's millis seed can land deep inside a prior run's emitted counter range and re-emit values still saved on pages — reintroducing the exact `oldText.equals(proposedText)` no-op this feature exists to kill (bounded to ~one transient no-op per page, but the invariant is false and it matters for re-runs against a persistent store). The hyphen is an RFC 3986 §2.3 *unreserved* character, so the token stays URL-safe and is spliced verbatim under model A (no encoding).
- **`@nonce` never triggers the skip-and-count path:** it is generated, not looked up, so it never returns `null`. A body with both `${{@nonce}}` and an unbound `${{csrf}}` still returns `null` (the real capture miss) — nonce does not mask it.
- **A nonce-only body needs no capture:** `needsSubstitution()` (body contains `${{`) is already true, so `seq.correlated()` is true and the worker runs `substitute` with an allocated (possibly empty) bindings map. Works unchanged in both `LoadRun` (worker loop) and `CoverageGuidedRun` (`runSequence`), which share `substitute`.
- **Grammar authoring:** the writer names their own rule and uses it like any generator, e.g. `$rev = <nonce>` then `…&_editedtext=${wikitext} ${rev}`. The JSPWiki `edit_save` grammar adds `$rev = <nonce>` and appends `${rev}` to `_editedtext`, so every replayed save body differs and JSPWiki's no-op guard never fires. (Multiple `${rev}` occurrences each expand to the marker and each take the next counter value at fire time → all unique.)

## Component 2 — Location-classified 3xx counters (load mode only)

`LoadRun.fire` stops auto-following redirects; the load driver then observes and classifies the direct response.

- `c.setInstanceFollowRedirects(false)` in `fire`. The capture path is unaffected (the edit-form GET is a plain `200`; `captureSessionCookie` already walks `Set-Cookie` headers regardless of following).
- New counters alongside `serverError`/`clientErrors`: a `redirects` `AtomicLong` (any `3xx`), and a bounded `redirectTargets` `ConcurrentHashMap<String,LongAdder>` keyed by a normalized `Location` label. Normalization: if `Location` has a `page=<X>` query param, key = `<X>` (JSPWiki's reject pages are `SessionExpired`/`PageModified`); else the last path segment; unknown/absent → `"?"`. **Truncate the key to 64 chars** so a pathological `Location` can't bloat the ~4 KB termination budget.
- **Cap is best-effort, not a hard bound** (deliberate — real cardinality is a handful of page names, so a rare few-key overshoot ≤ worker count is harmless and cheaper than locking):
  ```java
  LongAdder a = redirectTargets.get(key);
  if (a == null) {
      if (redirectTargets.size() >= CAP) key = "other";      // CAP = 12
      a = redirectTargets.computeIfAbsent(key, k -> new LongAdder());
  }
  a.increment();
  ```
- Worker recording block (post-warmup): `else if (code >= 300 && code < 400) { redirects.incrementAndGet(); redirectTargets bump; }` — mutually exclusive with the existing `code>=500`/`code>=400&&<500` branches. A `2xx` counts as neither.
- `summaryJson` gains `"redirects":N` and `"redirectTargets":{...}` after `clientErrors`. `StatusReporter` is **untouched** (terminal-summary-only, per DD-036).
- **Behavior change (documented):** load mode no longer follows redirects — it measures the server's direct response to the exact request (arguably more correct for a load test) and surfaces rejects. Latency for a redirecting request drops the follow hop. `CoverageGuidedRun` (explore) keeps its follow behavior (coverage, not redirect metrics). **DD-035 session interaction:** `captureSessionCookie(c, jar)` runs on the direct response *before* any follow decision, so a `Set-Cookie` on a `3xx` still populates the jar — but a cookie an app (re)sets on the *followed* `200` (the redirect target) is no longer observed. Harmless for JSPWiki (its session cookie is minted on the plain-`200` form GET), but a general corpus against an app that establishes/rotates a session cookie on a post-redirect `200` would lose session continuity across sequence steps. Documented as a known limitation of no-follow load mode.

## Data flow (JSPWiki honest write)

Grammar `$rev = <nonce>`; `edit_save` POST body `…&_editedtext=${wikitext} ${rev}&…`. At expansion `${rev}` → the `<nonce>` generator → the marker `${{@nonce}}` (baked into the corpus alongside the frozen `${wikitext}`). At replay, `substitute` splices the next `<RUN_SALT>-<counter>` value → each fire's body is unique → `saveText` writes a new version every time (real write cost in latency/heap). A rejected save now shows as a `redirects` bump keyed `SessionExpired`/`PageModified` instead of vanishing.

## Error handling / edge cases

- **No reserved *correlation* name.** The nonce lives in the `<…>` generator namespace, and its wire marker `@nonce` uses a `@` that `Capture.NAME_PATTERN` (`[A-Za-z0-9_]+`) can't produce — so it can never collide with an author-named capture, and the `${{}}` namespace stays purely author-controlled. (Reserving one keyword `<nonce>` in the generator namespace is like `<int>`/`<string>` — the correct home for value primitives; note it in the grammar-authoring docs. A future generated token (`<uuid>`, `<threadid>`) adds one more generator + one more `substitute` branch, same pattern.)
- **Capture from a 3xx under no-follow (new reachable state):** `getInputStream()` does not throw on `3xx` (only `≥400`), so a capture-bearing step that redirects reads a usually-empty body → `Capture.extract` returns `null` → surfaces downstream as a normal `captureMiss`. Benign and correct; noted because no-follow makes it reachable.
- `${{@nonce}}` with `bindings == null` (an uncorrelated-but-nonce body): the branch runs before any bindings deref, so no NPE.
- Multiple `${{@nonce}}` in one body: each occurrence takes the next counter value (all unique) — fine for uniqueness.
- `fire`'s existing drain-to-EOF + `-1`-on-transport-error paths unchanged; a `3xx` has a small/empty body.

## Testing

- `substitute` nonce: `substitute("x=${{@nonce}}", empty-map)` yields `x=<millis>-<n>`; two calls yield **different** values; a body with `${{@nonce}}` + an unbound `${{csrf}}` still returns `null`; `${{@nonce}}` needs no binding and never NPEs on a null bindings map.
- `RequestGrammar`: `$rev = <nonce>` in a route → `expandAll`/`randomRequest` yields the marker `${{@nonce}}` verbatim (the generator output isn't re-scanned), and `RequestLine.parse(...).needsSubstitution()` is true for that line.
- `fire` 3xx (JDK `HttpServer`): a handler returning `302` with `Location: /Wiki.jsp?page=SessionExpired` → `fire` returns `302` (not followed), and driving it increments `redirects` with `redirectTargets` key `SessionExpired`. A `200` increments neither. **Plus a `302`-carrying-`Set-Cookie` test** asserting the jar still captures it (pins the DD-035 interaction).
- `summaryJson` includes `redirects`/`redirectTargets`; update its **two** callers — `LoadRun.run` and `LoadDriftUnavailableTest` (the only ones) — for the new params.
- Full suite green. **`LoadFireTest` is NOT affected by the no-follow flip** (its handlers return `200` directly, never via a redirect — verified), so no existing assertion needs repair; the 3xx coverage is a *new* redirect-handler test, not a fix to an old one.

## Rejected alternatives

- **Random/UUID nonce:** works, but the `RUN_SALT + AtomicLong` token is smaller, allocation-lean, and equally unique; no need for randomness.
- **Single `AtomicLong` *seeded* with millis (no separate salt):** the seed and the counter advance in different units (wall-clock ms vs number-of-saves), so under load a later run's seed lands inside a prior run's emitted counter range and re-emits still-saved values → cross-run no-op collisions. Rejected for the two-field salt+counter (see Uniqueness).
- **Packing salt+counter into one integer's high/low bits:** caps a run's save count (bits spent on the salt) and collides when two runs start in the same millisecond. Rejected — the `<millis>-<counter>` string has neither limit and stays URL-safe.
- **Keep auto-follow, infer the target from `getURL()`:** `HttpURLConnection.getURL()` returns the *original* URL after following, not the final — so the redirect target isn't recoverable without disabling follow. Rejected.
- **3xx classification in explore too:** explore optimizes coverage, not load metrics; following redirects there reaches more code. Keep it load-only.

## Scope

One cohesive feature, one plan. Touch: `runner/coverage/RequestGrammar.java` (`<nonce>` generator → `${{@nonce}}` marker), `runner/coverage/LoadRun.java` (`substitute` `@nonce` branch + `RUN_SALT`/`NONCE`; `fire` no-follow + `redirects`/`redirectTargets`; worker loop; `summaryJson`), `examples/grammar/jspwiki.grammar` (`$rev = <nonce>` + `${rev}` in `edit_save`), tests, and the DD-038 doc/CHANGELOG. `CoverageGuidedRun` is untouched except that it already shares `substitute` (so `${{@nonce}}` works there for free). No new dependencies.
