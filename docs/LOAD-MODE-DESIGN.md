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
existing campaigns are unaffected. It joins the driver-spec hash, so flipping mode reruns (DD-025 §7c).

CEL: `load` requires a `corpusConfigMap` and forbids `iterations`/`grammarConfigMap` (a load run isn't
bounded by iterations and doesn't explore); `explore` keeps today's iterations-XOR-duration rule.

## 3. Producer — persist the interesting corpus (PR 1)

An `explore` run accumulates, in `CoverageGuidedRun`, the inputs that reached new coverage. Two halves:

- **Runner:** at end-of-run, write the interesting corpus to a file (one input per line) at a known
  path (e.g. `-Dclosurejvm.corpus.out=/closurejvm-out/corpus.txt`), alongside the existing summary.
  This is the same in-memory `corpus` list the run already keeps; we just serialize it.
- **Operator:** after the driver Job succeeds, read that file back (via a shared `emptyDir` +
  a tiny sidecar/`kubectl cp`-equivalent, or — simpler — the driver writes it into an
  operator-created **output ConfigMap** it has RBAC to patch) and expose `status.corpusConfigMap`
  pointing at a ConfigMap owner-ref'd to the campaign. The map is keyed like any corpus
  (`corpus.txt`), so **load mode consumes it through the existing corpus-ConfigMap mount + `corpusDir`
  path** — no new consumption code.

Independently useful beyond load mode: reproducibility (re-run exactly what was found) and the
**dashboard corpus view** (TODO, user request) — the dashboard can render `status.corpusConfigMap`.

> **Open (PR 1):** the read-back path. Options — (a) driver writes directly to a pre-created ConfigMap
> via the in-cluster API (needs a token/RBAC for the driver pod); (b) shared `emptyDir` + the operator
> reads it from the pod before GC (bounded by the ~1 MB ConfigMap limit either way); (c) cap the
> emitted corpus to the top-N-by-coverage inputs to stay well under 1 MB. Leaning (b)+(c).

## 4. Consumer — load mode (PR 2)

- **Runner:** a load driver (either a `-Dclosurejvm.mode=load` branch in the coverage runner or a
  small dedicated `runner.load.LoadRun`) that reads the corpus, then fires requests from a fixed-size
  worker pool (`concurrency`) in a tight loop until the deadline — **no mutation, no coverage fetch**
  (that's exploration overhead). Every request still passes through the invariant oracles and
  `StatusReporter`, so latency/heap/thread violations are recorded exactly as in explore mode.
- **Load metrics** (distinct from exploration's coverage %/corpus growth): throughput (req/s),
  latency percentiles under sustained load (p50/p90/p99/max), and heap/thread **drift over the soak**
  (start vs end, to catch slow leaks). Surfaced in a new `status.load` block and pushed to the
  dashboard.

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

- CRD: `spec.mode` (enum, default explore), `driver.concurrency` (load), `status.corpusConfigMap`,
  `status.load`. CEL rules per §2.
- `buildDriverJob`: pass `-Dclosurejvm.mode`, `-Dclosurejvm.concurrency`, `-Dclosurejvm.corpus.out`;
  in load mode, skip the grammar volume, keep the corpus volume.
- Reconciler: on an explore Job's success, capture the corpus → ConfigMap → `status.corpusConfigMap`.
- Runner: `-Dclosurejvm.corpus.out` write-out (PR 1); the load loop + load metrics (PR 2).

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
