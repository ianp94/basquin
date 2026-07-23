# DD-039 Session Carry Across Redirects — Implementation Plan

> **UNBLOCKED (PR #94 merged 2026-07-23) — but the task bodies still need a real revision.**
>
> The premise now builds: DD-040's `ResultStore`, `PodPollTargets`, `X-Basquin-Req` stamping and
> `pollResult` are on `main`, and this branch is cut from it.
>
> Still outstanding, from `.superpowers/sdd/plan-review-dd039-round2.md`: only **3 of 21** findings
> from the first review were genuinely folded in — the rest were moved into prose Global Constraints
> while the *copyable task code* still contains them, including two Criticals verbatim. That
> revision must be done against the task code itself, and verified, not asserted.
>
> The design question the review exposed is now **settled in the spec** (§4b): stamping every hop
> with DD-040's single per-request id does not work, because `ResultStore.put` replaces by key and
> each hop overwrites the last. The store accumulates per-hop entries under one id instead, `take`
> returns them all, and the driver — which runs the follow loop itself — polls whenever it made more
> than one hop. The plan must be rewritten to implement *that*, not the version that does not work.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Explore mode follows redirects itself — carrying the session cookie across every hop, stamping and recording each hop, and turning a redirect loop into a finding — so authenticated write paths become reachable **and every hop's measurement is actually reported**.

**Acceptance criterion (inherited from DD-040, which failed on exactly this):** a Roller explore campaign's reported violation count must come within a small tolerance of the `[Basquin][Invariant]` line count in the target pod's log for the same window. DD-040's acceptance run measured **1,413 reported against 1,602 logged — a gap of 189 (11.8%)** — because a method-rewritten redirect (POST → 302 → GET) loses the `X-Basquin-Req` header: the JDK does not carry a `setRequestProperty` value onto such a hop, so hop 2 evaluates, logs, and publishes nothing. The poll then still *succeeds* from hop 1 and the driver records `measured=true, count=0` — a clean zero for a request whose real work violated, invisible to `reportMisses`. **This plan's hop loop sets headers on every hop, which stamps each one by construction. Closing that gap is how this plan is judged.**

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
- **Every hop must be stamped with `X-Basquin-Req`** (DD-040's per-request id) and its result polled,
  because the JDK drops `setRequestProperty` values on a method-rewritten hop. This is the defect
  DD-040's acceptance run failed on. A hop that fires unstamped is a hop whose violation is
  evaluated, logged, and silently uncounted.
- **`CoverageGuidedRun.request(String base, RequestLine r)` DOES NOT EXIST.** Tests must call the
  package-private `request(String base, String step)` (house precedent:
  `ExploreCorrelationTest.java:88`). **Do not add a `RequestLine` overload** deriving `label` from
  `r.format()` — that violates DD-036 twice, and `format()` canonicalizes a no-body GET, silently
  rewriting recorded finding text.
- **Meta files are `.meta.txt`, not `.meta`** (`FuzzIO.java:38`). A test filtering `.meta` matches
  zero files and fails *after a correct implementation* — the worst failure mode there is.
- **Never record a hop URL with its query string.** Hop 0's URL is built from the **substituted**
  RequestLine (DD-038 substitutes the path deliberately), so `GET /edit?csrf=${{tok}}` would write a
  live CSRF token to disk. Strip to `scheme://host:port/path`. The existing guard test puts its
  token in the *body* and will not catch this.

---

### Task 1: Pure redirect-decision helpers

Unchanged from the reviewed version and confirmed sound — do not churn it. Adds `isRedirect`,
`resolveLocation` (returns null rather than throwing), `sameOrigin` (default-port normalized,
host case-insensitive), `defaultPort`, `followMethod`, on `CoverageGuidedRun`, with
`test/runner/coverage/RedirectPolicyTest.java`. One addition from review: `resolveLocation` logs
once on an unparseable Location rather than swallowing silently.

---

### Task 2: `ResultStore` accumulates per-hop entries under one id

**Files:** `agent/ResultStore.java`; `test/agent/ResultStoreTest.java`

**This task is new and it is the one that makes the feature work.** DD-040 mints ONE
`X-Basquin-Req` id per request and `ResultStore.put` **replaces by key** (`ResultStore.java:45`). So
stamping every hop with that id has each hop overwrite the last, one entry survives, and DD-040's
189-violation gap stays exactly where it is. See spec §4b.

**Interfaces produced (later tasks depend on these names):**
- `ResultStore.put(id, Entry)` **appends** to that id's hop list, capped at `MAX_HOPS_PER_ID` (5,
  matching the follow loop's own cap). Overflow drops the *newest* and records that it did — silently
  dropping a hop would be a lost violation, which is the defect class.
- `ResultStore.take(id)` returns `List<Entry>` (empty list, never null, on a miss) and removes the id.
  **Still exactly one remove-on-read**, which is why no double-count path exists.
- `ResultStore.format(List<Entry>)` emits one line per hop; the driver parses with a 4-field limit
  per line as today (`detail` is app-derived).

- [ ] **Step 1** Tests: two `put`s under one id yield two entries from one `take`; a third `take`
      misses; the per-id cap holds and overflow is recorded; a single-hop request is byte-identical
      in behaviour to today (the no-redirect path must not regress); concurrent `put`/`take` on the
      same id does not corrupt the list (the poll is a different connector thread — the existing
      `concurrentPutAndTakeDoNotCorruptTheStore` shows how to make this discriminate rather than
      pass trivially).
- [ ] **Step 2** Run; expect failure.
- [ ] **Step 3** Implement. Keep the store dependency-free and namespace-free (it loads into the
      target JVM). Keep `detail` capped per hop; state the resulting byte bound in the javadoc as
      today (`CAPACITY` × `MAX_HOPS_PER_ID` × cap).
- [ ] **Step 4** `./gradlew test` fully green.
- [ ] **Step 5** Commit.

---

### Task 3: The hop loop — manual follow, stamped and cookied on every hop

**Files:** `runner/coverage/CoverageGuidedRun.java`; `test/runner/coverage/ExploreRedirectTest.java` (create)

Replaces `c.setInstanceFollowRedirects(true)` (`CoverageGuidedRun.java:717`) with an explicit loop.

**Spell out the restructure — each of these is a compile error or a silent wrong behaviour if the
existing code is moved into a loop unchanged:**

- `HttpURLConnection c` and `int code` must be **hoisted above the loop**; they are loop-scoped
  today and the post-loop body-read/capture/serverError block would not compile.
- `new URL(base + r.path())` → `new URL(url)`; `setRequestMethod(r.method())` → `setRequestMethod(method)`.
- `if (r.body() != null)` → `if (reqBody != null)`. Leave it on `r.body()` and the POST body **and**
  its `Content-Type` are re-sent on a rewritten GET hop.
- **Name collision:** the request-body local cannot be `body` — one is already declared for the
  response. Use `reqBody`.
- **Set `Cookie` AND `X-Basquin-Req` explicitly on every hop.** The JDK carries neither onto a
  method-rewritten hop; that is both DD-039's motivating defect and DD-040's residual.
- Each hop is drained to EOF before the next is issued. Every terminal condition `break`s *before*
  the drain, so the final hop's body survives for the capture step.
- Keep `MAX_HOPS`, `drain`, and `captureSessionCookieFrom` **defined in this task**. It must
  **compile and commit alone**: the previous version of this plan called four symbols that a later
  task defined or that appeared nowhere, so the task could not be executed as written. No task may
  reference a symbol a later task introduces.

**The header/poll rule becomes exact, because the driver now knows its own hop count** (spec §4b):

> `hops == 1` → the cost header, if present, is complete. Use the existing `headerReported` path.
> `hops > 1` → **always poll**, regardless of any header, because a header can only ever describe
> the final hop.

Set `headerReported = false` whenever `hops > 1` so the existing `finally` polls. Do **not** add a
second poll path.

**Calling `request` from a test:** use the package-private `request(String base, String step)`
(house precedent: `ExploreCorrelationTest.java:88`). `request(String base, RequestLine r)` **does not
exist**, and **do not add it** — an overload deriving `label` from `r.format()` violates DD-036
twice, and `format()` canonicalizes a no-body GET, silently rewriting recorded finding text.

- [ ] Steps 1–5 as usual. The tests must include: a rotated `JSESSIONID` on a 302 reaching the next
      hop; a 302 that sets **no** cookie still carrying the pre-existing one (this discriminates
      where a "value survives" assertion does not); `HEAD` staying `HEAD`; 307 preserving method and
      body while 303-after-POST does not; and a non-redirect response behaving exactly as before.

---

### Task 4: Redirect policy — cross-origin, unparseable Location, loop detection

**Files:** `runner/coverage/CoverageGuidedRun.java`; extend `ExploreRedirectTest`

- [ ] Cross-origin refusal, a `crossOriginRedirects` counter, and a once-only warn naming the refused
      origin. **Surface the counter in the end-of-run summary** — Tasks 6 and 7 tell an operator to
      check it, so it must actually be printed. A target that renders redirects from a configured
      absolute base URL makes *every* redirect cross-origin, silently degrading this feature to its
      pre-DD-039 behaviour; a non-zero counter beside flat coverage is what makes that diagnosable.
- [ ] An unparseable `Location` ends the chain. **It must never throw** — the caller catches
      `Throwable` into `StatusReporter.recordCrash()`, so an escaping `URISyntaxException` files a
      false crash finding against the app with a stack pointing into driver code.
- [ ] Revisited-URL detection (a `LinkedHashSet` of resolved URLs) and the hop cap. Normalize both
      producers through `URI` before comparing — hop 0's key is built by concatenation and later keys
      by `URI.resolve`, so an unnormalized compare misses real loops.
- [ ] State whether "max 5 hops" means 5 total or 1 + 5 follows; `hop` is 0-based.

---

### Task 5: Per-hop records, summed cost, and the redirect-loop finding

**Files:** `runner/coverage/CoverageGuidedRun.java`; extend `ExploreRedirectTest`

- [ ] **One `Invariant-Remote` record per breaching hop**, each carrying `hop=<n>` and that hop's
      detail. A summed count with a single record reports "3 violations on POST /login" when 2 were
      the dashboard render — the multi-hop case this exists to capture is exactly the one a sum
      erases.
- [ ] **Delete the legacy single-record save** it replaces; leaving it double-records the final hop.
- [ ] **Never record a hop URL with its query string.** Hop 0's URL comes from the **substituted**
      RequestLine (DD-038 substitutes the path deliberately), so `GET /edit?csrf=${{tok}}` would
      write a live token to disk. Strip to `scheme://host:port/path`, and **add a test asserting a
      token substituted into the PATH never reaches disk** — the existing guard test
      (`ExploreCorrelationTest.correlatedInvariantFindingDoesNotPersistToken`) puts its token in the
      **body**, so it passes while the path leaks.
- [ ] Sum heap and thread deltas across hops. **Latency is not summed** — `latMs` is already client
      wall-clock around the whole call and has always spanned every hop.
- [ ] A redirect loop (revisited URL **or** the cap) saves a `"Redirect-Loop"` finding carrying the
      **raw step label** (DD-036) and the ordered hops. An app that redirects forever is unavailable,
      which is the oracle; silently scoring the last response discards a real finding.
- [ ] Meta files are **`.meta.txt`**, not `.meta` — a test filtering `.meta` matches zero files and
      fails *after a correct implementation*.
- [ ] Decide, in writing, whether the summed invariant count feeds `CostModel.score`. It changes
      corpus ranking, so it must be a decision, not an implementer's guess.
- [ ] Use `TriageSink`'s existing test-drain approach or the house helper
      `ExploreCorrelationTest.waitAndReadAll` — a naive queue-empty check is racy.

---

### Task 6: Record and document

- [ ] DD-039 record in `docs/DESIGN-DECISIONS.md`: the three defects (unreachable `Set-Cookie` on the
      3xx; the `Cookie` request header dropped on rewritten hops, so landing pages rendered
      anonymously; discarded intermediate-hop invariant counts), the JDK probe evidence, the §4b
      accumulate design and why per-hop ids were rejected, and the explore/load cost divergence.
- [ ] `docs/how-it-works.html`, `runner/CHANGELOG.md` `[Unreleased]`, and an `ONBOARDING.md` note on
      the cross-origin trap.

---

### Task 7: Prove it — close DD-040's gap

- [ ] Rebuild and load the agent + runner images; point the controller at them.
- [ ] **THE acceptance test.** A Roller explore campaign's reported violation count against the
      `[Basquin][Invariant]` count in the target pod's log for the same window. **DD-040 measured
      1,413 vs 1,602 — a gap of 189 (11.8%).** That gap must substantially close. Account for
      kubelet probe noise explicitly (~12/min heapDelta at idle) rather than ignoring it.
- [ ] **Assert on database rows, not coverage:** query `roller_entry` for rows written by
      `login_publish`, which has never written one. Coverage can rise for unrelated reasons.
- [ ] Confirm `crossOriginRedirects` is 0 for this target and `reportMisses` stays low.
- [ ] **Commit the evidence under `bench-results/`** — a figure without an artifact is the thing this
      project keeps getting wrong. Reuse `deploy/bench/collect.py`'s `_redact`.
- [ ] **If the counts still disagree materially, or no authenticated write lands, STOP and report.**
      DD-040's Task 7 did exactly this and was right to.

---

### Task 8: Wire the drift guards into CI (folded in from PR #93)

- [ ] Add a CI step running `python3 deploy/bench/check_claims.py` and `deploy/bench/test_redact.py`.
- [ ] Add the `X-Basquin-Token` case to `test_redact.py`'s `MUST_REDACT` set.
- [ ] Verify by introducing a deliberate misquote, confirming CI fails, then reverting.

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
