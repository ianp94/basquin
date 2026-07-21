# Changelog — Kubernetes operator + CLI

The `basquin.dev/v1alpha1` control plane (Go / kubebuilder) and the `basquin` CLI.
Release-level changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## [0.2.0] — 2026-07-21

First published release (`ghcr.io/ianp94/basquin-operator`; CLI binaries on the GitHub Release).

### Added
- **`BasquinTarget`** (DD-024): explicit, reversible instrumentation of a named Deployment —
  initContainer + shared `emptyDir` + agent flags appended to `jvmOptsVar` — with spec-hash
  idempotency, content-drift re-healing, finalizer-based byte-exact revert, and a headless
  coverage Service (`status.coverageEndpoint`) for union coverage across replicas. Deliberately
  **not** a mutating admission webhook.
- **`BasquinCampaign`** (DD-025): gates on an Injected target, launches the driver Job
  (coverage-classes initContainer from the target's image, summary via termination message),
  phase machine + `TargetGone`; `mode: explore` emits interesting inputs as a campaign-owned
  `<campaign>-corpus-out` ConfigMap; `mode: load` replays a corpus at fixed concurrency and
  publishes throughput/latency/drift to `status.load`.
- **Per-campaign dashboard**: owner-referenced Deployment + Service, `status.dashboardURL`, and a
  per-campaign 256-bit token Secret wired into dashboard + driver via `SecretKeyRef` + `$(VAR)`
  expansion, authenticating every push (write path; reads remain open — single-tenant).
- **Namespaced by design**: Role/RoleBinding only, `WATCH_NAMESPACE` required, no cluster-wide
  mode, no webhooks.
- **`basquin` CLI**: `instrument` (apply a target, `--wait`), `run` (grammar/corpus ConfigMaps
  from local files + apply a campaign, `--watch`), `status` (tables, `--watch`), `dashboard`
  (self-contained port-forward), `version` (tag-stamped; six cross-compiled binaries).
- **Verification**: envtest suite + an in-cluster kind e2e in CI (Helm install, raw JPetStore,
  explore + load campaigns, dashboard, zero RBAC errors).

### Known limitations
- War-only target images: the coverage-classes initContainer needs an exploded `WEB-INF/classes`
  in the target image; an unexploded `ROOT.war` fails loud (`verify-classes`) rather than
  reporting a silent 0%. Workaround: explode the war at image-build time.
