# Honest Load — Design Spec (DD-035)

**Status:** draft for review
**Date:** 2026-07-22
**Scope decision (user):** all four fixes ship together in ONE PR (task-by-task commits); Fix 3 uses **full-sequence replay**.

## Goal

Make Basquin's load-mode replay faithful and its measurement honest. Today the replay corpus is a list of bare GET paths; replaying JPetStore's form/stateful routes as sessionless GETs produces ~14% 5xx and a 30s-timeout tail that saturates the target — which in turn starves the drift poller so the heap-fatigue curve silently reports `0`. Four coupled fixes remove the whole chain.

## Background — the root-cause chain (from two investigations)

```
method-unaware replay  →  target saturates  →  drift poll times out  →  pollDrift()=null  →  heapDriftKb silently 0
   (form routes as GET)     (14% 5xx, 30s hangs)     (>3s poll budget)      (catch-all)         (looks like a real flat curve)
```

- The agent boundary + `/__basquin/drift` are **correct** (verified by live Tomcat repro and in-cluster: 200 in ~4ms idle). There is **no boundary bug**.
- The corpus is GET-only **by construction** — method exists nowhere upstream (grammar, explore issuer, `CorpusEntry`, emission, replay all assume bare `/`-paths). `CoverageGuidedRun.request()` hardcodes `setRequestMethod("GET")`.
- A coverage-finding *sequence* contributes only its **last step** to the replay corpus (`CoverageGuidedRun.java:199`), e.g. a bare `Order.action?newOrder=` with no signon/cart — guaranteed 5xx.
- `LoadRun.fire()` carries no `JSESSIONID` (explore's `request()` does).
- CI is blind to all of this: e2e asserts only client-side load outputs (`requests>0`, throughput non-empty), never that drift was sampled or the mode toggle succeeded.

## Corpus format v2

**One line = one sequence. Steps are TAB-separated. Each step is `METHOD path[ SP body]`.** A bare step (`/actions/...` with no method token) means `GET`. Bodies are URL-encoded `application/x-www-form-urlencoded` (no literal spaces or tabs, so tokenizing is unambiguous).

```
# 1-step GET sequence (backward-compatible with today's corpora / seed files)
/actions/Catalog.action

# explicit method
GET\t/actions/Catalog.action?viewCategory=&categoryId=FISH

# multi-step stateful sequence (TAB between steps), body as 3rd space-token
POST /actions/Account.action?signon SP username=j2ee&password=j2ee\tPOST /actions/Cart.action?addItemToCart=&workingItemId=EST-3\tPOST /actions/Order.action?newOrder=
```

- **Backward-compat:** an old bare `/…` line is a 1-step GET sequence → identical behavior. The `startsWith("/")` line filter (which excludes grammar value files like `values/keyword.txt`) generalizes to "first step's path starts with `/`", i.e. a line whose first TAB-field, after an optional `METHOD ` prefix, starts with `/`.
- **Byte budget:** the emitted replay corpus is spliced into the pod termination message (`TERMINATION_MSG_BUDGET=3900`, corpus cap `REPLAY_CORPUS_MAX_BYTES=3000`). Sequences cost more bytes; keep the existing byte-budget truncation in `replayCorpusJson()` and count whole sequences against it (drop lowest-cost sequences first, never emit a partial sequence).
- **Model:** new `runner/coverage/RequestLine.java` — `record RequestLine(String method, String path, String body)` with `parse(String)` / `format()`; and a `Sequence = List<RequestLine>` parsed from a TAB-split line. Everything (grammar, seeds, `CorpusEntry.input`, emission, load replay, explore seeds) consumes these two.

## Fix 1 — Method-aware replay

- `RequestLine` model (above) + unit tests (parse/format round-trip, GET default, body handling).
- **Grammar** (`RequestGrammar.java:82,105`): accept an optional `METHOD ` prefix on route templates and sequence steps; matching (`templateFor`) works with prefixes; `examples/grammar/jpetstore.grammar` marks the POST handlers (cart mutations, signon, newAccount/editAccount, newOrder).
- **Explore issuer** (`CoverageGuidedRun.request():478-530`): honor method + body (`setDoOutput`, write urlencoded body, `Content-Type: application/x-www-form-urlencoded`). `mutate()`/`replaceParam` unaffected (opaque strings).
- **Load replay** (`LoadRun.fire()`): honor method + body per step.
- **Seed files** (`examples/corpus/jpetstore/*.txt`): prefix POST routes.

## Fix 2 — Session-aware replay

- Each `LoadRun` worker owns a **cookie jar** (a `Map<String,String>` or a per-worker `CookieManager`): capture `Set-Cookie: JSESSIONID` from responses, send `Cookie` on subsequent requests. Mirrors `CoverageGuidedRun.request():484-497`.
- A worker's jar persists across the steps of a sequence and across sequences it runs (like a returning user), so stateful handlers behave.

## Fix 3 — Full-sequence replay

- **Emission** (`CoverageGuidedRun.java:199` / `writeSummary()`): when a coverage-finding *sequence* is kept, emit the WHOLE ordered sequence as one TAB-joined corpus line (not just the tail). Standalone finds remain 1-step lines. Cost-ranking (`CostCorpus.snapshotByCost`) ranks whole sequences.
- **Consumption** (`LoadRun`): parse each corpus line into a `Sequence`; a worker picks a random sequence and runs its steps **in order** with its cookie jar, then picks another. Metrics (hist, requests, serverError, drift baseline) record per **step** (so throughput/latency stay per-request, comparable to today).
- **Explore seeds** (`CoverageGuidedRun.loadSeeds():64-90`): parse the same v2 format so an emitted replay corpus round-trips as a seed corpus.

## Fix 4 — Silent-degradation hardening

- `LoadRun.setTargetMode()`: verify the response body is `ok:load`; record success/failure (don't just swallow).
- `LoadRun`: count failed drift polls; if the **baseline** poll never succeeded, emit `"driftUnavailable":true` in the summary JSON (and omit `heapDriftKb`/`threadDrift`, or mark them null) instead of a fake `0`. Log one clear line.
- Surface `driftUnavailable` into `status.load` (StatusReporter load block) so the dashboard/CLI can show "drift not captured" rather than a misleading flat 0.
- **e2e** (`deploy/e2e/e2e.sh`): during the load campaign assert (a) driver log has no `toggle failed`, (b) drift was actually sampled (the new flag is false), (c) `curl http://jpetstore-app.<ns>.svc:8080/__basquin/drift` over Service DNS returns 200 *while load mode is active*. Assert sampled-ness, not `>0` (GC can make drift ≤0 legitimately — observed -753MB in the clean run).
- Update the route-count grep `deploy/e2e/e2e.sh:386` (`grep -c '^/'`) for the v2 format.

## Files (from investigations)

Writers/readers: `runner/coverage/CoverageGuidedRun.java` (`request` ~478-530, `loadSeeds` 64-90, `writeSummary` 328-363, `replayCorpusJson` 370-391, sequence emit 199), `runner/coverage/LoadRun.java` (`readCorpus` 251-267, `fire` 168-191, worker loop 85-114, `setTargetMode` 238-248, `pollDrift` 225-235), `runner/coverage/CorpusEntry.java`, `runner/coverage/RequestGrammar.java` (82,105), `runner/util/StatusReporter.java` (load block). New: `runner/coverage/RequestLine.java`. Grammar/seeds: `examples/grammar/jpetstore.grammar`, `examples/corpus/jpetstore/*.txt`. Operator: **no Go changes** (`ReplayCorpus []string` + `\n`-join is format-agnostic); update test fixture strings only if they pin bare paths. e2e/docs: `deploy/e2e/e2e.sh` (386, load asserts), `docs/LOAD-MODE-DESIGN.md`, `docs/OPERATOR-USAGE.md`.

## Testing

- `RequestLine` parse/format unit tests (in `package runner.coverage`, matching convention).
- Sequence parse/replay unit tests (multi-step, TAB split, GET default, body).
- Method-aware `fire()` test (POST + body issued correctly) — Tomcat-free where possible.
- `driftUnavailable` test: baseline-poll-failure path emits the flag, not `0` (extend `LoadRunDriftTest`).
- e2e load assertions above.
- Keep fields package-private; tests live in-package (no visibility widening).

## Out of scope (v0.3.1 candidates)

- Serving `/__basquin/drift` off a dedicated port/executor (benchmark showed an *undersized-heap* target starves the shared pipeline; a healthy target does not — so this is a resilience nicety, not a correctness fix).
- A `/__basquin/health` version handler to distinguish a stub/stale agent jar from a live one.
- Richer per-step content-types / non-form bodies (v2 covers urlencoded form + GET, which is JPetStore/Stripes and virtually all servlet form apps).

## Benchmark note (parallel deliverable, not part of this PR)

Controlled 3-way (fresh load-mode target, c=50, 2m, same routes): **k6 10,856 rps / p50 2.2ms / p99 19ms**, **Locust(8 proc) 9,600 / 4 / 14**, **Basquin 6,848 / 1 / 34**, Locust(1 proc) 118 (GIL-bound). All converge to the target's capacity band → Basquin's engine is competitive, with the best p50, PLUS server-side heap-drift + invariant capture the others can't do. Report separately, both corpora side by side (clean vs method-unaware).
