# closurejvm-operator Helm chart

Deploy the ClosureJVM Kubernetes operator: the CRDs (`ClosureJVMTarget`, `ClosureJVMCampaign`),
namespaced RBAC, and the controller Deployment — with the three images it launches/injects wired via
values (no manual `kubectl patch` of the controller args, unlike the raw kustomize install).

The operator is **namespaced by design**: it watches and mutates only its own namespace
(`WATCH_NAMESPACE` from the release namespace via the downward API; it refuses to start if empty).
Install **one release per namespace** you want to instrument. There is no cluster-wide mode.

## Install from the published Helm repo (no clone)

The chart is published to a GitHub Pages Helm repo, and its default images to GitHub Container
Registry (`ghcr.io/ianp94/closurejvm-*`) — so a plain `helm install` pulls real images:

```bash
helm repo add closurejvm https://ianp94.github.io/closureJVM/charts
helm repo update
helm install closurejvm closurejvm/closurejvm-operator \
  --namespace closurejvm-system --create-namespace \
  --set fullnameOverride=closurejvm
```

## Install from a checkout

```bash
helm install closurejvm ./deploy/helm/closurejvm-operator \
  --namespace closurejvm-system --create-namespace \
  --set fullnameOverride=closurejvm
# ...add --set imageTag=… + --set image.repository=… / images.*=… to pin local (kind-loaded) images.
```

`--set fullnameOverride=closurejvm` makes resources read as `closurejvm-controller-manager`,
`closurejvm-manager-role`, … matching the rest of the docs; omit it for the default `<release>-<chart>`
prefix. The in-cluster e2e (`deploy/e2e/e2e.sh`) exercises this chart end to end with
`INSTALL=helm deploy/e2e/e2e.sh` (it `--set`s the locally-built `closurejvm/*` images).

## Quickstart

Two custom resources: a `ClosureJVMTarget` instruments a Deployment; a `ClosureJVMCampaign` runs a
test against it. Both are namespaced.

**1. Instrument an app** — point a target at an existing Deployment; the operator patches it to load
the agents and (optionally) exposes a coverage Service. Wait for `status.phase: Injected`.

```yaml
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMTarget
metadata: { name: myapp, namespace: closurejvm-system }
spec:
  deploymentRef: { name: myapp }
  container: app                 # required when the pod has >1 container
  jvmOptsVar: JAVA_TOOL_OPTIONS   # or CATALINA_OPTS for Tomcat; agent flags are appended
  agents:
    threadTracker: true
    coverage: { enabled: true, port: 6300, includes: "com.example.*" }   # includes REQUIRED when enabled
  invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }
  coverageService: true
```

**2. Run a coverage-guided test** — once the target is `Injected`. `baseURL` is the app's in-cluster
Service URL (you create the app Service). Grammar/corpus are optional ConfigMaps of routes/values.

```yaml
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMCampaign
metadata: { name: myapp-campaign, namespace: closurejvm-system }
spec:
  targetRef: { name: myapp }
  baseURL: http://myapp.closurejvm-system.svc.cluster.local:8080
  driver:
    iterations: 200                        # or duration: "10m"
    grammarConfigMap: myapp-grammar         # optional
    corpusConfigMap:  myapp-corpus          # optional
    classesPath: /usr/local/tomcat/webapps/ROOT/WEB-INF/classes   # where .class files live in the image
```

An explore run emits its interesting inputs as `status.corpusConfigMap` (`<campaign>-corpus-out`).

**3. Or replay that corpus under load** — `mode: load` hammers the saved corpus at a fixed
concurrency for a duration, watching the same invariants:

```yaml
apiVersion: closurejvm.dev/v1alpha1
kind: ClosureJVMCampaign
metadata: { name: myapp-load, namespace: closurejvm-system }
spec:
  mode: load
  targetRef: { name: myapp }
  baseURL: http://myapp.closurejvm-system.svc.cluster.local:8080
  driver: { duration: 30m, concurrency: 50, corpusConfigMap: myapp-campaign-corpus-out }
```

**4. Read results / open the dashboard:**

```bash
kubectl -n closurejvm-system get closurejvmcampaigns
# NAME             TARGET   PHASE       COVERAGE   FINDINGS   AGE
# myapp-campaign   myapp    Completed   22.5       44         5m
kubectl -n closurejvm-system get closurejvmcampaign myapp-load -o jsonpath='{.status.load}'   # load metrics
kubectl -n closurejvm-system port-forward svc/myapp-campaign-dashboard 7070:7070             # then open :7070
```

Field-by-field reference, grammar authoring, and troubleshooting are in the project's
`docs/OPERATOR-USAGE.md`.

## Publishing (maintainer)

`git tag v0.2.0 && git push origin v0.2.0` triggers [`release.yml`](../../../.github/workflows/release.yml),
which does it all: builds + pushes the four images to ghcr, attaches the CLI binaries + packaged chart
to the GitHub Release, and — in the `pages` job — repackages the chart into `docs/charts/`, regenerates
`index.yaml`, and commits it back to `main` so the Pages Helm repo updates itself. `helm package`
runs with `--app-version <tag>`, so the single `imageTag` resolves every image to that version — the
tag is the only version input. `deploy/helm/publish.sh` remains for a manual/bootstrap Pages refresh.

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
- One `imageTag` value (default: the chart's `appVersion`) sets all four image tags, so a release is
  a single version input.
