# closurejvm-operator Helm chart

Deploy the ClosureJVM Kubernetes operator: the CRDs (`ClosureJVMTarget`, `ClosureJVMCampaign`),
namespaced RBAC, and the controller Deployment — with the three images it launches/injects wired via
values (no manual `kubectl patch` of the controller args, unlike the raw kustomize install).

The operator is **namespaced by design**: it watches and mutates only its own namespace
(`WATCH_NAMESPACE` from the release namespace via the downward API; it refuses to start if empty).
Install **one release per namespace** you want to instrument. There is no cluster-wide mode.

## Install

```bash
# From a checkout (chart isn't published to a repo yet):
helm install closurejvm ./deploy/helm/closurejvm-operator \
  --namespace closurejvm-system --create-namespace \
  --set fullnameOverride=closurejvm \
  --set image.tag=0.2.0 \
  --set images.agents=closurejvm/agents:0.2.0 \
  --set images.runner=closurejvm/runner:0.2.0 \
  --set images.dashboard=closurejvm/dashboard:0.2.0
```

`--set fullnameOverride=closurejvm` makes resources read as `closurejvm-controller-manager`,
`closurejvm-manager-role`, … matching the rest of the docs; omit it to get the default
`<release>-<chart>` prefix. The in-cluster e2e (`deploy/e2e/e2e.sh`) exercises this chart end to end
with `INSTALL=helm deploy/e2e/e2e.sh`.

Then follow [docs/OPERATOR-USAGE.md](../../../docs/OPERATOR-USAGE.md): apply a `ClosureJVMTarget` to
instrument an app, then a `ClosureJVMCampaign` to run a coverage-guided test.

## Key values

| Value | Default | Purpose |
|-------|---------|---------|
| `image.repository` / `image.tag` | `closurejvm/operator` / `0.2.0` | the controller image |
| `images.agents` | `closurejvm/agents:0.2.0` | `--agents-image` (injection initContainer) |
| `images.runner` | `closurejvm/runner:0.2.0` | `--runner-image` (campaign driver Job) |
| `images.dashboard` | `closurejvm/dashboard:0.2.0` | `--dashboard-image` (per-campaign dashboard) |
| `leaderElect` | `true` | leader election (adds a namespaced leader-election Role) |
| `resources` | 10m/64Mi → 500m/128Mi | controller resource requests/limits |

See [`values.yaml`](values.yaml) for the full set.

## CRDs

CRDs live in `crds/` (Helm installs them before templates). **Helm does not upgrade or delete CRDs** —
on `helm upgrade`, re-apply changed CRDs manually; on `helm uninstall`, CRDs and any remaining CRs are
left in place. Keep `crds/` and the RBAC in step with the operator's generated manifests with
[`deploy/helm/sync-generated.sh`](../sync-generated.sh) after `make manifests`.

## Notes

- RBAC is **Roles + RoleBindings** (namespaced), not ClusterRoles — matching the operator's
  namespaced design. The rules mirror `operator/config/rbac/role.yaml`.
- This chart is not yet published to a Helm repository; install from a checkout.
