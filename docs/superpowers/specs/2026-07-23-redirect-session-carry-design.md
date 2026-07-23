# DD-039 — Session carry across redirects in explore mode

**Status:** revised after spec review (empirical JDK probe; see "Evidence")
**Date:** 2026-07-23
**Motivating evidence:** Apache Roller onboarding (`deploy/bench/roller/README.md`), where it was
reproduced live: `JSESSIONID 5E9F79…` before login, `ADA9D5…` issued **on the 302**, and every
subsequent sequence step running anonymous.

## Problem

This is **three** defects, not one. All three come from the same line — explore mode delegating
redirects to `HttpURLConnection` at `CoverageGuidedRun.java:605`:

```java
c.setInstanceFollowRedirects(true);
if (sessionCookie != null) c.setRequestProperty("Cookie", sessionCookie);
...
int code = c.getResponseCode();
for (int i = 0; ; i++) { ... }          // headers of the FINAL response only
```

**1. A `Set-Cookie` on the intermediate 3xx is unreachable.** When the JDK follows a redirect
itself, `followRedirect0` discards the response headers before re-issuing, so neither the indexed
walk at `:619-627` nor `getHeaderFields()` can see them. There is no system property and no index
quirk that recovers it. That is exactly where a large class of frameworks put the new session:
Spring Security's form login answers `POST /login` with a `302` **and issues the rotated
`JSESSIONID` on that 302**, a deliberate session-fixation defence. The redirect target sets no
cookie at all, because the server considers the client already told. The driver therefore keeps the
pre-login session forever.

**2. The `Cookie` *request* header is dropped on every method-rewritten hop.** Probed: on a
301/302/303 the JDK issues the follow hop with **no `Cookie` header at all** — the value set via
`setRequestProperty` is not carried. (A 307 *does* preserve it; it is specifically the
method-rewriting path that strips it.) So the landing page has been rendering **anonymously all
along**, even for a session that never rotated. Every explore step that redirects has been scoring
coverage against a logged-out page.

**3. The intermediate hop's `X-Basquin-Invariant-Count` and `X-Basquin-Cost` are discarded.** Same
mechanism: only the final hop's headers survive, so an invariant breach on a redirecting hop is
silently thrown away at `:628-635`. A login POST that redirects to a 900 ms dashboard render
reports nothing.

The downstream symptom of (1) is honest but misleading: later steps log
`unresolved correlation ref … step skipped`, because an anonymous request to an authenticated form
is itself redirected to login and has no token to capture. Nothing reports "you never logged in" —
the run simply explores less and looks fine.

**Blast radius.** Every Spring Security form-login app, plus anything else that rotates a session on
authentication (the recommended practice). Roller is the app that surfaced it, not the app that has
it.

**Why load mode is already fine.** DD-038 made load mode stop following redirects and read
`Set-Cookie` off the direct 3xx before any follow (`LoadRun.fireR`). This is the explore-mode half
of the same problem.

## Evidence

A JDK 17 probe against a local `HttpServer`, recording what the server actually received:

```
TEST A  POST /login -> 302 -> /landing
  HOP1  POST ct=application/x-www-form-urlencoded len=7 cookie=JSESSIONID=ANON body=u=a&p=b
  HOP2  GET  ct=null len=null                          cookie=null                body=
TEST B  307
  HOP2  POST ct=application/x-www-form-urlencoded      cookie=JSESSIONID=ANON     body=u=a&p=b
TEST D  auto-follow with a CookieManager installed
  HOP2  cookie=JSESSIONID=ROTATED;JSESSIONID=ANON
```

TEST A establishes defects 1 and 2 and shows the JDK already does POST→GET on a 302. TEST B shows
307 already preserves method, body, and cookie. TEST D is why `CookieHandler` is not the fix.

## Non-goals

- Cookie-jar generality (paths, domains, `Secure`/`SameSite`, multiple cookies). The driver tracks
  one session cookie; nothing in evidence needs more.
- Changing load mode. It is correct already.
- Changing explore's `sessionCookie` sharing model. It is a single `static volatile` field
  (`CoverageGuidedRun.java:480`) and the explore loop is **single-threaded**; sharing it across
  sequences within an epoch is intentional (DD-018). There is no concurrency bug here, and this
  change must not invent one to fix.
- Reporting redirect counters in explore. Those are load-mode output (DD-038).

## Design

Explore stops delegating redirects and follows them itself.

### 1. Manual follow

`setInstanceFollowRedirects(false)`, then loop while the status is in `[300,400)` and a `Location`
is present. Each hop:

1. captures `Set-Cookie: JSESSIONID=…` if present, updating `sessionCookie` **before** the next hop
   is issued;
2. records the hop's `X-Basquin-Invariant-Count`/`-Detail` (see §4) and accumulates its
   `X-Basquin-Cost` heap and thread deltas;
3. **reads the hop's response stream to EOF and discards it**, so the connection returns to the
   keep-alive pool. Tomcat's `sendRedirect` emits a boilerplate HTML body; abandoning it unread
   leaks sockets at explore's iteration rate and silently inflates measured latency, which is a
   cost-model input. `LoadRun` drains for exactly this reason (`LoadRun.java:396-399`);
4. issues the next request, explicitly setting the `Cookie` header from the current
   `sessionCookie` — the JDK will not carry it (defect 2).

The response code and body attributed to the input are the final hop's.

### 2. Bounds and loop detection

A redirect loop is **a finding, not a nuisance**. An app that redirects indefinitely is unavailable
— a browser surfaces it as `ERR_TOO_MANY_REDIRECTS` — and this tool's oracle is availability.
Silently scoring the 5th response as a normal iteration would discard a genuine finding and feed a
meaningless response into the cost model and the corpus.

- Track resolved URLs in a small `LinkedHashSet`. A **revisited URL** ends the chain. The hop cap
  alone is a poor detector: an auth-failure bounce (`/protected → /login → /protected`) is a real
  loop that never reaches 5 hops.
- **Max 5 hops** as a backstop.
- On either condition, save a `"Redirect-Loop"` finding through the existing `FuzzIO.saveWithMeta`
  path, carrying the **raw step label** (DD-036: findings are labelled with the recipe, never a
  substituted line) and the ordered hop URLs.
- **Same-origin only.** Resolve `Location` against the request URL and refuse to leave the target's
  scheme/host/port — comparing host case-insensitively and normalizing an absent port against the
  scheme default, since `URI.getPort()` returns `-1` and `http://svc/foo` must equal a base of
  `http://svc:80`. A fuzzed input inducing an open redirect must not turn the explorer into a client
  for another server, or attribute another host's cost to the app.
- A refusal is **counted and surfaced** (`crossOriginRedirects`, plus a one-line warn naming the
  refused origin on first occurrence). Many apps render redirects from a *configured* absolute base
  URL; if that value differs from the Service DNS the driver dials, every redirect is cross-origin
  and DD-039 silently degrades to today's behaviour — indistinguishable from the feature working. A
  non-zero counter beside flat coverage is immediately diagnosable. (Checked: the motivating app is
  safe — `deploy/bench/roller/setup.sh:116` seeds `site.absoluteurl` empty so Roller derives it from
  the request. This is a generic onboarding trap, worth a line in the onboarding doc.)
- **A `Location` that will not parse ends the chain**, and the 3xx becomes the final response.
  `URI` throws on unencoded spaces, `{}`, `|`, `^` — exactly the bytes a fuzzer reflects into a
  query string. `request(...)` is `throws Exception` and its caller (`:301-303`) catches `Throwable`
  into `StatusReporter.recordCrash()` + `FuzzIO.saveInteresting`, so an unguarded parse would file a
  **false crash finding against the app**, carrying a stack that points into driver code. Catch
  `URISyntaxException | IllegalArgumentException` and log once.

### 3. Method and body on the follow

This **preserves today's behaviour** rather than choosing new behaviour — the JDK already rewrites
301/302/303 to `GET` and already preserves 307/308 (Evidence, TESTs A and B). Deviating would be the
change, and would need its own justification.

- `301`/`302`/`303` → `GET` **when the original method carried a body** (POST/PUT/PATCH); the body
  and its `Content-Type` are dropped together. Carrying `application/x-www-form-urlencoded` (set at
  `:612`) onto a bodyless GET makes some filters attempt form parsing on an empty stream.
- Otherwise the method is **preserved** — notably `HEAD`, which a grammar can express and which must
  not silently become a `GET`; that would pull a full body the caller never asked for, inflating the
  input's measured latency and heap and corrupting its cost rank.
- `307`/`308` → preserve method and body, which is the entire reason those codes exist.

Why not re-POST to the redirect target, even though it is genuinely an interesting code path: it
would **double every write**. Roller's verified run wrote 16 comments with distinct nonces; an
implicit re-POST makes that 32 rows from 16 inputs, breaking the DD-038 `<nonce>` one-row-per-fire
accounting. An author who wants that can express it as an explicit second step, where it is visible
and countable.

### 4. Invariant and cost accounting

- **One `Invariant-Remote` record per breaching hop**, not one summed record.
  `X-Basquin-Invariant-Detail` is per-hop (`RequestBoundary.exitHeaders` emits that request's own
  violation), so summing counts while persisting a single record yields a finding reading `count=3`
  carrying one arbitrary hop's detail. A triager would read "3 violations on `POST /login`" when the
  truth is "1 on the login POST, 2 on the dashboard render" — the multi-hop case this section exists
  to capture is exactly the one whose identity a sum erases. Each record keeps the raw step label
  plus `hop=<n>` and that hop's resolved URL and detail.
- **Heap and thread deltas are summed** from `X-Basquin-Cost` across hops — the input's total cost.
- **Latency is not summed and needs no change.** `latMs` is already client wall-clock around the
  whole `request()` call (`:293-296`), which under auto-follow has always spanned every hop, and
  `X-Basquin-Cost`'s latency field is parsed then discarded (`:637-644`). Today's cost score
  therefore mixes an all-hops latency with a final-hop-only heap/thread reading; summing heap and
  thread is what makes it coherent.
- **The 25 ms latency budget is unaffected.** It is evaluated **server-side, per HTTP request**,
  inside the valve (`RequestBoundary.onExit` → `Agent.endIteration` →
  `Invariants.evaluateAndMaybeFail`) against that one request's own in-app time; the driver only
  reads a count off a header. A three-hop chain produces three independent per-hop evaluations.
  There is no client-side sum compared against a per-request budget, and so no false-positive
  mechanism.

### 5. Capture semantics across hops

Captures (DD-036/037) run against the **final** hop's body, unchanged. A token rendered on the page
you land on is the token you want; 3xx bodies are empty or boilerplate.

The session cookie is the deliberate exception — captured on *every* hop, because it is a transport
concern, not a document concern.

## Consequences

**Cross-engine cost divergence**, recorded here because neither DD-038 nor DD-039 says it otherwise.
The replay corpus is explore's output and load's input (DD-035). After this change explore ranks an
input by its summed multi-hop cost, while load replays that same input with
`setInstanceFollowRedirects(false)` and fires exactly one hop (DD-038, deliberately). A login POST
ranked expensive on the strength of its dashboard hop is, under load, a cheap 302. The ranking is a
heuristic and nothing breaks, but the corpus entry should record its hop count so the next person
reading a cost-ranked corpus knows the number is an explore-side measurement.

## Verification

Unit tests against a local `HttpServer` (the existing `LoadCorrelationTest` pattern). No existing
test asserts explore's follow behaviour — `ExploreCorrelationTest` never redirects — so there is no
test churn to plan for.

1. **The motivating case.** `POST /login` → `302` carrying `Set-Cookie: JSESSIONID=rotated`, whose
   target asserts it received `Cookie: JSESSIONID=rotated`. Fails on today's code.
2. **Defect 2 in isolation.** A `302` that sets **no** cookie: the target must still receive the
   pre-existing `Cookie`. Fails on today's code — and unlike a "the value survives to the next step"
   assertion, it actually discriminates.
3. A 3-hop chain, each hop rotating, ends with the last value.
4. **A redirect loop produces a `"Redirect-Loop"` finding** carrying the raw step label — asserted
   on the finding, not merely on termination. Covers both a revisited URL at 2 hops and the 5-hop
   cap.
5. A malformed `Location` ends the chain and does **not** produce a crash finding.
6. A cross-origin `Location` is not followed, and increments `crossOriginRedirects`.
7. A relative `Location` resolves against the request URL; `http://svc/x` matches a base of
   `http://svc:80`.
8. `307` preserves method and body; `303` after a POST does not; **`HEAD` stays `HEAD`**.
9. Two breaching hops produce **two** `Invariant-Remote` records with distinct `hop=` values and
   details, each labelled with the raw step.
10. Intermediate hops are drained — the connection is reused rather than leaked.
11. A non-redirect response behaves exactly as before.

Plus: a Roller explore campaign reaching an authenticated write path, evidenced by **rows in the
database**, not by a coverage number — coverage can rise for unrelated reasons.

## Rejected alternatives

- **Keep auto-follow and install a `CookieHandler`/`CookieManager`.** Demonstrably broken, not
  merely inelegant: with a manager installed, the handler's cookie *and* the manually-set `Cookie`
  request property are both applied, producing a duplicate-name header
  (`JSESSIONID=ROTATED;JSESSIONID=ANON`, Evidence TEST D) whose resolution is container-dependent.
  It is also process-global state in a driver that should not have any.
- **Follow only after a POST.** The rotation is a property of the response, not the method — a GET
  to a protected page can also 302 to login and rotate. The hole would surface only as "explores
  less than it should".
- **Do nothing; require grammars to avoid login redirects.** Pushes an HTTP-client bug onto every
  grammar author, and cannot be expressed anyway — the grammar cannot see the 302.
- **Adopt load mode's no-follow exactly.** Explore would stop at the 302 and never reach the
  post-login page, losing the coverage that is its whole purpose. The engines want the same *cookie*
  handling and different *follow* handling: load mode measures redirects, explore traverses them.
