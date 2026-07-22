# Load / soak mode — design proposal (DD-026)

**Status:** **accepted and implemented (DD-026)** — shipped as two PRs (producer: corpus persisted
to a ConfigMap; consumer: `runner.coverage.LoadRun` + `spec.mode: load`) behind a `spec.mode` field
on the existing `BasquinCampaign`. Extends [CAMPAIGN-DESIGN.md](CAMPAIGN-DESIGN.md) (DD-025). §9 below
covers **DD-035** ("honest load"): corpus format v2 (method/session/sequence-aware replay) and the
`driftUnavailable` signal, which replaced PR 2's method-unaware replay + silent-zero-drift behavior.

Two crux decisions are **already settled** (user, 2026-07-20):
- **A `mode: explore | load` field on `BasquinCampaign`** — *not* a separate `BasquinLoadTest`
  CRD. Fuzz and load differ in *objective*, not *infrastructure*: both drive HTTP at an instrumented
  target and watch the invariant oracles. Reusing the campaign machinery (target-gating, driver Job,
  dashboard, status, spec-hash rerun, the CLI) is far less code than a parallel controller (§8).
- **The interesting corpus is persisted as a ConfigMap** the campaign emits (`status.corpusConfigMap`),
  consumed by load mode via the *same* corpus-ConfigMap path that already exists (DD-018, wired in the
  corpus PR). K8s-native, ~1 MB is ample for interesting HTTP inputs (route strings). PVCs were
  considered and rejected for the first cut (§8).

---

## 1. The ask

A coverage-guided run *discovers* the interesting states of an app — the inputs that reached new code,
happy paths, deep lookups. Today that corpus lives only in the driver's memory and vanishes on
teardown. **Load/soak mode turns that discovery into a stress test:** persist the interesting corpus,
then replay it at high volume/concurrency for a duration, watching the *same* invariant oracles
(latency budget, heap-delta, thread/leak) hold under sustained load.

> **Fuzz to discover the interesting states, then hammer those states under load.**

This is the natural second half of the tool: exploration finds *where* the app does real work; load
mode asks *does it stay healthy when that work happens a thousand times a second*.

## 2. The `mode` field

```yaml
kind: BasquinCampaign
spec:
  mode: explore            # explore (default) | load
  targetRef: { name: jpetstore }
  baseURL: http://jpetstore-app...:8080
  driver:
    duration: 30m
    # explore-only: grammar/corpus inputs, iterations
    grammarConfigMap: jpetstore-grammar
    corpusConfigMap:  jpetstore-corpus
    # load-only:
    concurrency: 50        # parallel in-flight requests (load mode)
```

- **`mode: explore`** (default) — today's behavior, unchanged. Coverage-guided exploration; on
  completion it now also **emits its interesting corpus** (§3).
- **`mode: load`** — replay a saved corpus (`driver.corpusConfigMap`) at `driver.concurrency` for
  `driver.duration`, no mutation/coverage-guidance. Reports load metrics (§4).

`mode` is a `+kubebuilder:validation:Enum=explore;load` with `+kubebuilder:default=explore`, so
existing campaigns are unaffected. It joins the driver-spec hash (verified: `driverSpecHash` hashes the
whole `CampaignDriverSpec`, so new fields auto-join with no special-casing), so flipping mode reruns
(DD-025 §7c).

**CEL** (this is the *first* multi-field CEL on the campaign — only the iterations-XOR-duration rule
exists today, so this is new ground, spelled out concretely rather than left prose):

```
// on BasquinCampaignSpec:
rule: "self.mode != 'load' || (has(self.driver.corpusConfigMap) && size(self.driver.corpusConfigMap) > 0)"
  message: "mode: load requires driver.corpusConfigMap (the corpus to replay)"
rule: "self.mode != 'load' || !has(self.driver.grammarConfigMap)"
  message: "mode: load replays a fixed corpus and ignores a grammar; remove driver.grammarConfigMap"
```

`explore` keeps today's iterations-XOR-duration rule; `load` is duration-bounded (§8).

> **Semantic overload to name explicitly (review #32):** `driver.corpusConfigMap` means **different
> things** by mode. In `explore` (DD-018) its entries are mutation *seeds/values* — always run through
> `mutate()` before firing, there is no verbatim-replay path in the runner today. In `load` its entries
> are **verbatim, fully-formed requests replayed as-is**. Same field name, opposite semantics; CEL can
> enforce presence but not this meaning-shift. The producer (§3) closes the gap by emitting a corpus
> that is *already* in the verbatim-request shape (post-mutation route strings), so an emitted corpus
> fed back into `load` behaves correctly — but a hand-written explore-style value corpus pointed at
> `load` will not. Documented, not silently coupled.

## 3. Producer — persist the interesting corpus (PR 1)

An `explore` run accumulates, in `CoverageGuidedRun`, the inputs that reached new coverage.

- **Runner:** at end-of-run, serialize the interesting corpus to a file, one input per line
  (`-Dbasquin.corpus.out=...`). Crucially this is the **post-mutation, fully-formed route strings**
  the run actually fired (the `loadSeeds()`-shape, not DD-018's pre-mutation raw values) — the right
  shape for verbatim replay (§2). It's a *different artifact wearing the "corpus" name*; call it the
  *replay corpus* to avoid confusion with input corpora.
- **Operator:** capture that file into a ConfigMap owner-ref'd to the campaign and set
  `status.corpusConfigMap`. The ConfigMap's *mount* into a later load run reuses the existing
  corpus-volume + `-Dbasquin.corpusDir` mechanics — but see the honesty note below on what does
  **not** already exist.

Independently useful beyond load mode: reproducibility and the **dashboard corpus view** (TODO, user
request) — the dashboard can render `status.corpusConfigMap`.

> **Honest accounting of PR 1's real cost (review #32) — this is new plumbing, not reuse.** Neither a
> ConfigMap *write-back* path, the operator RBAC for it, nor any driver-pod identity exists today:
> - The driver summary is read via the **pod termination message + pod-list** (`campaign_resources.go`,
>   `basquincampaign_controller.go`), **not** a ConfigMap. There is no write-back path for *any*
>   artifact. The termination message is capped at **~4 KiB** — fine for a one-line summary, too small
>   for a corpus file. So "reuse the results ConfigMap" is a non-starter: it was never built.
> - The operator's Role grants `configmaps: get;list;watch` only — **no create/patch/update**. Emitting
>   a ConfigMap needs a new grant.
> - The driver Job runs with **no ServiceAccount** (namespace `default`, zero API access). Giving the
>   driver process credentials to write a ConfigMap would be the **first** driver-pod API identity — a
>   real new trust boundary (a buggy/compromised driver would gain namespace write access).
>
> **Recommendation:** (c) **cap the replay corpus to top-N-by-coverage** (bounds it well under 1 MiB;
> no RBAC question) is the only genuinely low-risk piece. For transport, prefer a **write-back sidecar
> with a tightly-scoped Role** (this Job's own SA; `configmaps: create;update` on the *single named*
> output object) over granting the driver process itself credentials — the driver stays credential-less.
> This is a documented PR-1 build item, not a free reuse. (The earlier "read from the pod before GC"
> idea rested on a GC race that doesn't exist — driver pods persist until the owner-ref cascade — so it
> isn't simpler; it still needs a sidecar/identity.)

## 4. Consumer — load mode (PR 2)

- **Runner:** a load driver (either a `-Dbasquin.mode=load` branch in the coverage runner or a
  small dedicated `runner.load.LoadRun`) that reads the corpus, then fires requests from a fixed-size
  worker pool (`concurrency`) in a tight loop until the deadline — **no mutation, no coverage fetch**
  (that's exploration overhead). Every request still passes through the invariant oracles and
  `StatusReporter`, so latency/heap/thread violations are recorded exactly as in explore mode.
- **Load metrics** (distinct from exploration's coverage %/corpus growth): throughput (req/s),
  latency percentiles under sustained load (p50/p90/p99/max), and heap/thread **drift over the soak**.
  Surfaced in a new `status.load` block and pushed to the dashboard.

**Soak-test mechanics that must be in PR 2 (review #32), not just "fire N workers":**
- **Warmup/ramp** — firing `concurrency` workers at t=0 conflates JIT + connection-pool warmup with
  real latency, and can trip a *false* latency violation at the start. Exclude a short warmup window
  from the percentile calculation (and from invariant enforcement), configurable via
  `driver.warmup` (e.g. `30s`). Fixed concurrency for the rest of the run (no ramp-*curve*) is an
  accepted first-cut simplification, documented as such.
- **Connection reuse / keepalive** — confirm the runner's HTTP layer pools connections across the
  worker pool. A soak that opens a new connection per request mostly measures TCP/TLS handshake cost,
  not the app's steady state. (There is no pooled HTTP-client abstraction in `runner/` today to point
  at — PR 2 must establish one or verify keep-alive on the existing client.)
- **Periodic heap/thread sampling** — `heapDriftKb`/`threadDrift` as a single start-vs-end delta (§5)
  misses non-monotonic patterns (a leak that plateaus, GC noise at sample time). Sample a handful of
  points across the soak, report the trend, not just the endpoints.

## 5. Status shape

```yaml
status:
  phase: Completed
  # explore emits:
  coveragePct: "22.5"
  findings: 44
  corpusConfigMap: nightly-corpus-out     # <- the persisted interesting corpus (PR 1)
  # load emits:
  load:
    requests: 1284003
    throughputRps: 713.4
    latencyMs: { p50: 8, p90: 22, p99: 61, max: 240 }
    heapDriftKb: 1840                      # end - start
    threadDrift: 0
    violations: { latency: 12, heap: 0, thread: 0 }
```

## 6. Phased plan

- [x] **PR 0 — this design note (DD-026).**
- [x] **PR 1 — producer.** **Chosen transport: reuse the termination-message channel, not a sidecar.**
  The driver splices a byte-capped (~3 KB, ≈top-N) `replayCorpus` array into the summary it already
  writes to `/dev/termination-log`; the operator (which now has `configmaps: create;update`) reads it
  back and materializes a campaign-owned `<campaign>-corpus-out` ConfigMap, setting
  `status.corpusConfigMap`. This keeps the driver **credential-less** (no new pod SA/identity — the
  costly path §3 flagged) and the top-N cap is the right load-replay semantics anyway. Trade-off: the
  emitted corpus is bounded to what fits the ~4 KiB termination message; a larger transport
  (sidecar/PVC) is a future extension if corpora need to be bigger.
- [x] **PR 2 — consumer.** `spec.mode: explore|load` + `driver.concurrency`/`driver.warmup` (+ CEL);
  `runner.coverage.LoadRun` replays the corpus from a fixed worker pool (HttpURLConnection keep-alive,
  warmup window excluded), reporting throughput + latency percentiles (histogram) + heap/thread drift
  → `status.load`. Operator: load-mode driver Job is coverage-free (no coverage props / extract-verify
  initContainers); CLI gains `--mode load --concurrency --warmup`. envtest + in-cluster e2e (replay the
  emitted corpus → Completed with load metrics). Deferred to a follow-up: periodic (vs start/end) drift
  sampling; target-side (vs driver-side) heap/thread; a configurable concurrency ramp.

Each is a review-gated PR with envtest + an in-cluster e2e slice (an explore run emits a corpus; a
follow-on load run replays it and reports throughput/latency/drift).

## 7. Reconciler + runner touch-points

- CRD: `spec.mode` (enum, default explore), `driver.concurrency` + `driver.warmup` (load),
  `status.corpusConfigMap`, `status.load`. CEL rules per §2.
- `buildDriverJob`: pass `-Dbasquin.mode`, `-Dbasquin.concurrency`, `-Dbasquin.warmup`,
  `-Dbasquin.corpus.out`; in load mode skip the grammar volume, keep the corpus volume.
- **New RBAC + identity (PR 1):** a `configmaps: create;update` grant, and a write-back **sidecar**
  with a Job-scoped SA that writes the replay corpus to the single named output ConfigMap (the driver
  container stays credential-less — §3).
- Reconciler: on an explore Job's success, set `status.corpusConfigMap` (owner-ref'd to the campaign).
- Runner: `-Dbasquin.corpus.out` write-out of the post-mutation route strings (PR 1); the load loop
  with a pooled/keep-alive HTTP client, warmup exclusion, and periodic drift sampling (PR 2).

## 8. Alternatives considered

- **Separate `BasquinLoadTest` CRD** — cleaner status separation, but duplicates target-gating, the
  driver Job, the dashboard, GC, and the CLI in a second controller. Rejected: the shared machinery is
  the bulk of the value, and a `mode` field expresses "same infra, different objective" honestly.
- **PVC for the corpus** — handles very large corpora, but adds StorageClass/PVC lifecycle + RWX-vs-RWO
  decisions and ties a run to a volume. Rejected for the first cut; the interesting corpus is small
  (route strings), and a ConfigMap reuses the existing consumption path. Revisit if corpora outgrow
  ~1 MB.
- **Load bound by iterations instead of duration** — soak testing is inherently time-based (watch
  drift *over time*); `concurrency` × `duration` is the natural knob. `iterations` stays explore-only.

## 9. Corpus format v2 + drift honesty (DD-035)

PR 2 shipped a **method-unaware** replay: every corpus line was fired as a bare `GET <line>`, no body,
no cookies. Against jpetstore that meant every `POST /actions/...` route the explore run had discovered
(logins, cart mutations, checkout) replayed under load as a `GET` — mostly 404/405/redirect-loop noise,
not the real endpoint. Enough of that traffic under `concurrency: 50` **saturated the target** (5xx +
timeouts), which in turn **starved the `/__basquin/drift` poller** (the same HTTP client competing for
the target's request-handling threads), and the driver's `driftDelta()` silently degraded a missing
poll to a zero delta — reporting `heapDriftKb:0` for a run whose heap was, in truth, never actually
sampled. A fabricated "no drift" is worse than no number at all. **DD-035 fixes both ends**: the replay
itself becomes honest (method, body, session), and the reported numbers become honest about when a
measurement didn't happen. See DD-035 in [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md) for the full
record; this section is the corpus-format reference.

**Corpus format v2** (`RequestLine`/`RequestLine.parseSequence`), backward-compatible with every v1
corpus already on disk or in a ConfigMap:

- **A step** is `METHOD? path( SP body )?` — an optional HTTP method token (`GET`, `POST`, `PUT`,
  `DELETE`, `PATCH`, `HEAD`; first space-delimited token, else it's not a method and the whole line is
  treated as a bare path), then the path, then an optional space-separated body (form-encoded, sent
  with `Content-Type: application/x-www-form-urlencoded` when present). A bare `/actions/foo` (no
  method token) is still `GET /actions/foo` — **v1 lines parse identically under v2**.
- **A line** is either **one step** or a **TAB-separated sequence of steps**, replayed **in order** as
  one logical user flow (e.g. `POST /actions/Account.action?signon= u=j2ee\tGET /actions/Cart.action`).
  TAB is the separator specifically because it can't appear in a URL or an urlencoded form body, so it
  never collides with real request content.
- **Corpus-file filtering** (`readCorpus`) keeps a line iff its **first step's** path starts with `/`
  (`RequestLine.firstPath`) — this is what lets a v2 sequence whose line literally starts with `POST `
  still get recognized as a route line (not a grammar value file, e.g. `values/keyword.txt`'s one bare
  token per line), without a naive `line.startsWith("/")` incorrectly dropping it.

**Whole-sequence replay.** A worker picks a random sequence from the corpus and fires **every step in
order**, not one route at a time — the unit of replay is the flow, not the request. A step is skipped
only if the run's deadline arrives mid-sequence (checked before each step, not just once per outer
loop), so a worker never overshoots the configured duration mid-flow.

**Per-worker session.** Each load worker owns one cookie jar (`Map<String,String>`, keyed by cookie
name — `JSESSIONID` in practice) for its **entire run**: the `Set-Cookie` a step 1 login response
sends is captured and replayed as the `Cookie` header on every later step of every later sequence that
worker fires, so an authenticated flow (login → cart → checkout) actually replays authenticated, the
way a real session would exercise it. Sessions are **not shared across workers** — one cookie jar per
worker thread, deliberately, so the discovered corpus explores as many independent sessions as
`concurrency` allows rather than serializing on one.

**The `driftUnavailable` signal.** `status.load`/the terminal `[Basquin] load done: {...}` JSON now
carries either a real `heapDriftKb`/`threadDrift` pair, **or** `"driftUnavailable":true` — never a
fabricated `heapDriftKb:0` standing in for "couldn't measure it." `driftUnavailable` is `true` whenever
ANY of: the baseline drift poll never landed, the terminal/current drift poll came back null, or the
target never explicitly confirmed load mode (`ok:load` from `/__basquin/mode`) — i.e. any condition
under which the reported delta would otherwise silently be `(0, 0)`. When `driftUnavailable` is
present, `heapDriftKb`/`threadDrift` are **omitted from the JSON entirely** (not printed as `0`) so a
dashboard/CLI reading the summary can't mistake "unavailable" for "flat."

## 10. Corpus format v3 — response correlation (DD-036)

DD-035's replay is method-, session-, and sequence-aware, but every value in a corpus line is still
fixed at emit time — it can capture nothing from a response and use it in a later request, so a write
path guarded by a per-session token (JSPWiki's `X-XSRF-TOKEN`, JPetStore Stripes' `_sourcePage`) always
replays with a dead token and is rejected. **Corpus format v3** adds an optional trailing capture suffix
to a step, `<<name=kind:arg` (`kind` is `header:<HeaderName>` or `input:<inputName>`, extracted via a
targeted regex over `<input>` tags rather than a full HTML parser), and lets a later step's body
reference the captured value as `${{name}}`, substituted URL-encoded at fire time from a bindings map
scoped to that one sequence execution. v3 is backward-compatible with v1/v2: a line with no `<<`/`${{}}`
parses and fires exactly as before. The corpus stores only the capture recipe, never the token itself —
tokens live only in the per-execution bindings map and never touch disk. Two counters in the terminal
summary keep this honest: `captureMisses` (a reference that never got bound) and `clientErrors` (4xx
responses), so a broken correlation shows up as a number instead of silently degrading to a no-op. See
DD-036 in [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md) for the full record, including the rejected
alternatives (a full HTML parser, JSON-lines, recording live tokens, automatic CSRF forwarding,
capture-once caching across executions).
