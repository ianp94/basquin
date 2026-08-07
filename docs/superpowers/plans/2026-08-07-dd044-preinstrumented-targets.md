# DD-044 / PR-3.5 — implementation plan (pre-instrumented targets) — rev 2

Grounded in `.superpowers/sdd/dd044-spec-validation.md` (fable: spec READY, zero drift, toolchain OK)
and revised per fable's plan review (5 material + 3 minor issues, all folded in below). One cohesive
PR, task-by-task commits, authored by `basquin-bot`.

## Locked decisions
- **§4 multi-replica condition** (fable endorsed the shape; only the lifecycle needed fixing):
  Type `ReplicaConfigSupported`, positive-polarity. On the observe path ALWAYS write it —
  `True`/Reason `SingleReplica` when `replicas == 1`, `False`/Reason `MultiReplicaUnsupported`
  (message names the single-replica limit + PR-3.5) when `> 1` — and `meta.RemoveStatusCondition`
  it when leaving the observe path, so a scale-back never leaves a stale warning (fable issue 3). It
  is a warning: it does **NOT** gate `Observed` or campaign readiness.
- **`Observed` is STICKY** (resolves fable issue 8): once `Observed`, a transient `ReadyReplicas`
  dip does not revert to `Observing`; the target leaves `Observed` only on a spec change or the
  Deployment going away. Rationale: matches the injected path's stability (which never dips because
  it keys on `UpdatedReplicas`), and prevents a pod-readiness blip under load from flipping
  `Observed→Observing` and terminally failing a Running campaign via the §2.3 set-membership gate.
  The zero-pod edge (all pods die post-Observed) is a known limitation shared with the injected path;
  noted, not solved here. **The spike (task 2) explicitly exercises a post-Observed dip to confirm.**
- **§7.7** wording-only: `waitForInjected` success set → `{Injected, Observed}`, distinct success line.
- **`mode: load` explicit** in the e2e + docs (campaign `mode` defaults to `explore` → omitted mode is
  terminally rejected by the §3 gate).
- **`basquin instrument` clobber gap** (`existing.Spec = desired.Spec`): out of scope, **noted in PR body**.

## Environment safety (every task)
- **NEVER bare `go test ./...` in `operator/`** — it runs the scaffolded `operator/test/e2e/` against the
  live kubeconfig (it installed/removed prometheus + cert-manager on the live `basquin` kind cluster
  during the spike). Always `cd operator && make test` (excludes `/e2e`). A live `basquin` kind cluster
  exists; `deploy/e2e/e2e.sh` uses its own — don't conflate.

## Tasks (one PR) — reordered per fable issue 1
1. **API + enum + CRD (must land first — the spike depends on it).** `basquintarget_types.go`: add
   `PreInstrumented bool` (§2.1 comment verbatim); extend the `:130` enum marker to add
   `Observing;Observed`; add `PhaseObserving`/`PhaseObserved` consts. `make manifests generate`; commit
   the regenerated CRD yaml + `zz_generated.deepcopy.go`. **Why first:** envtest loads CRDs from
   `config/crd/bases` (`suite_test.go:57`); before the regen `preInstrumented` is schema-pruned and
   `Observing`/`Observed` status writes are enum-rejected — so no spike or test can persist them
   (fable issue 1; the §2.1a silent breaker).
2. **Spike + build the target reconciler (observe path + revert-before-observe)**
   (`basquintarget_controller.go`). Start with a throwaway envtest spec (now runnable, post-task-1):
   create an injected target, flip `preInstrumented: true`, step reconcile passes asserting the phase
   sequence (`Reverting → Observing → Observed`), template cleanliness at each pass, AND a post-`Observed`
   `ReadyReplicas` dip staying `Observed` (sticky). Then write the production branch: branch on
   `spec.PreInstrumented` BEFORE the inject-if-drifted block; previously-injected →
   `revertInjection` **+ `removeCoverageService` + clear `status.coverageEndpoint`** → `Update` →
   `Reverting` (no fall-through to `applyInjection`) — the coverage-Service teardown closes the spec gap
   fable issue 4 (a reverted target must not keep a live Service/stale endpoint while claiming "did not
   modify"); readiness via `ReadyReplicas` → sticky `Observing`/`Observed` with §2.2 exact
   reason/message strings; the `ReplicaConfigSupported` condition per the lifecycle above; widen the
   `:200-202` requeue so `Observing`/`Reverting` advance. `injection.go` untouched. Committed envtest
   specs: never-mutates (template equality + `metadata.generation` + no `basquin.dev/*` metadata),
   pre-ready window, flip-reverts-first **incl. coverage-Service removal** (accept 3-4),
   **multi-replica → `ReplicaConfigSupported=False` and its removal on scale-back** (fable issue 2),
   all-agents-disabled-still-injects pin (accept 7), post-`Observed` dip stays `Observed`.
3. **Campaign gate** (`basquincampaign_controller.go`) — needs only task 1 (parallel with 4/5, fable
   issue 7; campaign tests set target phase directly). `:108` gate → `{Injected, Observed}` membership
   (`Observing`/`Reverting` stay pending); mid-run drop-out-of-set → terminal `TargetGone` (reword `:114`
   if keeping precision); explore-vs-preInstrumented terminal rejection via
   `fail(..., "ExploreUnsupportedForPreInstrumented", <message naming PR-4>)` — **message must name PR-4**
   (fable issue 6) — placed AFTER the `:94-104` fetch, BEFORE the `:120-123` coverage gate. Tests:
   Pending on `Observing` (accept 3), terminal reject of explore (accept 5), load runs on `Observed`,
   `Observed`-drop-out fails a Running campaign.
4. **CLI wait** (`cmd/basquin/instrument.go`) — needs only task 1. `waitForInjected` success set →
   `{Injected, Observed}`, distinct success line; flag help text. Test in `instrument_test.go`.
5. **e2e verification script** (model `deploy/e2e/e2e.sh`), §5: build injected native image via
   `basquin-maven-injector`, deploy, apply `BasquinTarget{preInstrumented: true}`, wait `Observed`,
   **triple negative assertion** (pod-template spec-hash equality + `metadata.generation` unchanged +
   no `basquin.dev/*` metadata), channel probe (`X-Basquin-Req` → `/__basquin/result` cost line, not
   `miss`; NOT the drift/mode routes), then a `mode: load` campaign to completion. **Execution is IN
   SCOPE (user-directed 2026-08-07): build the GraalVM-native injected image via `basquin-maven-injector`
   and run the script end-to-end against a kind cluster** (reuse/model the existing `deploy/e2e/e2e.sh`
   cluster setup). Capture the run output under `bench-results/dd044-e2e-<UTC>/` as committed evidence so
   acceptance 1-2 are genuinely verified. **Honest fallback:** if the native-image build is infeasible in
   this environment (GraalVM/mandrel/resources), report that explicitly with the exact failure, land the
   authored+shellcheck-clean script, and say plainly in the PR body that the run did not complete and why
   — never claim a run that did not happen (invariant 1).
6. **Regression sweep** (accept 6): `cd operator && make test` — all existing controller/campaign tests
   pass untouched (boundary contract, idempotency, exact revert).

Task ordering: **1 first**; then **2, 3, 4 in parallel** (all need only 1); 5 after 2; 6 last.

## Acceptance (spec §6, 8 items)
All 8 pinned by a named test or e2e assertion. Honesty pins: item 4 (no state where `Observed`'s
"did not modify" message coexists with an injected template — now incl. no live coverage Service) and
item 7 (all-agents-disabled still injects). Items 1-2 are e2e-pinned and are VERIFIED by the in-cluster
run (task 5, user-directed) with output committed under `bench-results/dd044-e2e-<UTC>/`.

## PR packaging
Branch `dd044-preinstrumented-targets` off `main`; commit the tracked plan doc
(`docs/superpowers/plans/2026-08-07-dd044-preinstrumented-targets.md`) as part of the PR; PR body notes
the two out-of-scope adjacents (instrument clobber; e2e execution venue). Then: `@claude` review →
address → fable approver → approval via ianp94 → human merge.

## Spec amendments this plan makes (to fold into the spec/PR)
- §2.2b: revert-before-observe must ALSO remove the coverage Service + clear `status.coverageEndpoint`
  (fable issue 4 — spec omitted it).
- §4: the `ReplicaConfigSupported` condition's lifecycle (always-write + remove-on-leave; non-gating).
- §2.2: `Observed` is sticky against transient `ReadyReplicas` dips.
