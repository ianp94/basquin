#!/usr/bin/env bash
# Publish the operator Helm chart to the GitHub Pages Helm repo under docs/charts/ (served at
# https://ianp94.github.io/basquin/charts). Run on a chart version bump, then commit
# docs/charts/*.tgz + docs/charts/index.yaml — Pages then serves an installable Helm repo:
#
#   helm repo add basquin https://ianp94.github.io/basquin/charts
#   helm repo update
#   helm install basquin basquin/basquin-operator --namespace basquin-system --create-namespace
#
# The chart version comes from Chart.yaml; bump it there before publishing a new release.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART="$REPO_ROOT/deploy/helm/basquin-operator"
OUT="$REPO_ROOT/docs/charts"
PAGES_URL="https://ianp94.github.io/basquin/charts"

command -v helm >/dev/null || { echo "ERROR: helm not found" >&2; exit 1; }

echo "==> Linting the chart"
helm lint "$CHART"

echo "==> Packaging into $OUT/"
mkdir -p "$OUT"
helm package "$CHART" --destination "$OUT"

echo "==> Regenerating the repo index (merging any existing entries)"
if [ -f "$OUT/index.yaml" ]; then
  helm repo index "$OUT" --url "$PAGES_URL" --merge "$OUT/index.yaml"
else
  helm repo index "$OUT" --url "$PAGES_URL"
fi

echo "==> Done. Commit docs/charts/*.tgz + docs/charts/index.yaml, then:"
echo "    helm repo add basquin $PAGES_URL && helm repo update"
