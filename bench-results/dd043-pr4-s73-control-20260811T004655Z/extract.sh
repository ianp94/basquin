#!/usr/bin/env bash
# Derived per-method record for the control claims (committed as flip-control.out).
# Uses the 2x2's committed flip.py. NOTE the class substring must be 'rest/VillainResource':
# the bare substring 'VillainResource' first matches HelloVillainResource ("Hello" +
# "VillainResource") in these XMLs' document order, and flip.py breaks on first match, so
# with the bare substring it prints NO method lines (Hello has none of these methods).
set -euo pipefail
cd "$(dirname "$0")"
python3 ../dd043-pr4-2x2-2026-08-10/flip.py \
  rest/VillainResource getVillain,getRandomVillain,getAllVillains t0.xml t1.xml \
  | tee flip-control.out
