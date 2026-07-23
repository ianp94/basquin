#!/usr/bin/env bash
# Latency/status probe over Roller's public surface — the "curl -w %{time_total} first" step from
# ONBOARDING.md §5, used to aim the grammar at the expensive routes. Usage: probe.sh [baseURL]
B="${1:-http://localhost:8095}"
for p in "/" "/basquin/" "/basquin/entry/bench-entry-05" "/basquin/category/Tech" \
         "/basquin/tags/bench" "/basquin/date/202607" "/basquin/feed/entries/rss" \
         "/basquin/feed/entries/atom" "/basquin/feed/comments/rss" "/basquin/search?q=velocity" \
         "/basquin/page/searchresults?q=render" "/roller-ui/login.rol" "/roller-ui/register.rol" \
         "/roller-services/opensearch/" "/roller-ui/rendering/rsd/basquin"; do
  printf '%s\n' "$(curl -s -o /dev/null -w '%{http_code} %{time_total}s %{size_download}B' "$B$p")  $p"
done
