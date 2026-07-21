# Basquin injection operator — design + status

**Status (2026-07-20):** approved and in build. **P1** (scaffold + CRD + observe-only controller),
**P2** (injection + idempotency + finalizer revert, **DD-024**), **P3** (headless coverage Service +
`status.coverageEndpoint`), and **P4** (apply-a-CR is now the documented path — USAGE + ARCHITECTURE
+ the `deploy/k8s` README point at the operator) are done and **validated in-cluster**
(`deploy/e2e/e2e.sh` instruments a raw JPetStore end-to-end, incl. the coverage Service). Only **P5**
(`BasquinCampaign` orchestration, §10) remains. Still deferred: valve mounting (needs a Tomcat
`context.xml` entry, not a JVM flag) and a multi-arch agents image.

**Decision being proposed (2026-07-20):** an *explicit patch controller* — a namespaced operator
that instruments only the Deployments you name in a `BasquinTarget` custom resource. No mutating
admission webhook; nothing is intercepted or rewritten unless you asked for it by name.

**Built in Go with kubebuilder / controller-runtime, as its own module** (decided 2026-07-20). The
operator is a *control plane*, not JVM code: the CR schema, reconcile loop, injection, and revert
are runtime-agnostic — only the assembled agent flags and the agents image are JVM-specific today.
Keeping the operator in the idiomatic operator stack (rather than coupling it to the Gradle build in
Java) is the deliberate bet that Basquin's availability-testing concepts carry to other runtimes
later; when they do, a new "runtime profile" supplies different agents/flags and the entire control
plane is reused unchanged. Cost accepted: a second language and build enter the repo.

---

## 1. Problem

Today, running a target under Basquin in Kubernetes means baking the agents into the image
(`deploy/k8s/Dockerfile.jpetstore`: three `COPY`s plus a hand-written `CATALINA_OPTS` with two
`-javaagent`s, `-Xbootclasspath/a`, and the invariant flags). That is fine for a demo but wrong for
real use:

- It forces a **custom image** of every app you want to test — you can't point Basquin at an app
  someone else owns without a rebuild.
- The `CATALINA_OPTS` string is fiddly and easy to get subtly wrong (agent order, the JaCoCo
  `includes` filter that DD-022 showed inflates the denominator when left as `*`).
- Scaling to replicas means every pod needs the JaCoCo port wired up and, for DD-023's union
  coverage, a stable way for the driver to find all of them.

The goal: **instrument an unmodified app image at deploy time**, reversibly, with the smallest
possible standing privilege in the cluster.

## 2. Why the explicit-patch model, not an admission webhook

A mutating admission webhook is the "seamless" option — label a namespace, every pod that appears
gets injected. It is also the heaviest trust boundary we could pick:

- It runs cluster-wide with the power to **mutate any pod at creation**, including workloads that
  have nothing to do with Basquin. A bug or a bad `failurePolicy` can wedge unrelated
  deployments across the cluster.
- It needs a TLS-served webhook endpoint, cert rotation, and careful `namespaceSelector` /
  `objectSelector` scoping just to *not* touch things.
- The mutation is invisible at apply time — you can't `kubectl diff` your way to what will run.

The explicit-patch controller inverts all of that. You create a `BasquinTarget` naming one
Deployment; the controller patches **that Deployment only**. Properties that fall out of the model:

- **Least privilege.** A namespaced `Role`, not a `ClusterRole`. It can read its own CRs and
  `get`/`patch` Deployments *in its namespace*. It cannot touch a pod it wasn't pointed at.
- **Auditable and opt-in.** The instrumentation is an ordinary Deployment patch — visible in
  `kubectl get deploy -o yaml`, revertible by deleting the CR, and diffable before it happens.
- **No admission-time blast radius.** A controller bug degrades to "this one target didn't get
  instrumented," not "pod creation is broken cluster-wide."

The cost is that instrumentation is a deliberate act per app rather than automatic. For a testing
tool you deliberately point at a target, that is the *right* default — and it is the same reasoning
as DD-022 (the default should be safe/narrow, breadth should be opt-in).

## 3. The `BasquinTarget` custom resource

Namespaced CRD, group `basquin.dev/v1alpha1`. The CR is the entire user-facing API.

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata:
  name: jpetstore
  namespace: demo
spec:
  # WHAT to instrument — a Deployment in this namespace, and which container in its pod template.
  deploymentRef: { name: jpetstore }
  container: jpetstore            # optional; defaults to the only container, else required

  # WHICH agents to inject. Each is independently toggleable.
  agents:
    threadTracker: true           # native JVMTI agent (-agentpath) — leak/thread oracle
    valve: true                   # Tomcat valve — server-side invariants
    coverage:                     # JaCoCo tcpserver for coverage-guided-over-HTTP
      enabled: true
      port: 6300
      includes: "org.mybatis.jpetstore.*"   # REQUIRED when enabled; must not default to "*" (DD-022)

  # HOW the JVM picks up the agents. Tomcat images honor CATALINA_OPTS; everything else uses
  # JAVA_TOOL_OPTIONS (the JVM appends it to every launch). The operator picks per `jvmOptsVar`.
  jvmOptsVar: CATALINA_OPTS       # or JAVA_TOOL_OPTIONS (default)

  # Invariant config passed straight through as -Dbasquin.invariant.* flags.
  invariants:
    mode: soft
    latencyMaxMs: 25
    heapDeltaMaxKb: 256

  # Where server-side findings/status push to (the standalone dashboard, DD-013).
  dashboardPush: "basquin-dashboard.demo.svc:7070"

  # Create a headless Service selecting this target's pods on the coverage port, so one driver can
  # reach every replica by DNS and DD-023's getAllByName expands it to all pod IPs. Opt-in.
  coverageService: true

status:
  phase: Injected                 # Pending | Injecting | Injected | Reverting | Error
  observedGeneration: 3
  instrumentedReplicas: 3
  coverageEndpoint: "jpetstore-basquin-jacoco.demo.svc:6300"   # for the driver flag
  conditions:
    - { type: Ready, status: "True", reason: RolloutComplete }
```

The spec is intentionally a superset of the hand-written `CATALINA_OPTS` in the demo Dockerfile —
anything that string encodes today, the CR encodes declaratively.

**Validation:** when `agents.coverage.enabled: true`, `agents.coverage.includes` is **required** (CRD
schema validation, no default). DD-022 showed that a `*` includes silently instruments Tomcat and
MyBatis, inflating the coverage denominator and adding enough overhead to fake latency violations;
the operator path must not be able to re-open that by omission, so there is deliberately no default
value to fall back to.

## 4. How agents get into an unmodified pod

The target image does **not** contain our agents, so we cannot just reference a path. Use the
standard "copy via init container + shared volume" pattern (the same shape the OpenTelemetry and
Istio operators use):

1. The operator patches the target Deployment's pod template to add:
   - a small **initContainer** running a `basquin/agents:<version>` image whose only job is
     `cp /agents/* /basquin/` into…
   - a shared **`emptyDir` volume** mounted at `/basquin` in both the initContainer and the app
     container, and
   - the assembled agent string — `-javaagent`/`-agentpath` pointing at
     `/basquin/basquin-agent.jar`, `/basquin/jacocoagent.jar` (scoped `includes`), plus the
     `-Dbasquin.*` flags — **appended to** the container's existing `jvmOptsVar`, not replacing
     it. Target containers routinely set their own `JAVA_TOOL_OPTIONS`/`CATALINA_OPTS` (heap sizing,
     GC flags); overwriting would silently change app behavior in ways unrelated to instrumentation.
     If the var is set, the operator appends our flags to the original value (the same original it
     stashes for revert, §5); if unset, it sets our flags alone.
   - the coverage **containerPort** (6300) if coverage is enabled.

2. If `coverageService: true`, the operator also creates a **headless Service**
   (`clusterIP: None`) selecting the target's pods on the coverage port. Its DNS name resolves to
   every pod IP, which is exactly what `JacocoCoverageProvider.parseEndpoints` +
   `InetAddress.getAllByName` consume for union coverage (DD-023). The resolved name is written to
   `status.coverageEndpoint` so the driver flag is copy-pasteable. Note that headless-Service DNS
   returns only **Ready** pod IPs, so during a rollout the DD-023 `[N/M pods]` denominator moves as
   pods cycle in and out — coherent (the panel shows exactly what is reachable), just expected rather
   than a surprise to rediscover in P3.

Because the agent binaries ship in a versioned image the operator controls, upgrading agents is an
operator/image bump, never a target rebuild.

## 5. Reconcile loop

Level-triggered, idempotent — the controller reconciles desired state, it does not apply diffs:

1. **Observe** a `BasquinTarget`. Load the referenced Deployment.
2. **Compute** the desired pod-template patch from the spec (initContainer, volume, env, port). The
   `jvmOptsVar` value is `<stashed original> + " " + <our flags>` — computed against the stashed
   original, never against the already-instrumented value, so re-reconciling never double-appends.
3. **Compare** against what's already there (identified by a `basquin.dev/injected: <hash>`
   annotation recording the spec hash we last applied, plus the original `jvmOptsVar` value stashed
   in `basquin.dev/original-<var>` so revert is exact). If the hash matches, do nothing —
   reconciling a steady target is a no-op, so this is safe to run on every resync.
4. **Patch** the Deployment if drift is detected; a rollout instruments the pods.
5. **Report** rollout progress into `status` (`instrumentedReplicas`, `Ready` condition).

**Revert via finalizer.** The CR carries a finalizer. On deletion, the controller first removes the
initContainer/volume/env/port from the Deployment (restoring `jvmOptsVar` from the stashed original
annotation) and deletes the coverage Service, *then* clears the finalizer. Deleting a
`BasquinTarget` therefore returns the app to exactly its pre-instrumentation state — no orphaned
agents, no leftover env.

## 6. RBAC — the trust boundary, concretely

Shipped as a namespaced install by default:

- `Role` (namespaced): `get/list/watch` on `basquintargets`; `get/list/watch/update/patch` on
  `deployments`; `get/create/update/delete` on `services` (for the coverage Service); `update` on
  `basquintargets/status` and `/finalizers`.
- `RoleBinding` to the operator's `ServiceAccount` in that namespace.
- **No `ClusterRole`, no webhook configuration object, no cluster-scoped verbs.** To instrument
  targets in another namespace you install another instance (or knowingly opt into a broader
  binding) — breadth is a deliberate act, never the default.

Stated bluntly, so the grant is honest: "patch Deployments in one namespace" is, concretely, the
power to inject an **arbitrary initContainer** — i.e. code execution on every workload in that
namespace. That is not nothing. It is still categorically narrower than an admission webhook's
"mutate any pod, cluster-wide, at creation," and naming it plainly is what makes the containment
argument credible: the blast radius is one namespace you chose, auditable in plain Deployment YAML,
not the whole cluster invisibly. This is the whole reason to prefer this model — the standing
privilege is bounded and inspectable, versus "mutate any pod at admission time."

## 7. Decisions

1. **Language / SDK — RESOLVED: Go + kubebuilder / controller-runtime, as its own module.** Chosen
   over Java/fabric8-JOSDK because the operator is a runtime-agnostic control plane (see the status
   note at the top): staying in the idiomatic operator stack keeps the door open to non-JVM runtime
   profiles later, and it does not want to be welded to the Gradle build. Agent versioning is by the
   **agents image tag** referenced in the CR/operator config, not by shared source constants — so
   the Go/Java language split costs nothing here (the operator only ever names an image and writes
   flags, it never links agent code). The operator lives under `operator/` as a self-contained Go
   module; the existing Gradle build is untouched.

2. **Non-Tomcat JVM opts — RESOLVED (revisit empirically in P2).** Default: the JVMTI native agent
   (`-agentpath`) and the Java agent (`-javaagent` on `basquin-agent.jar`) are injected for every
   JVM target via `jvmOptsVar`; the `-Xbootclasspath/a:basquin-agent.jar` append travels with the
   Java agent wherever it goes (it makes the agent's classes visible to bootstrap-loaded code, which
   is not Tomcat-specific). The **valve** is the only genuinely Tomcat-specific piece and is gated on
   `agents.valve` + a `CATALINA_OPTS`/Tomcat target. P2 verifies against a stock (non-Tomcat) JVM
   whether the bootclasspath append is actually required outside Tomcat; if it turns out unnecessary
   there, it gets scoped to Tomcat targets rather than applied blindly. This is an implementation
   verification, not a blocking design question.

### Still open (does not block P1)

- **Dashboard as control plane.** The operator writes status the dashboard could surface (fleet of
  instrumented targets). That edges toward the dashboard-as-control-plane question still open in
  TODO.md; this design keeps the operator authoritative and the dashboard read-only for now.
- **Multi-runtime profiles.** The forward-looking reason for Go (§ status note). Out of scope for
  the JVM operator, but the CRD should avoid JVM-only assumptions at its top level so a future
  `runtimeProfile: jvm | node | ...` can slot in without a breaking `v1alpha1 → v1alpha2` churn.

## 8. Phased delivery (each phase its own PR)

- **P0 — this doc.** Agree the model and settle §7. *(Model + Go/kubebuilder settled 2026-07-20.)* ✅
- **P1 — kubebuilder scaffold + CRD + no-op controller.** Go module under `operator/`, the
  `BasquinTarget` CRD, an observe-only reconciler, namespaced RBAC (§6). Zero mutation risk. ✅ *(merged)*
- **P2 — injection.** Deployment patch (initContainer + volume + appended env + port), spec-hash
  idempotency, finalizer-based exact revert (**DD-024**). Verified by envtest (patch shape, append,
  idempotency, revert, Injected phase). ✅ *(this PR)* — e2e against a real pod pending the
  `basquin/agents` image; valve mounting deferred.
- **P3 — coverage Service + status.** Headless Service (`clusterIP: None`) selecting the target's
  pods on the coverage port, owner-referenced to the target (GC'd on delete), created/removed as
  `spec.coverageService` toggles; `status.coverageEndpoint` = `<svc>.<ns>.svc.cluster.local:<port>`
  for the DD-023 flag. ✅ *(this PR)* — envtest (create/endpoint/toggle-off) + in-cluster e2e.
- **P4 — docs + demo.** ✅ *(this PR)* — USAGE gains a "Kubernetes: instrument any app with the
  operator" section (install, apply-a-`BasquinTarget`, read `status.coverageEndpoint`, revert),
  ARCHITECTURE describes the operator as the deploy-time control plane, and the `deploy/k8s` README
  now points at the operator as the preferred path (the baked image kept as the no-install demo).
- **P5 — orchestration (`BasquinCampaign`).** The second CRD that fires off a whole test —
  launches the runner + dashboard against an instrumented target and aggregates status. Designed in
  full (likely **DD-025**) then implemented. See §10.

## 9. Rejected alternatives (summary)

- **Mutating admission webhook** — most seamless, but cluster-privileged, invisible at apply time,
  and a webhook bug can wedge unrelated workloads. Wrong trust/reward trade for a testing tool you
  deliberately aim (§2).
- **Bake agents into every image** (status quo) — forces a custom rebuild per target, doesn't scale
  to apps you don't own, encodes the fiddly `CATALINA_OPTS` by hand (§1).
- **Sidecar that reads the app from outside** — an external observer still has to stop the JVM to
  read stacks/heap consistently; DD-004 already settled that in-process JVMTI is the right place for
  this signal. The operator's job is to *place* that in-process agent, not replace it.
- **Java + fabric8 java-operator-sdk** — would keep the repo one language and in one build, but
  welds the control plane to the JVM/Gradle world exactly when the intent is for it to outlive that
  scope (multi-runtime, §7.1). The operator only names an image and writes flags, so it shares no
  code with the agents anyway — the "one language" benefit is smaller than it looks.

---

## 10. Operator-orchestrated test — `BasquinCampaign` (P5)

**Status (2026-07-20):** direction and CRD shape **confirmed** — two CRDs, campaign built as **P5**
after the P1–P4 injection work (now complete). The full design is written up in
[CAMPAIGN-DESIGN.md](CAMPAIGN-DESIGN.md) (the **DD-025** proposal, under review); the summary below is
the sketch it expands.

**The ask.** The operator shouldn't only *instrument* a target — it should orchestrate the **whole
test**: bring up the target's instrumentation, launch the **runner** (the coverage-guided HTTP
driver) and the **dashboard** (aggregator), wire them together, and fire the run off from a single
custom resource. "Create a test, everything starts."

**Confirmed shape: two CRDs — a `BasquinCampaign` distinct from `BasquinTarget`.**

- `BasquinTarget` (P1–P4) stays the *instrument-a-Deployment* primitive: long-lived, "this app
  carries the agents." A target can exist with no test running.
- `BasquinCampaign` is the *test* unit — ephemeral, bounded. It references a target (or a target
  selector) and specifies the driver (grammar, corpus, duration, invariants, coverage endpoints) and
  the dashboard. This is the "test CRD that fires everything off."

Why two CRDs rather than folding it into `BasquinTarget`: separation of lifecycle. A target is a
Deployment-like steady state; a campaign is a Job-like bounded run. One target may be driven by many
campaigns over time (a nightly run, an ad-hoc repro), and a campaign should be deletable without
tearing down the instrumentation. Collapsing them would force "instrumented" and "currently being
tested" to be the same state, which they aren't.

**What the campaign reconciler does** (sketch, to be fleshed out):
1. Ensure the referenced target is instrumented (reuse the `BasquinTarget` machinery, or require
   an already-`Injected` target).
2. Create/ensure the **dashboard** (aggregator `Deployment` + `Service`) for this campaign.
3. Create the **driver** as a `Job` — the coverage-guided runner — pointed at the target `Service`,
   the JaCoCo endpoints (the DD-023 headless Service), and the dashboard push endpoint.
4. Aggregate `status`: running/complete, finds, coverage %, dashboard URL.
5. Lifecycle: owner references + a finalizer tear the driver `Job` and dashboard down when the
   campaign is deleted; whether campaign deletion also reverts the target's instrumentation is a
   sub-decision (probably not, since targets can be shared).

**New build components this pulls in** (tracked in TODO Backlog → *Operator orchestration*): the
**runner** and **dashboard** need to ship as images the operator can launch, alongside the
`basquin/agents` image the injection path already needs.

**This absorbs a previously-open question.** TODO's "launch/stop campaigns from the dashboard —
probably belongs with the operator" is answered here: the *operator* owns scheduling (it already has
the RBAC and the reconcile model for it), and the dashboard stays read-only (DD-013). The control
channel a dashboard-driven launcher would have needed — effectively RCE on the dashboard host — never
gets built; the operator's existing, namespaced authority does the job instead.

**Open sub-decisions before building:** one campaign → one target or many; does the campaign embed
the driver config or reference a ConfigMap; how run results/corpora are persisted beyond the Job's
lifetime; and whether this warrants its own DD (likely yes, as `DD-025`).
