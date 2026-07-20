# ClosureJVM operator

A Kubernetes operator that instruments a named Deployment with the ClosureJVM agents. Design and
rationale: [`../docs/OPERATOR-DESIGN.md`](../docs/OPERATOR-DESIGN.md). Built with kubebuilder /
controller-runtime as a self-contained Go module so the control plane stays runtime-agnostic (the
Gradle/JVM build is untouched).

## Status: P1 — scaffold + CRD + no-op controller

This is the first, deliberately **zero-mutation** phase. The controller only *observes*:

- It watches `ClosureJVMTarget` resources and reads (never writes) the Deployment each one names.
- It reports what it sees into `status` — `phase: Pending`, `observedGeneration`, and a `Ready`
  condition (`DeploymentNotFound` if the target is missing, else `ObserveOnly` stating plainly that
  this build does not inject yet).
- `instrumentedReplicas` is always `0` here — **no pod is ever patched in P1.**

Injection (the Deployment patch, finalizer, and exact revert) lands in **P2**, which is where this
records DD-024. See the phased plan in the design doc §8.

## The `ClosureJVMTarget` API

Group `closurejvm.dev`, version `v1alpha1`, **namespaced**. The spec is the whole user-facing API —
name a Deployment, choose which agents to inject and how the JVM picks them up. See
[`config/samples/closurejvm_v1alpha1_closurejvmtarget.yaml`](config/samples/closurejvm_v1alpha1_closurejvmtarget.yaml)
for a full example mirroring the JPetStore demo.

## Trust model (RBAC)

The operator is **namespaced**: its standing privilege is confined to the one namespace it runs in
(design doc §6). Concretely, in the rendered install:

- **No `ClusterRoleBinding`, no admission webhook** — nothing grants cluster-wide power.
- The manager's permissions come from a `ClusterRole` (`manager-role`) bound by a **`RoleBinding`**,
  not a `ClusterRoleBinding`. Binding a ClusterRole via a RoleBinding grants its verbs *only within
  that namespace* — the ClusterRole is just a reusable permission set (controller-gen regenerates
  it; the RoleBinding that scopes it is hand-maintained and is not regenerated).
- The manager scopes its cache/watches to `WATCH_NAMESPACE` (set from the pod's namespace via the
  downward API) and **refuses to start** if it is unset — the boundary can't erode by a missing env.
- In P1 the `apps/deployments` grant is **read-only** (`get;list;watch`); write verbs arrive with
  P2's injection.
- The kube-rbac-proxy metrics sidecar is disabled (it needs a cluster-scoped
  `system:auth-delegator` binding); metrics stay on a pod-local port.

To instrument targets in another namespace, install a second copy there — breadth is a deliberate
act, never the default.

## Develop

Requires Go 1.21 (controller-runtime 0.17). `make` bootstraps `controller-gen` into `./bin`.

```bash
make generate manifests   # regenerate deepcopy + CRD/RBAC from the Go types & markers
make build                # compile the manager binary
go vet ./...              # also compiles the envtest suite
kubectl kustomize config/default   # render the full install to inspect it
```

`make test` runs the envtest suite, which needs the `KUBEBUILDER_ASSETS` control-plane binaries
(`setup-envtest`); it is not wired into the Java CI and is run on demand.

## Deploy (P1)

```bash
make install                       # apply the CRD
make deploy IMG=<your-registry>/closurejvm-operator:tag   # apply the namespaced install
kubectl apply -f config/samples/   # create a ClosureJVMTarget
kubectl get closurejvmtargets      # watch status: phase/instrumented/age
```

Because P1 never mutates a workload, deploying it against a real app is safe — the worst it can do
is write status onto its own CRs.
