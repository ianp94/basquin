#!/usr/bin/env bash
# Keep the Helm chart's generated content in step with the operator's kubebuilder output.
#
# Copies the generated CRDs into the chart's crds/. The chart's manager Role
# (templates/rbac.yaml) MIRRORS operator/config/rbac/role.yaml by hand — this script prints a diff
# reminder if the generated rules changed, since a Role's rules can't be blindly copied (the chart
# ships a namespaced Role, the generated file is a ClusterRole).
#
# Run after `make manifests` in operator/ whenever CRDs or RBAC markers change.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART="$ROOT/deploy/helm/closurejvm-operator"

echo "==> Syncing CRDs into the chart"
cp "$ROOT/operator/config/crd/bases/closurejvm.dev_closurejvmtargets.yaml"   "$CHART/crds/"
cp "$ROOT/operator/config/crd/bases/closurejvm.dev_closurejvmcampaigns.yaml" "$CHART/crds/"

echo "==> Reminder: the chart's manager Role mirrors operator/config/rbac/role.yaml."
echo "    If you changed +kubebuilder:rbac markers, reconcile templates/rbac.yaml by hand against:"
echo "      $ROOT/operator/config/rbac/role.yaml"
echo "==> Done."
