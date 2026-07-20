# Load / soak mode — design proposal (DD-026)

**Status:** proposed, under review. Not yet implemented. On approval this becomes **DD-026** and lands
as two implementation PRs (producer, then consumer) behind a `spec.mode` field on the existing
`ClosureJVMCampaign`. Extends [CAMPAIGN-DESIGN.md](CAMPAIGN-DESIGN.md) (DD-025).

Two crux decisions are **already settled** (user, 2026-07-20):
- **A `mode: explore | load` field on `ClosureJVMCampaign`** — *not* a separate `ClosureJVMLoadTest`
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
kind: ClosureJVMCampaign
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
// on ClosureJVMCampaignSpec:
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
  (`-Dclosurejvm.corpus.out=...`). Crucially this is the **post-mutation, fully-formed route strings**
  the run actually fired (the `loadSeeds()`-shape, not DD-018's pre-mutation raw values) — the right
  shape for verbatim replay (§2). It's a *different artifact wearing the "corpus" name*; call it the
  *replay corpus* to avoid confusion with input corpora.
- **Operator:** capture that file into a ConfigMap owner-ref'd to the campaign and set
  `status.corpusConfigMap`. The ConfigMap's *mount* into a later load run reuses the existing
  corpus-volume + `-Dclosurejvm.corpusDir` mechanics — but see the honesty note below on what does
  **not** already exist.

Independently useful beyond load mode: reproducibility and the **dashboard corpus view** (TODO, user
request) — the dashboard can render `status.corpusConfigMap`.

> **Honest accounting of PR 1's real cost (review #32) — this is new plumbing, not reuse.** Neither a
> ConfigMap *write-back* path, the operator RBAC for it, nor any driver-pod identity exists today:
> - The driver summary is read via the **pod termination message + pod-list** (`campaign_resources.go`,
>   `closurejvmcampaign_controller.go`), **not** a ConfigMap. There is no write-back path for *any*
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

- **Runner:** a load driver (either a `-Dclosurejvm.mode=load` branch in the coverage runner or a
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
- [ ] **PR 1 — producer.** Runner writes the interesting corpus out; operator captures it into a
  campaign-owned ConfigMap and sets `status.corpusConfigMap`. Ships on its own (reproducibility +
  dashboard corpus view). Adds no `load` behavior yet.
- [ ] **PR 2 — consumer.** `mode: load` + `driver.concurrency`; the load driver; `status.load` metrics
  + dashboard wiring.

Each is a review-gated PR with envtest + an in-cluster e2e slice (an explore run emits a corpus; a
follow-on load run replays it and reports throughput/latency/drift).

## 7. Reconciler + runner touch-points

- CRD: `spec.mode` (enum, default explore), `driver.concurrency` + `driver.warmup` (load),
  `status.corpusConfigMap`, `status.load`. CEL rules per §2.
- `buildDriverJob`: pass `-Dclosurejvm.mode`, `-Dclosurejvm.concurrency`, `-Dclosurejvm.warmup`,
  `-Dclosurejvm.corpus.out`; in load mode skip the grammar volume, keep the corpus volume.
- **New RBAC + identity (PR 1):** a `configmaps: create;update` grant, and a write-back **sidecar**
  with a Job-scoped SA that writes the replay corpus to the single named output ConfigMap (the driver
  container stays credential-less — §3).
- Reconciler: on an explore Job's success, set `status.corpusConfigMap` (owner-ref'd to the campaign).
- Runner: `-Dclosurejvm.corpus.out` write-out of the post-mutation route strings (PR 1); the load loop
  with a pooled/keep-alive HTTP client, warmup exclusion, and periodic drift sampling (PR 2).

## 8. Alternatives considered

- **Separate `ClosureJVMLoadTest` CRD** — cleaner status separation, but duplicates target-gating, the
  driver Job, the dashboard, GC, and the CLI in a second controller. Rejected: the shared machinery is
  the bulk of the value, and a `mode` field expresses "same infra, different objective" honestly.
- **PVC for the corpus** — handles very large corpora, but adds StorageClass/PVC lifecycle + RWX-vs-RWO
  decisions and ties a run to a volume. Rejected for the first cut; the interesting corpus is small
  (route strings), and a ConfigMap reuses the existing consumption path. Revisit if corpora outgrow
  ~1 MB.
- **Load bound by iterations instead of duration** — soak testing is inherently time-based (watch
  drift *over time*); `concurrency` × `duration` is the natural knob. `iterations` stays explore-only.
