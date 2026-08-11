#!/usr/bin/env bash
# Opens drive.log by freezing the manifest: records MANIFEST.md's sha256 BEFORE the run's
# first request, so the pre-declaration (obligation 1) cannot be edited to fit the outcome
# afterwards. Must be the first step of the control run; drive.sh appends to the same log.
set -euo pipefail
cd "$(dirname "$0")"
sha256sum MANIFEST.md > drive.log
echo "manifest-hash-recorded-at: $(date -u +%Y-%m-%dT%H:%M:%SZ) (before app start, before any request of the run this log records)" >> drive.log
cat drive.log
