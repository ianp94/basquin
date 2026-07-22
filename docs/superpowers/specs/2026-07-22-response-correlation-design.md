# DD-036: Response correlation — capture/substitute dynamic tokens in replay sequences

**Status:** approved (design), ready for planning
**Date:** 2026-07-22

## Problem

DD-035 made replay method-, session-, and sequence-aware (corpus v2: a line is a TAB-separated ordered sequence of `METHOD? path( SP body )?` steps; each load worker replays a sequence in order with a per-worker cookie jar). But every value in a v2 line is **fixed at emit time**, so any write path guarded by a token scraped from a prior response is unreachable:

- **JSPWiki** (verified): page-save POSTs `/Edit.jsp` with `_editedtext`, `page`, `action=save`, and an **`X-XSRF-TOKEN`** hidden field whose value comes from the GET of the edit form, valid only for that session/form. A replayed static POST carries a dead token → the save is rejected (4xx).
- **JPetStore**: Stripes form handlers want a `_sourcePage` hidden field emitted into the form GET.
- Broadly: Spring Security and most modern form stacks default to CSRF tokens. Without correlation Basquin can neither load-drive nor fuzz their write paths — exactly the stateful paths that differentiate the tool (a read-only mix on a cache-friendly app like JSPWiki does NOT differentiate; every route warms to ~23ms).

This is what Gatling calls `.check(...).saveAs(...)` + `${...}` and JMeter calls extractors + variables: **capture a value from response N, substitute it into request N+k**. The DD-035 ordered-sequence model already guarantees the happens-before between capturing and referencing steps; this DD adds the capture and the substitution.

## Design decisions

### 1. Syntax — corpus v3: a capture suffix per step, a `${{name}}` reference in later steps
A v3 step extends the v2 step with one optional trailing capture token: `METHOD? path( SP body )?( SP <<name=kind:arg )?`.
- **Capture** `<<name=kind:arg` — the last space-separated token of a step iff it starts with `<<`. `name` ∈ `[A-Za-z0-9_]+`; `kind:arg` ∈ `header:Header-Name` | `input:FIELD_NAME` (the `value="…"` of the `<input>` whose `name` == `FIELD_NAME`).
- **Reference** `${{name}}` — appears in a *later* step's body; replaced at fire time, URL-encoded (form-urlencoded bodies; `_sourcePage` is Base64 with `+`/`=`).
- One capture per step; substitution in body only (MVP).

**Unambiguous** in the DD-035 spirit: a form-urlencoded body cannot contain a literal space (→ `+`/`%20`) or `<` (→ `%3C`), so ` <<` can't occur inside real content; `${{` can't be produced by any grammar generator. TAB still terminates the step; v2 splitting is unchanged. **Backward-compatible:** a step with no `<<`/`${{` parses and fires byte-for-byte as today (v1/v2 corpora untouched); version skew degrades *visibly* (a stale binary sends literal `${{csrf}}` → a 403, not a plausible-wrong success).

The two `${…}` forms coexist and differ by binding time: `${param}` binds once per sequence *expansion* (grammar time, DD-018); `${{name}}` binds once per sequence *execution* (fire time, from a live response).

Concrete corpus line (JSPWiki edit-save; ⇥ = TAB):
```
/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN⇥POST /Edit.jsp page=Main&action=save&X-XSRF-TOKEN=${{csrf}}&_editedtext=…
```
Grammar (`@sequence`, new `examples/grammar/jspwiki.grammar`):
```
@sequence edit_save
  /Edit.jsp?page=${pageName} <<csrf=input:X-XSRF-TOKEN
  POST /Edit.jsp page=${pageName}&action=save&X-XSRF-TOKEN=${{csrf}}&_editedtext=${wikitext}
  /Wiki.jsp?page=${pageName}
```
JPetStore Stripes `_sourcePage` follows the same idiom (`<<src=input:_sourcePage` on the form GET, `_sourcePage=${{src}}` on the submit POST).

### 2. Extraction — two extractors, no HTML parser
New `runner/coverage/Capture.java`: `record Capture(String name, Kind kind, String arg)` with `Kind = HEADER | INPUT`, `parse(String)`, `format()`, `extract(Function<String,String> headerLookup, String body) -> String|null`.
- `HEADER`: `HttpURLConnection.getHeaderField(arg)` (by-name lookup is case-insensitive — no `getHeaderFields()` map trap).
- `INPUT`: two precompiled regexes over `<input …>` tags matching `name`==`arg`, pulling `value` (both quote styles, either attribute order), then minimal HTML-entity unescape (`&amp; &quot; &lt; &gt; &#39;` — reuse `CoverageGuidedRun.unescapeHtml`).

Rationale for regex over a parser (jsoup/CSS): the runner's hot path is deliberately dependency-free (bare `HttpURLConnection`, hand-rolled JSON); a hidden token field is machine-generated markup, not adversarial HTML; a regex extractor is exactly the JMeter/Gatling CSRF recipe. A `re:pattern` kind is the deferred escape hatch.

**Binding scope:** a `Map<String,String>` scoped to **one sequence execution** — created when a correlated sequence starts, dead when it ends. Load: next to (not inside) the per-worker cookie jar; explore: local to one `runSequence()`. Never shared across workers, never cached across executions (the token-rotation answer), never allocated for uncorrelated sequences.

### 3. Runtime wiring (exact touch points)
- **`RequestLine.java:11-65`** — 4th record component `Capture capture` (3-arg convenience ctor with capture=null kept so all call sites/tests compile). `parse`: split a trailing `<<…` token → `Capture.parse` (malformed → logged once, treated absent). `format`: re-append ` `+`capture.format()`. Precompute `boolean needsSubstitution = body != null && body.contains("${{")`. A small `ReplaySequence(List<RequestLine>, boolean correlated)` wrapper (correlated = any step has a capture or reference) so the worker's per-iteration check is one boolean (allocation-lean).
- **`LoadRun.java:102-125`** — worker loop: after picking `seq`, `Map bindings = seq.correlated ? new HashMap<>(4) : null`. Inside the step loop, after the deadline check and before `t0`: if `step.needsSubstitution`, resolve body; an unresolvable ref → step **not fired**, `captureMiss++`, continue (skip-step, not abort-sequence). Warmup gate, baseline CAS, histogram/requests/serverError recording untouched.
- **`LoadRun.java:218-268` `fire()`** — gains the bindings map. After `getResponseCode()`+`captureSessionCookie()`: no capture → today's drain loop unchanged (no-capture fast path is literally today's code); capture present → read body into a capped 256KB buffer, `Capture.extract`, `bindings.put` on success / count miss on null. Extraction cost lands inside the step's measured latency (honest; body-read was already the drain).
- **`LoadRun.java:52-55, 192-205`** — add `captureMisses` and `clientErrors` (4xx) counters + summary fields. A rejected CSRF is a 403/redirect, invisible today; `clientErrors` is the correlation-failure signature (DD-035 honesty principle on the write path).
- **`CoverageGuidedRun.java:492-504 runSequence`** — per-run bindings; parse each step once; substitute-before-fire; skip-and-count misses; pass map into `request`.
- **`CoverageGuidedRun.java:536-603 request`** — new overload `request(base, step, bindings)` (existing signature delegates with null — `login()` + single-step main-loop call unchanged). When the step carries a capture and `code<500`, keep the body (256KB cap) and extract. The 5xx path (crash finding) is unchanged.
- **`RequestGrammar.java:236-250 expand` (MVP-CRITICAL)** — `expand` currently sees `${{csrf}}` as placeholder `{csrf`, finds no rule, expands to `""` → silently destroys the reference. `expand` MUST skip `${{…}}` verbatim (two-char lookahead at `open+2`, copy through the matching `}}`). Without this the feature is dead for grammar-authored sequences.
- **Ordering lint** — a reference with no earlier-step capture of that name logs one warning per corpus load. No new mechanism (ordered sequence already guarantees order).

### 4. Fuzzing preserved
Captured values are pass-through by construction — they live only in the per-execution bindings map, spliced at fire time, never seen by mutation. Everything else stays fuzzable: grammar `${pageName}`/`${wikitext}` re-bind every `expandSequence`, `${{csrf}}` rides through expansion untouched (§3 guard). `CoverageGuidedRun.mutate` already refuses TAB-joined lines, so emitted sequences are replayed not string-mutated. Correlation makes the write handler *reachable*; the grammar then probes the payload — availability cost is in what the app does with `_editedtext` (store mutation, cache invalidation, reindex), not the token.

### 5. Emission — the corpus stores the recipe, never the token
Grammar expansion leaves `<<…`/`${{…}}` verbatim (only `${param}` resolved); `runSequence` substitutes on a *copy* at fire time; the main loop's `sequence` list still holds the annotated form when a coverage find fires `corpus.consider(formatSequenceForCorpus(sequence), …)`. So the emitted line carries the recipe, never the session-bound value (which would fail on replay AND persist a credential into the ConfigMap). Byte-budget: `<<csrf=input:X-XSRF-TOKEN` (25B) + `${{csrf}}` (9B) is budget-neutral-to-negative vs baking in a ~36B live token; `firstPath` filter + `jsonString` TAB-escaping unaffected.

### 6. Scope + phasing
**MVP (this DD):** the `<<name=kind:arg`/`${{name}}` syntax; `header:`+`input:` kinds; one capture per step; body-only substitution; per-execution binding scope; skip-and-count on miss; `captureMisses`+`clientErrors` summary fields; the `expand` skip-guard; `RequestLine` v3 parse/format round-trip; a hand-authored `examples/grammar/jspwiki.grammar` `edit_save` sequence; a `_sourcePage` sequence in `examples/grammar/jpetstore.grammar`. Server-free unit tests (DD-021): `Capture.parse/format/extract` (both kinds, both quote styles, attribute order, entity unescape, truncation miss), `RequestLine` v3 round-trip + v1/v2 backcompat, substitution incl. URL-encoding of `+`/`=`, the `expand` guard, skip-on-miss worker behavior.
**Deferred:** multiple captures/step; substitution into path/query/headers; `re:pattern`; `json:pointer`; CSS selectors (rejected — imply the parser §2 refuses).

**Proof:** a JSPWiki campaign, arm A = read-only corpus, arm B = reads + the correlated `edit_save`. Success = arm B's save POSTs return 2xx/3xx with `captureMisses≈0`, `clientErrors≈0` (a static-token control run shows ~100% 4xx on the save — the before/after), and arm B shows write-path availability cost (p99 + heap drift from store mutation + cache invalidation) that arm A's cached reads structurally cannot.

### 7. Risks
- **Token rotation over a soak** — bindings re-captured every sequence execution (never cached per-worker/run). A "capture once, reuse" optimization is rejected (correctness beats one saved GET).
- **Capture miss / unresolved ref** — step not fired (firing literal `${{csrf}}` is manufactured 403 noise); miss counted; first few occurrences logged (rate-limited); remaining steps still run (matches explore's existing "a failing step doesn't abort the sequence").
- **Multi-worker isolation** — bindings stack-local to one worker's one execution; jar stays per-worker.
- **No-capture fast path** — uncorrelated sequences take today's exact code (no map alloc, drain loop untouched, zero per-step scans; `correlated`/`needsSubstitution` precomputed).
- **Truncated response** (token past 256KB) — miss, counted, not silent (real CSRF fields sit near the top).
- **Redirect-following** — extraction runs on the final response body (correct for form-GET).

### Rejected alternatives
Full HTML parser / CSS selectors (dependency-free invariant; `re:` is the escape hatch); JSON-lines corpus (4KB termination budget); recording live token values (fails on replay + credential-in-ConfigMap); automatic CSRF auto-forward (erases the fuzz/pass-through boundary — could revisit as an explore-phase *suggester*, not a replay behavior); capture-once-per-worker caching (rotation correctness).

## Files touched
`runner/coverage/RequestLine.java`, `runner/coverage/Capture.java` (new), `runner/coverage/LoadRun.java`, `runner/coverage/CoverageGuidedRun.java`, `runner/coverage/RequestGrammar.java`; `examples/grammar/jspwiki.grammar` (new), `examples/grammar/jpetstore.grammar`; `test/runner/coverage/{CaptureTest,RequestLineV3Test,…}`. No operator/CRD changes. DD-036 record → `docs/DESIGN-DECISIONS.md`.
