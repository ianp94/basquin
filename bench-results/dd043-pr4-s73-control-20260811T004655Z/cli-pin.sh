#!/usr/bin/env bash
# Records the identity of the jacoco-cli jar this control's analysis actually ran, so the
# committed record does not depend on the gitignored spike .m2 mirror it lives in.
# Output committed as cli-pin.txt.
set -euo pipefail
cd "$(dirname "$0")"
CLI=/mnt/c/Users/ianpa/OneDrive/Documents/GitHub/closureJVM/bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar
{
  echo "jacoco-cli jar used by analyze-control.sh (basename): $(basename "$CLI")"
  echo "sha1 (computed now): $(sha1sum "$CLI" | cut -d' ' -f1)"
  echo "sha1 (the pinned .sha1 file beside it): $(cat "$CLI.sha1")"
  echo "note: the jar lives in the Phase-0 spike's .m2 mirror, which is gitignored (not in"
  echo "this repo's tracked tree); this file is the committed pin of what actually ran."
} | tee cli-pin.txt
