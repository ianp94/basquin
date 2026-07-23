# DD-038: `${{nonce}}` unique-payload token + Location-classified 3xx counters

**Status:** design approved (Component 1 + Component 2 Option A), 2026-07-22.
**Builds on:** DD-036/037 response correlation (`LoadRun.substitute`, `${{name}}` refs, encoding model A).
**Motivation source:** `.superpowers/sdd/jspwiki-runner-save-rootcause.md`.

## Problem

Two findings from validating DD-037's correlated JSPWiki writes end-to-end:

1. **Idempotent write replays are invisible.** A load corpus replays a **fixed** request body per line. Change-detecting apps no-op an identical write: JSPWiki's `DefaultPageManager.saveText` returns *before writing* when `oldText.equals(proposedText)`, yet still issues the same `302 → success` redirect. So after the first replay a page never changes again, and the "save" costs nothing — the write-path cost the benchmark wants to measure disappears. This is a general CMS pattern, not JSPWiki-specific.
2. **Redirect outcomes are invisible in load metrics.** `LoadRun.fire` sets `setInstanceFollowRedirects(true)`, so a save's `302` is auto-followed to a final `200`. A *rejected* save (`302 → SessionExpired`/`Forbidden`/`PageModified`) is therefore counted as a normal request — not a 4xx (`clientErrors`) or 5xx (`serverError`), and capture succeeded so not a `captureMiss`. Rejections are silent.

## Goal

(1) Make every replayed write body unique so each save is a *real* change. (2) Make redirect outcomes visible in the load terminal summary.

## Component 1 — `${{nonce}}` unique-payload token

A **reserved** correlation-reference name. `LoadRun.substitute` (shared by both engines) already scans `${{name}}` via `CORRELATION_REF_PATTERN`. Add one branch, before the bindings lookup:

```java
static final java.util.concurrent.atomic.AtomicLong NONCE =
        new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
// inside substitute()'s while loop, name = m.group(1):
if (name.equals("nonce")) {
    out.append(Long.toString(NONCE.getAndIncrement()));   // URL-safe digits; verbatim-safe (model A)
} else {
    String value = bindings == null ? null : bindings.get(name);
    if (value == null) return null;
    out.append(value);
}
```

- **Uniqueness:** a single process-wide `AtomicLong`, seeded with `currentTimeMillis()`. Unique *within* a run (monotonic increment, thread-safe across workers) AND *across* runs (millis seed — a later run never re-emits a prior run's saved text). Value is decimal digits → URL-safe, spliced verbatim (no encoding needed under model A).
- **`nonce` never triggers the skip-and-count path:** it is generated, not looked up, so it never returns `null`. A body with both `${{nonce}}` and an unbound `${{csrf}}` still returns `null` (the real capture miss) — nonce does not mask it.
- **A nonce-only body needs no capture:** `needsSubstitution()` (body contains `${{`) is already true, so `seq.correlated()` is true and the worker runs `substitute` with an allocated (possibly empty) bindings map. Works unchanged in both `LoadRun` (worker loop) and `CoverageGuidedRun` (`runSequence`), which share `substitute`.
- **Grammar:** JSPWiki `edit_save`'s POST gets `${{nonce}}` appended to `_editedtext` (e.g. `_editedtext=${wikitext} ${{nonce}}`), so every replayed save differs and JSPWiki's no-op guard never fires.

## Component 2 — Location-classified 3xx counters (load mode only)

`LoadRun.fire` stops auto-following redirects; the load driver then observes and classifies the direct response.

- `c.setInstanceFollowRedirects(false)` in `fire`. The capture path is unaffected (the edit-form GET is a plain `200`; `captureSessionCookie` already walks `Set-Cookie` headers regardless of following).
- New counters alongside `serverError`/`clientErrors`: a `redirects` `AtomicLong` (any `3xx`), and a bounded `redirectTargets` `ConcurrentHashMap<String,LongAdder>` keyed by a normalized `Location` label. Normalization: if `Location` has a `page=<X>` query param, key = `<X>` (JSPWiki's reject pages are `SessionExpired`/`PageModified`); else the last path segment; unknown/absent → `"?"`. Cap distinct keys (e.g. 12); overflow folds into `"other"`.
- Worker recording block (post-warmup): `else if (code >= 300 && code < 400) { redirects.incrementAndGet(); redirectTargets bump; }` — mutually exclusive with the existing `code>=500`/`code>=400&&<500` branches. A `2xx` counts as neither.
- `summaryJson` gains `"redirects":N` and `"redirectTargets":{...}` after `clientErrors`. `StatusReporter` is **untouched** (terminal-summary-only, per DD-036).
- **Behavior change (documented):** load mode no longer follows redirects — it measures the server's direct response to the exact request (arguably more correct for a load test) and surfaces rejects. Latency for a redirecting request drops the follow hop. `CoverageGuidedRun` (explore) keeps its follow behavior (coverage, not redirect metrics).

## Data flow (JSPWiki honest write)

Corpus `edit_save` POST body `…&_editedtext=<wikitext> ${{nonce}}&…` → `substitute` splices the next `NONCE` value → each fire's body is unique → `saveText` writes a new version every time (real write cost in latency/heap). A rejected save now shows as a `redirects` bump keyed `SessionExpired`/`PageModified` instead of vanishing.

## Error handling / edge cases

- **`nonce` is a RESERVED reference name.** `substitute` checks `name.equals("nonce")` before the bindings lookup, so a capture named `nonce` (`<<nonce=…`) would be shadowed by the generated value. Document it (grammar authors must not name a capture `nonce`); no code guard needed for this unlikely collision.
- `${{nonce}}` with `bindings == null` (an uncorrelated-but-nonce body): the branch runs before any bindings deref, so no NPE.
- Multiple `${{nonce}}` in one body: each occurrence takes the next counter value (all unique) — fine for uniqueness.
- `redirectTargets` cardinality is bounded (top-N + `"other"`) so a fuzz corpus can't blow up the map or the ~4 KB termination-message budget.
- `fire`'s existing drain-to-EOF + `-1`-on-transport-error paths unchanged; a `3xx` has a small/empty body.

## Testing

- `substitute` nonce: `substitute("x=${{nonce}}", empty-map)` yields `x=<digits>`; two calls yield **different** values; a body with `${{nonce}}` + an unbound `${{csrf}}` still returns `null`; `${{nonce}}` needs no binding.
- `fire` 3xx (JDK `HttpServer`): a handler returning `302` with `Location: /Wiki.jsp?page=SessionExpired` → `fire` returns `302` (not followed), and driving it increments `redirects` with `redirectTargets` key `SessionExpired`. A `200` increments neither.
- `summaryJson` includes `redirects`/`redirectTargets`; update the `LoadDriftUnavailableTest`/any `summaryJson` caller for the new params.
- Grammar: `edit_save` POST expands with `${{nonce}}` preserved verbatim (grammar `expand` already skips `${{…}}`).
- Full suite green; existing load tests still pass (the follow-redirects change: confirm `LoadFireTest` expectations — a test asserting a followed-to-200 now sees the 3xx; update it to reflect the no-follow behavior, or point it at a non-redirecting handler).

## Rejected alternatives

- **Random/UUID nonce:** works, but a monotonic `AtomicLong` is smaller, allocation-lean, and equally unique; no need for randomness.
- **Keep auto-follow, infer the target from `getURL()`:** `HttpURLConnection.getURL()` returns the *original* URL after following, not the final — so the redirect target isn't recoverable without disabling follow. Rejected.
- **Per-run nonce prefix string (`<runid>-<n>`):** unnecessary — the millis seed already gives cross-run uniqueness, and a bare number keeps the body URL-safe with no encoding.
- **3xx classification in explore too:** explore optimizes coverage, not load metrics; following redirects there reaches more code. Keep it load-only.

## Scope

One cohesive feature, one plan. Touch: `runner/coverage/LoadRun.java` (`substitute` + `NONCE`; `fire` no-follow + `redirects`/`redirectTargets`; worker loop; `summaryJson`), `examples/grammar/jspwiki.grammar` (`edit_save` `${{nonce}}`), tests, and the DD-038 doc/CHANGELOG. `CoverageGuidedRun` is untouched except that it already shares `substitute` (so `${{nonce}}` works there for free). No new dependencies.
