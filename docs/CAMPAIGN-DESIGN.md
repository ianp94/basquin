# ClosureJVMCampaign — design proposal (DD-025, operator P5)

**Status:** proposed, under review. Not yet implemented. On approval this becomes **DD-025** and the
implementation lands as operator phase **P5**. Extends [OPERATOR-DESIGN.md](OPERATOR-DESIGN.md) §10,
whose confirmed decisions this fleshes out: **two CRDs** (`ClosureJVMTarget` = instrument, done P1–P4;
`ClosureJVMCampaign` = test), built after injection works (it does).

---

## 1. The ask

Today you instrument a target (`ClosureJVMTarget`) and then *manually* run the coverage-guided driver
and the dashboard against it (`deploy/e2e/e2e.sh` does the instrument half; the driving is a laptop
command). P5 makes the operator orchestrate the **whole test**: apply one resource and the operator
brings up the runner + dashboard against an instrumented target, wires them together, and reports the
result — "create a test, everything starts." The dashboard stays read-only (DD-013); the *operator*
owns scheduling, because it already has the namespaced authority and reconcile model for it (this is
the answer to TODO's long-open "launch runs from the dashboard" question).

## 2. Why a second CRD (recap)

`ClosureJVMTarget` is Deployment-like — a steady state ("this app carries the agents"). A campaign is
Job-like — a bounded run. One target may be driven by many campaigns over time (a nightly run, an
ad-hoc repro), and deleting a campaign must not un-instrument the app. Collapsing them would force
"instrumented" and "currently under test" to be the same state, which they aren't.

## 3. The `ClosureJVMCampaign` custom resource

Group `closurejvm.dev/v1alpha1`, namespaced. Sketch (exact fields settle during P5a):

```yaml
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMCampaign
metadata:
  name: nightly-jpetstore
  namespace: closurejvm-system
spec:
  # WHAT to drive — an existing ClosureJVMTarget in this namespace.
  targetRef: { name: jpetstore }

  # The app's HTTP entrypoint the driver hits. Optional: default to the target's app Service.
  baseURL: "http://jpetstore.closurejvm-system.svc:8080"

  driver:
    # Exploration surface. Provide a grammar inline OR reference a ConfigMap; likewise a seed corpus.
    grammarConfigMap: { name: jpetstore-grammar }     # or: grammar: |  <inline>
    corpusConfigMap:  { name: jpetstore-corpus }      # optional
    duration: "10m"                                    # OR iterations: 100000 (one of the two)
    invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }
    # Coverage source: default is the target's headless coverage Service (P3 status.coverageEndpoint,
    # DD-023 union-merge across replicas). Override only for special cases.
    coverageFromTarget: true

  dashboard:
    enabled: true            # operator creates a per-campaign dashboard...
    externalPush: ""         # ...or push to an existing dashboard at host:port instead

status:
  phase: Running             # Pending | Provisioning | Running | Completed | Failed
  driverJob: nightly-jpetstore-driver
  dashboardURL: "http://nightly-jpetstore-dashboard.closurejvm-system.svc:7070"
  startTime: "..."
  completionTime: "..."
  coveragePct: "23.1"
  findings: 19
  conditions: [ { type: Ready, ... } ]
```

## 4. What the reconciler does

1. **Resolve + gate on the target.** Look up `targetRef`; require its status `Injected` (else
   `Pending`, requeue — you can't drive an app that isn't instrumented yet). Read the target's app
   Service for the default `baseURL` and its `status.coverageEndpoint` (P3) for the coverage flag.
2. **Dashboard.** If `dashboard.enabled` and no `externalPush`: ensure a dashboard `Deployment` +
   `Service` (the standalone `DashboardServer`, DD-013), owner-referenced to the campaign. Set
   `status.dashboardURL`.
3. **Driver.** Create a `Job` running the coverage-guided runner (`CoverageGuidedRun`) with:
   `-Dexamples.http.baseUrl=<baseURL>`, `-Dclosurejvm.grammar=<mounted grammar>`,
   `-Dclosurejvm.coverage.jacoco=<target coverageEndpoint>`,
   `-Dclosurejvm.dashboard.push=<dashboard Service>`, plus the invariant flags and duration/iteration
   cap. Owner-referenced to the campaign. Grammar/corpus arrive via mounted ConfigMaps.
4. **Aggregate status.** Watch the `Job`: Running → `Running`, success → `Completed`, failure/backoff
   → `Failed`. Populate `coveragePct`/`findings` (see §7 open decision on *how*).
5. **Teardown.** Owner references on the Job + dashboard Deployment/Service mean deleting the campaign
   garbage-collects them. A finalizer is likely **not** needed (nothing external to clean) unless we
   want to archive results before deletion (§7).

## 5. New build components (prerequisites)

Like the `closurejvm/agents` image unblocked P2, P5 needs two images the operator launches — tracked
in TODO's *Operator orchestration* group:

- **`closurejvm/runner`** — a JRE image with the harness jar, entrypoint `runner.coverage.CoverageGuidedRun`.
- **`closurejvm/dashboard`** — a JRE image with the harness jar, entrypoint `runner.util.DashboardServer`.

Both come straight from the existing Gradle build (the tasks `runCoverageGuided` / `runDashboard`
already run these mainClasses). Could even be one image with two entrypoints. Reuse the
`deploy/agents-image/build.sh` pattern.

## 6. RBAC additions (still namespaced)

The operator gains, in its own namespace only:
- `batch/jobs`: `get;list;watch;create;delete` (drive the runner);
- `apps/deployments`: add `create;delete` (it currently only `update;patch`es the *target's* existing
  Deployment; the dashboard is one it *creates*);
- `core/configmaps`: `get;list;watch` (read grammar/corpus; `create` if we materialize inline grammar
  into a ConfigMap);
- `core/pods`: `get;list;watch` (surface driver pod status; read logs if we scrape results that way).

**Trust note.** The operator now *runs workloads* (Jobs) in its namespace — genuine scheduling
authority. That is the deliberate trade P5 makes, and it belongs with the operator (already namespaced,
already reconcile-driven) rather than bolted onto the read-only dashboard, which would have needed a
control channel amounting to RCE on the dashboard host. The Jobs run **pinned images with config**, not
arbitrary user commands.

## 7. Open sub-decisions (please weigh in)

1. **How status numbers are populated.** The dashboard already exposes `/api/campaign/{id}/{status,
   findings,clusters}`. Option A: the operator scrapes that HTTP API to fill `coveragePct`/`findings`
   (couples the operator to the dashboard's API, only works when a dashboard exists). Option B: the
   driver Job writes a final results summary (a ConfigMap or a terminationMessage) the operator reads
   (works even with `externalPush`, decouples from the dashboard). **Recommend B** for decoupling, with
   A as a live-progress nicety later.
2. **Duration vs iterations.** Support both; `duration` maps to a Job `activeDeadlineSeconds`,
   `iterations` to the runner's existing cap. One-of validation via CEL.
3. **Dashboard: per-campaign vs shared.** Default **per-campaign** (owner-ref'd, GC'd with the
   campaign — clean lifecycle). `externalPush` lets many campaigns fan into one long-lived dashboard
   (the fleet view, DD-013) when you want cross-campaign comparison. Both supported.
4. **Result persistence.** The Job + dashboard are ephemeral; findings/corpus vanish on teardown.
   Out of scope for the first cut (results live while the campaign does); a `results:` sink (PVC /
   object store) is a follow-up.
5. **One target vs many.** Start with a single `targetRef`. A label selector over targets (drive a
   whole fleet from one campaign) is a later extension.

## 8. Phased delivery within P5 (each its own PR)

- **P5a — CRD + driver Job.** `ClosureJVMCampaign` CRD + reconciler that launches the driver Job
  against the target (using its `status.coverageEndpoint`), status = Job state. No dashboard yet.
  Needs the `closurejvm/runner` image. Verified by extending `deploy/e2e/e2e.sh` (apply a campaign,
  assert the driver runs and coverage climbs).
- **P5b — dashboard.** Operator brings up the per-campaign dashboard `Deployment` + `Service`, wires
  the driver's push at it, sets `status.dashboardURL`. Needs the `closurejvm/dashboard` image.
- **P5c — aggregate status + completion.** `coveragePct`/`findings` into status (§7.1), phase
  transitions, `activeDeadlineSeconds`.
- **P5d — docs + demo.** USAGE campaign section; the e2e drives a full campaign end to end.

## 9. Rejected alternatives

- **Fold orchestration into `ClosureJVMTarget`** — conflates "instrumented" with "under test"; a
  target couldn't outlive a run or host multiple runs (§2).
- **Dashboard launches the runner** — reverses DD-013's read-only design and needs a control channel
  back to drivers that is effectively RCE on the dashboard host. The operator's existing namespaced
  authority does the job without that.
- **A CronJob-style built-in scheduler in the campaign** — recurring runs are better expressed by a
  Kubernetes `CronJob` that `kubectl apply`s campaigns, keeping the campaign itself a single bounded
  run. Revisit if there's demand.

## 10. What this does *not* change

The injection track (P1–P4) is untouched — a `ClosureJVMCampaign` only *consumes* an already-injected
`ClosureJVMTarget`. The Tomcat valve is still deferred, and the multi-arch agents image is still a
separate follow-up.
