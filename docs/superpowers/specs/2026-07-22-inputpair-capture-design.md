# DD-037: `inputpair` capture — dynamic-name form-field correlation

**Status:** design approved (encoding model A), 2026-07-22.
**Builds on:** DD-036 response correlation (`runner/coverage/Capture.java`, `RequestLine`, `LoadRun.substitute`).
**Motivation source:** `.superpowers/sdd/jspwiki-save-rootcause.md`.

## Problem

DD-036 lets a replay step capture a value from a response and substitute it into a later request
(`<<name=input:FIELD` → `${{name}}`). It only handles inputs with a **statically known name**.

Apache JSPWiki's `SpamFilter.checkHash` (called unconditionally from `Edit.jsp`, so no config can
disable it — confirmed by bytecode + live A/B in the root-cause doc) rejects any anonymous save with
`302 → SessionExpired` unless the POST replays a hidden field whose **name is itself dynamic**: 6
random lowercase letters (e.g. `ztbams`), session-pinned, rotated daily, with a value of
`lastModified ^ hash(clientIP)` that changes after every save. Because the field name is not known at
grammar-authoring time, DD-036 cannot express it — the grammar can't write `ztbams=${{...}}`. This
blocks JSPWiki (and any app using randomized-name anti-CSRF/bot-trap fields, a common CMS pattern)
from being a write-path benchmark data point.

## Goal

Capture a whole `name=value` form-input **pair**, where both name and value are selected by regex,
and substitute the pair as a ready-to-splice form-body fragment into a later request. Also: allow
**more than one capture per step**, because the motivating case needs two values (`X-XSRF-TOKEN` and
the spam-hash pair) from the *same* edit-form GET.

## Design

### Wire syntax

A new capture kind on the existing `<<name=kind:arg` grammar:

```
<<spam=inputpair:<nameRegex>=<valueRegex>
```

`arg` is split on its **first** `=` into `nameRegex` / `valueRegex` (documented limitation: a regex
containing a literal `=` is not expressible; none of our targets need one). Reference with `${{spam}}`
in a later request body; it expands to `name=value` (each half URL-encoded, the joining `=` literal).

JSPWiki `edit_save` (`examples/grammar/jspwiki.grammar`) becomes:

```
@sequence edit_save
  /Edit.jsp?page=${page} <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+
  POST /Edit.jsp page=${page}&action=save&X-XSRF-TOKEN=${{csrf}}&${{spam}}&_editedtext=${wikitext}&ok=Save
  /Wiki.jsp?page=${page}
```

The value regex is required for disambiguation: `action` (value `save`) also matches a `[a-z]{6}`
name, so the numeric `-?[0-9]+` value regex is what selects the spam-hash input specifically.

### Encoding model (decision A: encode-at-capture, verbatim-substitute)

The core change is where URL-encoding happens. Today `LoadRun.substitute` URL-encodes the whole bound
value; a pair binding `ztbams=1719016235` would have its structural `=` mangled to `%3D`. So:

- **`Capture.extract` now returns the final body-ready substitution TEXT**, not the raw value:
  - `HEADER` → `urlEncode(headerValue)`
  - `INPUT` → `urlEncode(inputValue)`
  - `INPUTPAIR` → `urlEncode(matchedName) + "=" + urlEncode(matchedValue)`
  - `null` on any miss (unchanged).
- **`LoadRun.substitute` becomes a verbatim replace**: `${{name}}` → `bindings.get(name)`, no
  encoding. Still returns `null` if any referenced name is unbound (unchanged skip-and-count behavior).

`urlEncode` = `URLEncoder.encode(s, StandardCharsets.UTF_8)` — the same encoder `substitute` uses
today. Moving it into `extract` keeps the bindings map a plain `Map<String,String>` holding
"exact text to splice in," makes `substitute` trivially correct for both value and pair bindings, and
requires no change to the `bindings` map type or to `fire`/`request`/`runSequence` signatures.

**Net effect on existing `input:`/`header:` captures: identical.** The final request body is the same
whether the value is encoded in `extract` (new) or in `substitute` (old) — encoding just moves one
layer. The only code consequence is that the shipped test asserting `substitute` URL-encodes moves to
assert `Capture.extract` URL-encodes.

### Multiple captures per step

DD-036's `RequestLine` carries a single `Capture capture` and `parse` peels exactly one trailing
`<<…` token. JSPWiki's `edit_save` step 1 needs **two** captures (`<<csrf=input:X-XSRF-TOKEN` and
`<<spam=inputpair:…`) from one GET, so this must generalize:

- `RequestLine`'s field becomes `List<Capture> captures` (was `Capture capture`); the accessor is
  `captures()`. An empty list means "no capture" (replaces `null`).
- `parse` peels trailing `<<…` tokens in a **loop**: while the last space-delimited token starts with
  `<<` and `Capture.parse` succeeds, strip it and prepend to the list (so the list preserves left-to-
  right source order). The DD-036 back-compat guard is preserved: a trailing token that does **not**
  parse as a `Capture` is left untouched in the body, so v1/v2 lines are byte-identical.
- `format` re-emits every capture in order (`" " + c.format()` for each), so
  `format(parse(line)) == line` still round-trips, now for multi-capture lines.
- The 3-arg convenience ctor becomes `this(m, p, b, List.of())`.
- Both engines' capture branches (`LoadRun.fire`, `CoverageGuidedRun.request`) iterate
  `step.captures()`, extract each, and `bindings.put(c.name(), value)` (or count a miss) per capture —
  the existing single-capture logic wrapped in a loop. `needsSubstitution()` (body-based) is unchanged.

### Components

1. **`runner/coverage/Capture.java`**
   - `enum Kind { HEADER, INPUT, INPUTPAIR }`.
   - `parse`: accept `inputpair`; its `arg` must contain a `=` splitting two non-empty halves, and
     both halves must compile as regex (`Pattern.compile` in a try; on `PatternSyntaxException` return
     `null`). Malformed → `null` (the trailing `<<…` then stays in the body, per `RequestLine`
     back-compat, exactly as a malformed `input:` does today).
   - `extract`: HEADER/INPUT return `urlEncode(value)`; INPUTPAIR scans `<input>` tags (reuse
     `INPUT_TAG_PATTERN`), and for each extracts its `name` and `value` attributes, returning
     `urlEncode(name)=urlEncode(value)` for the **first** input whose name matches `nameRegex` AND
     value matches `valueRegex`; `null` if none. (Name extraction needs a `NAME_ATTR_PATTERN` analogous
     to the existing `VALUE_PATTERN`.)
   - `format`: round-trips `inputpair` (`format(parse(x)) == x`).
2. **`runner/coverage/RequestLine.java`** — `capture` field → `List<Capture> captures` (accessor
   `captures()`); `parse` loops to peel multiple trailing `<<…` tokens; `format` re-emits all;
   3-arg ctor delegates with `List.of()`. (See "Multiple captures per step" above.)
3. **`runner/coverage/LoadRun.java`** — `substitute`: drop the `URLEncoder` call; verbatim replace.
   `fire`'s capture branch loops over `step.captures()`. Everything else (null-on-unbound, the
   `${{name}}` scan) unchanged.
4. **`runner/coverage/CoverageGuidedRun.java`** — `request`'s capture branch loops over
   `step.captures()` (was a single `step.capture()`).
5. **`examples/grammar/jspwiki.grammar`** — add the `<<spam=inputpair:[a-z]{6}=-?[0-9]+` capture to
   step 1 of `edit_save` and `&${{spam}}` to the save POST body.
6. **Docs** — DD-037 record in `docs/DESIGN-DECISIONS.md`; a corpus-v3.1 note in
   `docs/LOAD-MODE-DESIGN.md`; a `runner/CHANGELOG.md` `[Unreleased]` bullet.

### Data flow (JSPWiki save)

GET `/Edit.jsp?page=Main` → `extract` binds `csrf`→`urlEncode(uuid)` and `spam`→`ztbams=1719016235`
(both halves encoded; here no special chars) → POST body `…&X-XSRF-TOKEN=${{csrf}}&${{spam}}&…`
→ `substitute` (verbatim) → `…&X-XSRF-TOKEN=<enc-uuid>&ztbams=1719016235&…` → save accepted (2xx/3xx).

### Error handling / edge cases

- No input matches both regexes → `extract` returns `null` → `substitute` returns `null` → step
  skipped, `captureMisses` incremented (existing DD-036 semantics; warmup-gated in load).
- Grammar `expand` already preserves `${{spam}}` verbatim (DD-036 Task 3 guard covers any `${{…}}`).
- The corpus/finding persistence still stores the recipe, never the token (DD-036 token-leak fix
  unchanged — `runSequence` labels with the raw `step`).
- Load caveat (from root-cause): concurrent workers saving the *same* page race; losers get a real
  `302 PageModified` conflict. Spreading `${page}` keeps the persisted-write rate high — a corpus/data
  concern, not a code concern.

### Testing

- `Capture` parse/format round-trip for `inputpair`; assert the arg splits on its **first `=`** into
  name/value regex (a regex needing a literal `=` is the documented unsupported case). A `:` inside
  the arg is fine — only the first `:` separates `kind` from `arg`.
- `RequestLine` multi-capture: `parse` of `path <<a=input:X <<b=inputpair:[a-z]{2}=[0-9]+` yields two
  captures in source order; `format(parse(line)) == line`; a v1/v2 line (no `<<`, or a trailing
  non-capture token) is byte-identical (back-compat) and yields an empty capture list.
- `extract` INPUTPAIR: selects the right input among decoys (`action=save` present but excluded by the
  numeric value regex); returns `name=value` with each half URL-encoded; a name/value containing `+`,
  `=`, or `&` is encoded so it can't inject extra params.
- `extract` INPUT/HEADER now URL-encode (the migrated assertion).
- `substitute` verbatim: adjacent `${{csrf}}${{spam}}`, unbound-name → `null`.
- End-to-end (`ExploreCorrelationTest`-style, JDK `HttpServer`): a form with a random-named numeric
  input + a static-named CSRF input; `runSequence` binds both and the server receives a POST whose body
  carries `ztbams=<value>` and the substituted CSRF.
- Full suite stays green.

## Rejected alternatives

- **B — typed binding map** (`Map<String,Bound>` carrying the kind, rendered per-kind in `substitute`):
  cleanest separation, but changes the bindings type across both engines and the `fire`/`request`
  signatures — the largest footprint for no functional gain over A.
- **C — sentinel delimiter** (`extract` returns `namevalue`, `substitute` splits and encodes each
  half): smallest diff, but hides structure inside a `String` — implicit typing a reviewer would flag.
- **Config-disable JSPWiki's SpamFilter**: impossible — `SpamFilter.checkHash` is hard-wired into
  `Edit.jsp`, independent of filter registration (root-cause doc §1).
- **Two separate captures for name and value**: impossible — the dynamic name and its value must come
  from the *same* matched input, and the grammar cannot name the dynamic field.

## Scope

One cohesive feature, one plan — two coupled capabilities (`inputpair` kind + multiple captures per
step) that the JSPWiki write path needs together. Touch: `Capture.java`, `RequestLine.java`,
`LoadRun.java`, `CoverageGuidedRun.java`, `examples/grammar/jspwiki.grammar`, tests, and the three doc
files. No operator/CLI changes. No new dependencies (regex + `URLEncoder` only).
