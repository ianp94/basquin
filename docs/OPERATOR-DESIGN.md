# ClosureJVM injection operator — design proposal

**Status:** proposed, under review. Not yet implemented. On approval this becomes **DD-024** and
the implementation lands behind it.

**Decision being proposed (2026-07-20):** an *explicit patch controller* — a namespaced operator
that instruments only the Deployments you name in a `ClosureJVMTarget` custom resource. No mutating
admission webhook; nothing is intercepted or rewritten unless you asked for it by name.

---

## 1. Problem

Today, running a target under ClosureJVM in Kubernetes means baking the agents into the image
(`deploy/k8s/Dockerfile.jpetstore`: three `COPY`s plus a hand-written `CATALINA_OPTS` with two
`-javaagent`s, `-Xbootclasspath/a`, and the invariant flags). That is fine for a demo but wrong for
real use:

- It forces a **custom image** of every app you want to test — you can't point ClosureJVM at an app
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
  have nothing to do with ClosureJVM. A bug or a bad `failurePolicy` can wedge unrelated
  deployments across the cluster.
- It needs a TLS-served webhook endpoint, cert rotation, and careful `namespaceSelector` /
  `objectSelector` scoping just to *not* touch things.
- The mutation is invisible at apply time — you can't `kubectl diff` your way to what will run.

The explicit-patch controller inverts all of that. You create a `ClosureJVMTarget` naming one
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

## 3. The `ClosureJVMTarget` custom resource

Namespaced CRD, group `closurejvm.dev/v1alpha1`. The CR is the entire user-facing API.

```yaml
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMTarget
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
      includes: "org.mybatis.jpetstore.*"   # NOT "*" — see DD-022

  # HOW the JVM picks up the agents. Tomcat images honor CATALINA_OPTS; everything else uses
  # JAVA_TOOL_OPTIONS (the JVM appends it to every launch). The operator picks per `jvmOptsVar`.
  jvmOptsVar: CATALINA_OPTS       # or JAVA_TOOL_OPTIONS (default)

  # Invariant config passed straight through as -Dclosurejvm.invariant.* flags.
  invariants:
    mode: soft
    latencyMaxMs: 25
    heapDeltaMaxKb: 256

  # Where server-side findings/status push to (the standalone dashboard, DD-013).
  dashboardPush: "closurejvm-dashboard.demo.svc:7070"

  # Create a headless Service selecting this target's pods on the coverage port, so one driver can
  # reach every replica by DNS and DD-023's getAllByName expands it to all pod IPs. Opt-in.
  coverageService: true

status:
  phase: Injected                 # Pending | Injecting | Injected | Reverting | Error
  observedGeneration: 3
  instrumentedReplicas: 3
  coverageEndpoint: "jpetstore-closurejvm-jacoco.demo.svc:6300"   # for the driver flag
  conditions:
    - { type: Ready, status: "True", reason: RolloutComplete }
```

The spec is intentionally a superset of the hand-written `CATALINA_OPTS` in the demo Dockerfile —
anything that string encodes today, the CR encodes declaratively.

## 4. How agents get into an unmodified pod

The target image does **not** contain our agents, so we cannot just reference a path. Use the
standard "copy via init container + shared volume" pattern (the same shape the OpenTelemetry and
Istio operators use):

1. The operator patches the target Deployment's pod template to add:
   - a small **initContainer** running a `closurejvm/agents:<version>` image whose only job is
     `cp /agents/* /closurejvm/` into…
   - a shared **`emptyDir` volume** mounted at `/closurejvm` in both the initContainer and the app
     container, and
   - an **env var** (`jvmOptsVar`) whose value is the assembled agent string pointing at
     `/closurejvm/closurejvm-agent.jar`, `/closurejvm/jacocoagent.jar`, etc., plus the
     `-Dclosurejvm.*` flags from the spec, plus the JaCoCo `-javaagent` with the scoped `includes`.
   - the coverage **containerPort** (6300) if coverage is enabled.

2. If `coverageService: true`, the operator also creates a **headless Service**
   (`clusterIP: None`) selecting the target's pods on the coverage port. Its DNS name resolves to
   every pod IP, which is exactly what `JacocoCoverageProvider.parseEndpoints` +
   `InetAddress.getAllByName` consume for union coverage (DD-023). The resolved name is written to
   `status.coverageEndpoint` so the driver flag is copy-pasteable.

Because the agent binaries ship in a versioned image the operator controls, upgrading agents is an
operator/image bump, never a target rebuild.

## 5. Reconcile loop

Level-triggered, idempotent — the controller reconciles desired state, it does not apply diffs:

1. **Observe** a `ClosureJVMTarget`. Load the referenced Deployment.
2. **Compute** the desired pod-template patch from the spec (initContainer, volume, env, port).
3. **Compare** against what's already there (identified by a `closurejvm.dev/injected: <hash>`
   annotation recording the spec hash we last applied, plus the original `jvmOptsVar` value stashed
   in `closurejvm.dev/original-<var>` so revert is exact). If the hash matches, do nothing —
   reconciling a steady target is a no-op, so this is safe to run on every resync.
4. **Patch** the Deployment if drift is detected; a rollout instruments the pods.
5. **Report** rollout progress into `status` (`instrumentedReplicas`, `Ready` condition).

**Revert via finalizer.** The CR carries a finalizer. On deletion, the controller first removes the
initContainer/volume/env/port from the Deployment (restoring `jvmOptsVar` from the stashed original
annotation) and deletes the coverage Service, *then* clears the finalizer. Deleting a
`ClosureJVMTarget` therefore returns the app to exactly its pre-instrumentation state — no orphaned
agents, no leftover env.

## 6. RBAC — the trust boundary, concretely

Shipped as a namespaced install by default:

- `Role` (namespaced): `get/list/watch` on `closurejvmtargets`; `get/list/watch/update/patch` on
  `deployments`; `get/create/update/delete` on `services` (for the coverage Service); `update` on
  `closurejvmtargets/status` and `/finalizers`.
- `RoleBinding` to the operator's `ServiceAccount` in that namespace.
- **No `ClusterRole`, no webhook configuration object, no cluster-scoped verbs.** To instrument
  targets in another namespace you install another instance (or knowingly opt into a broader
  binding) — breadth is a deliberate act, never the default.

This is the whole reason to prefer this model: the standing privilege is "patch Deployments in one
namespace," which is auditable and bounded, versus "mutate any pod at admission time."

## 7. Open decisions to settle before coding

1. **Language / SDK.** The codebase is Java with a deliberately thin dependency set. Two realistic
   paths:
   - **Java, fabric8 `java-operator-sdk`** — stays in-language and in-repo, reuses the existing
     Gradle build; heavier runtime deps than the rest of the project.
   - **Go, controller-runtime (kubebuilder)** — the idiomatic operator stack with the best tooling,
     but introduces a second language and build to the repo.
   Recommendation: **Java/JOSDK**, to keep one language and let the operator share the agent-version
   constants with the build. Flagging for your call — this is the kind of core/infra choice
   agents.md reserves for you.
2. **Non-Tomcat JVM opts.** `JAVA_TOOL_OPTIONS` works for any JVM but the agent also wants
   `-Xbootclasspath/a` (as the demo does). Confirm bootclasspath append is needed outside Tomcat,
   or scope the valve/bootclasspath bits to `jvmOptsVar: CATALINA_OPTS` targets only.
3. **Dashboard as control plane.** The operator writes status the dashboard could surface (fleet of
   instrumented targets). That edges toward the dashboard-as-control-plane question still open in
   TODO.md; this design keeps the operator authoritative and the dashboard read-only for now.

## 8. Phased delivery (each phase its own PR)

- **P0 — this doc.** Agree the model and settle §7.
- **P1 — CRD + no-op controller.** Install the `ClosureJVMTarget` CRD and a controller that only
  writes `status` (observes, never patches). Proves the wiring with zero mutation risk.
- **P2 — injection.** Deployment patch (initContainer + volume + env + port), spec-hash idempotency,
  finalizer revert. Verified against the existing kind/JPetStore setup by instrumenting the
  **stock** JPetStore image instead of the baked one.
- **P3 — coverage Service + status.** Headless Service, `status.coverageEndpoint`, wire it to the
  DD-023 driver flag end to end across replicas.
- **P4 — docs + demo.** Replace the bake-it-into-the-image path in `deploy/k8s` with an
  apply-a-CR path; USAGE + ARCHITECTURE updates; record **DD-024**.

## 9. Rejected alternatives (summary)

- **Mutating admission webhook** — most seamless, but cluster-privileged, invisible at apply time,
  and a webhook bug can wedge unrelated workloads. Wrong trust/reward trade for a testing tool you
  deliberately aim (§2).
- **Bake agents into every image** (status quo) — forces a custom rebuild per target, doesn't scale
  to apps you don't own, encodes the fiddly `CATALINA_OPTS` by hand (§1).
- **Sidecar that reads the app from outside** — an external observer still has to stop the JVM to
  read stacks/heap consistently; DD-004 already settled that in-process JVMTI is the right place for
  this signal. The operator's job is to *place* that in-process agent, not replace it.
