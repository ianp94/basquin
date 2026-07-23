#!/usr/bin/env bash
# In-cluster smoke test: prove Roller is SEEDED and rendering, not just returning 200.
# A Roller that lost its schema, or one showing the install wizard, also answers 200 on every route —
# so the check has to be for entry text, not for a status code.
set -u
NS=basquin-system
SVC=http://roller-app.$NS.svc.cluster.local:8080
POD=$(kubectl -n $NS get pods -l app=roller-raw -o jsonpath='{.items[0].metadata.name}')
echo "# pod: $POD"
echo "# --- weblog home: entry titles rendered ---"
kubectl -n $NS exec "$POD" -- curl -s "$SVC/basquin/" | grep -oE "Bench entry [0-9]+" | sort -u | head -5
echo "# --- entry permalink + seeded comments ---"
kubectl -n $NS exec "$POD" -- curl -s "$SVC/basquin/entry/bench-entry-05" \
  | grep -oE "Bench entry 05|Comment [12] on entry 5" | sort -u
echo "# --- search (Lucene index auto-rebuilt from the SQL seed) ---"
kubectl -n $NS exec "$POD" -- curl -s "$SVC/basquin/search?q=paragraph" \
  | grep -oE "Bench entry [0-9]+" | sort -u | wc -l
echo "# --- route timings ---"
for p in "/basquin/" "/basquin/entry/bench-entry-05" "/basquin/date/202607" \
         "/basquin/feed/entries/rss" "/basquin/search?q=paragraph"; do
  echo "$(kubectl -n $NS exec "$POD" -- curl -s -o /dev/null -w '%{http_code} %{time_total}s' "$SVC$p")  $p"
done
