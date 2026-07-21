# BasquinCampaign — design proposal (DD-025, operator P5)

**Status:** proposed, under review. Not yet implemented. On approval this becomes **DD-025** and the
implementation lands as operator phase **P5**. Extends [OPERATOR-DESIGN.md](OPERATOR-DESIGN.md) §10,
whose confirmed decisions this fleshes out: **two CRDs** (`BasquinTarget` = instrument, done P1–P4;
`BasquinCampaign` = test), built after injection works (it does).

---

## 1. The ask

Today you instrument a target (`BasquinTarget`) and then *manually* run the coverage-guided driver
and the dashboard against it (`deploy/e2e/e2e.sh` does the instrument half; the driving is a laptop
command). P5 makes the operator orchestrate the **whole test**: apply one resource and the operator
brings up the runner + dashboard against an instrumented target, wires them together, and reports the
result — "create a test, everything starts." The dashboard stays read-only (DD-013); the *operator*
owns scheduling, because it already has the namespaced authority and reconcile model for it (this is
the answer to TODO's long-open "launch runs from the dashboard" question).

## 2. Why a second CRD (recap)

`BasquinTarget` is Deployment-like — a steady state ("this app carries the agents"). A campaign is
Job-like — a bounded run. One target may be driven by many campaigns over time (a nightly run, an
ad-hoc repro), and deleting a campaign must not un-instrument the app. Collapsing them would force
"instrumented" and "currently under test" to be the same state, which they aren't.

## 3. The `BasquinCampaign` custom resource

Group `basquin.dev/v1alpha1`, namespaced. Sketch (exact fields settle during P5a):

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata:
  name: nightly-jpetstore
  namespace: basquin-system
spec:
  # WHAT to drive — an existing BasquinTarget in this namespace.
  targetRef: { name: jpetstore }

  # REQUIRED: the app's HTTP entrypoint the driver hits. The operator does NOT create a Service in
  # front of the app (it only patches the Deployment + makes the coverage Service), and a target has
  # no app-Service field to default from — so the campaign names it explicitly.
  baseURL: "http://jpetstore.basquin-system.svc:8080"

  driver:
    # Exploration surface. Provide a grammar inline OR reference a ConfigMap; likewise a seed corpus.
    grammarConfigMap: { name: jpetstore-grammar }     # or: grammar: |  <inline>
    corpusConfigMap:  { name: jpetstore-corpus }      # optional
    duration: "10m"                                    # OR iterations: 100000 (one of the two)
    invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }
    # Coverage source: default is the target's headless coverage Service (P3 status.coverageEndpoint,
    # DD-023 union-merge across replicas). Override only for special cases.
    coverageFromTarget: true
    # Where the app's .class files live inside the target's image, so an initContainer can copy them
    # out for JaCoCo analysis (§7b). Default is the Tomcat WAR layout.
    classesPath: "/usr/local/tomcat/webapps/ROOT/WEB-INF/classes"

  dashboard:
    enabled: true            # operator creates a per-campaign dashboard...
    externalPush: ""         # ...or push to an existing dashboard at host:port instead

status:
  phase: Running             # Pending | Provisioning | Running | Completed | Failed
  driverJob: nightly-jpetstore-driver
  dashboardURL: "http://nightly-jpetstore-dashboard.basquin-system.svc:7070"
  startTime: "..."
  completionTime: "..."
  coveragePct: "23.1"
  findings: 19
  conditions: [ { type: Ready, ... } ]
```

## 4. What the reconciler does

1. **Resolve + gate on the target.** Look up `targetRef`; require its status `Injected` (else
   `Pending`, requeue — you can't drive an app that isn't instrumented yet). Read its
   `status.coverageEndpoint` (P3) for the coverage flag. `baseURL` is taken from the campaign spec
   (required — the operator doesn't front the app with a Service of its own).
2. **Dashboard.** If `dashboard.enabled` and no `externalPush`: ensure a dashboard `Deployment` +
   `Service` (the standalone `DashboardServer`, DD-013), owner-referenced to the campaign. Set
   `status.dashboardURL`.
3. **Driver.** Create a `Job` running the coverage-guided runner (`CoverageGuidedRun`) with:
   `-Dexamples.http.baseUrl=<baseURL>`, `-Dbasquin.grammar=<mounted grammar>`,
   `-Dbasquin.coverage.jacoco=<target coverageEndpoint>`,
   `-Dbasquin.coverage.classes=<extracted classes dir>` (§7b),
   `-Dbasquin.dashboard.push=<dashboard Service>`,
   `-Dbasquin.dashboard.id=<campaign name>` (so pushes land at `/api/campaign/<name>/…` rather than
   under the pod's random `HOSTNAME`), the invariant flags, and the run bound. Owner-referenced to the
   campaign; grammar/corpus arrive via mounted ConfigMaps; `restartPolicy: Never` + a small
   `backoffLimit`. **The run bound is enforced inside the runner, not by `activeDeadlineSeconds`** —
   see §7.2/§7a for why (a kill would skip the summary write).
4. **Aggregate status.** Watch the `Job`: Running → `Running`, success → `Completed`, failure/backoff
   → `Failed`. Populate `coveragePct`/`findings` (see §7 open decision on *how*).
5. **Teardown.** Owner references on the Job + dashboard Deployment/Service mean deleting the campaign
   garbage-collects them. A finalizer is likely **not** needed (nothing external to clean) unless we
   want to archive results before deletion (§7).

## 5. New build components (prerequisites)

Like the `basquin/agents` image unblocked P2, P5 needs two images the operator launches — tracked
in TODO's *Operator orchestration* group:

- **`basquin/runner`** — a JRE image with the harness jar, entrypoint `runner.coverage.CoverageGuidedRun`.
- **`basquin/dashboard`** — a JRE image with the harness jar, entrypoint `runner.util.DashboardServer`.

Both come straight from the existing Gradle build (the tasks `runCoverageGuided` / `runDashboard`
already run these mainClasses). Could even be one image with two entrypoints. Reuse the
`deploy/agents-image/build.sh` pattern.

## 6. RBAC additions (still namespaced)

The operator gains, in its own namespace only:
- `basquin.dev/basquincampaigns` (+ `/status`, `/finalizers`): `get;list;watch;update;patch` —
  the campaign's own CR, mirroring the grants `role.yaml` already gives `basquintargets`;
- `batch/jobs`: `get;list;watch;create;delete` (drive the runner);
- `apps/deployments`: add `create;delete` (P1–P4 only `update;patch` the *target's* existing
  Deployment; the dashboard is one the operator *creates*);
- `core/services`: already granted (P3); reused for the dashboard Service;
- `core/configmaps`: `get;list;watch` (read grammar/corpus), plus `create` if inline grammar is
  materialized into a ConfigMap and to write the `<campaign>-results` summary (§7a);
- `core/pods`: `get;list;watch` (surface driver pod status only). **Not** `pods/log` — reading
  container logs needs the separate `pods/log` subresource grant, which the §7.1-Option-B summary
  approach deliberately avoids (no log scraping).

**Trust note.** The operator now *runs workloads* (Jobs) in its namespace — genuine scheduling
authority. That is the deliberate trade P5 makes, and it belongs with the operator (already namespaced,
already reconcile-driven) rather than bolted onto the read-only dashboard, which would have needed a
control channel amounting to RCE on the dashboard host. The Jobs run **pinned images with config**, not
arbitrary user commands.

## 7. Decisions

**Resolved (2026-07-20):**
1. **Status numbers come from the driver, not the dashboard.** The driver Job writes a machine-readable
   results summary the operator reads (§7a) — so it works even with `externalPush`/no dashboard and the
   operator isn't coupled to the dashboard's HTTP API. Live-progress scraping can be added later.
3. **Dashboard is per-campaign by default**, owner-ref'd and GC'd with the campaign (clean lifecycle);
   `externalPush` fans many campaigns into one long-lived dashboard (the DD-013 fleet view) when you
   want cross-campaign comparison. Both supported.

2. **Duration vs iterations — RESOLVED (bound is enforced in the runner).** `iterations` uses the
   runner's existing count cap. `duration` needs a **new runner flag** (`-Dbasquin.run.duration`)
   that stops the loop and **exits 0 cleanly** at the deadline — NOT a Job `activeDeadlineSeconds`,
   which SIGKILLs the pod (Job condition `Failed`/`DeadlineExceeded`, and — worse — the driver never
   writes its `summary.json`, §7a). So both bounds end in a clean exit → `Completed` + a summary. A
   generous `activeDeadlineSeconds` can still be set as a last-resort backstop, treated as `Failed`.
   One-of (`duration` XOR `iterations`) validated via CEL. This is a second small additive runner
   change, alongside the summary write (§7a).

**Still open (not blocking P5a):**
4. **Result persistence.** The Job + dashboard are ephemeral; findings/corpus vanish on teardown. Out
   of scope for the first cut (results live while the campaign does); a `results:` sink (PVC / object
   store) is a follow-up.
5. **One target vs many.** Start with a single `targetRef`. A label selector over targets (drive a
   whole fleet from one campaign) is a later extension.

## 7a. The driver results contract (resolved decision #1)

For the operator to fill `status.coveragePct`/`findings` without touching the dashboard, the runner
must emit a small, machine-readable summary at end of run. Proposed contract:

- The driver, on completion (or on `activeDeadlineSeconds` expiry), writes a one-line JSON summary to a
  **known path on a shared `emptyDir`** (e.g. `/basquin-out/summary.json`): `{"coveragePct": 23.1,
  "coveredEdges": 549, "totalEdges": 2378, "findings": 19, "crashes": 7, "invariants": 12,
  "iterations": 41233}`. This reuses the numbers `StatusReporter.snapshotJson()` already computes.
- A tiny **sidecar** in the driver Job (or a `preStop`/final step) copies that summary into a
  `ConfigMap` named `<campaign>-results` (or writes it as the pod's `terminationMessage`), which the
  operator reads. A ConfigMap is simplest and survives the pod; `terminationMessage` is lighter but
  size-capped at 4 KiB (fine for a one-line summary) — **lean ConfigMap** so a short findings preview
  can grow into it later.
- Small runner change required: have `CoverageGuidedRun` write `summary.json` on shutdown (it already
  has all the numbers in `StatusReporter`). This is the one piece of *harness* code P5 touches; it's
  additive and behind a flag (`-Dbasquin.summary.out=/path`).

## 7b. Coverage classes in-cluster (implementation gap to solve in P5a)

The DD-023 coverage reader (`JacocoCoverageProvider`) needs the app's **`.class` files** to turn the
JaCoCo execution dump into covered/total probes — `-Dbasquin.coverage.classes=<dir>`. On a laptop
that's an extracted `WEB-INF/classes`; the driver Job needs the same in-cluster, and it's the one
non-obvious dependency. Options considered:

- **A — initContainer extracts classes from the target's app image (recommended).** The driver Job
  runs an initContainer using the *target Deployment's own container image* (which the operator already
  reads), copying `/usr/local/tomcat/webapps/ROOT/WEB-INF/classes` (path configurable) into a shared
  `emptyDir` the driver mounts at `-Dbasquin.coverage.classes`. No extra artifact — the classes come
  from the exact image under test, so they always match. Needs a configurable in-image path
  (`spec.driver.classesPath`, default the Tomcat WAR layout).
- **B — bake classes into a per-target artifact** (ConfigMap/image) at instrument time. Rejected:
  another build step, and it can drift from the running image.
- **C — coverage % without a denominator** (covered-count only). Rejected: loses the "% explored"
  signal that motivated DD-012/DD-016.

Recommend **A**. This is the main thing P5a must get right for coverage to work end-to-end; the e2e
will assert the driver reports a non-zero coverage % against the raw JPetStore.

## 7c. Reconcile detail — idempotency, phases, completion

- **Idempotency.** Like injection, hash the campaign spec into an annotation on the driver Job; a
  steady campaign is a no-op. The Job is immutable once created, so a spec change deletes+recreates the
  Job (a new run), which is the intuitive semantics for "I changed the test."
- **Phase machine.** `Pending` (target not yet `Injected`) → `Provisioning` (creating dashboard/Job) →
  `Running` (Job active) → `Completed` (Job succeeded, summary read) | `Failed` (Job backoff-limit hit
  or deadline with no summary). Requeue on Job/target watch events.
- **Completion.** Driver Jobs are bounded (`iterations` cap or `activeDeadlineSeconds` from
  `duration`), so the Job completes naturally; the operator reads the results ConfigMap and moves to
  `Completed`. `restartPolicy: Never` + a small `backoffLimit` so a genuinely broken run surfaces as
  `Failed` rather than looping.
- **Watches.** `Owns(&batchv1.Job{})`, `Owns(&corev1.Service{})`, `Owns(&appsv1.Deployment{})` for the
  dashboard, plus a watch mapping the referenced `BasquinTarget` back to campaigns (so a campaign
  created before its target is `Injected` starts as soon as injection completes).
- **Target reverted mid-run.** If the referenced target is deleted (or drops out of `Injected`) while a
  campaign is `Running`, the target-→-campaign watch re-reconciles; the campaign moves to a distinct
  terminal `Failed` state with reason `TargetGone` (rather than letting the driver silently rack up
  connection-refused "findings" that look like app bugs). First cut stops the run there; auto-resume
  when the target returns is a later nicety.

## 8. Phased delivery within P5 (each its own PR)

- **P5a — CRD + driver Job.** `BasquinCampaign` CRD + reconciler that launches the driver Job
  against the target (using its `status.coverageEndpoint`), status = Job state. No dashboard yet.
  Needs the `basquin/runner` image, the **coverage-classes initContainer** (§7b, option A), and the
  **driver summary** write (§7a). Verified by extending `deploy/e2e/e2e.sh` (apply a campaign, assert
  the driver runs and reports a **non-zero coverage %** against the raw JPetStore).
- **P5b — dashboard.** Operator brings up the per-campaign dashboard `Deployment` + `Service`, wires
  the driver's push at it, sets `status.dashboardURL`. Needs the `basquin/dashboard` image.
- **P5c — aggregate status + completion.** `coveragePct`/`findings` into status (§7.1), phase
  transitions, `activeDeadlineSeconds`.
- **P5d — docs + demo.** USAGE campaign section; the e2e drives a full campaign end to end.

## 9. Rejected alternatives

- **Fold orchestration into `BasquinTarget`** — conflates "instrumented" with "under test"; a
  target couldn't outlive a run or host multiple runs (§2).
- **Dashboard launches the runner** — reverses DD-013's read-only design and needs a control channel
  back to drivers that is effectively RCE on the dashboard host. The operator's existing namespaced
  authority does the job without that.
- **A CronJob-style built-in scheduler in the campaign** — recurring runs are better expressed by a
  Kubernetes `CronJob` that `kubectl apply`s campaigns, keeping the campaign itself a single bounded
  run. Revisit if there's demand.

## 10. What this does *not* change

The injection track (P1–P4) is untouched — a `BasquinCampaign` only *consumes* an already-injected
`BasquinTarget`. The Tomcat valve is still deferred, and the multi-arch agents image is still a
separate follow-up.
