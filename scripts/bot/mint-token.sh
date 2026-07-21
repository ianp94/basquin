#!/usr/bin/env bash
# Mint a short-lived (1h) GitHub App installation token from an App ID + private key.
# Usage: APP_ID=<id> APP_KEY=/path/to.pem [REPO=ianp94/basquin] bash mint-token.sh
# Prints the installation token (ghs_...) to stdout. Pure openssl JWT — no extra deps.
set -euo pipefail
: "${APP_ID:?set APP_ID}"; : "${APP_KEY:?set APP_KEY (path to .pem)}"
REPO="${REPO:-ianp94/basquin}"
b64url(){ openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' $((now-60)) $((now+540)) "$APP_ID" | b64url)
sig=$(printf '%s' "$header.$payload" | openssl dgst -sha256 -sign "$APP_KEY" | b64url)
jwt="$header.$payload.$sig"
# Find the installation for the repo's owner, then mint a repo-scoped token.
inst_id=$(curl -sf -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/installation" | grep -m1 '"id"' | grep -oE '[0-9]+')
[ -n "$inst_id" ] || { echo "no installation found for $REPO (is the app installed on it?)" >&2; exit 1; }
curl -sf -X POST -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/app/installations/$inst_id/access_tokens" \
  | grep -m1 '"token"' | sed -E 's/.*"token": *"([^"]+)".*/\1/'
