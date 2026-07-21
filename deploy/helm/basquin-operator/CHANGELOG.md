# Changelog — Helm chart

`basquin-operator` chart. The **published** chart version is the release tag: `release.yml` packages
with `--version <tag> --app-version <tag>`, overriding `Chart.yaml` — so chart version and
`appVersion` always equal the release. (`Chart.yaml`'s own `version` field only applies to local
installs from a checkout.) Release-level changelog: [`../../../CHANGELOG.md`](../../../CHANGELOG.md).

## [0.2.0] — 2026-07-21

First published chart (`helm repo add basquin https://ianp94.github.io/basquin/charts`).

### Added
- CRDs (`BasquinTarget`, `BasquinCampaign`) installed from `crds/` (Helm installs them before
  templates; Helm does **not** upgrade or delete CRDs — re-apply manually on upgrade).
- Namespaced RBAC only (Roles + RoleBindings mirroring `operator/config/rbac/role.yaml`,
  including the per-campaign dashboard-token Secret rule) — no ClusterRoles, matching the
  operator's one-release-per-namespace design.
- Controller Deployment with all four image repositories wired from values; a single `imageTag`
  value (default: the chart's `appVersion`) versions every image, so a release is one version
  input (`helm package --app-version <tag>` in `release.yml`).
- Defaults point at the published ghcr images (`ghcr.io/ianp94/basquin-*`), multi-arch
  (amd64 + arm64; arm64 build-validated only).
