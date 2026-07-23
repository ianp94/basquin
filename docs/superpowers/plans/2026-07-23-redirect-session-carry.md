# DD-039 Session Carry Across Redirects — Implementation Plan

> **DO NOT EXECUTE AS WRITTEN.** A plan review found six Critical defects; three of them mean the
> plan cannot compile, and one would ship a live-token disk leak that violates DD-036. The required
> corrections are listed in "Review corrections" at the bottom of this file and must be folded into
> the tasks first.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Explore mode follows redirects itself — carrying the session cookie across every hop, recording each hop's invariant breaches, and turning a redirect loop into a finding — so authenticated write paths become reachable.

**Architecture:** `CoverageGuidedRun.request(...)` stops setting `setInstanceFollowRedirects(true)` and instead loops over hops. The per-hop work (issue, capture cookie, record invariants, accumulate cost, drain) is extracted into a small private helper so the loop body stays readable and the redirect decision logic (`shouldFollow`, `resolveLocation`, `sameOrigin`, `followMethod`) becomes pure and unit-testable without a server.

**Tech Stack:** Java 17, `java.net.HttpURLConnection`, `java.net.URI`, JUnit 4 with `com.sun.net.httpserver.HttpServer` (the existing `LoadCorrelationTest` pattern).

## Global Constraints

Copied verbatim from the spec; every task's requirements implicitly include these.

- **DD-036 invariant, non-negotiable:** a saved finding is labelled with the **raw step label**, never a substituted line, and never a resolved hop URL in place of the label. Hop URLs are additional metadata, never the label.
- **Max 5 hops**, and a **revisited resolved URL ends the chain** (`LinkedHashSet`).
- **Same-origin only**: scheme + host (case-insensitive) + port, with an absent port normalized to the scheme default (`URI.getPort()` returns `-1`).
- **A `Location` that will not parse ends the chain.** Never let `URISyntaxException`/`IllegalArgumentException` escape `request(...)` — the caller catches `Throwable` into `StatusReporter.recordCrash()`, which would file a false crash finding against the app.
- **Every hop's response stream is read to EOF** before the next hop is issued.
- **The `Cookie` header must be set explicitly on every hop** — the JDK does not carry it across a method-rewritten redirect.
- **Method rules:** 301/302/303 → `GET` **only when the original method carried a body** (POST/PUT/PATCH), dropping body and `Content-Type` together; otherwise preserve the method (notably `HEAD`); 307/308 always preserve method and body.
- **One `Invariant-Remote` record per breaching hop**, each carrying `hop=<n>` and that hop's resolved URL and detail. Heap and thread deltas are **summed**; latency is **not** touched.
- Explore is **single-threaded**; `sessionCookie` stays a single `static volatile` field. Do not introduce per-worker state.
- Load mode (`LoadRun`) is **not** modified by this plan.

---

### Task 1: Pure redirect-decision helpers

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java`
- Test: `test/runner/coverage/RedirectPolicyTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, all `static` and package-private on `CoverageGuidedRun`:
  - `static boolean isRedirect(int code)` — `code >= 300 && code < 400`
  - `static java.net.URI resolveLocation(String requestUrl, String location)` — resolved absolute URI, or `null` if either will not parse
  - `static boolean sameOrigin(java.net.URI a, java.net.URI b)`
  - `static int defaultPort(String scheme)` — 80/443, `-1` otherwise
  - `static String followMethod(String method, int code)` — the method the follow hop must use

- [ ] **Step 1: Write the failing tests**

Create `test/runner/coverage/RedirectPolicyTest.java`:

```java
package runner.coverage;

import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

public class RedirectPolicyTest {

    @Test public void redirectRangeIsThreeHundredInclusiveToFourHundredExclusive() {
        assertFalse(CoverageGuidedRun.isRedirect(200));
        assertTrue(CoverageGuidedRun.isRedirect(301));
        assertTrue(CoverageGuidedRun.isRedirect(302));
        assertTrue(CoverageGuidedRun.isRedirect(307));
        assertFalse(CoverageGuidedRun.isRedirect(400));
        assertFalse(CoverageGuidedRun.isRedirect(-1));
    }

    @Test public void relativeLocationResolvesAgainstTheRequestUrl() {
        URI r = CoverageGuidedRun.resolveLocation("http://svc:8080/a/b?x=1", "/login");
        assertEquals("http://svc:8080/login", r.toString());
    }

    // DD-039: a fuzzed Location must NEVER throw out of request() -- the caller catches
    // Throwable into recordCrash(), which would file a false crash finding AGAINST THE APP
    // carrying a stack that points into driver code.
    @Test public void unparseableLocationReturnsNullRatherThanThrowing() {
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", "/x y|z^{}"));
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", "ht tp://nope"));
        assertNull(CoverageGuidedRun.resolveLocation(":::not a url:::", "/login"));
    }

    @Test public void sameOriginNormalizesAbsentPortAndIgnoresHostCase() {
        assertTrue(CoverageGuidedRun.sameOrigin(URI.create("http://svc:80/a"), URI.create("http://svc/b")));
        assertTrue(CoverageGuidedRun.sameOrigin(URI.create("http://SVC/a"),    URI.create("http://svc/b")));
        assertTrue(CoverageGuidedRun.sameOrigin(URI.create("https://s:443/a"), URI.create("https://s/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"),   URI.create("http://other/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"),   URI.create("https://svc/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"),   URI.create("http://svc:9/b")));
    }

    // Preserves what the JDK already did: 30x after a BODY method becomes GET; 307/308 keep
    // the method; and a bodyless method (notably HEAD) is NOT rewritten -- turning HEAD into
    // GET would pull a body the caller never asked for and corrupt that input's cost rank.
    @Test public void followMethodRewritesOnlyBodyCarryingMethodsOnThirtyOneTwoThree() {
        assertEquals("GET",  CoverageGuidedRun.followMethod("POST", 302));
        assertEquals("GET",  CoverageGuidedRun.followMethod("PUT", 301));
        assertEquals("GET",  CoverageGuidedRun.followMethod("PATCH", 303));
        assertEquals("HEAD", CoverageGuidedRun.followMethod("HEAD", 302));
        assertEquals("GET",  CoverageGuidedRun.followMethod("GET", 302));
        assertEquals("POST", CoverageGuidedRun.followMethod("POST", 307));
        assertEquals("POST", CoverageGuidedRun.followMethod("POST", 308));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*RedirectPolicyTest*'`
Expected: FAIL — `cannot find symbol: method isRedirect`.

- [ ] **Step 3: Implement the helpers**

Add to `CoverageGuidedRun`, near the existing `request(...)`:

```java
    /** DD-039: 3xx. Kept a named predicate so the hop loop reads as policy, not arithmetic. */
    static boolean isRedirect(int code) { return code >= 300 && code < 400; }

    /**
     * DD-039: resolve a {@code Location} against the request URL. Returns null when EITHER will
     * not parse. Null must be treated as "end of chain" by the caller — never propagated as an
     * exception: {@code request(...)} is {@code throws Exception} and its caller catches
     * {@code Throwable} into {@link runner.util.StatusReporter#recordCrash()}, so an escaping
     * URISyntaxException would be filed as a CRASH FINDING AGAINST THE APP, with a stack pointing
     * into driver code. A fuzzer reflects exactly the bytes that trigger it (spaces, {}|^).
     */
    static java.net.URI resolveLocation(String requestUrl, String location) {
        if (location == null || location.isEmpty()) return null;
        try {
            return new java.net.URI(requestUrl).resolve(new java.net.URI(location));
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    static int defaultPort(String scheme) {
        if ("http".equalsIgnoreCase(scheme)) return 80;
        if ("https".equalsIgnoreCase(scheme)) return 443;
        return -1;
    }

    /**
     * DD-039: scheme + host + port, with an absent port normalized to the scheme default —
     * {@code URI.getPort()} returns -1 when absent, so {@code http://svc/x} must still match a base
     * of {@code http://svc:80}. Host compares case-insensitively.
     */
    static boolean sameOrigin(java.net.URI a, java.net.URI b) {
        if (a == null || b == null) return false;
        if (a.getScheme() == null || !a.getScheme().equalsIgnoreCase(b.getScheme())) return false;
        if (a.getHost() == null || !a.getHost().equalsIgnoreCase(b.getHost())) return false;
        int pa = a.getPort() == -1 ? defaultPort(a.getScheme()) : a.getPort();
        int pb = b.getPort() == -1 ? defaultPort(b.getScheme()) : b.getPort();
        return pa == pb;
    }

    /**
     * DD-039: the method a follow hop uses. This PRESERVES what the JDK already did rather than
     * choosing new behaviour (probed: a 302 after POST already became a bodyless GET; a 307 already
     * kept method and body). 301/302/303 rewrite only a BODY-carrying method — a HEAD must stay a
     * HEAD, or the follow pulls a response body the caller never asked for and inflates that input's
     * measured latency and heap, corrupting its cost rank.
     */
    static String followMethod(String method, int code) {
        if (code == 307 || code == 308) return method;
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return hasBody ? "GET" : method;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*RedirectPolicyTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runner/coverage/CoverageGuidedRun.java test/runner/coverage/RedirectPolicyTest.java
git commit -m "feat(explore): pure redirect-policy helpers — resolve, same-origin, follow method (DD-039)"
```

---

### Task 2: Manual follow with session carry

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java` (the `request(...)` method, ~`:590-680`)
- Test: `test/runner/coverage/ExploreRedirectTest.java` (create)

**Interfaces:**
- Consumes: `isRedirect`, `resolveLocation`, `sameOrigin`, `followMethod` from Task 1.
- Produces: `static volatile long crossOriginRedirects` on `CoverageGuidedRun`, readable by tests and by the summary in Task 3.

- [ ] **Step 1: Write the failing tests**

Create `test/runner/coverage/ExploreRedirectTest.java`. Each test stands up an `HttpServer`, records what the server received, and asserts on that — never on driver-side state alone.

```java
package runner.coverage;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

public class ExploreRedirectTest {

    /** What the server actually received, which is the only thing worth asserting on. */
    record Seen(String method, String path, String cookie) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private String start(java.util.function.BiConsumer<com.sun.net.httpserver.HttpExchange, List<Seen>> handler)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                              ex.getRequestHeaders().getFirst("Cookie")));
            handler.accept(ex, seen);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // THE motivating case (DD-039 defect 1): Spring Security answers form login with a 302 that
    // CARRIES the rotated JSESSIONID. Auto-follow makes that header unreachable, so the driver
    // keeps the anonymous session forever and every later step runs logged out.
    @Test public void rotatedSessionCookieOnTheThreeOhTwoReachesTheNextHop() throws Exception {
        String base = start((ex, s) -> {
            try {
                if (ex.getRequestURI().getPath().equals("/login")) {
                    ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=rotated; Path=/");
                    ex.getResponseHeaders().add("Location", "/landing");
                    ex.sendResponseHeaders(302, -1);
                } else {
                    byte[] b = "ok".getBytes();
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                }
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("POST /login u=a"));

        assertEquals(2, seen.size());
        assertEquals("/landing", seen.get(1).path());
        assertEquals("JSESSIONID=rotated", seen.get(1).cookie());
    }

    // DD-039 defect 2, in isolation: even with NO cookie rotation, the JDK drops the Cookie
    // REQUEST header on a method-rewritten hop -- so every post-redirect landing page has been
    // rendering anonymously. This case discriminates where a "value survives" assertion does not.
    @Test public void preExistingCookieIsCarriedOntoAHopThatSetsNoCookie() throws Exception {
        String base = start((ex, s) -> {
            try {
                if (ex.getRequestURI().getPath().equals("/a")) {
                    ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=first; Path=/");
                    byte[] b = "seed".getBytes();
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } else if (ex.getRequestURI().getPath().equals("/b")) {
                    ex.getResponseHeaders().add("Location", "/c");   // NO Set-Cookie here
                    ex.sendResponseHeaders(302, -1);
                } else {
                    byte[] b = "done".getBytes();
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                }
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("/a"));      // seeds the session
        seen.clear();
        CoverageGuidedRun.request(base, RequestLine.parse("POST /b x=1"));

        assertEquals(2, seen.size());
        assertEquals("JSESSIONID=first", seen.get(0).cookie());
        assertEquals("the JDK drops Cookie on a rewritten hop; we must set it ourselves",
                     "JSESSIONID=first", seen.get(1).cookie());
    }

    @Test public void headStaysHeadAcrossAFollow() throws Exception {
        String base = start((ex, s) -> {
            try {
                if (ex.getRequestURI().getPath().equals("/h")) {
                    ex.getResponseHeaders().add("Location", "/target");
                    ex.sendResponseHeaders(302, -1);
                } else {
                    ex.sendResponseHeaders(200, -1);
                }
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("HEAD /h"));

        assertEquals(2, seen.size());
        assertEquals("HEAD", seen.get(1).method());
    }

    @Test public void crossOriginLocationIsNotFollowedAndIsCounted() throws Exception {
        long before = CoverageGuidedRun.crossOriginRedirects;
        String base = start((ex, s) -> {
            try {
                ex.getResponseHeaders().add("Location", "http://example.invalid/elsewhere");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("/x"));

        assertEquals("must not leave the target", 1, seen.size());
        assertEquals(before + 1, CoverageGuidedRun.crossOriginRedirects);
    }

    @Test public void unparseableLocationEndsTheChainWithoutThrowing() throws Exception {
        String base = start((ex, s) -> {
            try {
                ex.getResponseHeaders().add("Location", "/x y|z^{}");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("/x"));   // must not throw

        assertEquals(1, seen.size());
    }

    @Test public void nonRedirectResponseIsUnchanged() throws Exception {
        String base = start((ex, s) -> {
            try {
                byte[] b = "plain".getBytes();
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            } catch (Exception ignored) {}
        });

        CoverageGuidedRun.request(base, RequestLine.parse("/plain"));

        assertEquals(1, seen.size());
    }

    @org.junit.After public void stop() { if (server != null) server.stop(0); }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ExploreRedirectTest*'`
Expected: FAIL — `rotatedSessionCookieOnTheThreeOhTwoReachesTheNextHop` sees `cookie=null` on hop 2 (the current auto-follow behaviour), and `crossOriginRedirects` does not resolve.

- [ ] **Step 3: Implement the hop loop**

In `CoverageGuidedRun`, add the counter beside `sessionCookie` (~`:480`):

```java
    /** DD-039: refused cross-origin follows. Surfaced because a target that renders redirects from a
     *  configured absolute base URL different from the Service DNS the driver dials makes EVERY
     *  redirect cross-origin — silently degrading this feature to its pre-DD-039 behaviour, which is
     *  indistinguishable from it working. A non-zero count beside flat coverage is diagnosable. */
    static volatile long crossOriginRedirects = 0;
```

In `request(...)`, replace `c.setInstanceFollowRedirects(true);` with `c.setInstanceFollowRedirects(false);` and wrap the issue-and-read in a loop. The loop keeps the existing header-scrape, invariant-record, cost-parse and body-read code as the **final hop's** handling; each non-final hop captures the cookie, records its invariants (Task 3), drains, and moves on:

```java
        java.util.LinkedHashSet<String> visited = new java.util.LinkedHashSet<>();
        String url = baseUrl + r.path();
        String method = r.method();
        String body = r.body();
        for (int hop = 0; ; hop++) {
            visited.add(url);
            // ... existing connection setup, but with the Cookie header ALWAYS set from
            //     sessionCookie (the JDK will not carry it across a rewritten hop) ...
            int code = c.getResponseCode();
            captureSessionCookieFrom(c);          // every hop, before the next is issued
            recordHopInvariants(c, label, hop, url);   // Task 3
            if (!isRedirect(code) || hop >= MAX_HOPS) { /* fall through to final-hop handling */ break; }
            java.net.URI next = resolveLocation(url, c.getHeaderField("Location"));
            if (next == null) break;                                   // unparseable → end of chain
            if (!sameOrigin(java.net.URI.create(url), next)) { crossOriginRedirects++; break; }
            if (!visited.add(next.toString())) { /* loop → Task 3 finding */ break; }
            drain(c);                                                  // keep-alive, not a leak
            method = followMethod(method, code);
            if (!method.equals(r.method())) body = null;               // body + Content-Type dropped
            url = next.toString();
        }
```

Keep the final hop's existing body read, `X-Basquin-Cost` parse, and `CostSample` construction exactly as they are today.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests '*ExploreRedirectTest*' --tests '*ExploreCorrelationTest*'`
Expected: PASS, including the pre-existing correlation tests (no regression on the non-redirect path).

- [ ] **Step 5: Commit**

```bash
git add runner/coverage/CoverageGuidedRun.java test/runner/coverage/ExploreRedirectTest.java
git commit -m "feat(explore): follow redirects manually, carrying the session cookie across every hop (DD-039)"
```

---

### Task 3: Per-hop invariant records, summed cost, and the redirect-loop finding

**Files:**
- Modify: `runner/coverage/CoverageGuidedRun.java`
- Test: `test/runner/coverage/ExploreRedirectTest.java` (extend)

**Interfaces:**
- Consumes: the hop loop from Task 2.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Write the failing tests**

Append to `ExploreRedirectTest`. Findings are written through `FuzzIO`, so point it at a temp dir with `basquin.fuzz.resultsDir` and assert on the files:

```java
    // DD-039: summing counts while persisting ONE record reports "3 violations on POST /login"
    // when 2 of them were the dashboard render. One record per breaching hop, each labelled with
    // the RAW step (DD-036) and carrying which hop it was.
    @Test public void eachBreachingHopGetsItsOwnRecordLabelledWithTheRawStep() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dd039-hops");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            String base = start((ex, s) -> {
                try {
                    ex.getResponseHeaders().add("X-Basquin-Invariant-Count", "1");
                    ex.getResponseHeaders().add("X-Basquin-Invariant-Detail",
                            ex.getRequestURI().getPath().equals("/one") ? "latency=30ms" : "latency=900ms");
                    if (ex.getRequestURI().getPath().equals("/one")) {
                        ex.getResponseHeaders().add("Location", "/two");
                        ex.sendResponseHeaders(302, -1);
                    } else {
                        ex.sendResponseHeaders(200, -1);
                    }
                    ex.close();
                } catch (Exception ignored) {}
            });

            CoverageGuidedRun.request(base, RequestLine.parse("/one"));
            runner.util.TriageSink.drainForTest();      // triage is off the hot path

            List<String> metas = new java.util.ArrayList<>();
            try (var st = java.nio.file.Files.walk(dir)) {
                for (java.nio.file.Path p : st.filter(p -> p.toString().endsWith(".meta")).toList()) {
                    metas.add(java.nio.file.Files.readString(p));
                }
            }
            assertEquals("one record per breaching hop", 2, metas.size());
            assertTrue(metas.stream().anyMatch(m -> m.contains("hop=0") && m.contains("latency=30ms")));
            assertTrue(metas.stream().anyMatch(m -> m.contains("hop=1") && m.contains("latency=900ms")));
            assertTrue("DD-036: labelled with the raw step, never a hop URL",
                       metas.stream().allMatch(m -> m.contains("route=/one")));
        } finally {
            System.clearProperty("basquin.fuzz.resultsDir");
        }
    }

    // An app that redirects indefinitely is UNAVAILABLE -- a browser calls it
    // ERR_TOO_MANY_REDIRECTS. Silently scoring the last response would discard the finding and
    // feed a meaningless response into the cost model. A revisited URL catches the common
    // 2-hop auth bounce that a hop cap never reaches.
    @Test public void aRedirectLoopProducesAFinding() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dd039-loop");
        System.setProperty("basquin.fuzz.resultsDir", dir.toString());
        try {
            String base = start((ex, s) -> {
                try {
                    ex.getResponseHeaders().add("Location",
                            ex.getRequestURI().getPath().equals("/protected") ? "/login" : "/protected");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                } catch (Exception ignored) {}
            });

            CoverageGuidedRun.request(base, RequestLine.parse("/protected"));
            runner.util.TriageSink.drainForTest();

            boolean found = false;
            try (var st = java.nio.file.Files.walk(dir)) {
                for (java.nio.file.Path p : st.filter(p -> p.toString().endsWith(".meta")).toList()) {
                    String m = java.nio.file.Files.readString(p);
                    if (m.contains("Redirect-Loop")) {
                        found = true;
                        assertTrue("labelled with the raw step", m.contains("route=/protected"));
                        assertTrue("carries the ordered hops", m.contains("/login"));
                    }
                }
            }
            assertTrue("a redirect loop must be a finding, not a silent truncation", found);
        } finally {
            System.clearProperty("basquin.fuzz.resultsDir");
        }
    }
```

If `TriageSink` has no test drain, add one in this task (`static void drainForTest()` that awaits the queue) rather than sleeping — a sleep makes this test flaky under CI load.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ExploreRedirectTest*'`
Expected: FAIL — one record instead of two; no `Redirect-Loop` record.

- [ ] **Step 3: Implement**

```java
    private static final int MAX_HOPS = 5;

    /** DD-039: one record per breaching hop. X-Basquin-Invariant-Detail is per-request, so a summed
     *  count with a single record reports one arbitrary hop's detail against the whole chain. */
    private static int recordHopInvariants(java.net.HttpURLConnection c, String label, int hop, String hopUrl) {
        String inv = c.getHeaderField("X-Basquin-Invariant-Count");
        if (inv == null) return 0;
        int count = 0;
        try { count = Integer.parseInt(inv.trim()); } catch (NumberFormatException ignored) { return 0; }
        if (count <= 0) return 0;
        String detail = c.getHeaderField("X-Basquin-Invariant-Detail");
        FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Invariant-Remote",
                "route=" + label + "\nhop=" + hop + "\nhopUrl=" + hopUrl
                        + "\ncount=" + count + (detail != null ? "\ndetail=" + detail : ""));
        return count;
    }

    /** DD-039: a redirect loop is a finding — an app that redirects forever is unavailable. */
    private static void saveRedirectLoop(String label, java.util.Collection<String> hops) {
        FuzzIO.saveWithMeta(label.getBytes(StandardCharsets.UTF_8), "Redirect-Loop",
                "route=" + label + "\nhops=" + String.join(" -> ", hops));
    }

    /** DD-039: read the hop to EOF so the connection returns to the keep-alive pool. */
    private static void drain(java.net.HttpURLConnection c) {
        try (InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (is != null) { byte[] buf = new byte[4096]; while (is.read(buf) >= 0) { /* discard */ } }
        } catch (IOException ignored) { }
    }
```

Wire `saveRedirectLoop` into both the revisited-URL branch and the `hop >= MAX_HOPS` branch, and accumulate `heapKb`/`threadDelta` across hops into the `CostSample` the method already returns. Leave `latMs` alone — it is client wall-clock in the caller and already spans the chain.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add runner/coverage/CoverageGuidedRun.java test/runner/coverage/ExploreRedirectTest.java
git commit -m "feat(explore): per-hop invariant records, summed hop cost, redirect-loop finding (DD-039)"
```

---

### Task 4: Record and document

**Files:**
- Modify: `docs/DESIGN-DECISIONS.md` (append DD-039 after DD-038)
- Modify: `docs/how-it-works.html` (the "Reaching write paths that defend themselves" section)
- Modify: `runner/CHANGELOG.md` (`[Unreleased]`)
- Modify: `deploy/bench/ONBOARDING.md` (the cross-origin onboarding trap)

- [ ] **Step 1: Write the DD-039 record**

Append `## DD-039: Session carry across redirects in explore mode (2026-07-23)` after DD-038, mirroring its `**Context.** / **Decision.** / **Verified.** / **Rejected alternatives.**` shape. It must state that this fixes **three** defects (unreachable `Set-Cookie` on the 3xx; the `Cookie` request header dropped on rewritten hops, so landing pages rendered anonymously; discarded intermediate-hop invariant counts), carry the JDK probe evidence, and include the **Consequences** note about explore/load cost divergence from the spec.

- [ ] **Step 2: Update the docs page**

In `docs/how-it-works.html`, extend the correlation section with a paragraph on session carry: a login that rotates the session on its 302 is now followed manually so the rotated cookie is carried, and a redirect loop is reported as a finding.

- [ ] **Step 3: CHANGELOG + onboarding note**

One `[Unreleased]` bullet. In `ONBOARDING.md`, add a gotcha: if the app renders redirects from a configured absolute base URL that differs from the Service DNS the driver dials, every redirect is cross-origin and session carry silently no-ops — check `crossOriginRedirects` in the summary.

- [ ] **Step 4: Verify**

Run: `./gradlew test -q` (docs-only, confirms nothing broke). Re-read each file; confirm DD-039 is the newest record and the CHANGELOG bullet is under the existing `[Unreleased]`.

- [ ] **Step 5: Commit**

```bash
git add docs/DESIGN-DECISIONS.md docs/how-it-works.html runner/CHANGELOG.md deploy/bench/ONBOARDING.md
git commit -m "docs: DD-039 session carry across redirects + changelog"
```

---

### Task 5: Prove it against the real app

**Files:** none committed unless a defect is found.

- [ ] **Step 1** Rebuild and load the runner image: `deploy/runner-image/build.sh <tag> basquin`, then point the controller's `--runner-image` at that tag.
- [ ] **Step 2** Run a Roller explore campaign against the seeded target.
- [ ] **Step 3** **Assert on database rows, not coverage.** Query `roller_entry`/`roller_comment` for rows written by the campaign. Coverage can rise for unrelated reasons and is not acceptance evidence.
- [ ] **Step 4** Check the summary's `crossOriginRedirects` is 0 for this target (`site.absoluteurl` is seeded empty, so Roller derives it from the request).
- [ ] **Step 5** Record the result in the DD-039 record's **Verified.** paragraph. If no authenticated write lands, STOP and report — the feature has not been demonstrated, whatever the tests say.


---

## Review corrections (must be applied before execution)

A pre-execution review checked every symbol the plan uses against the real code. Findings, in the
order they would bite an implementer.

### Critical

1. **`CoverageGuidedRun.request(String, RequestLine)` does not exist.** Every server test in Tasks 2
   and 3 calls it. The real overloads are `static CostSample request(String base, String step)`
   (`:558`, package-private, callable from a same-package test) and a `private` 4-arg one (`:593`).
   Use the **String** overload — house precedent `ExploreCorrelationTest.java:88`. **And explicitly
   forbid the tempting fix:** adding `request(base, r)` that derives `label` from `r.format()`
   violates DD-036 twice — the Javadoc at `:586-591` forbids it, and `:571-574` records that
   `format()` canonicalizes a no-body GET, silently rewriting recorded finding text.

2. **Meta files are `.meta.txt`, not `.meta`** (`FuzzIO.java:38`, `:76`). The Task 3 assertions
   filter `.meta` and match zero files, so they fail with `0` **after a correct implementation** —
   the worst possible failure mode, because the implementer will hunt a nonexistent bug or weaken
   the assertion.

3. **`hopUrl=` and `hops=` leak a live captured token to disk — a real DD-036 break.** Hop 0's URL is
   `base + r.path()` where `r` is the **substituted** RequestLine (`runSequence` substitutes the path
   at `:500`, deliberately, per DD-038). So a step `GET /edit?csrf=${{tok}}` writes the real CSRF
   token into the finding meta. The existing guard test
   `ExploreCorrelationTest.correlatedInvariantFindingDoesNotPersistToken` puts the token in the
   **body** and will not catch it. **Strip query and fragment from every recorded hop URL**
   (`scheme://host:port/path` only) and add a test for a token substituted into the **path**.

4. **Task 2 cannot compile or be committed on its own.** Its loop calls `recordHopInvariants`,
   `MAX_HOPS`, and `drain` (all defined in Task 3) plus `captureSessionCookieFrom`, which the plan
   never defines anywhere. Move `MAX_HOPS`, `drain`, and an explicit `captureSessionCookieFrom`
   (extracted verbatim from `:619-627`) into Task 2, and keep the legacy `:628-635` invariant block
   as final-hop handling until Task 3 replaces it.

5. **Duplicate `Invariant-Remote` record for the final hop.** Task 3 says to keep the existing
   final-hop handling but never says to **delete** `:628-635`, which `recordHopInvariants` replaces.
   Leaving it yields 3 records in the 2-hop test.

6. **The terminal-condition branch is fused, and wiring `saveRedirectLoop` into it as written floods
   findings.** `if (!isRedirect(code) || hop >= MAX_HOPS) break;` plus "wire saveRedirectLoop into
   the `hop >= MAX_HOPS` branch" makes **every ordinary non-redirect response** file a
   `Redirect-Loop` finding. Split them:
   ```java
   if (!isRedirect(code)) break;                                   // normal terminal response
   if (hop >= MAX_HOPS) { saveRedirectLoop(label, visited); break; }
   ```
   Also pin the off-by-one: `hop` is 0-based, so `hop >= 5` issues **six** requests. Decide whether
   "max 5 hops" means 5 total or 1 + 5 follows, and say so.

### Important

7. **Task 2 must carry the full rewritten `request(...)`, not a sketch.** The elided "existing
   connection setup" *is* the code that changes. Concretely: `HttpURLConnection c` and `int code`
   must be **hoisted** above the loop or the post-loop code (`:636-675`) does not compile;
   `new URL(base + r.path())` → `new URL(url)`; `setRequestMethod(r.method())` → `setRequestMethod(method)`;
   `if (r.body() != null)` → `if (reqBody != null)` (leave it on `r.body()` and the POST body **and**
   its `Content-Type` are re-sent on the rewritten GET hop — the exact failure §3 exists to prevent);
   and the sketch's `String body = r.body()` collides with the existing `StringBuilder body` at
   `:646` — rename to `reqBody`.
8. **`crossOriginRedirects` is never surfaced.** Tasks 4 and 5 both tell the operator to "check it in
   the summary"; no task prints it. Add it beside the end-of-run summary at `:326-327`, plus the
   spec's once-only warn naming the refused origin.
9. **Invariant-count accounting is undecided.** `recordHopInvariants` returns `int` and nothing
   consumes it; `CostSample.invariantCount` feeds `CostModel.score`. Summing is probably right but
   must be a written decision, not an implementer's guess.
10. **Per-hop `X-Basquin-Cost` accumulation has no code.** Stated in prose only; today's parse is at
    `:636-644`, after the loop.
11. **`TriageSink.drainForTest()` does not exist** and a naive queue-empty check is racy (the
    consumer can be mid-write). Either use a sentinel (`submit(latch::countDown)` then await) or
    reuse the existing house helper `ExploreCorrelationTest.waitAndReadAll` (`:210-227`), which
    already solves this by polling the directory with a deadline and touches no production code.
12. **Static `sessionCookie` leaks between tests.** It is `private static volatile` and
    `resetSession()` is `private`, so a same-package test cannot reset it, and the suite shares one
    JVM. Make `resetSession()` package-private and add `@Before`.
13. **The riskiest line has no over-the-wire test.** `if (!method.equals(r.method())) body = null;`
    governs body/`Content-Type` dropping, and only `followMethod` is tested in isolation. Add server
    tests asserting the received body and `Content-Type` for 307-after-POST and 303-after-POST.

### Minor

14. Spec verification items with no test: #3 (three-hop rotation chain), #4's 5-hop-cap half, #10
    (drain / connection reuse); #11 is only weakly covered.
15. Spec Consequences requires the corpus entry to record its hop count — no task implements it.
    Implement it or mark it accepted-not-implemented.
16. `resolveLocation` swallows silently; the spec requires a once-only log.
17. `IOException` is not imported in `CoverageGuidedRun.java` — the `drain` snippet won't compile.
18. Loop-detection keys mix two producers (`base + r.path()` vs a normalized `URI.toString()`), so a
    real loop can go undetected. Normalize both through `URI`. Also `visited.add(url)` at the top of
    the loop is dead.
19. Task 2 Step 2's "expected: FAIL" is imprecise — the class won't compile, and only two of six
    tests genuinely discriminate (the JDK already preserves HEAD across a 302, so that one is a
    regression guard, not a red test).
20. Tests clear `basquin.fuzz.resultsDir` rather than saving and restoring a prior value (house
    pattern: `ExploreCorrelationTest.java:128`).
21. `catch (Exception ignored) {}` in every handler means a broken response reads as a passing test.

### Re-cut of Task 2 (it is both too large and mis-cut)

- **2a — mechanical restructure:** hoist `c`/`code`, extract `captureSessionCookieFrom`, add
  `MAX_HOPS` + `drain`, convert to the hop loop with the `Cookie` header set on every hop and
  body/`Content-Type` dropped on rewrite. Tests: both cookie tests, HEAD, 307/303 body, non-redirect.
- **2b — redirect policy:** cross-origin refusal + counter + warn, unparseable `Location`,
  revisited-URL and cap detection (breaking without a finding yet).
- **3 — findings and accounting:** `recordHopInvariants` replacing `:628-635`, `saveRedirectLoop`
  wired into both branches, summed heap/thread/invariant count, hop-URL redaction.

### Confirmed sound — do not churn

Task 1 in full (helpers and tests). `FuzzIO.saveWithMeta(byte[], String, String)` is exactly the
signature used. The discrimination logic of the two cookie tests is right — `seen.clear()` sits after
the seeding request, so the static field's prior value is never asserted on. Drain placement and
final-hop preservation in the loop sketch are correct: every terminal condition `break`s before the
`drain(c)` call, so the final hop's body is never consumed. `example.invalid` poses no DNS risk —
`sameOrigin` is a pure string/port comparison performed before any connection is opened. Task 4's
targets all exist and are accurately named. Task 5's acceptance bar ("assert on database rows, not
coverage"; "if no authenticated write lands, STOP and report") should be kept verbatim.
