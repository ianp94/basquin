#!/usr/bin/env bash
# Run the load half of the benchmark battery for one app and collect its metrics.
#
# Load runs are executed STRICTLY ONE AT A TIME. The bench cluster is a single kind
# node, so two concurrent drivers contend for the same CPU and each one's latency
# percentiles then measure the other driver as much as the app — the numbers would be
# unusable for a published comparison. This script therefore blocks until each
# campaign reaches a terminal phase before starting the next.
#
# Usage:
#   deploy/bench/battery.sh <app> <corpusConfigMap> <classesPath> [concurrency...]
#
# Example:
#   deploy/bench/battery.sh jspwiki jspwiki-dd038-explore-corpus-out \
#       /usr/local/tomcat/webapps/ROOT/WEB-INF/classes 1 8
set -euo pipefail

APP="${1:?app name (the BasquinTarget name)}"
CORPUS="${2:?corpus ConfigMap produced by an explore run}"
CLASSES="${3:?classesPath}"
shift 3
LEVELS=("${@:-1 8}")

NS=basquin-system
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DURATION="${DURATION:-5m}"
WARMUP="${WARMUP:-30s}"

for c in "${LEVELS[@]}"; do
  NAME="${APP}-bench-load-c${c}"
  echo "==> ${NAME}  (concurrency=${c}, warmup=${WARMUP}, duration=${DURATION})"
  kubectl delete basquincampaign "$NAME" -n "$NS" --ignore-not-found >/dev/null

  kubectl apply -n "$NS" -f - <<EOF >/dev/null
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata:
  name: ${NAME}
  namespace: ${NS}
spec:
  mode: load
  targetRef:
    name: ${APP}
  baseURL: http://${APP}-app.${NS}.svc.cluster.local:8080
  driver:
    classesPath: ${CLASSES}
    corpusConfigMap: ${CORPUS}
    concurrency: ${c}
    warmup: ${WARMUP}
    duration: ${DURATION}
EOF

  # Poll to a terminal phase. The driver Job's own deadline bounds this; the extra
  # ceiling here only stops a wedged campaign from hanging the whole battery.
  for _ in $(seq 1 80); do
    phase="$(kubectl get basquincampaign "$NAME" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in
      Completed|Failed) break ;;
    esac
    sleep 15
  done
  # The poll above is bounded, so a campaign that never terminates falls through it. Collecting and
  # then STARTING THE NEXT ONE would break the one-campaign-at-a-time guarantee this script exists
  # to provide, and silently corrupt both runs' numbers. Refuse instead.
  case "${phase:-}" in
    Completed|Failed) ;;
    *) echo "!! ${NAME} did not reach a terminal phase (phase=${phase:-unknown}); aborting the"
       echo "!! battery rather than starting another campaign beside it."
       exit 1 ;;
  esac
  echo "    phase=${phase}"
  python3 "$ROOT/deploy/bench/collect.py" "$NAME"
done

echo "==> battery complete for ${APP}"
