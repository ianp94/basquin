# DD-039 Session Carry Across Redirects — Implementation Plan

**Base commit:** `main` @ `28228fb` (DD-040, PR #94, merged). Branch `feat/dd039-session-carry`.
Every line reference in this document was re-derived against that tree on 2026-07-23 by reading the
files, not from memory. Where a previous review asserted a fact that the code contradicts, this plan
says so explicitly rather than repeating it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking. **Each task compiles, passes `./gradlew test`, and commits
> on its own.** No task references a symbol a later task introduces. This has broken twice; the
> ordering below is the fix, not a preference.

**Goal:** Explore mode follows redirects itself — carrying the session cookie across every hop,
stamping and *recovering* every hop's measurement, and turning a redirect loop into a finding — so
authenticated write paths become reachable **and every hop's violation is actually reported**.

**Acceptance criterion (inherited from DD-040, which failed on exactly this):** a Roller explore
campaign's reported violation count must come within a small tolerance of the `[Basquin][Invariant]`
line count in the target pod's log for the same window. DD-040's acceptance run measured **1,413
reported against 1,602 logged — a gap of 189 (11.8%)**. Root cause: a method-rewritten redirect
(POST → 302 → GET) loses the `X-Basquin-Req` header, so hop 2 evaluates, logs, and publishes
nothing, while the poll still succeeds from hop 1 and the driver records `measured=true, count=0` —
a clean zero for a request whose real work violated, invisible to `reportMisses`.

**Tech Stack:** Java 17, `java.net.HttpURLConnection`, `java.net.URI`, JUnit 4 with
`com.sun.net.httpserver.HttpServer` (the `ReportChannelTest` / `MultiReplicaPollTest` pattern).

---

## The two questions this plan is judged on

**1. Will executing it close the 189-violation gap?**

Yes, and the reason the previous version would not is now fixed. Round 3's C3 was correct and
verified again here:

```java
CoverageGuidedRun.java:934   // fetchResult
    while ((line = br.readLine()) != null && sb.length() < 4096) sb.append(line);
CoverageGuidedRun.java:873   // pollResult
    String[] f = (body == null) ? new String[0] : body.split("\\|", 4);
```

`readLine()` strips the terminator and `append(line)` adds none back, so an accumulated N-hop body
arrives as one concatenated string and `split("\\|", 4)` reads hop 0's count only. Accumulating in
`ResultStore` without changing these two methods closes approximately none of the gap. **Task 3
exists solely to fix the driver side, and it lands before anything can produce a multi-hop body.**

**2. Would its own tests detect a failure to close it?**

Yes. Three tests fail if the mechanism is removed, and each is named in its task with the exact
symptom:

| test | task | fails if |
|---|---|---|
| `AccumulatedPollTest.aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers` | 3 | `fetchResult` drops `\n`, or `pollResult` does not parse per line — you get **1** record, not 3 |
| `ExploreRedirectTest.aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce` | 4 | the header save is not suppressed (**3** records), or `hops > 1` does not force a poll (**1** record, hop 0's count only) |
| `ExploreRedirectTest.everyHopCarriesTheSameStampedIdAndTheSessionCookie` | 4 | hop 2 arrives without `X-Basquin-Req` — the literal 189-violation defect |

None of these can be satisfied through the header path. `ExploreRedirectTest`'s discriminating hop
is a **committed** 200 with no reporting headers at all, ported from
`ReportChannelTest.serveCommittedApp` (`test/runner/coverage/ReportChannelTest.java:82`) — the 97.3%
case DD-040 measured. A test asserting only that a poll returned *something* is explicitly not
sufficient and appears nowhere below.

---

## Spec §4b needs amending — four bounded amendments

**I cannot edit the spec. These must be applied to
`docs/superpowers/specs/2026-07-23-redirect-session-carry-design.md` §4b before or alongside
execution.** The §4b *direction* — accumulate per-hop entries under one id — is right and is what
this plan implements. But four of its statements are false against the code as it stands, and an
implementation that believes them ships a bug.

**A4b-1 — "No double-count question" is false as stated.** §4b:191-209 argues that one id plus one
`take` means "there is no path where a violation is counted from both the header and the store".
That property belongs to DD-040's *code*, where the header and the poll are mutually exclusive by
construction — not to the store's arity. The header path saves the instant it reads the header:

```java
CoverageGuidedRun.java:756-764
    String inv = c.getHeaderField("X-Basquin-Invariant-Count");
    if (inv != null) {
        headerReported = true;
        try { invCount = Integer.parseInt(inv.trim()); } catch (NumberFormatException ignored) {}
        String detail = c.getHeaderField("X-Basquin-Invariant-Detail");
        FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                "route=" + label + "\ncount=" + inv
                        + (detail != null ? "\ndetail=" + detail : "") + podMeta);
    }
```

A 302 is small and uncommitted, so it essentially always carries this header when it violated. Under
"more than one hop → always poll" that hop is filed **once from its header and again from the poll**,
and counted twice into `CostSample.invariantCount`, which feeds `CostModel.score`
(`CoverageGuidedRun.java:307`). Clearing a flag afterwards does not unlink a file.
**Amendment:** the exclusivity property must be restated as a property of the driver — *all saving
happens at one post-response reconciliation point; the header-derived record is emitted only when
the poll returned nothing well-formed* — not as a consequence of the single `take`.

**A4b-2 — "always poll" without a fallback can abort the run.** §4b:216-217 says "More than one hop
→ always poll, regardless of whether a header arrived." A forced poll that misses (entry evicted at
`ResultStore.CAPACITY` = 256, pod unreachable, or `PodPollTargets.pollBases` returning empty because
pod addressing was demanded and DNS could not answer — `PodPollTargets.java:148-150`) discards a
perfectly good final-hop header reading and records a miss (`CoverageGuidedRun.java:944-947`). On a
redirect-heavy target that is a large fraction of all requests, and
`missesAreTheMajority` fires `System.exit(3)` (`:651-658`) — the run fails *because of the fix*, on a
target DD-040 was reporting fine.
**Amendment:** the rule is *poll and merge*, not *poll and replace*. The poll result wins whenever it
is well-formed; otherwise fall back to the header-derived sample rather than recording a miss.

**A4b-3 — one id is incompatible with `pollResult`'s break-at-first-pod fan-out.** Verified:

```java
CoverageGuidedRun.java:862-871
    for (String pollBase : pollBases) {
        String candidate = fetchResult(pollBase, reqId);
        if (candidate != null && candidate.indexOf('|') >= 0) { body = candidate; ...; break; }
    }
```

One id shared across hops plus a Service VIP that routes each new connection independently means
hop 0's list can sit on pod A and hop 1's on pod B. The fan-out finds A, breaks, and reports a
partial result as `measured=true` — nothing missed, so `reportMisses` cannot see it. That is
*worse* than DD-040's documented final-hop-wins, because the answer now looks complete.
**Amendment (option (a) of round 3's C5):** when the driver made more than one hop it must ask
**every** pod base and merge the answers, rather than stopping at the first. Single-hop requests keep
break-at-first exactly — one pod can hold the id, so asking further pods is pure latency. This is
implemented in Task 3 and it leaves
`MultiReplicaPollTest.theFanOutFindsTheEntryBehindAPodThatMisses` (`:164`, which drives the 3-arg
single-hop entry point) passing unchanged, verified by reading it.
Two shipped comments assert the opposite of what will ship and are corrected in Task 6:
`PodPollTargets.java:32-40` ("stamps every hop with its own id") and `CoverageGuidedRun.java:849-853`
("DD-039's per-hop ids remove the case").

**A4b-4 — `hop=<n>` is undefined.** §4 and §4b both require `hop=<n>` on each record.
`ResultStore.Entry` (`agent/ResultStore.java:28`) has no hop field, and the target cannot know one:
`publishResult` (`agent/RequestBoundary.java:170-182`) sees one request and one stamped id.
**Amendment:** define it — *`hop` is the position in the sequence returned by `take`, which is
publish order, which is hop order because the driver issues hops sequentially into one JVM.* Add the
caveat that a chain whose hops were served by different replicas is recovered per-pod, so the index
is a position in the recovered sequence rather than a proof of hop order; the acceptance run (Task 7)
must therefore state its replica count. Adding a hop number to the wire would require per-hop
information in the id or a second header and a `RequestBoundary` change, which §4b's own three
arguments exist to avoid.

---

## Global Constraints

Each of these is **also restated inside the task it binds**. The round-2 rejection was that findings
sat here while the copyable task code still contained them; this section is a summary, never the
only place a constraint appears.

1. **DD-036, non-negotiable:** a saved finding is labelled with the **raw step label**, never a
   substituted request line, and never a hop URL in place of the label. (Tasks 3, 4, 5.)
2. **Never record a hop URL with its query string or fragment.** Hop 0's URL is built from the
   **substituted** `RequestLine` — `runSequence` substitutes the path at `CoverageGuidedRun.java:518`,
   deliberately, per DD-038 — so `GET /edit?csrf=${{tok}}` would write a live CSRF token to disk.
   Strip to `scheme://host[:port]/path` **at the point of construction**, in Task 4, because Task 5's
   `visited` set is what a `Redirect-Loop` finding writes out. (Tasks 1, 4, 5.)
3. **Max 5 hops means 5 REQUESTS total** — hop 0 plus at most four follows. `hops` counts requests
   issued, so the guard is `hops >= MAX_HOPS` *after* the increment. `ResultStore.MAX_HOPS_PER_ID` is
   the same 5. Two caps that disagree silently drop a hop, and a silently dropped hop is a lost
   violation — the defect class this plan exists to remove. (Tasks 2, 4, 5.)
4. **A revisited stripped URL ends the chain** (`LinkedHashSet`), and **same-origin only**: scheme +
   host (case-insensitive) + port, with an absent port normalized to the scheme default
   (`URI.getPort()` returns `-1`). (Tasks 1, 5.)
5. **Nothing in the redirect path may throw.** `request(...)` is `throws Exception` and its caller
   catches `Throwable` into `StatusReporter.recordCrash()` + `FuzzIO.saveInteresting`
   (`CoverageGuidedRun.java:310-312`, `:537-539`), so an escaping `URISyntaxException` files a **false
   crash finding against the app** carrying a stack that points into driver code. This applies to
   hop 0's own URL too, which is the *more* hostile string: it is the substituted path. Parse every
   URL through a null-returning helper. (Tasks 1, 4, 5.)
6. **Every hop's response is read to EOF before the next is issued**, and every terminal condition
   `break`s *before* the drain, so the final hop's body survives for the capture step. (Task 4.)
> **Spike caveat, banked for Task 7:** the `Cookie` re-attach on the rewritten hop is the one piece
> with **no failing unit test** until the driver is pointed at a session-rotating app — which is
> DD-039's whole purpose. Task 7's acceptance (a Roller login-then-publish writing a DB row) is what
> actually exercises it; a green unit suite does not prove the cookie carry works.

7. **`Cookie` and `X-Basquin-Req` are set explicitly on every hop.** The JDK carries neither onto a
   method-rewritten hop (spec Evidence TEST A: hop 2 arrived with `cookie=null`). That is DD-039's
   motivating defect and DD-040's residual, in one line. (Task 4.)
8. **Method rules:** 301/302/303 → `GET` **only when the original method carried a body**
   (POST/PUT/PATCH), dropping body and `Content-Type` together; otherwise preserve the method —
   notably `HEAD`; 307/308 always preserve method and body. (Tasks 1, 4.)
9. **One `Invariant-Remote` record per breaching hop**, each carrying `hop=<n>`. Heap and thread
   deltas are **summed**. (Tasks 3, 4.)
10. **The summed invariant count DOES feed `CostModel.score`.** Decided here, in writing, not
    delegated: spec §4 calls the summed deltas "the input's total cost", and
    `CostSample.invariantCount` already reaches `CostModel.score` at `CoverageGuidedRun.java:307` and
    `corpus.consider` at `:330-331`. Summing is the change that makes the number coherent; an
    implementer must not re-decide it. (Tasks 3, 4.)
11. **`CoverageGuidedRun.request(String base, RequestLine r)` DOES NOT EXIST.** Tests call the
    package-private `request(String base, String step)` (`CoverageGuidedRun.java:662`; house
    precedent `ExploreCorrelationTest.java:88`). **Do not add a `RequestLine` overload** deriving
    `label` from `r.format()` — the Javadoc at `:690-695` forbids it, and `:675-678` records that
    `format()` canonicalizes a no-body GET, silently rewriting recorded finding text. (Tasks 3–6.)
12. **Meta files are `.meta.txt`, not `.meta`** (`runner/util/FuzzIO.java:38` and `:76` both write
    `base + ".meta.txt"`). A test filtering `.meta` matches zero files and fails *after a correct
    implementation* — the worst failure mode there is. (Tasks 3–6.)
13. **`IOException` is not imported** in `CoverageGuidedRun.java` — verified, `:9-18` imports
    `BufferedReader`, `InputStream`, `InputStreamReader` and no `IOException`. Fully qualify it or add
    the import. (Task 4.)
14. **Explore is single-threaded**; `sessionCookie` stays one `static volatile` field
    (`:498`). Do not introduce per-worker state. Load mode (`LoadRun`) is not modified by this plan.
15. **No `catch (Exception ignored) {}` in a test handler.** A handler that throws mid-response lets
    the test pass on a truncated response. Record the throwable into an `AtomicReference` and assert
    it is null. (Tasks 3–6.)
16. **Save and restore `basquin.fuzz.resultsDir`**, never `clearProperty` blindly. House pattern:
    `ReportChannelTest.withResultsDir` (`:398-407`). (Tasks 3–6.)

---

## Task 1: Pure redirect-decision helpers

**Scope is unchanged from the version round 2 and round 3 both verified sound — do not redesign the
five helpers.** Round 3's I8 was that the *code* had been deleted from the task, not that the design
was wrong. The code is restored below. Two additional pure helpers (`safeUri`, `strippedUrl`) are
new; they exist because Global Constraints 2 and 5 need a never-throwing parse and a query strip, and
both belong here with the other pure functions rather than inline at a call site.

**Files:**
- modify: `runner/coverage/CoverageGuidedRun.java`
- create: `test/runner/coverage/RedirectPolicyTest.java`

**Interfaces produced** (later tasks consume these exact signatures; none of them touch the network):

```java
static boolean isRedirect(int code)
static java.net.URI resolveLocation(String requestUrl, String location)   // null, never throws
static java.net.URI safeUri(String url)                                   // null, never throws
static boolean sameOrigin(java.net.URI a, java.net.URI b)
static int defaultPort(java.net.URI u)
static String followMethod(int code, String method)
static String strippedUrl(String url)
static void warnOnce(String key, String msg)
```

**Consumes:** nothing. This task adds no call sites; the helpers are dead code until Task 4.

- [ ] **Step 1 — write `test/runner/coverage/RedirectPolicyTest.java`:**

```java
package runner.coverage;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-039 §2/§3: the redirect decision, as pure functions. Kept off the network deliberately — the
 * riskiest behaviours here (an unparseable Location, a default-port comparison, HEAD surviving a
 * 302) are decisions, not I/O, and a decision that can only be exercised through a server is a
 * decision nobody tests at its edges.
 */
public class RedirectPolicyTest {

    @Test public void isRedirectCoversThe3xxRangeAndNothingElse() {
        assertTrue(CoverageGuidedRun.isRedirect(301));
        assertTrue(CoverageGuidedRun.isRedirect(302));
        assertTrue(CoverageGuidedRun.isRedirect(308));
        assertFalse(CoverageGuidedRun.isRedirect(200));
        assertFalse(CoverageGuidedRun.isRedirect(400));
        // getResponseCode() returns -1 on an unparseable status line; that must not read as a redirect.
        assertFalse("an unparseable status line is not a redirect", CoverageGuidedRun.isRedirect(-1));
    }

    @Test public void relativeLocationResolvesAgainstTheRequestUrl() {
        assertEquals(URI.create("http://svc:8080/login"),
                CoverageGuidedRun.resolveLocation("http://svc:8080/a/b?x=1", "/login"));
        assertEquals(URI.create("http://svc:8080/a/next"),
                CoverageGuidedRun.resolveLocation("http://svc:8080/a/b", "next"));
    }

    // THE test that keeps a fuzzed input from filing a false crash finding against the app. URI
    // throws on unencoded space, {}, | and ^ — exactly the bytes a fuzzer reflects into a query
    // string — and request(...) is `throws Exception` whose caller catches Throwable into
    // StatusReporter.recordCrash().
    @Test public void unparseableLocationReturnsNullRatherThanThrowing() {
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", "/x y|z^{}"));
        assertNull("a base that will not parse must not throw either",
                CoverageGuidedRun.resolveLocation(":::not a url:::", "/login"));
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", null));
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", ""));
    }

    // Hop 0's URL is base + the SUBSTITUTED path, so it is a MORE hostile string than a Location.
    @Test public void safeUriNeverThrowsOnASubstitutedPath() {
        assertNull(CoverageGuidedRun.safeUri("http://svc/edit?q=a b|c^{}"));
        assertEquals(URI.create("http://svc/edit"), CoverageGuidedRun.safeUri("http://svc/edit"));
        assertNull(CoverageGuidedRun.safeUri(null));
    }

    @Test public void sameOriginNormalizesTheDefaultPortAndIgnoresHostCase() {
        assertTrue("URI.getPort() is -1 for http://svc/foo; it must equal a base of http://svc:80",
                CoverageGuidedRun.sameOrigin(URI.create("http://svc:80/a"), URI.create("http://svc/b")));
        assertTrue(CoverageGuidedRun.sameOrigin(URI.create("https://SVC/a"), URI.create("https://svc:443/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"), URI.create("http://evil.example.invalid/b")));
        assertFalse("a scheme change is a different origin",
                CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"), URI.create("https://svc/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc:8080/a"), URI.create("http://svc:9090/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(null, URI.create("http://svc/b")));
    }

    @Test public void defaultPortIsSchemeDerivedOnlyWhenAbsent() {
        assertEquals(8080, CoverageGuidedRun.defaultPort(URI.create("http://svc:8080/a")));
        assertEquals(80, CoverageGuidedRun.defaultPort(URI.create("http://svc/a")));
        assertEquals(443, CoverageGuidedRun.defaultPort(URI.create("https://svc/a")));
    }

    // Preserves today's JDK behaviour rather than choosing new behaviour (spec §3, Evidence A and B).
    @Test public void followMethodMatchesTheJdkTableItReplaces() {
        assertEquals("GET", CoverageGuidedRun.followMethod(302, "POST"));
        assertEquals("GET", CoverageGuidedRun.followMethod(303, "POST"));
        assertEquals("GET", CoverageGuidedRun.followMethod(301, "PUT"));
        assertEquals("GET", CoverageGuidedRun.followMethod(302, "PATCH"));
        assertEquals("307 exists to preserve the method", "POST", CoverageGuidedRun.followMethod(307, "POST"));
        assertEquals("POST", CoverageGuidedRun.followMethod(308, "POST"));
        assertEquals("GET stays GET", "GET", CoverageGuidedRun.followMethod(302, "GET"));
        assertEquals("HEAD must NOT silently become GET — that pulls a body the caller never asked "
                + "for, inflating the input's measured latency and heap and corrupting its cost rank",
                "HEAD", CoverageGuidedRun.followMethod(302, "HEAD"));
        assertEquals("HEAD", CoverageGuidedRun.followMethod(303, "HEAD"));
    }

    // DD-036: a recorded hop URL must never carry a substituted token.
    @Test public void strippedUrlRemovesQueryAndFragment() {
        assertEquals("http://svc:8080/edit",
                CoverageGuidedRun.strippedUrl("http://svc:8080/edit?csrf=LIVE-TOKEN-123&page=Main"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit#frag"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit#f?notaquery"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit"));
        assertEquals("", CoverageGuidedRun.strippedUrl(null));
    }
}
```

- [ ] **Step 2 — run it and confirm the failure is a COMPILE error, not a red test.**
      `./gradlew test --tests 'runner.coverage.RedirectPolicyTest'`
      Expected: `cannot find symbol: method isRedirect(int)` and seven siblings. There is no red-test
      stage for this task; the helpers do not exist.

- [ ] **Step 3 — add the helpers to `runner/coverage/CoverageGuidedRun.java`**, immediately after
      `resetSession()` (`:500`):

```java
    // ------------------------------------------------------------------ DD-039 redirect decisions
    // Pure, package-private, and off the network on purpose: an unparseable Location, a default-port
    // comparison and HEAD-stays-HEAD are decisions, and a decision only reachable through a server
    // is a decision nobody tests at its edges.

    /** A 3xx that may carry a Location. -1 (an unparseable status line, which getResponseCode()
     *  returns) is deliberately NOT a redirect. */
    static boolean isRedirect(int code) {
        return code >= 300 && code < 400;
    }

    /**
     * {@code location} resolved against {@code requestUrl}, or null if either will not parse.
     *
     * <p><b>Never throws.</b> {@code request(...)} is {@code throws Exception} and both its callers
     * catch {@code Throwable} into {@link StatusReporter#recordCrash()} +
     * {@link FuzzIO#saveInteresting} ({@code :310-312}, {@code :537-539}), so an escaping
     * {@code URISyntaxException} files a false crash finding AGAINST THE APP carrying a stack that
     * points into driver code. {@code URI} throws on unencoded space, <code>{}</code>, {@code |} and
     * {@code ^} — exactly the bytes a fuzzer reflects into a query string.
     */
    static java.net.URI resolveLocation(String requestUrl, String location) {
        if (location == null || location.isEmpty()) return null;
        try {
            return new java.net.URI(requestUrl).resolve(new java.net.URI(location));
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            warnOnce("bad-location", "unparseable Location; the 3xx becomes the final response "
                    + "(first occurrence: " + location + ")");
            return null;
        }
    }

    /** A parsed URI, or null. The never-throwing parse for a URL built from the SUBSTITUTED request
     *  path ({@code runSequence} substitutes at {@code :518}) — a more hostile string than a
     *  {@code Location}, and the one {@code new URL(...)} used to accept leniently. */
    static java.net.URI safeUri(String url) {
        if (url == null) return null;
        try {
            return new java.net.URI(url);
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Same scheme + host (case-insensitive) + port, with an absent port normalized to the scheme
     *  default. A fuzzed input inducing an open redirect must not turn the explorer into a client
     *  for another server, or attribute another host's cost to the app. */
    static boolean sameOrigin(java.net.URI a, java.net.URI b) {
        if (a == null || b == null) return false;
        String sa = a.getScheme(), sb = b.getScheme();
        if (sa == null || sb == null || !sa.equalsIgnoreCase(sb)) return false;
        String ha = a.getHost(), hb = b.getHost();
        if (ha == null || hb == null || !ha.equalsIgnoreCase(hb)) return false;
        return defaultPort(a) == defaultPort(b);
    }

    /** The URI's port, or the scheme default when absent — {@code URI.getPort()} returns -1 for
     *  {@code http://svc/foo}, which must compare equal to a base of {@code http://svc:80}. */
    static int defaultPort(java.net.URI u) {
        if (u == null) return -1;
        if (u.getPort() != -1) return u.getPort();
        String s = u.getScheme();
        if ("https".equalsIgnoreCase(s)) return 443;
        if ("http".equalsIgnoreCase(s)) return 80;
        return -1;
    }

    /**
     * The method the follow hop must use. This PRESERVES today's JDK behaviour rather than choosing
     * new behaviour (spec §3, Evidence TESTs A and B): deviating would be the change and would need
     * its own justification.
     *
     * <p>301/302/303 rewrite to GET only when the original carried a body — the body and its
     * {@code Content-Type} are dropped together, because carrying
     * {@code application/x-www-form-urlencoded} onto a bodyless GET makes some filters attempt form
     * parsing on an empty stream. Everything else keeps its method, notably HEAD: a grammar can
     * express HEAD, and silently promoting it to GET pulls a full body the caller never asked for,
     * inflating the input's measured latency and heap and corrupting its cost rank.
     */
    static String followMethod(int code, String method) {
        if (code == 307 || code == 308) return method;
        boolean carriesBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        if ((code == 301 || code == 302 || code == 303) && carriesBody) return "GET";
        return method;
    }

    /**
     * {@code scheme://host[:port]/path} — query and fragment REMOVED. Every recorded hop URL goes
     * through this, and hop 0's URL is built at construction time from the SUBSTITUTED
     * {@link RequestLine} (DD-038 substitutes the path deliberately, {@code :518}), so a step
     * {@code GET /edit?csrf=${{tok}}} would otherwise write a live CSRF token into a finding meta
     * file. The existing DD-036 guard test puts its token in the BODY and cannot see this.
     */
    static String strippedUrl(String url) {
        if (url == null) return "";
        int cut = url.length();
        int q = url.indexOf('?');
        if (q >= 0) cut = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        return url.substring(0, cut);
    }

    private static final java.util.Set<String> WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** One warn per distinct {@code key} for the whole run. Keyed rather than message-keyed because a
     *  refusal on every iteration, each naming a different URL, would bury the log line it exists to
     *  make diagnosable. */
    static void warnOnce(String key, String msg) {
        if (WARNED.add(key)) System.err.println("[Basquin] explore: " + msg);
    }
```

- [ ] **Step 4 — `./gradlew test --tests 'runner.coverage.RedirectPolicyTest'`** → all green
      (8 tests). Then `./gradlew test` → fully green; this task adds no call sites, so nothing else
      can move.
- [ ] **Step 5 — commit.** `feat(explore): pure redirect-decision helpers (DD-039 Task 1)`

**Mechanism-removal check:** delete `defaultPort`'s scheme fallback and
`sameOriginNormalizesTheDefaultPortAndIgnoresHostCase` fails on its first assertion. Delete
`resolveLocation`'s catch and `unparseableLocationReturnsNullRatherThanThrowing` fails with the
thrown `URISyntaxException`. Delete `strippedUrl`'s query cut and
`strippedUrlRemovesQueryAndFragment` fails carrying `LIVE-TOKEN-123`.

---

## Task 2: `ResultStore` accumulates per-hop entries under one id

**This is the agent-side half of spec §4b.** DD-040 mints one `X-Basquin-Req` id per request and
`ResultStore.put` **replaces by key** (`agent/ResultStore.java:48`, `MAP.put(id, e)`). Stamping every
hop with that id therefore has each hop overwrite the last: a chain of N hops still yields exactly
one entry and DD-040's 189-violation gap stays exactly where it is. "Stamp every hop" is necessary
and not sufficient.

**Files:**
- modify: `agent/ResultStore.java`
- modify: `test/agent/ResultStoreTest.java`
- modify: `test/agent/LeakSoftModeTest.java` (lines 71, 106, 118)
- modify: `test/agent/RequestBoundaryIdTest.java` (lines 24, 48, 88)
- modify: `test/agent/RequestBoundaryLoadPathCostTest.java` (line 109)
- **verify, do not edit:** `agent/LoadModeControl.java` (line 61) and `test/LoadModeControlTest.java`
  and `test/agent/LeakSoftModeTest.java:89`

> **Correction to round 3's C1.** It stated that changing the signatures "breaks every caller" and
> listed `LoadModeControl.java:61` among them. Verified by reading the line: it is
> `return ResultStore.format(ResultStore.take(id));`. Under `take → List<Entry>` and
> `format(List<Entry>)` that source line compiles **unchanged**. The same is true of
> `LeakSoftModeTest.java:89` (`ResultStore.format(ResultStore.take("salt-soft-wire"))`) and of every
> line in `LoadModeControlTest.java`, which goes through `LoadModeControl.handle` and compares against
> `ResultStore.MISS`. They are listed above so the agent *verifies* rather than assumes — but the
> real edit list is five files, not six, and inventing an edit to `LoadModeControl` would be churn.

**Interfaces produced:**

```java
public static final int MAX_HOPS_PER_ID = 5;
public static void put(String id, Entry e)          // APPENDS; capped; drops the OLDEST on overflow
public static java.util.List<Entry> take(String id) // ALL hops, immutable copy, empty list never null
public static String format(java.util.List<Entry> hops)   // one line per hop; MISS for null/empty
public static long overflowedHops()
```

**Interfaces consumed:** none new. `Entry` is unchanged.

**Constraints binding this task (restated here, not only in Global Constraints):**
- **`MAX_HOPS_PER_ID` is 5 and means five hops**, the same number as the driver's `MAX_HOPS` (five
  requests total). Do not pick a different number: two caps that disagree silently drop a hop, and a
  silently dropped hop is a lost violation.
- **Overflow drops the OLDEST, not the newest.** The newest hop of a redirect chain is the landing
  page — the committed render that is the highest-value measurement and the whole reason this feature
  exists. The oldest is a boilerplate 302. Count the drops; a silent drop is the defect class.
- **`format(null)` and `format(emptyList)` must both be `MISS`**, or
  `LoadModeControlTest:57,61,93` break and the driver's `POLL_MISS.equals(body)` check
  (`CoverageGuidedRun.java:874`) stops recognising a miss.
- **`detail` is app-derived and must be newline-sanitised**, not just pipe-sanitised. This is a new
  hazard the multi-line format creates: a `\n` inside `detail` would forge an extra hop line and the
  driver would count a violation the app invented.
- **`synchronized (MAP)` around both the append and the read-and-remove.** The natural one-liner
  `MAP.computeIfAbsent(id, k -> new ArrayList<>()).add(e)` performs the `add` **outside** the
  synchronized wrapper's monitor, so a concurrent `take` can hand a connector thread a list that is
  mid-append. The class javadoc at `:18-20` already establishes that the reader holds no
  `ITERATION_LOCK`, so this is a live race, not a theoretical one. `take` returns an immutable copy.

- [ ] **Step 1 — migrate the five existing test files.** Each is a mechanical widening; the
      *assertions* must strengthen, not weaken.

`test/agent/ResultStoreTest.java` — replace the bodies at the noted lines:

```java
    // :11-17
    @Test public void putThenTakeReturnsTheEntryExactlyOnce() {
        ResultStore.put("salt-1", new ResultStore.Entry("12,340,0", 2, "latency: 719ms > 250ms", false));
        java.util.List<ResultStore.Entry> e = ResultStore.take("salt-1");
        assertEquals(1, e.size());
        assertEquals(2, e.get(0).invariantCount());
        assertTrue("remove-on-read: a second take must miss", ResultStore.take("salt-1").isEmpty());
    }

    // :22-24
    @Test public void unknownIdMisses() {
        assertTrue(ResultStore.take("other-salt-1").isEmpty());
    }

    // :26-33  — size() still counts IDS, not hops, so this test says what it always said
    @Test public void evictsOldestBeyondCapacityAndStaysBounded() {
        for (int i = 0; i < ResultStore.CAPACITY + 50; i++) {
            ResultStore.put("s-" + i, new ResultStore.Entry("1,2,0", 0, null, false));
        }
        assertEquals(ResultStore.CAPACITY, ResultStore.size());
        assertTrue("oldest evicted", ResultStore.take("s-0").isEmpty());
        assertFalse("newest retained", ResultStore.take("s-" + (ResultStore.CAPACITY + 49)).isEmpty());
    }

    // :37-41
    @Test public void detailIsCappedSoRetentionIsBounded() {
        String huge = "x".repeat(5000);
        ResultStore.put("s-cap", new ResultStore.Entry("1,2,0", 1, huge, false));
        assertTrue(ResultStore.take("s-cap").get(0).detail().length() <= 200);
    }

    // :134-144  — format now takes a list; one entry must still be exactly one four-field line
    @Test public void formatSanitizesPipesInDetailSoTheWireFormatStaysFourFields() {
        ResultStore.Entry e = new ResultStore.Entry("12,340,0", 2, "a|b|c", true);
        String body = ResultStore.format(java.util.List.of(e));
        assertEquals("one entry is exactly one line", 1, body.split("\n", -1).length);
        String[] fields = body.split("\\|", -1);
        assertEquals("detail containing '|' must not add wire fields: " + body, 4, fields.length);
        assertEquals("12,340,0", fields[0]);
        assertEquals("2", fields[1]);
        assertEquals("a/b/c", fields[2]);
        assertEquals("leak flag must stay in the 4th field, not get pushed out by an unescaped '|'",
                "leak", fields[3]);
    }
```

and the reader lambda inside `concurrentPutAndTakeDoNotCorruptTheStore` (`:95-108`):

```java
        Runnable reader = () -> {
            try {
                java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int k = rnd.nextInt(POOL);
                    for (ResultStore.Entry e : ResultStore.take("c-" + k)) {
                        if (e == null || e.invariantCount() < 0 || e.invariantCount() >= POOL) {
                            throw new AssertionError("took a corrupted/aliased entry: " + e);
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        };
```

Then **add** these five tests to the same file:

```java
    // THE test for §4b. DD-040's put REPLACED by key, so a two-hop chain stamped with one id
    // recovered exactly one hop — which is why 189 violations stayed lost. Revert put() to
    // MAP.put(id, e) and this fails with "expected:<2> but was:<1>".
    @Test public void twoPutsUnderOneIdYieldTwoHopsFromOneTake() {
        ResultStore.put("salt-chain", new ResultStore.Entry("5,10,0", 1, "hop0 latency", false));
        ResultStore.put("salt-chain", new ResultStore.Entry("900,4096,2", 3, "hop1 latency", false));

        java.util.List<ResultStore.Entry> hops = ResultStore.take("salt-chain");

        assertEquals("both hops of the chain, under one id", 2, hops.size());
        assertEquals("in publish order, which is hop order", 1, hops.get(0).invariantCount());
        assertEquals(3, hops.get(1).invariantCount());
        assertTrue("still exactly ONE remove-on-read", ResultStore.take("salt-chain").isEmpty());
    }

    // A chain longer than the driver's own cap must not grow without bound, and must not throw away
    // the landing page — the committed render is the highest-value measurement in the chain.
    @Test public void overflowDropsTheOldestHopAndCountsIt() {
        long before = ResultStore.overflowedHops();
        for (int i = 0; i < ResultStore.MAX_HOPS_PER_ID + 2; i++) {
            ResultStore.put("salt-long", new ResultStore.Entry("1,2,0", i, null, false));
        }
        java.util.List<ResultStore.Entry> hops = ResultStore.take("salt-long");
        assertEquals(ResultStore.MAX_HOPS_PER_ID, hops.size());
        assertEquals("the NEWEST hop — the landing page — must survive",
                ResultStore.MAX_HOPS_PER_ID + 1, hops.get(hops.size() - 1).invariantCount());
        assertEquals("the oldest was dropped, not the newest", 2, hops.get(0).invariantCount());
        assertEquals("a dropped hop is a lost violation, so it is counted",
                before + 2, ResultStore.overflowedHops());
    }

    // The driver's miss detection is POLL_MISS.equals(body) and LoadModeControlTest compares against
    // ResultStore.MISS. Both break if an empty list formats as "" or "[]".
    @Test public void anEmptyOrNullListFormatsAsMiss() {
        assertEquals(ResultStore.MISS, ResultStore.format(null));
        assertEquals(ResultStore.MISS, ResultStore.format(java.util.List.of()));
    }

    @Test public void formatEmitsOneLinePerHopAndTheDriverCanSplitThem() {
        String body = ResultStore.format(java.util.List.of(
                new ResultStore.Entry("12,340,0", 2, "d0", false),
                new ResultStore.Entry("900,4096,1", 3, "d1", true)));
        String[] lines = body.split("\n", -1);
        assertEquals(2, lines.length);
        assertEquals("2", lines[0].split("\\|", 4)[1]);
        assertEquals("3", lines[1].split("\\|", 4)[1]);
        assertEquals("leak", lines[1].split("\\|", 4)[3]);
    }

    // NEW hazard created by the multi-line format: detail is app-derived, and a '\n' in it would
    // forge an extra hop line — the driver would count a violation the app invented. The '|'
    // sanitisation has always existed for the field-shifting version of this; the newline one is new.
    @Test public void aNewlineInDetailCannotForgeAnExtraHopLine() {
        String body = ResultStore.format(java.util.List.of(
                new ResultStore.Entry("1,2,0", 1, "boom\n9,9,9|99|forged|leak", false)));
        assertEquals("app-derived text must not be able to add a hop: " + body,
                1, body.split("\n", -1).length);
        assertFalse("nor smuggle a leak flag in", body.endsWith("|leak"));
    }
```

`test/agent/LeakSoftModeTest.java` — at `:71`, `:106`, `:118` the shape is identical; apply it three
times (the assertion *strengthens* from "not null" to "exactly one hop"):

```java
        // was: ResultStore.Entry e = ResultStore.take("salt-soft-record");
        //      assertNotNull("...", e);
        java.util.List<ResultStore.Entry> hops = ResultStore.take("salt-soft-record");
        assertEquals("a soft-mode leak must still publish an entry — otherwise soft mode loses the "
                + "finding entirely (stderr is not a channel the driver reads)", 1, hops.size());
        ResultStore.Entry e = hops.get(0);
        assertTrue("and that entry must carry the leak flag", e.leakDetected());
```

Leave `:89` (`ResultStore.format(ResultStore.take("salt-soft-wire"))`) **unchanged** — it compiles and
still asserts four fields on one line.

`test/agent/RequestBoundaryIdTest.java` — same widening at `:24` and `:48`. At `:88`:

```java
        // was: assertNotNull(ResultStore.take("salt-first"));
        assertFalse("the explore path must publish at all — this is the property the whole DD-040 "
                + "channel rests on, and assertNotNull on a never-null List asserts nothing",
                ResultStore.take("salt-first").isEmpty());
```

`test/agent/RequestBoundaryLoadPathCostTest.java:109`:

```java
            assertFalse("and the explore path must still publish",
                    ResultStore.take("salt-probe").isEmpty());
```

> **Why this rewrite is mandatory and not cosmetic (round 3's C2).** With `take` returning "empty
> list, never null", `assertNotNull(ResultStore.take(...))` is **vacuously true** and would pass
> forever — including after a regression that stops publishing entirely. Those two assertions are
> what pin "the explore path publishes at all". Sweep for any other
> `assertNotNull(ResultStore.take(` before finishing: `grep -rn "assertNotNull(ResultStore.take" test/`
> must return nothing.

- [ ] **Step 2 — run and confirm the shape of the failure.**
      `./gradlew test --tests 'agent.ResultStoreTest'`
      Expected: **compile errors** across the five edited files (`incompatible types: List<Entry>
      cannot be converted to Entry`, `cannot find symbol: MAX_HOPS_PER_ID`,
      `cannot find symbol: overflowedHops`), not red tests. Of the new tests, three are genuine red
      tests once it compiles (`twoPutsUnderOneIdYieldTwoHopsFromOneTake`,
      `overflowDropsTheOldestHopAndCountsIt`, `aNewlineInDetailCannotForgeAnExtraHopLine`); the other
      two (`anEmptyOrNullListFormatsAsMiss`, `formatEmitsOneLinePerHopAndTheDriverCanSplitThem`) and
      every migrated test are **regression guards** that must pass before and after.

- [ ] **Step 3 — rewrite `agent/ResultStore.java`.** The class javadoc `:7-21` stays; append the §4b
      paragraph noted below.

```java
package agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ... (keep the existing DD-040 javadoc at :7-21 verbatim) ...
 *
 * <p>DD-039 §4b: a value is a LIST of hops, not a single entry. Explore follows redirects itself and
 * stamps every hop of a chain with one id, so {@code put} must ACCUMULATE — DD-040's
 * {@code MAP.put(id, e)} had each hop overwrite the last, which is why a POST → 302 → GET chain
 * recovered exactly one hop and 189 of a measured 1,602 violations were never reported.
 *
 * <p>Order is publish order within THIS JVM, which is hop order because the driver issues hops
 * sequentially. That is what makes the driver's {@code hop=<n>} meaningful. A chain whose hops were
 * served by different replicas is recovered per-pod, so the driver's index is a position in the
 * recovered sequence rather than a proof of hop order — see {@code CoverageGuidedRun.pollResult}.
 *
 * <p>Retention bound: {@value #CAPACITY} ids × {@value #MAX_HOPS_PER_ID} hops × a 200-char detail
 * ≈ 256 KB worst case, in the JVM whose heap deltas this tool reports.
 */
public final class ResultStore {

    public static final int CAPACITY = 256;

    /**
     * Hops retained per id. The SAME number as the driver's follow cap
     * ({@code CoverageGuidedRun.MAX_HOPS} = 5 requests total), so a conforming driver never
     * overflows and this is a defensive bound on a version-skewed producer. Two caps that disagree
     * would silently drop a hop, and a silently dropped hop is a lost violation — the defect class
     * this whole change exists to remove.
     */
    public static final int MAX_HOPS_PER_ID = 5;

    private static final int DETAIL_MAX = 200;   // same cap the header path applies
    public static final String MISS = "miss";

    public record Entry(String costCsv, int invariantCount, String detail, boolean leakDetected) {
        public Entry {
            if (detail != null && detail.length() > DETAIL_MAX) detail = detail.substring(0, DETAIL_MAX);
        }
    }

    private static final AtomicLong TOTAL_VIOLATIONS = new AtomicLong();
    private static final AtomicLong OVERFLOWED_HOPS = new AtomicLong();

    private static final Map<String, List<Entry>> MAP =
            Collections.synchronizedMap(new LinkedHashMap<String, List<Entry>>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Entry>> eldest) {
                    return size() > CAPACITY;   // CAPACITY IDS, not entries — size() means what it meant
                }
            });

    private ResultStore() {}

    /**
     * APPEND this hop's measurements to the id's list.
     *
     * <p>Both the lookup and the append run inside {@code synchronized (MAP)} — the monitor for a
     * {@code Collections.synchronizedMap} is the wrapper itself. The natural one-liner,
     * {@code MAP.computeIfAbsent(id, k -> new ArrayList<>()).add(e)}, performs the {@code add}
     * OUTSIDE that monitor, so a concurrent {@code take} can hand a connector thread a list that is
     * mid-append. The reader holds no ITERATION_LOCK (see the class javadoc), so that is a live race.
     */
    public static void put(String id, Entry e) {
        if (id == null || e == null) return;
        TOTAL_VIOLATIONS.addAndGet(e.invariantCount());
        synchronized (MAP) {
            List<Entry> hops = MAP.get(id);
            if (hops == null) {
                hops = new ArrayList<>(2);
                MAP.put(id, hops);          // the only put: removeEldestEntry evicts whole IDS
            }
            hops.add(e);
            while (hops.size() > MAX_HOPS_PER_ID) {
                // Drop the OLDEST. The newest hop of a redirect chain is the landing page — the
                // committed render that is the highest-value measurement and the entire reason this
                // feature exists. The oldest is a boilerplate 302.
                hops.remove(0);
                OVERFLOWED_HOPS.incrementAndGet();
            }
        }
    }

    /**
     * Returns ALL hops recorded under this id, in publish order, and REMOVES the id. Empty list on a
     * miss — never null.
     *
     * <p>Still exactly ONE remove-on-read, so a duplicate poll honestly misses. Note that this alone
     * does NOT give the driver header/poll exclusivity: that is a property of the driver reconciling
     * at one point (see {@code CoverageGuidedRun.reconcile}), because the header path saves the
     * moment it reads the header and no flag can un-save a file.
     *
     * <p>The copy is taken inside the monitor: the caller must never hold a list a concurrent
     * {@code put} can append to.
     */
    public static List<Entry> take(String id) {
        if (id == null) return Collections.emptyList();
        synchronized (MAP) {
            List<Entry> hops = MAP.remove(id);
            if (hops == null || hops.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(hops));
        }
    }

    /** IDS currently held, not hops — unchanged meaning, so the capacity assertions still hold. */
    public static int size() { return MAP.size(); }

    public static long totalViolations() { return TOTAL_VIOLATIONS.get(); }

    /** Hops dropped because a chain exceeded {@link #MAX_HOPS_PER_ID}. Counted because a silently
     *  dropped hop is a lost violation, which is exactly the defect class DD-039 removes. */
    public static long overflowedHops() { return OVERFLOWED_HOPS.get(); }

    /**
     * Wire format: ONE LINE PER HOP, {@code '\n'}-separated, each line
     * {@code costCsv|invariantCount|detail|leak} — four fields, plaintext. {@code detail} is
     * app-derived, so BOTH {@code '|'} and newlines are sanitised here: a pipe would shift the field
     * boundaries, and a newline would forge an extra hop line and have the driver count a violation
     * the app invented. The driver splits on {@code '\n'} and parses each line with a 4-field limit.
     *
     * <p>Null or empty is {@link #MISS}, so {@code LoadModeControl}'s caller and the driver's
     * {@code POLL_MISS.equals(body)} check are unchanged by accumulation.
     */
    public static String format(List<Entry> hops) {
        if (hops == null || hops.isEmpty()) return MISS;
        StringBuilder sb = new StringBuilder();
        for (Entry e : hops) {
            if (e == null) continue;
            if (sb.length() > 0) sb.append('\n');
            String d = e.detail() == null ? ""
                    : e.detail().replace('|', '/').replace('\n', ' ').replace('\r', ' ');
            sb.append(e.costCsv() == null ? "" : e.costCsv()).append('|')
              .append(e.invariantCount()).append('|').append(d).append('|')
              .append(e.leakDetected() ? "leak" : "");
        }
        return sb.length() == 0 ? MISS : sb.toString();
    }

    public static void clearForTest() {
        MAP.clear();
        TOTAL_VIOLATIONS.set(0);
        OVERFLOWED_HOPS.set(0);
    }
}
```

- [ ] **Step 4 — verify the two untouched files really are untouched.**
      `grep -n "ResultStore.format(ResultStore.take" agent/LoadModeControl.java` → line 61, unchanged.
      Then `./gradlew test` → fully green, including `LoadModeControlTest`,
      `RequestBoundaryIdTest`, `RequestBoundaryLoadPathCostTest`, `LeakSoftModeTest`,
      `ReportChannelTest` and `MultiReplicaPollTest` (the driver still receives one line per poll, so
      its unchanged parse is unaffected).
- [ ] **Step 5 — commit.** `feat(agent): ResultStore accumulates per-hop entries under one id (DD-039 Task 2)`

**Mechanism-removal check:** revert `put` to `MAP.put(id, e)` and
`twoPutsUnderOneIdYieldTwoHopsFromOneTake` fails `expected:<2> but was:<1>`. Change the overflow to
drop the newest and `overflowDropsTheOldestHopAndCountsIt` fails on the landing-page assertion. Drop
the newline sanitisation and `aNewlineInDetailCannotForgeAnExtraHopLine` fails with 2 lines.

---

## Task 3: The driver parses the accumulated multi-hop wire format

**This is the task round 3's C3 found missing, and without it nothing else closes the gap.** Nothing
in the previous plan touched `fetchResult` or `pollResult`; `fetchResult` destroys the line
boundaries before `pollResult` ever sees them.

**Files:**
- modify: `runner/coverage/CoverageGuidedRun.java` (`fetchResult` `:920-941`, `pollResult` `:855-912`)
- create: `test/runner/coverage/AccumulatedPollTest.java`

**Interfaces produced:**

```java
static CostSample pollResult(String base, String reqId, String label)              // unchanged shape, hops = 1
// (pollResultOrNull's javadoc {@link #reconcile} is a forward reference to a Task-4 symbol; javac
//  ignores an unresolved @link, so it compiles in Task 3 and resolves once Task 4 lands.)
static CostSample pollResult(String base, String reqId, String label, int hops)    // counts a miss
private static CostSample pollResultOrNull(String base, String reqId, String label, int hops)  // null on miss
```

**Interfaces consumed:** `ResultStore`'s multi-line wire format (Task 2); `PodPollTargets.pollBases`
(`runner/coverage/PodPollTargets.java:105`); `FuzzIO.saveWithMeta(byte[], String, String)`
(`runner/util/FuzzIO.java:64`, `public static`).

**Constraints binding this task:**
- **The 3-arg `pollResult` must keep byte-identical behaviour**, because `ReportChannelTest:251` and
  `MultiReplicaPollTest:133,150,169,248` all drive it and all must stay green untouched.
- **`hop=<n>` is the position in the concatenated recovered sequence** (spec amendment A4b-4). Write
  that in the javadoc, with the multi-replica caveat.
- **The summed invariant count feeds `CostModel.score`** — decided in Global Constraint 10, not by
  the implementer.
- **Multi-pod merge only when `hops > 1`** (spec amendment A4b-3). A single-hop request can only have
  been served by one pod, so break-at-first is correct *and* cheaper there; a multi-hop chain can be
  split across pods and breaking early returns a silently truncated result as `measured=true`, which
  `reportMisses` cannot see.
- **Never throw.** `pollResult` runs from a `finally`; an escape replaces the in-flight `serverError`
  and the 500 finding — the most interesting request there is — is lost.
- Meta files are `.meta.txt`; save/restore `basquin.fuzz.resultsDir`; no `catch (Exception ignored)`
  in a handler.

- [ ] **Step 1 — write `test/runner/coverage/AccumulatedPollTest.java`:**

```java
package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DD-039 §4b, driver half: the store now returns N hops under one id, and the driver must READ all
 * of them.
 *
 * <p>The defect this file makes unreachable is the one that would have made DD-039 close none of
 * DD-040's 189-violation gap while every other test passed: {@code fetchResult} appended each line
 * without its terminator, so an N-hop body arrived as one concatenated string and
 * {@code split("\\|", 4)} read hop 0's count and silently discarded hops 1..N-1.
 */
public class AccumulatedPollTest {

    private HttpServer server;
    private String base;
    private volatile String pendingBody;
    private final AtomicInteger pollHits = new AtomicInteger();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private String priorPodHost;

    @Before
    public void startServer() throws IOException {
        priorPodHost = System.getProperty("basquin.report.podHost");
        System.setProperty("basquin.report.podHost", "off");   // single-target: no fan-out
        PodPollTargets.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            try {
                pollHits.incrementAndGet();
                String body = pendingBody;
                pendingBody = null;                                  // remove-on-read
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) {
                // NEVER swallow: a handler that dies mid-response would let a truncated read pass.
                handlerFailure.compareAndSet(null, t);
                throw t;
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
        if (priorPodHost != null) System.setProperty("basquin.report.podHost", priorPodHost);
        else System.clearProperty("basquin.report.podHost");
        PodPollTargets.resetForTest();
        assertNull("a test server handler threw", handlerFailure.get());
    }

    /**
     * THE test. Three hops accumulated under one id must produce THREE Invariant-Remote records with
     * distinct hop numbers and a summed count — not one record carrying hop 0's number.
     *
     * <p>Remove the '\n' from fetchResult, or the per-line parse from pollResult, and this fails with
     * "expected:<3> but was:<1>" and a CostSample of 2 instead of 6. That single assertion is the
     * difference between closing DD-040's 189-violation gap and closing none of it.
     */
    @Test
    public void aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers() throws Exception {
        pendingBody = "12,340,0|2|latency: 300ms > 250ms|\n"
                    + "5,10,0|1|heap: 900KB > 500KB|\n"
                    + "900,4096,2|3|latency: 900ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-accum-3hop");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-3hop", "/login", 3);

            assertTrue(s.measured);
            assertEquals("the input's TOTAL violations across the chain, per spec §4", 6, s.invariantCount);
            assertEquals("heap deltas are SUMMED across hops", 340L + 10L + 4096L, s.heapDeltaKb);
            assertEquals("thread deltas are summed too", 0 + 0 + 2, s.threadDelta);

            List<String> metas = waitForMetas(dir, "Invariant-Remote", 3);
            assertEquals("one record per breaching hop — a single summed record reads "
                    + "'6 violations on POST /login' when 3 were the dashboard render", 3, metas.size());
            assertTrue(joined(metas).contains("hop=0"));
            assertTrue(joined(metas).contains("hop=1"));
            assertTrue(joined(metas).contains("hop=2"));
            assertTrue("each record carries ITS OWN hop's detail",
                    joined(metas).contains("heap: 900KB > 500KB"));
            assertTrue("labelled with the raw step, never a hop URL (DD-036)",
                    joined(metas).contains("route=/login"));
        });
    }

    /** A clean hop in the middle of a chain contributes cost but no finding. */
    @Test
    public void onlyBreachingHopsProduceRecords() throws Exception {
        pendingBody = "12,340,0|0||\n5,10,0|2|boom|";

        Path dir = Files.createTempDirectory("basquin-accum-clean");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-mixed", "/x", 2);
            assertEquals(2, s.invariantCount);
            assertEquals(350L, s.heapDeltaKb);
            List<String> metas = waitForMetas(dir, "Invariant-Remote", 1);
            assertEquals("a count=0 hop must not file a finding", 1, metas.size());
            assertTrue("and the record must name the hop that actually breached",
                    metas.get(0).contains("hop=1"));
        });
    }

    /** A leak on ANY hop of the chain is a leak for the input. */
    @Test
    public void aLeakOnAnyHopIsRecovered() throws Exception {
        pendingBody = "12,340,0|0||\n5,10,1|0||leak";
        Path dir = Files.createTempDirectory("basquin-accum-leak");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.pollResult(base, "salt-leak", "/leaky", 2);
            assertEquals(1, waitForMetas(dir, "Leak-Remote", 1).size());
        });
    }

    /** Regression guard: the single-hop path is byte-identical to DD-040's. */
    @Test
    public void aSingleHopBodyBehavesExactlyAsBefore() throws Exception {
        pendingBody = "719,120,0|2|latency: 719ms > 250ms|";
        Path dir = Files.createTempDirectory("basquin-accum-1hop");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-1hop", "/big");
            assertTrue(s.measured);
            assertEquals(2, s.invariantCount);
            assertEquals(120L, s.heapDeltaKb);
            assertEquals(1, waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    /** An unparseable body is a miss, never a measured zero — the degeneration DD-040 exists to stop. */
    @Test
    public void anUnparseableBodyIsAMissNotAMeasuredZero() {
        pendingBody = "not|a|number|at-all";     // f[1] is not an int on any line
        long before = CoverageGuidedRun.reportMisses;
        CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-bad", "/x", 2);
        assertFalse(s.measured);
        assertEquals(before + 1, CoverageGuidedRun.reportMisses);
    }

    /** A truncated tail line must be discarded by its own parse, not shift the fields of a good one. */
    @Test
    public void aTruncatedTailLineIsDiscardedNotMisparsed() throws Exception {
        pendingBody = "12,340,0|2|ok|\n5,10,0";     // second line has 2 '|'-free fields → f.length < 2
        Path dir = Files.createTempDirectory("basquin-accum-trunc");
        withResultsDir(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.pollResult(base, "salt-trunc", "/x", 2);
            assertTrue(s.measured);
            assertEquals("only the well-formed hop counts", 2, s.invariantCount);
            assertEquals(1, waitForMetas(dir, "Invariant-Remote", 1).size());
        });
    }

    // --- helpers (no production code, no sleeps beyond a bounded poll) ---

    private interface Body { void run() throws Exception; }

    private static void withResultsDir(Path dir, Body b) throws Exception {
        String prior = System.getProperty("basquin.fuzz.resultsDir");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try { b.run(); } finally {
            if (prior != null) System.setProperty("basquin.fuzz.resultsDir", prior);
            else System.clearProperty("basquin.fuzz.resultsDir");
        }
    }

    /**
     * Poll the results dir until EXACTLY-OR-MORE-THAN n .meta.txt files of a classification exist,
     * then settle briefly and return them.
     *
     * <p>Written locally rather than reusing ExploreCorrelationTest.waitAndReadAll (which is
     * {@code private static} at {@code :210} and returns as soon as ONE file appears — right for its
     * own "no token anywhere" assertion, wrong for a count) and rather than draining TriageSink
     * (whose public surface is {@code ensureStarted()}/{@code submit()} only, and whose consumer
     * {@code take()}s a task off the queue BEFORE running it, so the queue can be empty while a write
     * is still in flight).
     */
    static List<String> waitForMetas(Path dir, String classification, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<String> found = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            found = readMetas(dir, classification);
            if (found.size() >= n) break;
            Thread.sleep(25);
        }
        if (found.size() < n) {
            fail("expected " + n + " " + classification + " record(s) in " + dir + ", saw "
                    + found.size() + " — a recovered violation that is not SAVED is not reported, "
                    + "which is the defect DD-040 exists to fix");
        }
        Thread.sleep(150);          // settle, so an EXTRA record (a double-save) is caught too
        return readMetas(dir, classification);
    }

    private static List<String> readMetas(Path dir, String classification) throws Exception {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        for (Path p : Files.newDirectoryStream(dir, "*.meta.txt")) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (text.contains("classification=" + classification)) out.add(text);
        }
        return out;
    }

    private static String joined(List<String> metas) { return String.join("\n", metas); }
}
```

- [ ] **Step 2 — run; expect red, not a compile error** (the 3-arg `pollResult` exists; the 4-arg does
      not, so the four tests that use it fail to compile — fix by adding the overload first if you
      prefer a red-then-green cycle).
      `./gradlew test --tests 'runner.coverage.AccumulatedPollTest'`
      Expected once it compiles: `aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers` fails
      `expected:<6> but was:<2>` and `expected 3 Invariant-Remote record(s), saw 1`.
      `aSingleHopBodyBehavesExactlyAsBefore` is a **regression guard** and passes throughout.

- [ ] **Step 3a — `fetchResult` must preserve the line boundaries.** Replace the read loop at
      `CoverageGuidedRun.java:929-936`:

```java
            StringBuilder sb = new StringBuilder();
            InputStream is = pc.getResponseCode() >= 400 ? pc.getErrorStream() : pc.getInputStream();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    // DD-039: PRESERVE the '\n'. readLine() strips the terminator and the pre-DD-039
                    // loop appended none back, so an accumulated N-hop body arrived as ONE
                    // concatenated string and pollResult's split("\\|", 4) read hop 0's count and
                    // silently discarded hops 1..N-1. One missing character was the difference
                    // between closing DD-040's 189-violation gap and closing none of it.
                    //
                    // The cap is also checked AFTER the append now: checked before, it truncated the
                    // body one line early. A tail line cut by the cap fails its own per-line parse
                    // in pollResult and is discarded, which is the honest outcome.
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line);
                        if (sb.length() >= POLL_BODY_MAX) break;
                    }
                }
            }
```

and add the constant beside `POLL_READ_TIMEOUT_MS` (`:609`):

```java
    /** DD-039: the result body is now up to {@code ResultStore.MAX_HOPS_PER_ID} lines. Bound:
     *  5 hops × (200-char detail + costCsv + count + separators) ≈ 1.2 KB; 8 KB is generous headroom
     *  for a version-skewed target without letting a hostile body grow unbounded in the driver. */
    private static final int POLL_BODY_MAX = 8192;
```

- [ ] **Step 3b — rewrite `pollResult` (`:855-912`) as three methods.** Keep the existing javadoc at
      `:821-854` and replace its "Known residual (multi-replica + followed redirect)" paragraph with
      the amended rule (see Task 6, which owns the comment corrections; here, write the new javadoc
      text below).

```java
    /**
     * DD-040/DD-039: recover a request's measurements from the target's id-keyed result store, for
     * the responses that could not carry a header because they had already committed.
     *
     * <p>Two things this method must never do, both learned the expensive way: <b>throw</b> (it is
     * called from a {@code finally}; an exception escaping there REPLACES the in-flight
     * {@code serverError} and the 500 finding is lost), and <b>stop at recovering the count</b> (a
     * recovered violation is only a FINDING once it is saved via {@link FuzzIO#saveWithMeta} →
     * {@code StatusReporter.recordSaved} → {@code findInvariant}).
     *
     * <p><b>DD-039: the body is one line per hop.</b> Explore stamps every hop of a redirect chain
     * with one id and {@code ResultStore} accumulates them, so this sums invariant counts, sums heap
     * and thread deltas, ORs the leak flags, and saves ONE {@code Invariant-Remote} record per
     * BREACHING hop carrying {@code hop=<n>}. A single summed record reads "6 violations on
     * POST /login" when 3 of them were the dashboard render — the multi-hop case this exists to
     * capture is exactly the one a sum erases.
     *
     * <p><b>What {@code hop=<n>} means.</b> The position in the sequence returned by {@code take},
     * which is publish order, which is hop order because the driver issues hops sequentially into
     * one JVM. When a chain's hops were served by DIFFERENT replicas the sequence is the
     * concatenation of each pod's list in poll order, so the index is a position in the recovered
     * sequence rather than a proof of hop order. The count and the cost are unaffected.
     *
     * <p><b>Pod fan-out.</b> {@code hops <= 1}: at most one pod can hold the id, so the first
     * well-formed answer is the whole answer and asking further pods is pure latency — DD-040's
     * behaviour exactly. {@code hops > 1}: the Service VIP routes each hop's connection
     * independently, so hop 0's list can sit on pod A and hop 1's on pod B; stopping at the first
     * hit would return a plausible-looking, silently TRUNCATED result as {@code measured=true},
     * which {@code reportMisses} cannot see. So every base is asked and the answers are merged.
     */
    static CostSample pollResult(String base, String reqId, String label) {
        return pollResult(base, reqId, label, 1);
    }

    /** As above, for a chain of {@code hops} requests. A miss is COUNTED here. */
    static CostSample pollResult(String base, String reqId, String label, int hops) {
        CostSample s = pollResultOrNull(base, reqId, label, hops);
        return s != null ? s : recordMiss();
    }

    /**
     * The poll itself: the recovered sample, or {@code null} when nothing well-formed came back.
     * Never throws and never counts a miss — the CALLER decides whether "nothing" is a miss or a
     * fall-back to the header reading, which is what stops a forced multi-hop poll from converting a
     * measured request into a miss (see {@link #reconcile}).
     */
    private static CostSample pollResultOrNull(String base, String reqId, String label, int hops) {
        List<String> pollBases = PodPollTargets.pollBases(base);
        // Pod addressing was demanded and DNS could not say where the pods are. Guessing via the VIP
        // would restore the 1-in-N lottery behind a poll that looks like it worked (§A.6).
        if (pollBases.isEmpty()) return null;
        List<String> bodies = new ArrayList<>();
        List<String> servedBy = new ArrayList<>();
        for (String pollBase : pollBases) {
            String candidate = fetchResult(pollBase, reqId);
            // '|' is the field separator: anything without it is `miss` or garbage, and the next pod
            // may still hold the real entry. Only a well-formed body counts as an answer.
            if (candidate == null || candidate.isEmpty() || POLL_MISS.equals(candidate)
                    || candidate.indexOf('|') < 0) {
                continue;
            }
            bodies.add(candidate);
            servedBy.add(pollBases.size() > 1 ? pollBase : null);   // only meaningful when we chose
            if (hops <= 1) break;                                   // see the javadoc: one pod, one answer
        }
        if (bodies.isEmpty()) return null;

        int totalCount = 0; long heapKb = 0; int threadDelta = 0; boolean anyLeak = false;
        int hop = 0; int parsedLines = 0;
        String leakPod = "";
        List<String[]> breaching = new ArrayList<>();     // {hop, count, detail-or-null, podMeta}
        for (int b = 0; b < bodies.size(); b++) {
            String podMeta = servedBy.get(b) == null ? "" : "\npod=" + servedBy.get(b);
            for (String line : bodies.get(b).split("\n")) {
                if (line.isEmpty()) continue;
                // 4-field limit: `detail` is app-derived and a version-skewed target could still emit
                // a separator. The count and the cost come from fields the app cannot reach.
                String[] f = line.split("\\|", 4);
                if (f.length < 2) continue;              // a truncated tail line: discard, never shift
                int count;
                try { count = Integer.parseInt(f[1].trim()); }
                catch (NumberFormatException e) { continue; }
                parsedLines++;
                totalCount += count;
                String[] cost = f[0].split(",");
                if (cost.length == 3) {
                    try {
                        heapKb += Long.parseLong(cost[1].trim());
                        threadDelta += Integer.parseInt(cost[2].trim());
                    } catch (NumberFormatException ignored) { }
                }
                if (f.length > 3 && "leak".equals(f[3].trim())) { anyLeak = true; leakPod = podMeta; }
                if (count > 0) {
                    String detail = f.length > 2 && !f[2].isEmpty() ? f[2] : null;
                    breaching.add(new String[]{String.valueOf(hop), String.valueOf(count), detail, podMeta});
                }
                hop++;
            }
        }
        if (parsedLines == 0) return null;   // an unparseable body is a miss; it is not a measured zero

        try {
            // The step that makes the campaign's number move. `label` is the RAW recipe (DD-036) —
            // never a substituted request line, which would write a live CSRF token to disk.
            for (String[] r : breaching) {
                FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                        "route=" + label + "\ncount=" + r[1] + "\nhop=" + r[0]
                                + (r[2] != null ? "\ndetail=" + r[2] : "") + r[3]);
            }
            if (anyLeak) {
                FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Leak-Remote",
                        "route=" + label + "\nleak=true" + leakPod);
            }
        } catch (Throwable ignored) {
            // Saving is best-effort against the never-throw contract above; the measurement stands.
        }
        reportMeasured++;
        return new CostSample(heapKb, threadDelta, totalCount, true);
    }
```

- [ ] **Step 4 — `./gradlew test`** → fully green. Specifically re-check, by name, that these
      pre-existing tests still pass untouched: `ReportChannelTest` (all 13),
      `MultiReplicaPollTest` (all 10 — `theFanOutFindsTheEntryBehindAPodThatMisses` still sees
      `podA.polls == 1` and `podB.polls == 1` because it drives the single-hop entry point, where A
      answers `miss` and B answers the entry), `ExploreCorrelationTest`, `ExploreRequestTest`.
- [ ] **Step 5 — commit.** `feat(explore): parse the accumulated multi-hop result body (DD-039 Task 3)`

**Mechanism-removal check:** revert the `sb.append('\n')` and
`aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers` fails `expected:<6> but was:<2>`. Revert the
per-line loop to a single `split("\\|", 4)` and it fails the same way. Revert `if (hops <= 1) break;`
to an unconditional `break` and Task 4's split-pod case (added there) fails; nothing here regresses,
which is why the unconditional-break regression is pinned in Task 4 rather than asserted twice.

---

## Task 4: The hop loop, and one reconciliation point for reporting

The largest task, and it must stay one task: round 3's C4 (suppress the header save) and I11 (poll
and merge, don't poll and replace) are **one decision**, and the previous plan's attempt to specify
either alone produced a bug. Landing the loop without the reconciliation ships a double-save; landing
the reconciliation without the loop is unobservable.

**Proven by throwaway spike before this build (see `.superpowers/sdd/dd039-spike-report.md`).** The
data path here takes the motivating POST→302→GET case from **1 counted violation to 2**. Three things
the spike established that an implementer will otherwise get wrong, each now in the spec's §4b too:

1. **`reconcile` prefers the polled hop set and falls back to the header ONLY when the poll returns
   no hops.** Do not attempt to de-duplicate by hop identity — `Entry` has none, and the header is
   always a *subset* of what the store holds for that id, so a union would double-count the final
   hop.
2. **The header's `saveWithMeta` is REMOVED from the header-read site**, not guarded by a flag. It
   moves into `reconcile`, which is the sole caller of `saveWithMeta` for this request. Clearing
   `headerReported` cannot un-save a call that already ran — the spike's stock run proved this is a
   structural change, not a reorder.
3. **The measured-vs-miss decision is made once, inside `reconcile`.** If the poll path records a
   miss and `reconcile` then records measured off the header fallback, both counters tick for one
   request. `reconcile` owns the decision.

**Files:**
- modify: `runner/coverage/CoverageGuidedRun.java` (`request` 4-arg `:697-819`, `resetSession` `:500`)
- create: `test/runner/coverage/ExploreRedirectTest.java`

**Interfaces produced:**

```java
static final int MAX_HOPS = 5;                        // FIVE REQUESTS total
static void resetSession()                            // was private (:500); widened for @Before
private static void drain(HttpURLConnection c)
private static void captureSessionCookieFrom(HttpURLConnection c)
private static void saveHeaderInvariants(String label, java.util.List<String[]> breaches)
static CostSample reconcile(String base, String reqId, String label, int hops,
        boolean headerReported, CostSample headerSample, java.util.List<String[]> hdrBreaches)
```

**Interfaces consumed:** Task 1's `isRedirect`, `resolveLocation`, `safeUri`, `sameOrigin`,
`followMethod`, `strippedUrl`; Task 3's `pollResultOrNull` and 4-arg `pollResult`; `LoadRun.RUN_SALT`
(`runner/coverage/LoadRun.java:595`, `static final String`, same package).

### Spell out the restructure

**Kept verbatim from the previous plan's Task 3 — round 3 called this "the best work in the revision"
and verified every item against `:697-728` and `:780`. Do not churn it.**

- `HttpURLConnection c` and `int code` must be **hoisted above the loop**; they are loop-scoped today
  and the post-loop body-read/capture/`serverError` block would not compile.
- `new URL(base + r.path())` → `new URL(url)`; `setRequestMethod(r.method())` →
  `setRequestMethod(method)`.
- `if (r.body() != null)` → `if (reqBody != null)`. Leave it on `r.body()` and the POST body **and**
  its `Content-Type` are re-sent on a rewritten GET hop.
- **Name collision:** the request-body local cannot be `body` — `StringBuilder body` is already
  declared at `:780` in the same method scope. Use `reqBody`.
- **Set `Cookie` AND `X-Basquin-Req` explicitly on every hop.** The JDK carries neither onto a
  method-rewritten hop; that is both DD-039's motivating defect and DD-040's residual.
- Each hop is drained to EOF before the next is issued. Every terminal condition `break`s *before*
  the drain, so the final hop's body survives for the capture step.
- Keep `MAX_HOPS`, `drain`, and `captureSessionCookieFrom` **defined in this task**. It must
  **compile and commit alone**: no task may reference a symbol a later task introduces.

### Additional constraints binding this task

- **`IOException` is not imported** (`:9-18` — verified). `drain` must write
  `catch (java.io.IOException ignored)` or the task adds the import. Do not assume it is there.
- **Strip the query and fragment AT CONSTRUCTION.** `visited.add(strippedUrl(url))`, never
  `visited.add(url)` — Task 5's `Redirect-Loop` finding writes `visited` out as `hops=`, and hop 0's
  URL is `base + r.path()` where `r` is the **substituted** `RequestLine` (`runSequence` substitutes
  at `:518`). A Task-5 instruction to strip cannot retroactively clean what this task put in the set.
- **Parse hop 0's URL through `safeUri`, never `URI.create`.** Hop 0's path is the substituted path
  and can carry a space, `{}`, `|` or `^`. `URI.create` throws `IllegalArgumentException`, which
  escapes `request(...)` into `runSequence`'s `catch (Throwable)` (`:537-539`) →
  `StatusReporter.recordCrash()` + `FuzzIO.saveInteresting` → a **false crash finding filed against
  the app**, carrying a stack that points into driver code. Today's `new URL(base + r.path())`
  (`:699`) is lenient and does not validate, so an unguarded `URI.create` is a regression this
  restructure would introduce.
- **`resetSession()` must become package-private.** `sessionCookie` is `private static volatile` at
  `:498` and `resetSession()` is `private` at `:500`, so a same-package test cannot clear it and the
  whole suite shares one JVM. `ExploreRedirectTest`'s pre-existing-cookie test seeds a session that
  would otherwise persist into every later test in the JVM, including `ExploreCorrelationTest`.
- **`hops >= MAX_HOPS` after the increment = five requests**, per Global Constraint 3.
- **Nothing saves inside the loop.** All saving happens in `reconcile`.

### The reconciliation rule, stated once

> `hops == 1` and a reporting header arrived → the header describes the one hop that happened, so it
> is complete. Save from it; do **not** poll (remove-on-read would take the store's copy of the same
> violation).
> `hops > 1` → poll for all hops, because a header can only ever describe the final one.
> **The poll result wins whenever it is well-formed; the header-derived record is emitted only when
> the poll returned nothing at all.** That is what keeps "never both, for the same hop" true without
> needing to know which hops the poll happened to recover — and it is why a forced poll that misses
> falls back to the header reading instead of recording a miss.

Round 3's C4, restated so it cannot be missed: **clearing `headerReported` does not undo a save.**
`FuzzIO.saveWithMeta` at `:761-763` fires the instant the header is read. That call site is
**deleted** by this task; the record is held in a local and emitted, or not, by `reconcile`.

- [ ] **Step 1 — write `test/runner/coverage/ExploreRedirectTest.java`:**

```java
package runner.coverage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-039: explore follows redirects itself.
 *
 * <p>Three defects, one line ({@code setInstanceFollowRedirects(true)}): a {@code Set-Cookie} on the
 * intermediate 3xx is unreachable (the JDK discards the response headers before re-issuing); the
 * {@code Cookie} REQUEST header is dropped on every method-rewritten hop, so landing pages rendered
 * anonymously; and the intermediate hop's {@code X-Basquin-*} headers are discarded.
 *
 * <p>The measurement half is what closes DD-040's 189-violation gap, and the test that pins it —
 * {@link #aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce} — deliberately makes its second
 * hop a COMMITTED response with no reporting headers at all, the 97.3% case DD-040 measured. Every
 * assertion in it is unreachable through the header path.
 */
public class ExploreRedirectTest {

    /** One request the fake server saw. */
    record Seen(String method, String path, String cookie, String reqId,
                String contentType, String body) { }

    private HttpServer server;
    private String base;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pollHits = new AtomicInteger();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private volatile String pendingBody;
    private String priorPodHost;

    @Before
    public void setUp() throws IOException {
        CoverageGuidedRun.resetSession();          // the suite shares one JVM; a seeded cookie leaks
        priorPodHost = System.getProperty("basquin.report.podHost");
        System.setProperty("basquin.report.podHost", "off");
        PodPollTargets.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.createContext("/__basquin/result", (HttpExchange ex) -> {
            try {
                pollHits.incrementAndGet();
                String body = pendingBody;
                pendingBody = null;                                    // remove-on-read
                byte[] out = (body == null ? "miss" : body).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        CoverageGuidedRun.resetSession();
        if (priorPodHost != null) System.setProperty("basquin.report.podHost", priorPodHost);
        else System.clearProperty("basquin.report.podHost");
        PodPollTargets.resetForTest();
        assertNull("a test server handler threw — a truncated response must not read as a pass",
                handlerFailure.get());
    }

    private void start() {
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void record(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("Cookie"),
                ex.getRequestHeaders().getFirst("X-Basquin-Req"),
                ex.getRequestHeaders().getFirst("Content-Type"),
                new String(b, StandardCharsets.UTF_8)));
    }

    /** A 3xx to {@code target}, optionally issuing a rotated session, with a boilerplate body that
     *  MUST be drained (Tomcat's sendRedirect emits one). */
    private void redirect(String path, int code, String target, String setCookie) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                if (setCookie != null) ex.getResponseHeaders().add("Set-Cookie", setCookie);
                ex.getResponseHeaders().add("Location", target);
                byte[] out = "<html><body>Moved</body></html>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    /** An ordinary 200 carrying {@code body}. */
    private void page(String path, String body) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    /** A response that has ALREADY COMMITTED: 16 KB, no reporting headers. Ported from
     *  ReportChannelTest.serveCommittedApp — the case that CANNOT report through a header. */
    private void committed(String path) {
        server.createContext(path, (HttpExchange ex) -> {
            try {
                record(ex);
                byte[] out = new byte[16384];
                java.util.Arrays.fill(out, (byte) 'x');
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
    }

    // ------------------------------------------------------------------ the motivating cookie cases

    /** Spec Verification #1. Spring Security answers POST /login with a 302 and issues the ROTATED
     *  JSESSIONID ON THAT 302 — a deliberate session-fixation defence. Under auto-follow the JDK
     *  discards those headers before re-issuing, so the driver kept the pre-login session forever. */
    @Test
    public void aSessionRotatedOnThe302ReachesTheNextHop() throws Exception {
        redirect("/login", 302, "/landing", "JSESSIONID=ROTATED; Path=/; HttpOnly");
        page("/landing", "ok");
        start();

        CoverageGuidedRun.request(base, "POST /login u=a&p=b");

        assertEquals(2, seen.size());
        assertEquals("JSESSIONID=ROTATED", seen.get(1).cookie());
        assertEquals("a 302 after a POST rewrites to GET (spec §3, JDK Evidence TEST A)",
                "GET", seen.get(1).method());
    }

    /** Spec Verification #2, and the one that DISCRIMINATES: a 302 that sets NO cookie. The target
     *  must still receive the pre-existing one. The JDK issues a method-rewritten hop with no Cookie
     *  header at all, so this failed before DD-039 even for a session that never rotated — every
     *  redirecting explore step had been scoring coverage against a logged-out page. */
    @Test
    public void aPreExistingCookieIsCarriedOntoAHopThatSetsNoCookie() throws Exception {
        page("/seed-page", "seed");
        redirect("/go", 302, "/dest", null);
        page("/dest", "ok");
        server.createContext("/seed", (HttpExchange ex) -> {
            try {
                ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=PREEXISTING; Path=/");
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.request(base, "/seed");     // establishes the session
        seen.clear();                                  // so the static field's prior value is never asserted on

        CoverageGuidedRun.request(base, "/go");

        assertEquals(2, seen.size());
        assertEquals("hop 0 carries the session", "JSESSIONID=PREEXISTING", seen.get(0).cookie());
        assertEquals("and so must the hop the JDK used to send anonymously",
                "JSESSIONID=PREEXISTING", seen.get(1).cookie());
    }

    /** Spec Verification #3: a three-hop chain, each hop rotating, ends with the last value. */
    @Test
    public void aThreeHopChainEndsWithTheLastRotatedSession() throws Exception {
        redirect("/a", 302, "/b", "JSESSIONID=ONE; Path=/");
        redirect("/b", 302, "/c", "JSESSIONID=TWO; Path=/");
        page("/c", "done");
        start();

        CoverageGuidedRun.request(base, "/a");

        assertEquals(3, seen.size());
        assertNull("hop 0 had no session yet", seen.get(0).cookie());
        assertEquals("JSESSIONID=ONE", seen.get(1).cookie());
        assertEquals("JSESSIONID=TWO", seen.get(2).cookie());
    }

    // ------------------------------------------------------------------------ method and body rules

    /** Spec Verification #8, HEAD half. A grammar can express HEAD; promoting it to GET pulls a body
     *  the caller never asked for, inflating the input's measured latency and heap. Regression guard:
     *  the JDK already preserved HEAD, so this is red only if the rewrite rule is written wrong. */
    @Test
    public void headStaysHeadAcrossAFollow() throws Exception {
        redirect("/h", 302, "/h2", null);
        page("/h2", "");
        start();

        CoverageGuidedRun.request(base, "HEAD /h");

        assertEquals(2, seen.size());
        assertEquals("HEAD", seen.get(1).method());
    }

    /** Spec Verification #8, body half — the riskiest line in the change. 307 exists to preserve the
     *  method AND the body, and 303-after-POST must drop the body AND its Content-Type together
     *  (carrying application/x-www-form-urlencoded onto a bodyless GET makes some filters attempt
     *  form parsing on an empty stream). */
    @Test
    public void a307PreservesMethodAndBodyAndA303AfterPostDropsBoth() throws Exception {
        redirect("/p307", 307, "/sink307", null);
        page("/sink307", "");
        redirect("/p303", 303, "/sink303", null);
        page("/sink303", "");
        start();

        CoverageGuidedRun.request(base, "POST /p307 u=a&p=b");
        assertEquals("POST", seen.get(1).method());
        assertEquals("u=a&p=b", seen.get(1).body());
        assertEquals("application/x-www-form-urlencoded", seen.get(1).contentType());

        seen.clear();
        CoverageGuidedRun.request(base, "POST /p303 u=a&p=b");
        assertEquals("GET", seen.get(1).method());
        assertEquals("", seen.get(1).body());
        assertNull("the Content-Type must be dropped WITH the body, not left behind",
                seen.get(1).contentType());
    }

    // ----------------------------------------------------------------- stamping and reconciliation

    /**
     * THE stamping test. DD-040's acceptance run lost 189 violations because the JDK does not carry a
     * {@code setRequestProperty} value onto a method-rewritten hop, so hop 2 arrived unstamped and
     * published nothing at all. Delete either {@code setRequestProperty} from the loop body and this
     * fails on hop 1.
     */
    @Test
    public void everyHopCarriesTheSameStampedIdAndTheSessionCookie() throws Exception {
        redirect("/login", 302, "/landing", "JSESSIONID=ROTATED; Path=/");
        committed("/landing");
        start();

        CoverageGuidedRun.request(base, "POST /login u=a&p=b");

        assertEquals(2, seen.size());
        assertNull(seen.get(0).cookie());
        assertEquals("JSESSIONID=ROTATED", seen.get(1).cookie());
        assertFalse("hop 0 must be stamped", seen.get(0).reqId() == null);
        assertEquals("hop 1 must carry the SAME id — the store accumulates under one id (§4b), and "
                + "an unstamped hop is a hop whose violation is evaluated, logged, and never counted",
                seen.get(0).reqId(), seen.get(1).reqId());
        assertTrue("ids are run-salted so a foreign or stale id misses honestly",
                seen.get(0).reqId().startsWith(LoadRun.RUN_SALT + "-"));
    }

    /**
     * THE reconciliation test, and the one that would have caught DD-040's failure before an
     * acceptance run. Hop 0 is a 302 that DID report through headers (small, uncommitted). Hop 1 is a
     * COMMITTED 200 with no reporting headers at all — the 97.3% case. The store holds both.
     *
     * <p>It fails, distinguishably, on each of the three ways this can go wrong:
     * <ul>
     *   <li>header save not suppressed → <b>3</b> records (hop 0 filed twice)</li>
     *   <li>{@code fetchResult} drops '\n', or {@code hops > 1} does not force a poll → <b>1</b>
     *       record carrying hop 0's count only, and {@code invariantCount == 2} instead of 5</li>
     *   <li>a second poll path added → {@code pollHits != 1}</li>
     * </ul>
     */
    @Test
    public void aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce() throws Exception {
        server.createContext("/login", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "2");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "latency: 300ms > 250ms");
                ex.getResponseHeaders().add("Location", "/dashboard");
                byte[] out = "<html>Moved</html>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(302, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        committed("/dashboard");
        start();
        // What the target's ResultStore holds after BOTH hops published under the one id.
        pendingBody = "12,340,0|2|latency: 300ms > 250ms|\n900,4096,1|3|latency: 900ms > 250ms|";

        Path dir = Files.createTempDirectory("basquin-dd039-reconcile");
        long measuredBefore = CoverageGuidedRun.reportMeasured;
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "POST /login u=a&p=b");

            assertTrue("the chain was measured", s.measured);
            assertEquals("BOTH hops' violations — this is the 189 that used to be lost",
                    5, s.invariantCount);
            assertEquals("heap summed across hops (spec §4)", 340L + 4096L, s.heapDeltaKb);
            assertEquals("exactly one poll for the whole input, never one per hop", 1, pollHits.get());

            List<String> metas = AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 2);
            assertEquals("one record per breaching hop, and NOT also one from hop 0's header — "
                    + "clearing a flag cannot un-save a file, so the header save had to be deleted "
                    + "from the header-read site", 2, metas.size());
            assertTrue(String.join("\n", metas).contains("hop=0"));
            assertTrue(String.join("\n", metas).contains("hop=1"));
            assertTrue("the committed hop's own detail is what makes the record triageable",
                    String.join("\n", metas).contains("latency: 900ms > 250ms"));
            assertTrue("labelled with the RAW step (DD-036)",
                    String.join("\n", metas).contains("route=POST /login u=a&p=b"));
        });
        assertEquals("one input, one measured request", measuredBefore + 1,
                CoverageGuidedRun.reportMeasured);
    }

    /**
     * The fallback that stops the fix from failing runs it used to pass. A multi-hop chain whose poll
     * MISSES (entry evicted, pod unreachable, DNS unable to answer) must fall back to the header
     * reading rather than record a miss — otherwise, on a redirect-heavy target, forced polling
     * pushes {@code missesAreTheMajority} past its threshold and {@code System.exit(3)} fails a run
     * DD-040 was reporting fine.
     */
    @Test
    public void aMultiHopPollThatMissesFallsBackToTheHeaderRatherThanCountingAMiss() throws Exception {
        redirect("/a", 302, "/b", null);
        server.createContext("/b", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "1");
                ex.getResponseHeaders().add("X-Basquin-Invariant-Detail", "heap: 900KB > 500KB");
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();
        pendingBody = null;                    // the store has nothing: the poll will miss
        long missesBefore = CoverageGuidedRun.reportMisses;

        Path dir = Files.createTempDirectory("basquin-dd039-fallback");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/a");
            assertEquals("the poll ran, because hops > 1", 1, pollHits.get());
            assertTrue("...and its miss must not discard a perfectly good header reading", s.measured);
            assertEquals(1, s.invariantCount);
            assertEquals("the header record is emitted HERE, and only here, because the poll took "
                    + "nothing", 1, AccumulatedPollTest.waitForMetas(dir, "Invariant-Remote", 1).size());
        });
        assertEquals("a measured request must not be counted as a miss",
                missesBefore, CoverageGuidedRun.reportMisses);
    }

    // ------------------------------------------------------------------------- unchanged behaviours

    /** Spec Verification #11. A non-redirect response must behave exactly as it did before. */
    @Test
    public void aNonRedirectResponseIsUnchangedAndIsNotPolledWhenItReportedAHeader() throws Exception {
        server.createContext("/plain", (HttpExchange ex) -> {
            try {
                record(ex);
                ex.getResponseHeaders().add("X-Basquin-Cost", "12,340,0");
                byte[] out = "hello".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "/plain");

        assertEquals(1, seen.size());
        assertTrue(s.measured);
        assertEquals(340L, s.heapDeltaKb);
        assertEquals("a single hop with a cost header must NOT poll — remove-on-read would take the "
                + "store's copy of the same violation", 0, pollHits.get());
    }

    /** Spec Verification #10. The intermediate hop is drained; the FINAL hop's body is not, so a
     *  DD-036 capture still has something to read after a redirect. Drain the final hop by mistake
     *  and this fails with an unresolved correlation ref. */
    @Test
    public void theFinalHopsBodySurvivesTheDrainSoACaptureStillWorks() throws Exception {
        redirect("/form", 302, "/realform", null);
        page("/realform", "<input name=\"X-XSRF-TOKEN\" value=\"tok999\">");
        final String[] posted = new String[1];
        server.createContext("/save", (HttpExchange ex) -> {
            try {
                posted[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, 0);
                ex.getResponseBody().close();
            } catch (Throwable t) { handlerFailure.compareAndSet(null, t); throw t; }
        });
        start();

        CoverageGuidedRun.runSequence(base, List.of(
                "/form <<csrf=input:X-XSRF-TOKEN",
                "POST /save X-XSRF-TOKEN=${{csrf}}"));

        assertEquals("the capture ran against the FINAL hop's body, per spec §5",
                "X-XSRF-TOKEN=tok999", posted[0]);
    }

    /** An unsupported method is not sent, and a request that never went out is not a miss — the
     *  pre-DD-039 early return sat before the try/finally and counted nothing. */
    @Test
    public void anUnsupportedMethodIsNotSentAndIsNotCountedAsAMiss() throws Exception {
        start();
        long missesBefore = CoverageGuidedRun.reportMisses;
        CoverageGuidedRun.CostSample s = CoverageGuidedRun.request(base, "TRACE /x");
        assertFalse(s.measured);
        assertEquals(0, seen.size());
        assertEquals(0, pollHits.get());
        assertEquals(missesBefore, CoverageGuidedRun.reportMisses);
    }
}
```

> Two helpers from Task 3's test are reused rather than copied: promote `withResultsDir` and
> `waitForMetas` in `AccumulatedPollTest` to package-private `static`, and promote its `Body`
> functional interface likewise, so `ExploreRedirectTest` (this task and Task 5) can drive a server
> body through the same helper. **Both Task 4 and Task 5 therefore MODIFY
> `test/runner/coverage/AccumulatedPollTest.java` — add it to their Files blocks.** `ExploreRedirectTest`
> will not compile until that promotion is made
> (`static void withResultsDirFor(Path, Body)`, `static List<String> waitForMetas(...)`) as part of
> this step. Both are test-only; no production code is added for testing.

- [ ] **Step 2 — run; expect red, not a compile error, on four tests.**
      `./gradlew test --tests 'runner.coverage.ExploreRedirectTest'`
      Expected before the implementation: `resetSession()` is `private` → **compile error** first;
      once widened, the genuinely red tests are `aSessionRotatedOnThe302ReachesTheNextHop`,
      `aPreExistingCookieIsCarriedOntoAHopThatSetsNoCookie`,
      `aThreeHopChainEndsWithTheLastRotatedSession`,
      `everyHopCarriesTheSameStampedIdAndTheSessionCookie`,
      `aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce`,
      `aMultiHopPollThatMissesFallsBackToTheHeaderRatherThanCountingAMiss` and
      `theFinalHopsBodySurvivesTheDrainSoACaptureStillWorks`.
      **Regression guards that pass before and after:** `headStaysHeadAcrossAFollow` (the JDK already
      preserves HEAD across a 302), `a307PreservesMethodAndBodyAndA303AfterPostDropsBoth` (the JDK
      already does this too — it is red only if the rewrite rule is written wrong),
      `aNonRedirectResponseIsUnchangedAndIsNotPolledWhenItReportedAHeader`, and
      `anUnsupportedMethodIsNotSentAndIsNotCountedAsAMiss`.

- [ ] **Step 3a — widen `resetSession` (`:500`) and add the loop's helpers**, next to the Task 1
      helpers:

```java
    /** Package-private (was private): the suite shares one JVM and {@code sessionCookie} is a static
     *  field, so a redirect test that seeds a session would otherwise leak it into every later test
     *  in the JVM, including ExploreCorrelationTest. Nothing in production calls this from outside. */
    static void resetSession() { sessionCookie = null; }

    /**
     * Spec §2: at most FIVE REQUESTS in a chain — hop 0 plus at most four follows. {@code hops}
     * counts requests ISSUED, so the guard is {@code hops >= MAX_HOPS} AFTER the increment: five
     * requests, not six. The same number is {@code ResultStore.MAX_HOPS_PER_ID}; two caps that
     * disagree would silently drop a hop, and a silently dropped hop is a lost violation.
     */
    static final int MAX_HOPS = 5;

    /**
     * Read this hop's response to EOF and discard it, so the connection returns to the keep-alive
     * pool. Tomcat's {@code sendRedirect} emits a boilerplate HTML body; abandoning it unread leaks
     * sockets at explore's iteration rate and silently inflates measured latency, which is a
     * cost-model input. {@code LoadRun} drains for exactly this reason.
     *
     * <p>{@code java.io.IOException} is fully qualified: CoverageGuidedRun does not import it
     * ({@code :9-18} imports BufferedReader, InputStream, InputStreamReader and nothing else from
     * java.io).
     */
    private static void drain(HttpURLConnection c) {
        try {
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return;
            byte[] buf = new byte[4096];
            try (InputStream in = is) {
                while (in.read(buf) >= 0) { /* discard */ }
            }
        } catch (java.io.IOException ignored) {
            // A hop we are abandoning anyway; failing to drain it must never fail the request.
        }
    }

    /**
     * Capture/refresh JSESSIONID from THIS hop — the indexed walk lifted verbatim from the
     * pre-DD-039 {@code :741-749}. Runs on EVERY hop, deliberately: the session cookie is a
     * transport concern, not a document concern (spec §5), and Spring Security issues the rotated
     * JSESSIONID ON the 302, whose headers the JDK's auto-follow discarded before re-issuing.
     */
    private static void captureSessionCookieFrom(HttpURLConnection c) {
        for (int i = 0; ; i++) {
            String key = c.getHeaderFieldKey(i);
            String val = c.getHeaderField(i);
            if (key == null && val == null) break;
            if (key != null && key.equalsIgnoreCase("Set-Cookie") && val != null
                    && val.startsWith("JSESSIONID=")) {
                sessionCookie = val.split(";", 2)[0];
            }
        }
    }

    /** The header-derived {@code Invariant-Remote} records, one per breaching hop. Called from
     *  {@link #reconcile} and NOWHERE else — see that method's javadoc for why the distinction is
     *  the bug fix rather than a tidy-up. Each element is {@code {hop, rawCount, detail, podMeta}}. */
    private static void saveHeaderInvariants(String label, java.util.List<String[]> breaches) {
        for (String[] b : breaches) {
            FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                    "route=" + label + "\ncount=" + b[1] + "\nhop=" + b[0]
                            + (b[2] != null ? "\ndetail=" + b[2] : "") + b[3]);
        }
    }

    /**
     * DD-039: the ONE place that decides what a request reports, and the only place that saves.
     *
     * <p><b>Why the extraction IS the fix.</b> DD-040 saved the header-derived
     * {@code Invariant-Remote} record the instant it read {@code X-Basquin-Invariant-Count}
     * ({@code :761-763}). A later decision to poll instead therefore could not un-save it — the
     * record was already on disk, and the same violation was filed twice AND counted twice into
     * {@code CostSample.invariantCount}, which feeds {@link CostModel#score}. Clearing a
     * {@code headerReported} flag does not unlink a file. Spec §4b's claim that one id plus one
     * {@code take} removes the double-count question is true of DD-040's code, where the two channels
     * are mutually exclusive by construction; it is not a property of the store's arity.
     *
     * <p>The rule:
     * <ul>
     *   <li>{@code hops == 1} with a reporting header — the header describes the one hop that
     *       happened, so it is complete. Save from it and do NOT poll: remove-on-read would take the
     *       store's copy of the same violation.</li>
     *   <li>{@code hops > 1} — a header can only ever describe the FINAL hop, so poll for all of
     *       them. <b>The poll wins when it is well-formed; the header record is emitted only when the
     *       poll returned nothing at all.</b> That keeps "never both, for the same hop" true without
     *       needing to know which hops the poll happened to recover.</li>
     *   <li>Poll returned nothing and there was no header either — a miss, exactly as DD-040.</li>
     * </ul>
     *
     * <p>The fall-back matters as much as the suppression. A forced poll that misses (entry evicted
     * at {@code ResultStore.CAPACITY}, pod unreachable, {@code PodPollTargets.pollBases} empty
     * because pod addressing was demanded and DNS could not answer) would otherwise discard a
     * perfectly good final-hop header reading and record a miss — and on a redirect-heavy target push
     * {@link #missesAreTheMajority} past its threshold and {@code System.exit(3)} a run DD-040 was
     * reporting fine.
     */
    static CostSample reconcile(String base, String reqId, String label, int hops,
            boolean headerReported, CostSample headerSample, java.util.List<String[]> hdrBreaches) {
        // Never sent (an unsupported method): nothing to poll, nothing to count. The pre-DD-039 early
        // return sat before the try/finally and counted nothing, and that is preserved exactly.
        if (hops == 0) return CostSample.UNMEASURED;
        if (headerReported && hops == 1) {
            saveHeaderInvariants(label, hdrBreaches);
            reportMeasured++;
            return headerSample;
        }
        CostSample polled = pollResultOrNull(base, reqId, label, hops);
        if (polled != null) return polled;                 // reportMeasured++ happened inside
        if (headerReported) {
            // The poll found nothing, so it took nothing and saved nothing: emitting the header
            // record here cannot double-count.
            saveHeaderInvariants(label, hdrBreaches);
            reportMeasured++;
            return headerSample;
        }
        return recordMiss();
    }
```

- [ ] **Step 3b — rewrite the 4-arg `request` (`:697-819`).** Keep its javadoc `:681-696` verbatim
      (it carries the DD-036 label contract) and add the DD-039 paragraph. The body:

```java
    private static CostSample request(String base, RequestLine r, java.util.Map<String, String> bindings,
            String label) throws Exception {
        // DD-039: explore follows redirects ITSELF. setInstanceFollowRedirects(true) made the JDK
        // discard the intermediate hop's response headers before re-issuing (so a Set-Cookie on the
        // 3xx was unreachable and the hop's X-Basquin-* were thrown away) and issue the follow hop
        // with NO Cookie and NO X-Basquin-Req at all on a method rewrite. See the spec's JDK probe.
        String method = r.method();
        String reqBody = r.body();          // NOT `body`: StringBuilder body is declared below (:780)
        String url = base + r.path();
        String reqId = LoadRun.RUN_SALT + "-" + REQ_SEQ.getAndIncrement();
        java.util.LinkedHashSet<String> visited = new java.util.LinkedHashSet<>();
        HttpURLConnection c = null;         // hoisted: the post-loop body read / capture / serverError
        int code = -1;                      // block below needs both
        int hops = 0;
        boolean headerReported = false;
        long heapKb = 0; int threadDelta = 0; int invCount = 0;
        java.util.List<String[]> hdrBreaches = new ArrayList<>();
        CostSample sample = CostSample.UNMEASURED;
        try {
            while (true) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(10000);
                try {
                    c.setRequestMethod(method);
                } catch (java.net.ProtocolException pe) {
                    System.err.println("[Basquin] explore: unsupported HTTP method " + method
                            + " for " + r.path() + "; not sent");
                    break;                  // hops is still 0; reconcile treats that as "never sent"
                }
                c.setInstanceFollowRedirects(false);
                // BOTH headers on EVERY hop: the JDK carries NEITHER onto a method-rewritten hop
                // (spec Evidence TEST A — hop 2 arrived with cookie=null). That single fact is
                // DD-039's motivating defect and DD-040's 189-violation residual.
                c.setRequestProperty("X-Basquin-Req", reqId);
                if (sessionCookie != null) c.setRequestProperty("Cookie", sessionCookie);
                if (reqBody != null) {
                    byte[] bodyBytes = reqBody.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (java.io.OutputStream out = c.getOutputStream()) { out.write(bodyBytes); }
                }
                code = c.getResponseCode();
                hops++;
                // Stripped AT CONSTRUCTION (DD-036): hop 0's URL comes from the SUBSTITUTED
                // RequestLine (runSequence substitutes the path at :518), so an unstripped key would
                // put a live captured token into `visited` — which a Redirect-Loop finding writes out.
                visited.add(strippedUrl(url));
                captureSessionCookieFrom(c);            // every hop: transport, not document (spec §5)

                // Read this hop's reporting headers. NOTHING IS SAVED HERE — reconcile() owns every
                // save, because a save that has already fired cannot be undone by clearing a flag.
                String podHdr = c.getHeaderField("X-Basquin-Pod");
                String podMeta = podHdr == null ? "" : "\npod=" + podHdr;
                String inv = c.getHeaderField("X-Basquin-Invariant-Count");
                if (inv != null) {
                    headerReported = true;
                    try { invCount += Integer.parseInt(inv.trim()); }
                    catch (NumberFormatException ignored) { }
                    hdrBreaches.add(new String[]{String.valueOf(hops - 1), inv,
                            c.getHeaderField("X-Basquin-Invariant-Detail"), podMeta});
                }
                // The fast-path discriminator is the COST header, which the boundary emits on EVERY
                // explore exit — not the invariant header, which is absent on every clean request and
                // would make the driver poll almost always (DD-040 §A.5).
                String costHdr = c.getHeaderField("X-Basquin-Cost");   // "latencyMs,heapDeltaKb,threadDelta"
                if (costHdr != null) {
                    headerReported = true;
                    String[] p = costHdr.split(",");
                    if (p.length == 3) {
                        try {
                            heapKb += Long.parseLong(p[1].trim());     // summed across hops (spec §4)
                            threadDelta += Integer.parseInt(p[2].trim());
                        } catch (NumberFormatException ignored) { }
                    }
                }

                // ---- terminal conditions. EVERY ONE breaks BEFORE the drain, so the final hop's
                // ---- body survives for the capture step and the post-loop read.
                if (!isRedirect(code)) break;                       // the ordinary terminal response
                if (hops >= MAX_HOPS) break;                        // Task 5 files the finding here
                java.net.URI next = resolveLocation(url, c.getHeaderField("Location"));
                if (next == null) break;                            // absent/unparseable: the 3xx is final
                java.net.URI here = safeUri(url);                    // NEVER URI.create: substituted path
                if (here == null || !sameOrigin(here, next)) break;  // Task 5 counts the refusal here
                String nextUrl = strippedUrl(next.toString());
                if (visited.contains(nextUrl)) break;               // Task 5 files the finding here

                drain(c);                                            // only now is this hop discardable
                String nextMethod = followMethod(code, method);
                if (!nextMethod.equals(method)) reqBody = null;      // body AND Content-Type together
                method = nextMethod;
                url = next.toString();                               // the FULL url is dialled; only
                                                                     // the recorded key is stripped
            }
            if (headerReported) sample = new CostSample(heapKb, threadDelta, invCount, true);
            if (hops > 0) {
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder body = new StringBuilder();
                // ... UNCHANGED from :780-808: the capped body read, the capture loop, and the
                // ... `if (code >= 500) throw serverError(code, label, body.toString());`
            }
        } finally {
            sample = reconcile(base, reqId, label, hops, headerReported, sample, hdrBreaches);
        }
        return sample;
    }
```

  Note the two deliberate details: `url = next.toString()` (the full URL, query included, is what we
  **dial**) while `visited` and any recorded URL use `strippedUrl` (what we **record**); and the
  `if (hops > 0)` guard around the post-loop read, since `c` is null when the method was rejected.

- [ ] **Step 4 — `./gradlew test`** → fully green, including `ExploreCorrelationTest`,
      `ExploreRequestTest`, `ReportChannelTest`, `MultiReplicaPollTest`, `AccumulatedPollTest`,
      `RedirectPolicyTest`.
- [ ] **Step 5 — commit.** `feat(explore): follow redirects manually, and reconcile reporting once (DD-039 Task 4)`

**Mechanism-removal check:** delete `c.setRequestProperty("X-Basquin-Req", reqId)` from the loop and
`everyHopCarriesTheSameStampedIdAndTheSessionCookie` fails on hop 1 (`null`). Delete
`if (sessionCookie != null) c.setRequestProperty("Cookie", ...)` and both cookie tests fail. Move
`saveHeaderInvariants` back to the header-read site and
`aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce` fails `expected:<2> but was:<3>`. Change
`reconcile`'s `hops == 1` to an unconditional header fast path and the same test fails
`expected:<5> but was:<2>` with 1 record. Remove the header fall-back and
`aMultiHopPollThatMissesFallsBackToTheHeaderRatherThanCountingAMiss` fails on `s.measured` and on
`reportMisses`.

---

## Task 5: Redirect policy — cross-origin, unparseable `Location`, loop detection, the finding

**Scope kept verbatim from the previous plan's Task 4, which round 3 verified as "all accurate and
all needed", plus the `Redirect-Loop` finding it was missing a home for.**

**Files:**
- modify: `runner/coverage/CoverageGuidedRun.java`
- modify: `test/runner/coverage/ExploreRedirectTest.java` (extend)

**Interfaces produced:** `static volatile long crossOriginRedirects;`,
`private static void saveRedirectLoop(String label, java.util.Collection<String> visited)`.
**Interfaces consumed:** everything Task 4 produced. `MAX_HOPS`, `drain` and
`captureSessionCookieFrom` are **already defined in Task 4 — do not redeclare them.**

- [ ] **Cross-origin refusal, a `crossOriginRedirects` counter, and a once-only warn naming the
      refused origin.** **Surface the counter in the end-of-run summary** — Tasks 7 and 8 tell an
      operator to check it, so it must actually be printed. A target that renders redirects from a
      *configured* absolute base URL makes **every** redirect cross-origin, silently degrading this
      feature to its pre-DD-039 behaviour — indistinguishable from the feature working. A non-zero
      counter beside flat coverage is what makes that diagnosable. (Checked: the motivating app is
      safe — `deploy/bench/roller/setup.sh:116` seeds `site.absoluteurl` empty.)
- [ ] **An unparseable `Location` ends the chain and must NEVER throw** — already true via
      `resolveLocation` (Task 1) and the `next == null` break (Task 4). This task adds the test.
- [ ] **Revisited-URL detection and the hop cap each file a `Redirect-Loop` finding**, carrying the
      **raw step label** (DD-036) and the ordered hops. An app that redirects indefinitely is
      unavailable — a browser surfaces `ERR_TOO_MANY_REDIRECTS` — and this tool's oracle is
      availability. Silently scoring the 5th response as a normal iteration discards a real finding
      and feeds a meaningless response into the cost model and the corpus.
- [ ] **The two terminal conditions stay SPLIT.** `if (!isRedirect(code)) break;` must remain a
      separate statement from `if (hops >= MAX_HOPS) { ... }`. Fusing them into
      `if (!isRedirect(code) || hops >= MAX_HOPS) break;` and hanging `saveRedirectLoop` off the
      fused branch makes **every ordinary non-redirect response** file a `Redirect-Loop` finding.
- [ ] **The recorded hops are already stripped** (`visited` holds `strippedUrl` values, applied at
      construction in Task 4). Do not strip again, and do not record `url` directly.

- [ ] **Step 1 — tests** (append to `ExploreRedirectTest`):

```java
    /** Spec Verification #6. A fuzzed input inducing an open redirect must not turn the explorer
     *  into a client for another server, or attribute another host's cost to the app. */
    @Test
    public void aCrossOriginLocationIsNotFollowedAndIsCounted() throws Exception {
        redirect("/out", 302, "http://evil.example.invalid/steal", null);
        start();
        long before = CoverageGuidedRun.crossOriginRedirects;

        CoverageGuidedRun.request(base, "/out");

        assertEquals("only hop 0 was issued", 1, seen.size());
        assertEquals("the refusal must be COUNTED — a configured absolute base URL makes every "
                + "redirect cross-origin and degrades DD-039 to its pre-DD-039 behaviour, and a "
                + "non-zero counter beside flat coverage is the only thing that makes that visible",
                before + 1, CoverageGuidedRun.crossOriginRedirects);
    }

    /** Spec Verification #7. A relative Location resolves, and http://svc/x matches http://svc:80. */
    @Test
    public void aRelativeLocationResolvesAndIsFollowed() throws Exception {
        redirect("/dir/one", 302, "two", null);
        page("/dir/two", "ok");
        start();

        CoverageGuidedRun.request(base, "/dir/one");

        assertEquals(2, seen.size());
        assertEquals("/dir/two", seen.get(1).path());
    }

    /** Spec Verification #5. URI throws on unencoded space, {}, | and ^ — the bytes a fuzzer
     *  reflects. request(...) is `throws Exception` and its caller files a CRASH finding against the
     *  app on any Throwable, so an unguarded parse would blame the app for a driver bug. */
    @Test
    public void anUnparseableLocationEndsTheChainWithoutFilingACrash() throws Exception {
        redirect("/bad", 302, "/next?q=a b|c^{}", null);
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-badloc");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/bad");     // must not throw
            assertEquals("the 3xx becomes the final response", 1, seen.size());
            assertEquals("no crash finding may be filed against the app",
                    0, AccumulatedPollTest.readMetasFor(dir, "Crash").size());
        });
    }

    /** Spec Verification #4, revisit half. /protected -> /login -> /protected is a real loop that
     *  never reaches five hops, which is why the cap alone is a poor detector. */
    @Test
    public void aRevisitedUrlEndsTheChainAndFilesARedirectLoopFinding() throws Exception {
        redirect("/protected", 302, "/login", null);
        redirect("/login", 302, "/protected", null);
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-loop");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/protected");

            // The loop detects a revisited URL BEFORE re-issuing it (look-ahead), so a self-loop
            // is exactly 2 requests: the original and the one hop that reveals the repeat. "A
            // revisited URL ends the chain" (spec §2) does not require wastefully re-fetching it.
            assertEquals("stopped at the revisit (look-ahead), not at the cap", 2, seen.size());
            List<String> metas = AccumulatedPollTest.waitForMetas(dir, "Redirect-Loop", 1);
            assertEquals(1, metas.size());
            assertTrue("labelled with the RAW step, never a resolved hop URL (DD-036)",
                    metas.get(0).contains("route=/protected"));
            assertTrue("the ordered hops are the evidence", metas.get(0).contains("/login"));
        });
    }

    /** Spec Verification #4, cap half. Five REQUESTS total, not six. */
    @Test
    public void theHopCapStopsAtFiveRequestsAndFilesTheFinding() throws Exception {
        for (int i = 0; i < 9; i++) redirect("/n" + i, 302, "/n" + (i + 1), null);
        page("/n9", "end");
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-cap");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/n0");
            assertEquals("MAX_HOPS counts REQUESTS: five, not 1 + 5", 5, seen.size());
            assertEquals(1, AccumulatedPollTest.waitForMetas(dir, "Redirect-Loop", 1).size());
        });
    }

    /** An ordinary non-redirect response must NOT file a Redirect-Loop finding — the failure mode of
     *  fusing the two terminal conditions into one branch. */
    @Test
    public void anOrdinaryResponseFilesNoRedirectLoopFinding() throws Exception {
        page("/plainer", "hello");
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-noloop");
        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.request(base, "/plainer");
            Thread.sleep(200);
            assertEquals("fusing !isRedirect with the cap check makes EVERY response a loop finding",
                    0, AccumulatedPollTest.readMetasFor(dir, "Redirect-Loop").size());
        });
    }

    /** DD-036, the half the existing guard test cannot see: it puts its token in the BODY. Hop 0's
     *  URL is built from the SUBSTITUTED path, so an unstripped hop URL writes a live token to disk. */
    @Test
    public void aTokenSubstitutedIntoThePathNeverReachesDisk() throws Exception {
        page("/edit", "<input name=\"X-XSRF-TOKEN\" value=\"SECRET-PATH-TOKEN-42\">");
        redirect("/save", 302, "/save", null);      // an immediate self-loop: forces the loop finding
        start();
        Path dir = Files.createTempDirectory("basquin-dd039-pathtoken");

        AccumulatedPollTest.withResultsDirFor(dir, () -> {
            CoverageGuidedRun.runSequence(base, List.of(
                    "/edit <<csrf=input:X-XSRF-TOKEN",
                    "/save?csrf=${{csrf}}"));

            String all = String.join("\n", AccumulatedPollTest.readMetasFor(dir, "Redirect-Loop"));
            assertFalse("a token substituted into the PATH must never reach a hop URL on disk: " + all,
                    all.contains("SECRET-PATH-TOKEN-42"));
            assertTrue("the safe recipe is what is recorded", all.contains("${{csrf}}"));
        });
    }
```

  (`readMetasFor` is `readMetas` from `AccumulatedPollTest`, promoted to package-private `static` in
  this step alongside the two already promoted in Task 4.)

- [ ] **Step 2 — run; expect red on all seven** except `anOrdinaryResponseFilesNoRedirectLoopFinding`
      (a regression guard that passes before and after — it exists to catch the fused-branch mistake,
      which is a mistake this task could introduce).
      `./gradlew test --tests 'runner.coverage.ExploreRedirectTest'` → `cannot find symbol:
      crossOriginRedirects`, then failures on the loop findings.

- [ ] **Step 3 — implement.** Add beside `reportMisses` (`:593`):

```java
    /**
     * DD-039: {@code Location}s refused because they left the target's origin. Surfaced in the
     * end-of-run summary because a target that renders redirects from a CONFIGURED absolute base URL
     * makes every redirect cross-origin, silently degrading this feature to its pre-DD-039 behaviour
     * — indistinguishable from the feature working. A non-zero counter beside flat coverage is
     * immediately diagnosable; nothing else in the run says so.
     */
    static volatile long crossOriginRedirects;
```

  Replace the two terminal breaks in the loop (Task 4's `sameOrigin` and `visited` branches, plus the
  cap) with:

```java
                if (hops >= MAX_HOPS) { saveRedirectLoop(label, visited); break; }
                java.net.URI next = resolveLocation(url, c.getHeaderField("Location"));
                if (next == null) break;
                java.net.URI here = safeUri(url);
                if (here == null || !sameOrigin(here, next)) {
                    crossOriginRedirects++;
                    warnOnce("cross-origin", "refusing a cross-origin redirect to "
                            + next.getScheme() + "://" + next.getHost()
                            + (next.getPort() > 0 ? ":" + next.getPort() : "")
                            + " — if EVERY redirect is refused, the target is rendering redirects "
                            + "from a configured absolute base URL and DD-039 is degraded to its "
                            + "pre-DD-039 behaviour");
                    break;
                }
                String nextUrl = strippedUrl(next.toString());
                if (visited.contains(nextUrl)) { saveRedirectLoop(label, visited); break; }
```

  and add:

```java
    /**
     * A redirect loop is a FINDING, not a nuisance: an app that redirects indefinitely is
     * unavailable — a browser surfaces it as ERR_TOO_MANY_REDIRECTS — and this tool's oracle is
     * availability. Scoring the 5th response as a normal iteration would discard a genuine finding
     * and feed a meaningless response into the cost model and the corpus.
     *
     * <p>{@code label} is the RAW step recipe (DD-036), never a resolved hop URL. {@code visited}
     * already holds query-stripped URLs — they are stripped where they are CONSTRUCTED, because
     * hop 0's URL comes from the substituted RequestLine and would otherwise carry a live token.
     */
    private static void saveRedirectLoop(String label, java.util.Collection<String> visited) {
        FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Redirect-Loop",
                "route=" + label + "\nhops=" + String.join(" -> ", visited));
    }
```

  and surface the counter next to the existing summary printf (`:341-345`):

```java
        System.out.printf("CoverageGuidedRun done: corpus=%d coverage=%d/%d pheromone=%s seed=%d%n",
                corpus.size(), best, total, pheromoneOn ? "on" : "off", seed);
        if (crossOriginRedirects > 0) {
            System.out.println("[Basquin] explore: " + crossOriginRedirects + " cross-origin redirect(s)"
                    + " refused. If this is close to the iteration count, the target renders redirects"
                    + " from a configured absolute base URL and DD-039 is not actually following"
                    + " anything — check the app's site-URL setting before trusting the coverage.");
        }
```

- [ ] **Step 4 — `./gradlew test`** → fully green.
- [ ] **Step 5 — commit.** `feat(explore): redirect policy — cross-origin, loop detection, findings (DD-039 Task 5)`

**Mechanism-removal check:** delete `crossOriginRedirects++` and
`aCrossOriginLocationIsNotFollowedAndIsCounted` fails on the counter while still passing on the hop
count — which is the point: the refusal without the counter is the silent-degradation case. Fuse the
two terminal conditions and `anOrdinaryResponseFilesNoRedirectLoopFinding` fails with 1. Remove
`strippedUrl` from the `visited.add` in Task 4 and `aTokenSubstitutedIntoThePathNeverReachesDisk`
fails carrying `SECRET-PATH-TOKEN-42`.

---

## Task 6: Residual accounting, stale comments, and the record

**Files:**
- modify: `runner/coverage/CoverageGuidedRun.java`, `runner/coverage/CorpusEntry.java`,
  `runner/coverage/CostCorpus.java`, `runner/coverage/PodPollTargets.java`
- create: `test/runner/coverage/HopAccountingTest.java`
- modify: `docs/DESIGN-DECISIONS.md`, `docs/how-it-works.html`, `runner/CHANGELOG.md`,
  `deploy/bench/ONBOARDING.md`

- [ ] **Step 1 — `latMs` must not include the forced poll.** `latMs` is client wall-clock measured in
      the caller around `request()` (`:299-302`) and `reconcile` runs inside `request()`'s own
      `finally`, so making `hops > 1` always poll adds up to `POLL_READ_TIMEOUT_MS` (4000 ms default,
      `:609`) per pod base **inside that window, on every redirecting input and no others**. `latMs`
      feeds `CostModel.score` at `:307`, so redirecting inputs would systematically out-rank
      non-redirecting ones in the corpus — the exact ranking distortion DD-040 set out to repair.

```java
    /** DD-039: milliseconds the last {@code request()} call spent inside its result poll. Subtracted
     *  from the caller's wall-clock, because a forced multi-hop poll would otherwise inflate latMs —
     *  which feeds CostModel.score, so redirecting inputs would out-rank everything else purely for
     *  being polled. Single-threaded explore loop, like the other run counters. */
    static volatile long lastPollMs;

    /** Wire time only. Pure, so the subtraction is assertable without a run. */
    static long wireLatency(long elapsedMs, long pollMs) {
        return Math.max(0L, elapsedMs - pollMs);
    }
```
  `lastPollMs = 0;` at the top of the 4-arg `request`; `lastPollMs += (System.nanoTime() - p0) /
  1_000_000L;` around the fetch loop in `pollResultOrNull`; and at `:302`:
  `latMs = wireLatency((System.nanoTime() - t0) / 1_000_000L, lastPollMs);`
  Tests: `wireLatency` is pure (including the `Math.max(0, ...)` floor); `lastPollMs > 0` after a
  request that polled and `== 0` after one that took the header fast path.

- [ ] **Step 2 — the corpus entry records its hop count** (spec Consequences, previously unimplemented
      and not waived). `CostSample` gains `final int hops` with a 4-arg constructor delegating
      `hops = 1`, so every existing construction site compiles unchanged. `CorpusEntry` gains
      `public final int hops` with the existing 7-arg constructor delegating `hops = 1`, so
      `CostCorpus.java:47` and all ~20 test call sites compile unchanged. `CostCorpus.consider` gains
      an 8-arg overload taking `hops`; the 7-arg one delegates with 1. `CoverageGuidedRun`'s loop
      passes `sample.hops`, and the cost-ranked line at `:349-353` appends `(h=N)` when `N > 1`.
      **This is inert unless the driver actually threads its real hop count into the sample it
      returns** — every existing construction site builds the 4-arg form (`hops = 1`), so without
      this step `sample.hops` is always 1 and `(h=N)` never prints. Construct BOTH the header sample
      and the poll sample (`request`'s final-hop `CostSample` and `pollResultOrNull`'s) with the
      5-arg constructor, passing `request`'s own `hops` local. A test must assert a 3-hop input
      records `hops=3` on its `CorpusEntry`, or this whole step is a silent no-op — which is exactly
      the spec Consequence it claims to satisfy going unimplemented.
      **Why it matters, in the record:** the replay corpus is explore's output and load's input
      (DD-035), and load fires exactly one hop (DD-038, deliberately). A login POST ranked expensive
      on the strength of its dashboard hop is, under load, a cheap 302 — so the number must be
      readable as an explore-side measurement.

- [ ] **Step 3 — correct the in-tree comments that assert the opposite of what shipped.** Tasks 3
      and 4 rewrite two of the three the earlier draft listed, so by this task only one survives:
      **verify each target still exists before editing it** rather than assuming the pre-DD-040
      text. Each
      is load-bearing prose that a future reader would trust:
      - `CoverageGuidedRun.java:714-716` — "a followed hop re-sends this same id and the target's
        second put overwrites the first: FINAL-HOP-WINS". Now false: the store accumulates. Replace
        with the §4b statement.
      - `CoverageGuidedRun.java:849-853` — "DD-039's per-hop ids remove the case". DD-039 ships ONE
        id and an all-pods merge instead. Replace with amendment A4b-3's rule.
      - `PodPollTargets.java:32-40` — "DD-039 dissolves both by replacing the JDK's auto-follow with
        an explicit hop loop that stamps every hop with its own id." Same correction; state that the
        multi-hop case is handled by asking every base and merging, and that
        `MultiReplicaPollTest.theFanOutFindsTheEntryBehindAPodThatMisses` still pins break-at-first
        for the single-hop path.

- [ ] **Step 4 — the DD-039 record in `docs/DESIGN-DECISIONS.md`:** the three defects (unreachable
      `Set-Cookie` on the 3xx; the `Cookie` request header dropped on rewritten hops, so landing pages
      rendered anonymously; discarded intermediate-hop invariant counts), the JDK probe evidence, the
      §4b accumulate design and why per-hop ids were rejected, **the four §4b amendments this plan
      required and why**, the explore/load cost divergence, and the `latMs` poll-exclusion.
- [ ] **Step 5 — `docs/how-it-works.html`, `runner/CHANGELOG.md` `[Unreleased]`, and an
      `ONBOARDING.md` note on the cross-origin trap** (a configured absolute base URL degrades DD-039
      silently; check `crossOriginRedirects` in the summary).
- [ ] **Step 6 — `./gradlew test`** green; commit.
      `feat(explore): hop-count accounting, poll-excluded latency, and the DD-039 record (Task 6)`

---

## Task 7: Prove it — close DD-040's gap

**Kept verbatim: round 3 called this "the strongest part of the plan" and it should survive
unchanged. DD-040's Task 7 proved its worth by stopping exactly this plan's premise.**

- [ ] Rebuild and load the agent + runner images; point the controller at them.
- [ ] **State the replica count of the target** before running. Per spec amendment A4b-4, `hop=<n>`
      is exact on a single-replica target and a position-in-recovered-sequence on a multi-replica
      one. Run single-replica, or record that it was not and treat the hop labels accordingly.
- [ ] **THE acceptance test.** A Roller explore campaign's reported violation count against the
      `[Basquin][Invariant]` count in the target pod's log for the same window. **DD-040 measured
      1,413 vs 1,602 — a gap of 189 (11.8%).** That gap must substantially close. Account for kubelet
      probe noise explicitly (~12/min heapDelta at idle) rather than ignoring it.
- [ ] **Assert on database rows, not coverage:** query `roller_entry` for rows written by
      `login_publish`, which has never written one. Coverage can rise for unrelated reasons.
- [ ] Confirm `crossOriginRedirects` is 0 for this target, `reportMisses` stays low, and
      `ResultStore.overflowedHops()` is 0 (a non-zero value means chains exceeded the cap and hops
      were dropped, which would reopen the gap from the other end).
- [ ] **Commit the evidence under `bench-results/`** — a figure without an artifact is the thing this
      project keeps getting wrong. Reuse `deploy/bench/collect.py`'s `_redact` (`:92`).
- [ ] **If the counts still disagree materially, or no authenticated write lands, STOP and report.**

---

## Task 8: Wire the drift guards into CI

Content-wise a genuine gap and worth doing; **scope-wise it is unrelated to session carry**, so it is
last, it is its own commit, and it is separable into a `chore/ci-drift-guards` PR without touching
anything above. The repo convention is one cohesive feature per PR.

**Files:** `.github/workflows/ci.yml`, `deploy/bench/test_redact.py`

- [ ] Add a step to the `build-and-test` job running both guards. They are pure-Python and need no
      JDK, so they belong before the Gradle steps:

```yaml
      - name: Bench artifact drift guards
        run: |
          python3 deploy/bench/check_claims.py
          python3 deploy/bench/test_redact.py
```
      Note that `ci.yml`'s `on.push`/`on.pull_request` `paths:` filters do **not** currently include
      `deploy/**`, so a change to these scripts alone would not trigger CI. Add `deploy/bench/**` to
      both filter lists, or the guard is wired in but unreachable by the changes it guards.
- [ ] Add `X-Basquin-Token` to `deploy/bench/test_redact.py`'s `MUST_REDACT` (`:21-27`). Verified:
      `collect.py:82-87` already matches it in `_RE_HEADER`, so this pins existing behaviour that
      nothing currently asserts:

```python
    ("X-Basquin-Token: e4e86b722d0f4ecd", "the dashboard token as a header — collect.py redacts it, "
                                          "and nothing pinned that it must"),
```
- [ ] **Verify locally, not by pushing a deliberate failure to shared CI.** Run
      `python3 deploy/bench/test_redact.py` (expect a pass), then temporarily narrow `_RE_HEADER` in
      a scratch copy to confirm the new case genuinely fails, then discard the scratch copy. Do the
      same for `check_claims.py` by editing a doc figure in the working tree, running it, confirming
      non-zero exit, and reverting.
- [ ] Commit. `ci: run the bench artifact drift guards, and pin X-Basquin-Token redaction`

---

## Coverage of the spec's Verification list

| spec item | where |
|---|---|
| 1. rotated JSESSIONID on the 302 reaches the next hop | Task 4, `aSessionRotatedOnThe302ReachesTheNextHop` |
| 2. a 302 setting NO cookie still carries the pre-existing one | Task 4, `aPreExistingCookieIsCarriedOntoAHopThatSetsNoCookie` |
| 3. three-hop chain, each rotating, ends with the last | Task 4, `aThreeHopChainEndsWithTheLastRotatedSession` |
| 4. redirect loop → finding, both revisit and 5-hop cap | Task 5, `aRevisitedUrlEndsTheChainAndFilesARedirectLoopFinding` + `theHopCapStopsAtFiveRequestsAndFilesTheFinding` |
| 5. malformed Location, no crash finding | Task 5, `anUnparseableLocationEndsTheChainWithoutFilingACrash` |
| 6. cross-origin refused and counted | Task 5, `aCrossOriginLocationIsNotFollowedAndIsCounted` |
| 7. relative Location resolves; default-port equality | Task 5, `aRelativeLocationResolvesAndIsFollowed`; Task 1, `sameOriginNormalizesTheDefaultPortAndIgnoresHostCase` |
| 8. 307 preserves; 303-after-POST does not; HEAD stays HEAD | Task 4, `a307PreservesMethodAndBodyAndA303AfterPostDropsBoth` + `headStaysHeadAcrossAFollow`; Task 1, `followMethodMatchesTheJdkTableItReplaces` |
| 9. two breaching hops → two records, distinct hop= and details | Task 3, `aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers`; Task 4, `aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce` |
| 10. intermediate hops drained; final hop's body survives | Task 4, `theFinalHopsBodySurvivesTheDrainSoACaptureStillWorks` |
| 11. non-redirect response unchanged | Task 4, `aNonRedirectResponseIsUnchangedAndIsNotPolledWhenItReportedAHeader` |
| Consequences: corpus records its hop count | Task 6, Step 2 |
| Acceptance: Roller campaign, rows in the database | Task 7 |

## Disposition of every round-3 finding

| # | disposition |
|---|---|
| C1 Task 2 cannot compile alone | Task 2 Files lists all seven touched files, five edited and two verified. **Corrected:** `LoadModeControl.java:61` compiles unchanged under the chosen signatures — verified by reading it. `format(null) == format(empty) == MISS` is a named constraint with its own test. |
| C2 vacuous `assertNotNull` | Task 2 Step 1 rewrites both to `assertFalse(...isEmpty())` with the reason inline, plus a `grep` sweep before finishing. |
| C3 multi-line format never parsed | **Task 3 exists for this.** `fetchResult` preserves `\n` (cap moved after the append); `pollResult` splits on `\n`, parses per line with the 4-field limit, sums counts and deltas, ORs leaks, saves one record per breaching hop. Pinned by `aThreeHopBodyYieldsThreeRecordsWithDistinctHopNumbers`. |
| C4 `headerReported = false` does not undo a save | Task 4 **deletes** the save at `:761-763` and routes all saving through `reconcile`, per the review's option (b). Pinned by `aCommittedHopWithNoHeadersIsRecoveredAndFiledExactlyOnce` failing at 3 records. |
| C5 single id vs pod fan-out | Spec amendment **A4b-3**, option (a): merge across all bases when `hops > 1`, break-at-first when `hops <= 1`. `MultiReplicaPollTest` verified unaffected. Comments corrected in Task 6 Step 3. |
| C6 tests cannot detect non-closure | Three named discriminators in "The two questions", each built on a **committed, header-less** hop ported from `ReportChannelTest.serveCommittedApp`. Every task also carries a mechanism-removal check. |
| I7 stale "Review corrections" section | **Deleted.** Its ten surviving items are migrated: #12 → Task 4 (`resetSession`); #14 → the spec-coverage table; #15 → Task 6 Step 2; #17 → Global Constraint 13 + Task 4's `drain`; #19 → each Step 2's red/guard split; #20 → Global Constraint 16; #21 → Global Constraint 15; #6's split-branch code → Task 5; #9 → Global Constraint 10; #11 → Task 3's `waitForMetas`. |
| I8 no code in the tasks | Every task carries real, copyable code. |
| I9 drain/wait guidance names things that do not exist | Task 3's `waitForMetas` polls the results dir for N `.meta.txt` files with a deadline, then settles so an EXTRA record is caught. No production code; `TriageSink` and `waitAndReadAll` are named only to say why neither is used. |
| I10 hop-cap semantics undecided | **Decided:** five requests total, guard after the increment. Global Constraint 3, Task 2's `MAX_HOPS_PER_ID`, Task 4's `MAX_HOPS`, and `theHopCapStopsAtFiveRequestsAndFilesTheFinding`. Overflow drops the **oldest**. |
| I11 forced poll converts measured into misses | Spec amendment **A4b-2**; `reconcile`'s fall-back; `aMultiHopPollThatMissesFallsBackToTheHeaderRatherThanCountingAMiss`. |
| I12 `latMs` includes the poll | Task 6 Step 1: `lastPollMs` + the pure `wireLatency`. Not merely documented. |
| I13 `Entry` has no hop field | Spec amendment **A4b-4**: hop = position in the recovered sequence, stated in `ResultStore`'s and `pollResult`'s javadoc with the multi-replica caveat, and Task 7 must state its replica count. |
| I14 concurrency property with no mechanism | Task 2 mandates `synchronized (MAP)` around both the append and the read-and-remove, and an immutable copy from `take`, with the `computeIfAbsent` trap named. |
| I15 query strip specified in the wrong task | Moved to **Task 4**, at the point of construction (`visited.add(strippedUrl(url))`); Tasks 3 and 5 state it is already applied upstream. |
| I16 `URI.create` on a substituted path | Task 1's `safeUri`; Task 4 mandates it and forbids `URI.create` by name. |
| M17 invariant-count decision re-delegated | **Decided** in Global Constraint 10: sum, per spec §4. |
| M18 CI task is unrelated carry-over | Task 8, last, own commit, separable; the "push a deliberate failure" step is replaced with local verification. |
| M19 "expect failure" imprecise | Every Step 2 says whether the failure is a compile error or a red test, and names which tests are regression guards. |
| M20 three false in-tree comments | Task 6 Step 3, all three by file and line. |
| M21 spec items with no task | The spec-coverage table above; all eleven items and the Consequences hop count now have a home. |
| M22 `sessionCookie` leaks between tests | Task 4 widens `resetSession()` to package-private; `ExploreRedirectTest` calls it in both `@Before` and `@After`. |
