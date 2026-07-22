# DD-037: inputpair capture + multi-capture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a replay step capture a whole dynamic `name=value` form-input pair (regex-selected) and carry more than one capture per step, so JSPWiki's randomized-name spam-hash write path becomes replayable.

**Architecture:** Extends DD-036 response correlation. Encoding moves from `LoadRun.substitute` into `Capture.extract` (model A: extract returns the final URL-encoded body text, substitute becomes a verbatim splice) so a pair binding `name=value` can be spliced with only its two halves encoded. A new `Capture.Kind.INPUTPAIR` selects an input by anchored name+value regex. `RequestLine.capture` becomes `List<Capture> captures` so one GET can bind several values.

**Tech Stack:** Java 17, JUnit 4, Gradle. Package `runner.coverage`. No new dependencies.

## Global Constraints

- **Encoding model A (verbatim):** after this feature, `Capture.extract` returns the final body-ready text (URL-encoded); `LoadRun.substitute` inserts bindings **verbatim** (no encoding). Net request body for existing `input:`/`header:` captures is byte-identical.
- **Anchored matching:** `inputpair` name/value regexes match the **whole** attribute value (`Matcher.matches()`, never `find()`) — DD-036 exact-match precedent (`Capture.java:65`, `CaptureTest.nameSubstringDoesNotFalseMatch`).
- **Back-compat:** a v1/v2 corpus/grammar line (no `<<`, or a trailing token that fails `Capture.parse`) parses and fires byte-for-byte as before; `format(parse(line)) == line`.
- **Captures are trailing:** all `<<…` tokens come after method/path/body; the peel loop consumes only from the end.
- **Token-leak invariant unchanged:** findings/crash records are labeled with the raw recipe `step`, never the substituted body (`runSequence`).
- `urlEncode(x)` everywhere means `java.net.URLEncoder.encode(x, java.nio.charset.StandardCharsets.UTF_8)`.
- Build/test: `./gradlew test --tests runner.coverage.<Test>` for one class, `./gradlew test` for the full suite. All existing tests stay green at every task boundary.

---

### Task 1: Encoding model A — extract encodes, substitute is verbatim

Behavior-preserving refactor: move URL-encoding out of `substitute` into `Capture.extract`. No new capability; the request bytes are identical. Isolating it first keeps the later tasks clean.

**Files:**
- Modify: `runner/coverage/Capture.java` (`extract`, lines 59-75)
- Modify: `runner/coverage/LoadRun.java` (`substitute`, lines 345-359)
- Test: `test/runner/coverage/CaptureTest.java` (migrate extract-return assertions), `test/runner/coverage/LoadCorrelationTest.java` (migrate the substitute-encodes test)

**Interfaces:**
- Produces: `Capture.extract(Function<String,String> headerLookup, String body)` now returns the **URL-encoded** value (or `null` on a miss). `LoadRun.substitute(String body, Map<String,String> bindings)` now splices bindings **verbatim** (still returns `null` if any referenced name is unbound).

- [ ] **Step 1: Migrate the two assertions that pin the OLD encoding location to fail first**

In `test/runner/coverage/CaptureTest.java`, `inputExtractBasic` uses a body whose input value is `abc+/=` and asserts `extract(...)` returns the raw `abc+/=`. Change the expected value to the URL-encoded form:
```java
// inputExtractBasic: value in the form is "abc+/="; extract now returns it URL-encoded
assertEquals("abc%2B%2F%3D", c.extract(h -> null, body));
```
In `test/runner/coverage/LoadCorrelationTest.java`, `substituteUrlEncodesPlusAndEqualsInBoundValue` (≈lines 47-54) asserts `LoadRun.substitute` URL-encodes. Under model A that responsibility moves to `Capture.extract`. **Rewrite it** as an extract test (keep the method name or rename to `extractUrlEncodesPlusAndEquals`): feed an `input` whose value is `a+b=c` and assert `Capture.parse("<<x=input:F").extract(h->null, "<input name=\"F\" value=\"a+b=c\">")` equals `a%2Bb%3Dc`. Remove the old `substitute`-encodes assertion.

- [ ] **Step 2: Run the two tests — they must FAIL against current code**

Run: `./gradlew test --tests runner.coverage.CaptureTest --tests runner.coverage.LoadCorrelationTest`
Expected: FAIL — `inputExtractBasic` still returns raw `abc+/=`; the rewritten extract test returns raw `a+b=c` (extract doesn't encode yet).

- [ ] **Step 3: Move encoding into `Capture.extract`**

In `runner/coverage/Capture.java`, wrap both return paths of `extract` in a null-safe encoder and add the helper:
```java
public String extract(Function<String, String> headerLookup, String body) {
    if (kind == Kind.HEADER) {
        return enc(headerLookup.apply(arg));
    }
    // INPUT
    if (body == null) return null;
    Pattern namePattern = Pattern.compile("name\\s*=\\s*([\"'])" + Pattern.quote(arg) + "\\1");
    Matcher tagMatcher = INPUT_TAG_PATTERN.matcher(body);
    while (tagMatcher.find()) {
        String tag = tagMatcher.group();
        if (!namePattern.matcher(tag).find()) continue;
        Matcher valueMatcher = VALUE_PATTERN.matcher(tag);
        if (!valueMatcher.find()) continue;
        return enc(CoverageGuidedRun.unescapeHtml(valueMatcher.group(2)));
    }
    return null;
}

/** URL-encode for splicing into a form body; null-safe so a header/input miss stays null. */
static String enc(String s) {
    return s == null ? null : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
}
```

- [ ] **Step 4: Make `LoadRun.substitute` verbatim**

In `runner/coverage/LoadRun.java`, change line 354 from `out.append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));` to:
```java
out.append(value);   // bindings already hold the URL-encoded, body-ready text (DD-037 model A)
```
Update the method javadoc (lines 339-344): replace "URL-encoded value bound to `name`" with "the (already URL-encoded) value bound to `name`".

- [ ] **Step 5: Run the migrated tests, then the full suite**

Run: `./gradlew test --tests runner.coverage.CaptureTest --tests runner.coverage.LoadCorrelationTest`
Expected: PASS.
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — the end-to-end tests (`ExploreCorrelationTest`, `LoadCorrelationTest`'s server-received-body checks) use tokens with no special chars, so their bytes are unchanged.

- [ ] **Step 6: Commit**

```bash
git add runner/coverage/Capture.java runner/coverage/LoadRun.java test/runner/coverage/CaptureTest.java test/runner/coverage/LoadCorrelationTest.java
git commit -m "refactor(correlation): encode at capture, substitute verbatim (DD-037 model A)"
```

---

### Task 2: `INPUTPAIR` capture kind

Add the new kind to `Capture` only. A single `inputpair` capture works with today's single-capture `RequestLine`, so this is independently testable.

**Files:**
- Modify: `runner/coverage/Capture.java` (`Kind` enum, `parse`, `extract`, `format`; add `NAME_ATTR_PATTERN`)
- Test: `test/runner/coverage/CaptureTest.java`

**Interfaces:**
- Consumes: `Capture.enc(String)` from Task 1.
- Produces: `Capture.Kind.INPUTPAIR`; wire form `<<name=inputpair:<nameRegex>=<valueRegex>`; `extract` returns `urlEncode(matchedName)=urlEncode(matchedValue)` (a literal `=` joins the two encoded halves) or `null`.

- [ ] **Step 1: Write the failing tests**

Add to `test/runner/coverage/CaptureTest.java`:
```java
@Test public void parseFormatRoundTripsInputPair() {
    Capture c = Capture.parse("<<spam=inputpair:[a-z]{6}=-?[0-9]+");
    assertNotNull(c);
    assertEquals("spam", c.name());
    assertEquals(Capture.Kind.INPUTPAIR, c.kind());
    assertEquals("[a-z]{6}=-?[0-9]+", c.arg());
    assertEquals("<<spam=inputpair:[a-z]{6}=-?[0-9]+", c.format());
}

@Test public void inputPairParseNullOnMissingEquals() {
    assertNull(Capture.parse("<<spam=inputpair:[a-z]{6}"));      // no name=value split
    assertNull(Capture.parse("<<spam=inputpair:[a-z]{6}="));     // empty value regex
    assertNull(Capture.parse("<<spam=inputpair:=[0-9]+"));       // empty name regex
    assertNull(Capture.parse("<<spam=inputpair:[a-z(=[0-9]+"));  // name regex doesn't compile
}

@Test public void inputPairExtractsPairEncodedAmongDecoys() {
    Capture c = Capture.parse("<<spam=inputpair:[a-z]{6}=-?[0-9]+");
    String body =
        "<input type='hidden' name='action' value='save' />" +          // name matches [a-z]{6}, value not numeric
        "<input type='hidden' name='_editedtext' value='12' />" +       // value numeric, name not 6-lower
        "<input type='hidden' name='ztbams' value='1719016235' />";     // the one
    assertEquals("ztbams=1719016235", c.extract(h -> null, body));
}

@Test public void inputPairAnchoredAndEncodes() {
    Capture c = Capture.parse("<<p=inputpair:[a-z]{3}=[a-z+]+");
    // value contains '+', name is exactly 3 lowercase; both halves URL-encoded, joined by literal '='
    String body = "<input name=\"abc\" value=\"x+y\" />";
    assertEquals("abc=x%2By", c.extract(h -> null, body));
}

@Test public void inputPairDataNameNotMistakenForName() {
    Capture c = Capture.parse("<<p=inputpair:[a-z]{6}=[0-9]+");
    // only a data-name attribute matches; there is no real name=, so no match
    String body = "<input data-name=\"ztbams\" value=\"123\" />";
    assertNull(c.extract(h -> null, body));
}
```

- [ ] **Step 2: Run — must FAIL**

Run: `./gradlew test --tests runner.coverage.CaptureTest`
Expected: FAIL (`INPUTPAIR` undefined / parse returns null for `inputpair`).

- [ ] **Step 3: Implement the kind**

In `runner/coverage/Capture.java`:
1. Extend the enum: `public enum Kind { HEADER, INPUT, INPUTPAIR }`.
2. In `parse`, after the existing `input`/`header` branch, accept `inputpair` and validate its arg splits into two compilable regexes:
```java
} else if ("inputpair".equals(kindStr)) {
    int a = arg.indexOf('=');
    if (a <= 0 || a == arg.length() - 1) return null;   // need non-empty name AND value regex
    try {
        Pattern.compile(arg.substring(0, a));
        Pattern.compile(arg.substring(a + 1));
    } catch (java.util.regex.PatternSyntaxException e) {
        return null;
    }
    kind = Kind.INPUTPAIR;
} else {
    return null;
}
```
(`format` already round-trips because it prints `kind.name().toLowerCase()` + `arg` — `INPUTPAIR` → `inputpair`, arg unchanged. No change needed to `format`.)
3. Add a name-attribute pattern beside `VALUE_PATTERN`:
```java
private static final Pattern NAME_ATTR_PATTERN = Pattern.compile("(?<![\\w-])name\\s*=\\s*([\"'])(.*?)\\1");
```
The `(?<![\w-])` guards against matching `data-name=`/`formname=` (the `-`/word-char before `name`). 
4. In `extract`, add the INPUTPAIR branch (anchored matching on both halves):
```java
if (kind == Kind.INPUTPAIR) {
    if (body == null) return null;
    int a = arg.indexOf('=');
    Pattern nameRe = Pattern.compile(arg.substring(0, a));
    Pattern valRe  = Pattern.compile(arg.substring(a + 1));
    Matcher tags = INPUT_TAG_PATTERN.matcher(body);
    while (tags.find()) {
        String tag = tags.group();
        Matcher nm = NAME_ATTR_PATTERN.matcher(tag);
        if (!nm.find()) continue;
        Matcher vm = VALUE_PATTERN.matcher(tag);
        if (!vm.find()) continue;
        String nameVal = CoverageGuidedRun.unescapeHtml(nm.group(2));
        String valVal  = CoverageGuidedRun.unescapeHtml(vm.group(2));
        if (nameRe.matcher(nameVal).matches() && valRe.matcher(valVal).matches()) {
            return enc(nameVal) + "=" + enc(valVal);
        }
    }
    return null;
}
```
Place this branch after the `HEADER` check and before the `INPUT` logic (or guard the existing INPUT block with `if (kind == Kind.INPUT)`).

- [ ] **Step 4: Run — must PASS**

Run: `./gradlew test --tests runner.coverage.CaptureTest`
Expected: PASS (all new + existing Capture tests).

- [ ] **Step 5: Commit**

```bash
git add runner/coverage/Capture.java test/runner/coverage/CaptureTest.java
git commit -m "feat(correlation): INPUTPAIR capture — anchored name=value pair, encoded (DD-037)"
```

---

### Task 3: Multiple captures per step

Change `RequestLine.capture` → `List<Capture> captures` and repoint every caller. This is one atomic breaking change so the build compiles at the task boundary.

**Files:**
- Modify: `runner/coverage/RequestLine.java` (record field, ctor, `parse`, `format`)
- Modify: `runner/coverage/LoadRun.java` (`fire` capture branch ≈288-322, the 4-arg reconstruction line 131, `lintCorrelationOrdering` line 513-528)
- Modify: `runner/coverage/CoverageGuidedRun.java` (`request` capture branch: guard 639, extract 650-653; the 4-arg reconstruction line 504)
- Test: `test/runner/coverage/RequestLineV3Test.java` (migrate `.capture()` → `.captures()`), `test/runner/coverage/LoadCorrelationTest.java` (line 100 ctor)

**Interfaces:**
- Consumes: `Capture` (Tasks 1–2).
- Produces: `RequestLine(String method, String path, String body, List<Capture> captures)`; accessor `captures()` returns `List<Capture>` (empty when none). 3-arg ctor `RequestLine(m,p,b)` delegates with `List.of()`. `parse` peels **all** trailing `<<…` tokens; `format` re-emits them in order.

- [ ] **Step 1: Write the failing test**

Add to `test/runner/coverage/RequestLineV3Test.java`:
```java
@Test public void parsesMultipleTrailingCaptures() {
    RequestLine r = RequestLine.parse("/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+");
    assertEquals("/Edit.jsp?page=Main", r.path());
    assertEquals(2, r.captures().size());
    assertEquals("csrf", r.captures().get(0).name());   // source order preserved
    assertEquals("spam", r.captures().get(1).name());
    assertEquals(Capture.Kind.INPUTPAIR, r.captures().get(1).kind());
    // round-trip
    assertEquals("/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+", r.format());
}

@Test public void v2LineHasEmptyCapturesAndByteIdenticalFormat() {
    RequestLine r = RequestLine.parse("POST /actions/Order.action?newOrder= _sourcePage=/x");
    assertTrue(r.captures().isEmpty());
    assertEquals("POST /actions/Order.action?newOrder= _sourcePage=/x", r.format());
}
```

- [ ] **Step 2: Run — must FAIL (won't compile: `captures()` undefined)**

Run: `./gradlew test --tests runner.coverage.RequestLineV3Test`
Expected: FAIL — compile error, `captures()` does not exist.

- [ ] **Step 3: Change `RequestLine` to a capture list**

In `runner/coverage/RequestLine.java`:
```java
import java.util.ArrayList;
// ...
public record RequestLine(String method, String path, String body, List<Capture> captures) {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

    public RequestLine(String method, String path, String body) {
        this(method, path, body, List.of());
    }

    public boolean needsSubstitution() {
        return body != null && body.contains("${{");
    }

    public static RequestLine parse(String step) {
        if (step == null || step.isEmpty()) {
            return parseCore(step);
        }
        java.util.ArrayList<Capture> caps = new java.util.ArrayList<>();
        while (true) {
            int ls = step.lastIndexOf(' ');
            if (ls < 0 || !step.startsWith("<<", ls + 1)) break;
            Capture cap = Capture.parse(step.substring(ls + 1));
            if (cap == null) break;               // back-compat guard: a non-capture trailing token stays in the body
            caps.add(0, cap);                      // prepend → preserve left-to-right source order
            step = step.substring(0, ls);
        }
        RequestLine r = parseCore(step);
        return new RequestLine(r.method(), r.path(), r.body(), caps);
    }
    // parseCore + isMethod UNCHANGED (they already return List.of() via the 3-arg ctor)

    public String format() {
        StringBuilder sb = new StringBuilder(
            (method.equals("GET") ? "" : method + " ") + path + (body == null ? "" : " " + body));
        for (Capture c : captures) sb.append(' ').append(c.format());
        return sb.toString();
    }
```
Leave `parseCore`, `isMethod`, `parseSequence`, `formatSequence`, `firstPath` as-is.

- [ ] **Step 4: Repoint `LoadRun` callers**

In `runner/coverage/LoadRun.java`:
- Line 131 (the substituted-step reconstruction): `toFire = new RequestLine(step.method(), step.path(), b, step.captures());`
- `fire` capture branch (288-322): replace the single-capture guard + extract with a loop. The guard `if (step.capture() == null || bindings == null)` becomes `if (step.captures().isEmpty() || bindings == null)`; in the else-branch, after computing `String body = retained.toString(...)`, replace the single extract/put with:
```java
for (Capture cap : step.captures()) {
    String val = cap.extract(c::getHeaderField, body);
    if (val != null) bindings.put(cap.name(), val);
}
```
- `lintCorrelationOrdering` (513-528): replace lines 526-527 with:
```java
for (Capture cap : step.captures()) capturedSoFar.add(cap.name());
```

- [ ] **Step 5: Repoint `CoverageGuidedRun` callers**

In `runner/coverage/CoverageGuidedRun.java`:
- Line 504: `r = new RequestLine(r.method(), r.path(), b, r.captures());`
- Line 639 (body-retention guard): change `r.capture() != null` to `!r.captures().isEmpty()`.
- Lines 650-653 (extract/bind): replace with a loop:
```java
if (!r.captures().isEmpty() && bindings != null && code < 500) {
    for (Capture cap : r.captures()) {
        String val = cap.extract(c::getHeaderField, body.toString());
        if (val != null) bindings.put(cap.name(), val);
    }
}
```

- [ ] **Step 6: Migrate the remaining test `.capture()` references**

In `test/runner/coverage/RequestLineV3Test.java`, change each `.capture()` assertion to the list form:
- `assertNotNull(r.capture())` → `assertEquals(1, r.captures().size())`
- `r.capture().name()` → `r.captures().get(0).name()` (and `.kind()`, `.arg()` similarly)
- `assertNull(r.capture())` / `assertNull(seq.get(1).capture())` → `assertTrue(r.captures().isEmpty())` / `assertTrue(seq.get(1).captures().isEmpty())`
In `test/runner/coverage/LoadCorrelationTest.java` line 100: `new RequestLine(step2.method(), step2.path(), substituted, step2.captures())`.

- [ ] **Step 7: Run the touched tests, then the full suite**

Run: `./gradlew test --tests runner.coverage.RequestLineV3Test --tests runner.coverage.LoadCorrelationTest --tests runner.coverage.ExploreCorrelationTest`
Expected: PASS.
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If any `.capture()` compile error remains, `grep -rn '\.capture()' runner/ test/` and repoint it (only `.captures()` should remain).

- [ ] **Step 8: Commit**

```bash
git add runner/coverage/RequestLine.java runner/coverage/LoadRun.java runner/coverage/CoverageGuidedRun.java test/runner/coverage/RequestLineV3Test.java test/runner/coverage/LoadCorrelationTest.java
git commit -m "feat(correlation): multiple captures per step (RequestLine List<Capture>) (DD-037)"
```

---

### Task 4: JSPWiki `edit_save` grammar — the two correlated captures

Wire the feature into the example grammar and prove it expands.

**Files:**
- Modify: `examples/grammar/jspwiki.grammar` (the `@sequence edit_save` block)
- Verify with the ad-hoc grammar validator (compiled classes under `build/classes/java/coverage`).

**Interfaces:**
- Consumes: `INPUTPAIR` (Task 2), multi-capture parse (Task 3).

- [ ] **Step 1: Edit the `edit_save` sequence**

In `examples/grammar/jspwiki.grammar`, change step 1 of `@sequence edit_save` to carry both captures and the save POST to reference the spam pair. The block becomes:
```
@sequence edit_save
  /Edit.jsp?page=${page} <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+
  POST /Edit.jsp page=${page}&action=save&X-XSRF-TOKEN=${{csrf}}&${{spam}}&_editedtext=${wikitext}&ok=Save
  /Wiki.jsp?page=${page}
```
Update the explanatory comment above it to note the spam-hash field (randomized `[a-z]{6}` name, `lastModified ^ hash(clientIP)` value) captured as a whole pair.

- [ ] **Step 2: Compile the runner classes (if not already built)**

Run: `./gradlew compileCoverageJava -q` (populates `build/classes/java/coverage`).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Validate the grammar expands with both captures preserved**

Write `/tmp/GV.java` (compile against `build/classes/java/coverage:build/classes/java/main`) that loads the grammar and asserts the expanded `edit_save`:
```java
import runner.coverage.*; import java.nio.file.*; import java.util.*;
public class GV {
  public static void main(String[] a) throws Exception {
    RequestGrammar g = RequestGrammar.load(Paths.get("examples/grammar/jspwiki.grammar"), new Random(1));
    for (List<String> seq : g.expandAllSequences()) {
      List<RequestLine> rs = new ArrayList<>();
      for (String s : seq) rs.add(RequestLine.parse(s));
      boolean isEdit = seq.stream().anyMatch(s -> s.contains("action=save"));
      if (!isEdit) continue;
      RequestLine step1 = rs.get(0);
      if (step1.captures().size() != 2) throw new AssertionError("step1 captures=" + step1.captures().size());
      if (step1.captures().get(1).kind() != Capture.Kind.INPUTPAIR) throw new AssertionError("not inputpair");
      String post = seq.stream().filter(s -> s.contains("action=save")).findFirst().get();
      if (!post.contains("${{csrf}}") || !post.contains("${{spam}}")) throw new AssertionError("refs lost: " + post);
      System.out.println("edit_save OK: " + seq.get(0) + "  ||  " + post);
    }
    System.out.println("GRAMMAR OK");
  }
}
```
Run: `javac -cp build/classes/java/coverage:build/classes/java/main -d /tmp /tmp/GV.java && java -cp build/classes/java/coverage:build/classes/java/main:/tmp GV examples/grammar/jspwiki.grammar`
Expected: prints `edit_save OK: …` then `GRAMMAR OK` (two captures on step 1, `${{csrf}}`/`${{spam}}` intact in the POST).

- [ ] **Step 4: Full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (grammar is data; confirms nothing else broke).

- [ ] **Step 5: Commit**

```bash
git add examples/grammar/jspwiki.grammar
git commit -m "feat(examples): JSPWiki edit_save captures the spam-hash pair (DD-037)"
```

---

### Task 5: DD-037 record + changelog

**Files:**
- Modify: `docs/DESIGN-DECISIONS.md` (append a `## DD-037:` record after DD-036)
- Modify: `docs/LOAD-MODE-DESIGN.md` (a short corpus-v3.1 note after §10)
- Modify: `runner/CHANGELOG.md` (a bullet under `## [Unreleased]`)

**Interfaces:** none (docs only).

- [ ] **Step 1: Write the DD-037 record**

Append to `docs/DESIGN-DECISIONS.md` a `## DD-037: Dynamic-name field correlation — inputpair capture + multi-capture (2026-07-22)` record, mirroring DD-036's structure (**Context** / **Decision** / **Verified** / **Rejected alternatives**). Content, from the spec `docs/superpowers/specs/2026-07-22-inputpair-capture-design.md`:
- **Context:** DD-036 captures a value of a statically-named input; JSPWiki's `SpamFilter.checkHash` (unconditional in `Edit.jsp`; root-cause `.superpowers/sdd/jspwiki-save-rootcause.md`) requires a hidden field whose NAME is randomized 6 lowercase letters and value `lastModified ^ hash(clientIP)`, un-nameable at grammar time — a whole `name=value` pair must be captured; and the edit form needs two captures (CSRF + spam) from one GET.
- **Decision:** (1) `Capture.Kind.INPUTPAIR`, `<<name=inputpair:<nameRegex>=<valueRegex>`, anchored name+value match, binds `urlEncode(name)=urlEncode(value)`; (2) encoding model A — encode in `extract`, `substitute` verbatim — so the pair's structural `=` survives; (3) `RequestLine` carries `List<Capture> captures`, `parse` peels all trailing `<<…`, `format` round-trips; captures are trailing.
- **Verified:** unit tests (Capture parse/format/extract with anchoring + decoys; RequestLine multi-capture round-trip + v1/v2 back-compat; substitute verbatim), grammar expansion of `edit_save`, full suite green. Encoding move is byte-identical for existing captures. Live root-cause proof (test E) shows the captured spam pair flips `302 SessionExpired` into a persisted save.
- **Rejected alternatives:** typed binding map (larger footprint), sentinel delimiter (hidden structure), config-disabling the SpamFilter (impossible — hard-wired in `Edit.jsp`), two separate captures for name+value (the dynamic name must come from the same matched input). Keep each 1–3 sentences.

- [ ] **Step 2: LOAD-MODE note + CHANGELOG**

`docs/LOAD-MODE-DESIGN.md`: after §10 (corpus v3), add a 1–2 sentence note that v3.1 adds `inputpair` (dynamic name=value pair) and multiple captures per step, cross-referencing DD-037.
`runner/CHANGELOG.md`: under `## [Unreleased]`, add:
```
- **Dynamic-name field correlation (DD-037):** a capture can now grab a whole `name=value` form-input pair whose name is itself dynamic (`<<x=inputpair:<nameRegex>=<valueRegex>`), and a step can carry more than one capture — unlocking write paths guarded by randomized-name anti-CSRF/spam fields (JSPWiki's spam-hash). Correlation encoding moved into the capture layer (`substitute` is now a verbatim splice); existing `input:`/`header:` captures are byte-identical.
```

- [ ] **Step 3: Commit**

```bash
git add docs/DESIGN-DECISIONS.md docs/LOAD-MODE-DESIGN.md runner/CHANGELOG.md
git commit -m "docs: DD-037 inputpair + multi-capture record + changelog"
```

---

## Notes for the executor

- **Order:** 1 (encoding) → 2 (inputpair kind) → 3 (multi-capture) → 4 (grammar) → 5 (docs). Each leaves a green build.
- **The one cross-task invariant to re-check at the whole-branch review:** encoding model A is byte-identical for existing `input:`/`header:` captures (extract now encodes, substitute no longer does) — confirm no third consumer of a binding exists beyond `substitute`, and that no binding is persisted to disk (the token-leak fix labels findings with the raw `step`).
- **Anchoring is load-bearing** (Global Constraints): `Matcher.matches()`, never `find()`, on both the name and value regex — verify in review with the decoy test.
- **Whole-branch review must also check:** (1) `grep -rn '\.capture()' runner/ test/` returns nothing (all migrated to `captures()`); (2) `lintCorrelationOrdering` registers ALL captures' names; (3) `format(parse(line)) == line` for 0/1/2/3 captures and for v1/v2 lines; (4) the `data-name=` guard in `NAME_ATTR_PATTERN`.
- **Proof (post-merge, in the benchmark campaign):** re-run JSPWiki `edit_save` explore→load against the live cluster; the save POSTs return 2xx/3xx, `captureMisses≈0`, and the write-path cost is visible where cached reads are flat.
