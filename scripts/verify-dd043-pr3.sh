#!/usr/bin/env bash
# Sequentially verify DD-043 PR-3 (basquin-maven-injector) and write a fresh results directory.
#
# Usage:
#   bash scripts/verify-dd043-pr3.sh                 # fast stages only (unit, jar, guards) — no docker
#   bash scripts/verify-dd043-pr3.sh all             # everything, including the ~15-min native build
#   bash scripts/verify-dd043-pr3.sh unit jar        # named stages, in the order given
#   bash scripts/verify-dd043-pr3.sh all --allow-dirty  # full run against a dirty tree anyway —
#                                                     # RESULTS.md comes out stamped NON-CITABLE
#   APP_DIR=/path/to/rest-villains bash scripts/verify-dd043-pr3.sh jvm
#
# A FULL run (`all`, or the same five stages named explicitly) is the run of record and REFUSES to
# start against a dirty or unmeasurable tree, exit 3, unless --allow-dirty is passed — see the
# IS_FULL / --allow-dirty comments below. A fast/partial run (the default, or any named subset) is
# never refused; a dirty tree there just gets a NON-CITABLE RESULTS.md, same as always.
#
# Stages:
#   unit    ./gradlew check — whole-suite test count and failures, read from the JUnit XML
#   jar     the injector jar's integrity properties: the Sisu index exists, it names a class
#           actually present in the jar, the baked version matches build.gradle, and
#           basquin-init.gradle's hard-coded default injected version has not drifted from it
#   guards  mutation-checks each fail-loudly guard: neuter it, confirm ITS OWN test fails, revert
#   jvm     spec §5.2 half 1 — rest-villains instrumented with ZERO edits to its source, JVM mode
#   native  spec §5.2 half 2 — the Phase-0 fixture instrumented and built as a native image
#
# Results land in bench-results/verify-<UTC timestamp>/ with a RESULTS.md whose every number is
# derived from an artifact in that directory. Nothing is hand-typed; a figure with no artifact behind
# it is a figure that drifts.
#
# WHY THE `jar` STAGE EXISTS AT ALL, since the unit suite passes without it:
# Maven discovers a core extension through META-INF/sisu/javax.inject.Named, not by scanning @Named.
# Gradle does not generate that index (annotation processors run only from the `annotationProcessor`
# configuration, and this module's Maven artifacts are compileOnly). Without the index the jar builds,
# every unit test passes — they drive inject() directly and bypass container wiring — and Maven never
# instantiates the participant, so a target application builds GREEN and COMPLETELY UNINSTRUMENTED.
# That is the failure this whole design exists to prevent, and only this stage catches it cheaply.
#
# SAFETY, deliberate and worth reading before the first run:
#   * Containers: this script only ever touches containers it created, all named `verify-pr3-*`.
#     It never stops, removes, or inspects anything else. (This machine carries unrelated containers.)
#   * Local Maven repos: the `jvm` and `native` stages must purge `com/basquin` from the repo the build
#     under test uses, or resolution can succeed from a stale local copy and the run measures nothing.
#     Everything removed is reproducible with `./gradlew publishToMavenLocal`; pass --restore-m2 to
#     republish into ~/.m2 at the end.
#   * The target application tree: the `jvm` stage REFUSES to run if the clone is dirty, rather than
#     reverting your work. Zero edits to that tree is the property under test, so the script verifies
#     it before AND after the build and fails if anything appears.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="bench-results/verify-$TS"
PORT="${BASQUIN_VERIFY_PORT:-8000}"
RESTORE_M2=0
ALLOW_DIRTY=0
STAGES=()

for arg in "$@"; do
  case "$arg" in
    --restore-m2)  RESTORE_M2=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    all)          STAGES=(unit jar guards jvm native) ;;
    unit|jar|guards|jvm|native) STAGES+=("$arg") ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done
[ ${#STAGES[@]} -eq 0 ] && STAGES=(unit jar guards)

# A FULL run (every stage — `all`, or the same five named explicitly) is the one whose RESULTS.md
# is meant to become the run of record: round-11's approver found the DIRTY marker below is
# annotation, not a gate — a dirty tree still produces a full page of PASS rows, exit 0, and
# nothing stops it from being cited or committed as such. A fast dev-loop invocation (the no-args
# default, or any named subset) is deliberately NOT gated here: running `guards` against
# in-progress edits is exactly what the fast loop is for, and gating it would punish the workflow
# this script's own header comment documents. Only completeness decides FULL, never the literal
# spelling `all` vs `unit jar guards jvm native` typed out — an accidental way to spell the same
# run must not dodge the same gate.
IS_FULL=1
for _want in unit jar guards jvm native; do
  _found=0
  for _s in "${STAGES[@]}"; do [ "$_s" = "$_want" ] && { _found=1; break; }; done
  [ "$_found" -eq 1 ] || { IS_FULL=0; break; }
done
unset _want _found _s

mkdir -p "$OUT"
PASS=0; FAIL=0; SKIP=0
declare -a ROWS

# The header must stamp the tree that RAN, not merely HEAD. A run made on a dirty tree and stamped
# with a bare commit id invites the reproduction the header exists for — checkout, re-run — against a
# commit that provably cannot yield these artifacts (round 4 measured exactly that on this directory's
# predecessor: rows unreachable at the stamped commit). Captured at run START, before the guards stage
# mutates and restores sources mid-run, and before a mid-run commit could move HEAD. Tracked-file
# drift is what breaks "checkout the commit and re-run", so it alone sets DIRTY; untracked files are
# still recorded in git-status.txt for the reader (each run's own bench-results/verify-* output is
# untracked, so counting untracked paths would mark EVERY run dirty and the marker would bind nothing).
#
# The verdict is rc-bound and graded from the CAPTURED file. The old shape read empty stdout as
# clean — but `git status` prints NOTHING and exits non-zero under an index lock or a broken .git,
# so a git failure stamped `tree clean at run start` on a tree nothing measured (round-5 B2's
# vacuous-pass shape, in the header every row inherits). It also ran a SECOND live `git status`
# for the verdict, so the graded state and the committed evidence could diverge; now one call
# feeds both, and tracked drift is derived by filtering the untracked `??` lines from the capture.
GIT_COMMIT="$(git rev-parse --short HEAD)"
# DD-045 item 4B: this repo squash-merges, so the commit above stops resolving the moment the
# branch is pruned — 17 cited SHAs across TODO.md and docs/ROADMAP.md had already died that way
# before item 4B migrated them. The TREE hash survives: a squash commit's tree is byte-identical
# to the branch head's, so this still identifies HEAD's tree on a fresh clone where GIT_COMMIT
# resolves to nothing. It is the MEASURED tree only on a clean run: a dirty run reads the
# working tree, which has no hash here — hence the header says so rather than implying it.
GIT_TREE="$(git rev-parse HEAD^{tree})"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git status --porcelain > "$OUT/git-status.txt" 2>"$OUT/git-status-stderr.txt"
GIT_STATUS_RC=$?
if [ "$GIT_STATUS_RC" -ne 0 ]; then
  TREE_STATE="UNMEASURED"
elif grep -qv '^??' "$OUT/git-status.txt"; then
  TREE_STATE="DIRTY"
else
  TREE_STATE="clean"
fi

# Annotation alone (the DIRTY stamp above) is not a gate: round-11's approver ran this exact
# script against a dirty tree and got a full RESULTS.md of PASS rows, exit 0 — reproducible from
# nowhere, citable by anyone who does not read the header. A FULL run (IS_FULL, computed above
# from the actual stage set — not the literal word `all`) is the one whose output this project
# treats as the run of record (round 4's predecessor directory was superseded for exactly this:
# rows unreachable at the commit they were stamped with), so it is the one that must REFUSE by
# default rather than merely warn. The fast dev loop is deliberately exempt — see IS_FULL's
# comment — and stays annotate-only, same as before this round.
# UNMEASURED is refused on the same terms as DIRTY, not just DIRTY: "git status itself failed"
# is strictly less known than "git status ran and found drift", so a full run must not treat a
# failed measurement as looser than a positive one.
# --allow-dirty is the one documented escape hatch, for a deliberate exploratory full run against
# a tree the operator already knows is dirty; its RESULTS.md still comes out stamped NON-CITABLE
# below, so the opt-in cannot accidentally mint a citable artifact either.
if [ "$TREE_STATE" != "clean" ] && [ "$IS_FULL" -eq 1 ] && [ "$ALLOW_DIRTY" -ne 1 ]; then
  {
    echo "REFUSED: a full run (${STAGES[*]}) is the run of record — its RESULTS.md is meant to be"
    echo "cited, and citing it requires the tree that produced it to be reproducible from the"
    echo "stamped commit alone."
    echo
    if [ "$TREE_STATE" = "DIRTY" ]; then
      echo "Tracked files differ from \`$GIT_COMMIT\` on \`$GIT_BRANCH\` (full listing: $OUT/git-status.txt):"
      grep -v '^??' "$OUT/git-status.txt"
    else
      echo "git status itself FAILED at run start (rc=$GIT_STATUS_RC, see $OUT/git-status-stderr.txt) —"
      echo "the tree's state cannot even be determined, which is strictly worse than known-dirty."
    fi
    echo
    echo "Commit or stash your changes and re-run for a citable run of record."
    echo "For a deliberate, non-citable exploratory full run against this tree as-is, pass --allow-dirty."
    echo "Fast dev-loop runs (no arguments, or any named subset short of all five stages) are not"
    echo "gated by this check — only a full run is, since only a full run's output is meant to become"
    echo "the run of record."
  } >&2
  exit 3
fi

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$*"; PASS=$((PASS+1)); ROWS+=("PASS|$1|${2:-}"); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAIL=$((FAIL+1)); ROWS+=("FAIL|$1|${2:-}"); }
skip() { printf '  \033[33mSKIP\033[0m  %s\n' "$*"; SKIP=$((SKIP+1)); ROWS+=("SKIP|$1|${2:-}"); }

# Total tests and failures across every module, read from the JUnit XML rather than from console text.
suite_counts() {
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
tot = fail = 0
for p in glob.glob("**/build/test-results/**/*.xml", recursive=True):
    try:
        r = ET.parse(p).getroot()
        tot  += int(r.get("tests", 0))
        fail += int(r.get("failures", 0)) + int(r.get("errors", 0))
    except Exception:
        pass
print(f"{tot} {fail}")
PY
}

# Name every failing test, so a mutation check can assert WHICH test failed rather than how many.
# Takes the JUnit-XML directory as $1 so the guards stage can point it at the COPY inside $OUT —
# the verdict and the committed evidence must be the same bytes (round-5 S3: guards rows named a
# failing test whose XML lived only in build/, which is never committed, so a reader could not
# check which test failed against the run directory).
failing_test_names() {  # $1 = directory holding JUnit XML (searched recursively)
  python3 - "$1" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
for p in glob.glob(os.path.join(sys.argv[1], "**", "*.xml"), recursive=True):
    for tc in ET.parse(p).getroot().iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            print(tc.get("name"))
PY
}

# "<total> <failures+errors>" summed over one directory's JUnit XML — same contract as
# suite_counts() but scoped, so guards:restored can derive its counts from the copy in $OUT.
xml_counts() {  # $1 = directory holding JUnit XML (searched recursively)
  python3 - "$1" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
tot = fail = 0
for p in glob.glob(os.path.join(sys.argv[1], "**", "*.xml"), recursive=True):
    try:
        r = ET.parse(p).getroot()
        tot  += int(r.get("tests", 0))
        fail += int(r.get("failures", 0)) + int(r.get("errors", 0))
    except Exception:
        pass
print(f"{tot} {fail}")
PY
}

# A PASS from an ABSENCE check must first prove the check actually ran. `grep -q X file || ok` reports a
# clean result when the file is missing or empty — the grep simply fails to match, and "X is absent" holds
# vacuously. That is this project's most serious defect class (a reported zero that does not mean "checked
# and clean"), and PR #103's approver found it here, in the script whose whole job is verifying claims.
#
# So an absence claim requires a SENTINEL: positive evidence in the same file that the relevant activity
# happened at all. No sentinel, no PASS — it reports UNMEASURED instead, which is the honest outcome.
#
# (The *:not-from-central rows apply this doctrine through assert_resolved_from_injected below rather
# than through this helper: their failure condition is "downloaded from any id OTHER than
# basquin-injected", which a single ERE cannot express, and round 4 showed a sentinel loose enough to
# fit one regex here was loose enough to be satisfied by the injector's own stdout. Any future absence
# row that CAN bind with one regex should use this helper — jvm:injected-not-predeclared and
# native:injected-not-predeclared are its call sites: round 7 found the helper defined with zero
# callers while the PR description sold it as live protection, and round 8 found the native stage
# attributing injection to a bare `if grep` over the fixture pom — the vacuous-absence shape this
# helper exists to prevent.)
assert_absent() {  # $1=file  $2=must-be-absent regex  $3=sentinel regex  $4=label  $5=pass message
  local f="$1" bad_re="$2" sentinel="$3" label="$4" msg="$5"
  if [ ! -s "$f" ]; then
    bad "$label" "UNMEASURED: $(basename "$f") is missing or empty, so absence proves nothing"; return
  fi
  if ! grep -qE "$sentinel" "$f"; then
    bad "$label" "UNMEASURED: no sentinel (/$sentinel/) in $(basename "$f") — cannot tell 'checked and clean' from 'never ran'"; return
  fi
  if grep -qE "$bad_re" "$f"; then
    bad "$label" "found what must be absent (/$bad_re/)"
  else
    ok "$label" "$msg"
  fi
}

# The resolution-provenance row, one implementation for BOTH build stages. PR #103's approver found
# the native half of §5.2 had no row binding its pass to injection: native:build, native:serves and
# native:banner all still PASS if purge_basquin silently fails and com.basquin resolves from the
# fixture's stale local repo — which the extension's own dd043Spike publishing target populates, so
# that is a reachable state, not a hypothetical. Success proves nothing about injection; only
# resolution provenance does, and a provenance row the jvm stage has and the native stage lacks is a
# PASS for something never measured — this repo's most serious defect class.
#
# The sentinel must bind to THIS claim, not merely prove the log is non-trivial. Two prior sentinels
# failed that test: "Downloading from" (only shows Maven fetched something), and
# "com/basquin|com\.basquin" — satisfied by the injector's OWN "[basquin-injector] instrumented …
# (com.basquin:…)" stdout, printed at afterProjectsRead BEFORE any resolution, so a build whose
# injected repo never reached Aether still PASSed this row while resolving nothing from anywhere.
# Binding requires resolver evidence on both sides:
#   * sentinel: a com/basquin artifact actually DOWNLOADED from basquin-injected at the served URL.
#     The injector never prints "Downloaded from" (its every line is "[basquin-injector] "-prefixed),
#     so only Maven's resolver can satisfy this — and if the purge failed and everything resolved
#     from a stale local copy, this is absent and the row honestly reports UNMEASURED.
#   * failure: any com/basquin download under any other id:URL. Matching the artifact PATH in the
#     URL rather than the literal id "central" is what catches a <mirrorOf>*</mirrorOf> in the build
#     host's settings.xml: the mirror relabels the line with its own id — including for
#     basquin-injected itself, which a catch-all mirror also captures, and that IS a failure,
#     because the build then did not resolve from the injected repo and the acceptance is confounded.
assert_resolved_from_injected() {  # $1 = the stage's Maven build log  $2 = row label
  local blog="$1" label="$2" inj_dl foreign_dl
  inj_dl="$(grep -cE "Downloaded from basquin-injected: http://localhost:$PORT/com/basquin/" "$blog" 2>/dev/null)"
  foreign_dl="$(grep -E 'Downloaded from [^:]+: [^ ]*/com/basquin/' "$blog" 2>/dev/null \
                 | grep -v "Downloaded from basquin-injected: http://localhost:$PORT/")"
  if [ -n "$foreign_dl" ]; then
    bad "$label" "com/basquin downloaded from a repository other than basquin-injected: $(echo "$foreign_dl" | head -1)"
  elif [ "${inj_dl:-0}" -gt 0 ]; then
    ok "$label" "$inj_dl com/basquin download(s), every one from basquin-injected; none from central or any mirror id"
  else
    bad "$label" "UNMEASURED: no com/basquin artifact was ever Downloaded from basquin-injected — cannot tell 'none came from central' from 'nothing resolved at all' (see $(basename "$blog"))"
  fi
}

# The boundary's wire shape, one grader for BOTH build stages' poll rows. ResultStore.format
# (basquin-core/src/main/java/agent/ResultStore.java:141-154) emits ONE LINE PER HOP, each line
# `costCsv|invariantCount|detail|leak`, where costCsv is `latencyMs,heapDeltaKb,threadDelta` — the
# per-hop shape test/RequestBoundaryTest.java:81 already pins for the header path
# (`^-?\d+,-?\d+,-?\d+$`; heapDelta and threadDelta can legitimately go negative, so both carry an
# optional sign). `detail` is pipe- and newline-sanitised at the producer, so `[^|]*` is exact, and
# the last field is `leak` or empty, nothing else. "miss" (ResultStore.MISS) is the only non-CSV
# body the boundary itself can legitimately produce — for "never ran" — and it FAILS here,
# correctly: these rows certify a measurement, not liveness. Anything else non-matching is a
# transport failure or an error page riding a 200.
#
# Per-LINE grading, never a collapse to one line: the previous implementation stripped newlines and
# matched a single hop, so a genuine multi-hop body — the wire format's own documented shape — was
# rejected as "not the boundary's CSV shape" (PR #103 round 9, should-fix 9): a false negative on a
# correct measurement. Every line must match; a truncated tail line fails its own match, and awk's
# END{NR} counts a final unterminated line too, so truncation always diverges the two counts.
boundary_poll_shape_ok() {  # $1 = poll body file; 0 iff the file holds >=1 line and EVERY line is one formatted hop
  local f="$1" total match
  total="$(awk 'END{print NR}' "$f" 2>/dev/null)"
  match="$(grep -cE '^-?[0-9]+,-?[0-9]+,-?[0-9]+\|[0-9]+\|[^|]*\|(leak)?\r?$' "$f" 2>/dev/null)"
  [ "${total:-0}" -ge 1 ] && [ "${match:-0}" -eq "$total" ]
}

purge_basquin() {  # $1 = a local maven repository root  $2 = proof file name; exit status asserts the purge
  local repo="$1" proof="$OUT/$2"
  { echo "# purge proof — $(date -u +%FT%TZ)"; echo "# repository: $repo"; echo
    echo "## BEFORE:"; find "$repo/com/basquin" -maxdepth 1 -mindepth 1 2>&1 || true
    echo; echo "## PURGE: rm -rf $repo/com/basquin"
  } > "$proof"
  rm -rf "$repo/com/basquin"
  { echo; echo "## AFTER (a 'No such file' error here is the proof):"
    find "$repo/com/basquin" -type f 2>&1 || true
  } >> "$proof"
  # Captured is not graded: until PR #103 round 6 nothing ever read the AFTER section back, so an rm
  # that failed (EPERM, an open handle on a mount that resurrects the dir) left a proof file
  # faithfully RECORDING the survivors while the stage built on against the very copy the purge
  # existed to remove. The exit status now asserts the one thing the proof is for, and both call
  # sites refuse the stage on it rather than running a build whose attribution is already confounded.
  [ ! -e "$repo/com/basquin" ]
}

# Stage the injector jar that will ride maven.ext.class.path — one implementation for BOTH build
# stages. Three defects lived in the old inline copies (approver finding 6, round 8): the staging
# dir was never cleared, so a previous run's jar sat there ready to be selected; the cp's exit
# status was discarded, so with build/libs empty (./gradlew clean, fresh checkout) the glob failed
# to expand, cp failed silently, and `ls | head -1` served up the STALE jar — every row in the
# stage then grades bytes the stamped commit never produced; and the -sources.jar exclusion
# run_jar applies was omitted here, and '-' (0x2D) sorts before '.' (0x2E), so `head -1` preferred
# the classless sources jar outright whenever one existed. Now: clear the dir, select from
# build/libs with the same exclusion run_jar uses, verify the copy landed, and record the staged
# bytes' sha256 in the run directory so a reader can tie the build's extension to an exact
# artifact rather than to a filename. Non-zero means NOTHING trustworthy got staged and the
# caller must refuse the stage.
STAGE_DIR=""; STAGE_JAR=""
stage_injector_jar() {  # $1 = stage tag; sets STAGE_DIR and STAGE_JAR on success
  local tag="$1" src
  STAGE_DIR="$REPO_ROOT/build/tmp/verify-pr3-inj"; STAGE_JAR=""
  rm -rf "$STAGE_DIR" && mkdir -p "$STAGE_DIR" || return 1
  src="$(ls basquin-maven-injector/build/libs/basquin-maven-injector-*.jar 2>/dev/null \
        | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)"
  [ -n "$src" ] || return 1
  cp "$src" "$STAGE_DIR/" || return 1
  STAGE_JAR="$(basename "$src")"
  [ -s "$STAGE_DIR/$STAGE_JAR" ] || return 1
  sha256sum "$STAGE_DIR/$STAGE_JAR" > "$OUT/injected-jar-sha256-$tag.txt" || return 1
}

serve_pages() {  # $1 = stage tag; publishes the chain to a scratch dir and serves it on 127.0.0.1
  # PER-STAGE log files, never a shared one: `>` TRUNCATES, and `native` runs after `jvm` in the
  # default `all` order, so with a shared $OUT/http-access.log the native stage's serve_pages
  # destroyed the JVM stage's HTTP evidence mid-run and refilled the file with native traffic
  # (round-5 B1: the run of record's jvm:deployment-from-injected-repo row cited a log that no
  # longer held the traffic it was graded on — hidden because the native build happened to make
  # the same number of deployment GETs). Any row citing one of these logs must cite the file
  # tagged with ITS OWN stage.
  local tag="$1" dir="$OUT/pages-repo"
  rm -rf "$dir" || return 1
  ./gradlew -q --no-daemon "-PbasquinPagesDir=$dir" \
    :basquin-core:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository \
    > "$OUT/publish-$tag.log" 2>&1 || return 1
  ( cd "$dir" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 ) \
    > "$OUT/http-access-$tag.log" 2>&1 &
  echo $! > "$OUT/http-server.pid"
  sleep 2
  curl -sf -o /dev/null "http://localhost:$PORT/com/basquin/basquin-quarkus/0.3.0/basquin-quarkus-0.3.0.pom"
}

stop_server() {
  [ -f "$OUT/http-server.pid" ] && kill "$(cat "$OUT/http-server.pid")" 2>/dev/null
  rm -f "$OUT/http-server.pid"
}

# Only ever removes containers this script created.
cleanup() {
  stop_server
  docker ps -aq --filter 'name=verify-pr3-' 2>/dev/null | xargs -r docker rm -f >/dev/null 2>&1
  docker network rm verify-pr3-net >/dev/null 2>&1
}
trap cleanup EXIT

run_unit() {
  log "unit — whole test suite"
  # `check` does not delete a module's test-results dir for tests that were renamed or removed — the
  # old XML just sits there, still glob-matched by suite_counts() below. That is the exact trap that
  # produced the "2 module / 361 repo-wide" misread on this branch, now feeding RESULTS.md's totals
  # under a header promising every number is derived from an artifact (see top of file). Clearing
  # every module's test-results dir before the run is the fix: it requires no change to build.gradle
  # (redirecting Gradle's test output to a per-run directory would), and nothing later in this script
  # reads test-results before run_unit repopulates it. rc-checked: a clear that silently failed
  # leaves stale XML feeding suite_counts, and the totals below would blend runs.
  if ! find . -type d -path '*/build/test-results' -prune -exec rm -rf {} +; then
    bad "unit" "UNMEASURED: could not clear stale test-results dirs — suite counts could include a previous run's XML"; return
  fi
  ./gradlew check --console=plain --no-daemon > "$OUT/gradle-check.log" 2>&1
  local rc=$? counts; counts="$(suite_counts)"
  local tot="${counts% *}" f="${counts#* }"
  echo "$counts" > "$OUT/suite-counts.txt"
  if [ "$rc" -eq 0 ] && [ "$f" -eq 0 ] && [ "$tot" -gt 0 ]; then
    ok "unit" "$tot tests, 0 failures"
  else
    bad "unit" "$tot tests, $f failures (gradle rc=$rc) — see gradle-check.log"
  fi
}

run_jar() {
  log "jar — injector discoverability and baked version"
  # The build's exit code decides whether there is anything TO grade. A failed build does not
  # delete the previous archive — so with rc unread, every row below graded whatever jar was
  # already sitting in build/libs: three PASSes against a stale artifact, RESULTS.md stamping a
  # commit that never produced those bytes, in the one stage that exists to catch silent
  # non-discovery (approver finding 1, round 8). rc gates the stage, and the graded jar's sha256
  # goes into jar-integrity.txt so the certified bytes are identifiable, not just a filename.
  ./gradlew -q --no-daemon :basquin-maven-injector:jar > "$OUT/jar-build.log" 2>&1
  local jrc=$?
  if [ "$jrc" -ne 0 ]; then
    bad "jar" "UNMEASURED: the jar build FAILED (gradle rc=$jrc, see jar-build.log) — any jar under build/libs is a previous build's artifact, and grading it would certify bytes this run never produced"
    return
  fi
  # Exclude -sources.jar / -javadoc.jar: build.gradle enables withSourcesJar(), and '-' (0x2D) sorts
  # BEFORE '.' (0x2E), so a plain `head -1` picks basquin-maven-injector-X-sources.jar when it exists —
  # which has no .class files and no baked properties, so both checks below would spuriously FAIL against
  # a perfectly good build. A false negative in the one stage that exists to catch silent non-discovery.
  local j; j="$(ls basquin-maven-injector/build/libs/basquin-maven-injector-*.jar 2>/dev/null \
        | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)"
  if [ -z "$j" ]; then bad "jar" "no jar produced"; return; fi

  local idx cls ver jsha
  idx="$(unzip -p "$j" META-INF/sisu/javax.inject.Named 2>/dev/null | tr -d '\r' | head -1)"
  ver="$(unzip -p "$j" basquin-injector.properties 2>/dev/null | tr -d '\r' | sed -n 's/^version=//p')"
  jsha="$(sha256sum "$j" 2>/dev/null | awk '{print $1}')"
  { echo "jar: $j"; echo "sha256: ${jsha:-<UNREADABLE>}"; echo "sisu index: ${idx:-<MISSING>}"; echo "baked version: ${ver:-<MISSING>}"; } \
    > "$OUT/jar-integrity.txt"

  if [ -z "$idx" ]; then
    bad "jar:sisu-index" "META-INF/sisu/javax.inject.Named MISSING — Maven would never load the participant"
  else
    cls="$(echo "$idx" | tr '.' '/')"
    if unzip -l "$j" | grep -q "$cls.class"; then
      ok "jar:sisu-index" "$idx (class present in jar)"
    else
      bad "jar:sisu-index" "index names $idx but no such class in the jar — a stale index is as silent as a missing one"
    fi
  fi

  local declared; declared="$(sed -n "s/^version *= *'\(.*\)'/\1/p" basquin-maven-injector/build.gradle | tr -d '\r' | head -1)"
  if [ -n "$ver" ] && [ "$ver" = "$declared" ]; then
    ok "jar:baked-version" "$ver matches build.gradle"
  else
    bad "jar:baked-version" "baked='${ver:-<MISSING>}' build.gradle='$declared'"
  fi

  # basquin-init.gradle (the Gradle counterpart to this jar) hand-types the injected version's
  # default where the Maven path above does not — see verifyGradleInitScriptVersion's comment in
  # build.gradle. That task is finalizedBy('jar'), so it already ran once, silently, as a side effect
  # of the `:basquin-maven-injector:jar` invocation at the top of this function. That invocation's
  # exit code is NOW gated (the stale-jar fix above), so a failing finalizer refuses the whole stage
  # rather than sailing through — but the shared rc still cannot say WHICH task failed or show the
  # drift, so the check keeps its own dedicated, log-backed invocation: it's cheap (a regex over one
  # checked-in text file, no compilation), and it gives this row an artifact of its own to derive
  # its detail from.
  #
  # No `-q`: with it, Gradle suppresses LIFECYCLE output entirely (verified empirically — the PASS
  # message never appears in a `-q` log, only a FAILURE would), so a quiet log carries a sentinel on
  # failure but not on success — an asymmetry that would make "ran and passed" indistinguishable from
  # "never ran". Same reasoning `_mutate` already recorded for its own `--no-daemon --console=plain`
  # (no -q) invocations.
  #
  # rc alone cannot tell "ran and passed" from "gradle never reached the task" (unknown task name,
  # daemon/JVM failure, etc. — verified empirically: a bogus task name also exits non-zero with no
  # task output at all) — the same discriminator class as `_mutate`'s fresh-XML requirement and
  # `run_unit`'s tot>0. The task's `doLast` prints a message prefixed "verifyGradleInitScriptVersion:"
  # on BOTH outcomes (lifecycle on pass, GradleException text on fail — both verified empirically), so
  # that prefix is the sentinel that the task body actually executed; only then does rc pick which of
  # the two outcomes it was.
  ./gradlew --no-daemon --console=plain :basquin-maven-injector:verifyGradleInitScriptVersion \
    > "$OUT/gradle-init-version.log" 2>&1
  local girc=$? sentinel
  sentinel="$(grep -o 'verifyGradleInitScriptVersion:.*' "$OUT/gradle-init-version.log" | head -1)"
  if [ -z "$sentinel" ]; then
    bad "jar:gradle-init-version" "UNMEASURED: no verifyGradleInitScriptVersion: line in gradle-init-version.log (gradle rc=$girc) — the task never ran, so no verdict exists"
  elif [ "$girc" -eq 0 ]; then
    ok "jar:gradle-init-version" "$sentinel"
  else
    bad "jar:gradle-init-version" "$sentinel (gradle rc=$girc)"
  fi
}

# Each guard is neutered one at a time and must fail ITS OWN named test. A guard test that cannot
# fail is worse than no test: it licenses the bug it claims to prevent.
run_guards() {
  log "guards — mutation checks"
  local src=basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java
  local backup="$OUT/BasquinInjector.java.orig"
  # An unchecked backup here is worse than a wrong verdict: if this cp fails, every _mutate below
  # still mutates the source and every restore silently cp's from a file that does not exist,
  # leaving the TREE mutated after the script exits. No backup, no mutations.
  if ! cp "$src" "$backup"; then
    bad "guards" "UNMEASURED: could not back up $src into $OUT — refusing to mutate a source file with no restore copy"
    return
  fi

  # $2 asserts MEMBERSHIP, not exclusivity: neutering one guard can legitimately take a shared
  # helper down with it and fail more than one test (declaration-usability does — the scope, type,
  # classifier, and exclusions checks all live behind the same `if (problem == null)` gate, so
  # disabling it fails every test that exercises that gate, not only the named one). This helper only
  # asserts that $2 is AMONG the failures — the thing that actually matters, since a guard whose own
  # test does not fail when neutered is silently disabled — and never claims $2 is the only failure.
  _mutate() { # $1 = python replacement expr file content, $2 = a test expected among the failures, $3 = label
    python3 - "$src" <<PY
import pathlib, sys
p = pathlib.Path(sys.argv[1]); t = p.read_text(encoding="utf-8")
old, new = $1
if old not in t:
    sys.exit("MUTATION TARGET NOT FOUND")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
PY
    if [ $? -ne 0 ]; then cp "$backup" "$src"; bad "guards:$3" "mutation target not found — the guard's code shape changed"; return; fi
    # failing_test_names() globs whatever XML sits in build/test-results. run_unit clears that dir for
    # the identical trap one function away, but a previous MUTATION's failures land there too, and a
    # mutation's expected test can be a member of the previous mutation's failing set (managed-version's
    # set contains managed-exclusions' expected test — measured, not hypothetical). If this run then
    # produces no fresh XML (compile error, daemon or lock failure, OOM), the old code read the stale
    # set and printed PASS off a run that never happened. So: clear before the run, and refuse to read
    # a verdict out of a dir the run did not repopulate. The clear itself is rc-checked: an rm that
    # fails leaves the stale set in place, and the fresh-XML count below cannot tell survivors from
    # fresh output — restore the source and refuse rather than let last mutation's verdict answer.
    if ! rm -rf basquin-maven-injector/build/test-results; then
      cp "$backup" "$src"
      bad "guards:$3" "UNMEASURED: could not clear stale test-results — a previous run's XML could impersonate this mutation's verdict"; return
    fi
    # No -q: at lifecycle level the log carries "N tests completed, M failed" and a FAILED line per
    # failing test, so the committed guard-$3.log can support the row's named test on its own
    # (round-5 S3: the -q logs showed only a count and a pointer to an uncommitted HTML report).
    ./gradlew :basquin-maven-injector:test --no-daemon --console=plain > "$OUT/guard-$3.log" 2>&1
    local rc=$?
    # Copy the run's JUnit XML into the results directory and read the verdict FROM THE COPY:
    # what the check graded and what the run directory holds are then the same bytes, and the
    # fresh-XML count over the copy doubles as the did-it-run discriminator below.
    local xmldir="$OUT/guard-$3-junit"
    rm -rf "$xmldir"; mkdir -p "$xmldir"
    find basquin-maven-injector/build/test-results -name '*.xml' -type f -exec cp {} "$xmldir/" \; 2>/dev/null
    local names; names="$(failing_test_names "$xmldir")"
    local fresh; fresh="$(find "$xmldir" -name '*.xml' -type f | wc -l)"
    cp "$backup" "$src"; local resrc=$?
    # Gradle's exit code alone cannot say "the test task did not execute": tests-ran-and-failed (the
    # expected outcome under a mutation) and never-compiled BOTH exit 1. Fresh XML is the discriminator.
    # A failed RESTORE outranks any verdict: the next mutation would then stack on this one and
    # guards:restored would grade a tree nobody intended — flag it before anything else can pass.
    if [ "$resrc" -ne 0 ]; then
      bad "guards:$3" "RESTORE FAILED (cp rc=$resrc): $src may still carry this mutation — restore it from $backup before trusting ANY later row"
    elif [ "$fresh" -eq 0 ]; then
      bad "guards:$3" "UNMEASURED: no fresh JUnit XML (gradle rc=$rc) — the mutated suite never ran, so no verdict exists; see guard-$3.log"
    elif [ "$rc" -eq 0 ]; then
      bad "guards:$3" "module suite GREEN (rc=0) with the guard neutered — $2 cannot fail, the guard is dead"
    elif echo "$names" | grep -qx "$2"; then
      ok "guards:$3" "neutering it fails $2 (per guard-$3-junit/; among the failures — other tests sharing the guard may fail too, not asserted exclusive)"
    else
      bad "guards:$3" "expected $2 to fail; got: ${names:-<none>} (guard-$3-junit/)"
    fi
  }

  _mutate '("if (Boolean.parseBoolean(props.getProperty(PROP_SKIP)))", "if (false)")' \
          "injectsNothingWhenSkipIsSet" "skip"
  _mutate '("        DependencyManagement dm = p.getModel().getDependencyManagement();", "        DependencyManagement dm = null;")' \
          "failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion" "managed-version"
  # The managed-version mutation above disables the WHOLE managed-path lookup (dm = null), which takes
  # both of failOnConflictingManagedVersion's conditions down together and only asserts the version
  # test among the failures. That leaves the exclusions branch added in a61a90c unbound by any
  # mutation — it could be deleted entirely and this stage would still report all-PASS. This mutation
  # neuters ONLY that branch's condition, leaving the dm lookup and the version check intact.
  _mutate '("            if (managed.getExclusions() != null && !managed.getExclusions().isEmpty()) {", "            if (false) {")' \
          "failsLoudlyWhenDependencyManagementCarriesExclusions" "managed-exclusions"
  # The managed-version mutation (dm = null) takes the whole managed loop down and only asserts the
  # version test among the failures — the same reason the managed-exclusions branch needed its own row
  # applies verbatim to the managed-scope branch added for the eighth bypass: without this row it could
  # be deleted entirely and this stage would still report all-PASS. Nulling the branch's own input
  # neuters ONLY the scope check (the condition starts managedScope != null), leaving the dm lookup and
  # the exclusions and version branches intact. The anchor is unique: the sibling guard's scope check
  # reads from a Dependency named d into a variable named scope, never managedScope.
  _mutate '("            String managedScope = managed.getScope();", "            String managedScope = null;")' \
          "failsLoudlyWhenDependencyManagementPinsOurGroupToAnUnusableScope" "managed-scope"
  _mutate '("if (declared == null || declared.equals(version))", "if (true)")' \
          "failsLoudlyWhenTheProjectDeclaresOurArtifactAtADifferentVersion" "declared-version"
  # The fourth guard. PR #103's approver found the header comment claimed to mutation-check "each"
  # fail-loudly guard while only three of the four had a _mutate call — the usability guard was added
  # after the script and never wired in. A claim wider than its check, in the script whose whole job is
  # checking claims.
  _mutate '("        if (problem == null) {", "        if (true) {")' \
          "failsLoudlyWhenOurArtifactIsDeclaredAtAnUnusableScope" "declaration-usability"
  # failOnUnusableSiblingDeclaration has TWO independent conditions (scope, then version) guarding a
  # SIBLING basquin artifact's declaration, not our own — a whole-method mutation would leave one
  # branch unbound. The scope anchor's 12 leading spaces (inside the for-loop) are load-bearing: an
  # identical check at 8 spaces lives inside failOnUnusableDeclaration, and _mutate replaces only the
  # first match, so the wrong indentation would silently mutate the wrong guard.
  _mutate '("            if (!\"compile\".equals(scope) && !\"runtime\".equals(scope)) {", "            if (false) {")' \
          "failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAnUnusableScope" "sibling-scope"
  _mutate '("            if (declared != null && !declared.equals(version)) {", "            if (false) {")' \
          "failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAConflictingVersion" "sibling-version"
  # DD-043 PR-4, Task 2, decision D2. failOnConflictingJacocoDeclaration has TWO independent
  # conditions — an active instrument-bound execution, then a version collision — guarding the
  # target's own org.jacoco:jacoco-maven-plugin declaration against the execution this injector is
  # about to add. Same reasoning as sibling-scope/sibling-version just above: a whole-method
  # mutation would leave one branch unbound, so each condition gets its own row. The goal-check
  # anchor is unique to this guard (its sibling in failOnConflictingManagedVersion checks
  # managedScope, not exec.getGoals()); the version-check anchor's variable name "jacocoVersion"
  # (not "version") is what keeps it distinct from the declared-version/sibling-version anchors
  # just above, which read from a variable literally named "version".
  _mutate '("                if (exec.getGoals() != null && exec.getGoals().contains(JACOCO_GOAL)) {", "                if (false) {")' \
          "failsLoudlyWhenAnActiveJacocoInstrumentExecutionAlreadyExists" "jacoco-instrument-conflict"
  _mutate '("            if (declared != null && !declared.equals(jacocoVersion)) {", "            if (false) {")' \
          "failsLoudlyWhenAJacocoDeclarationIsAtAConflictingVersion" "jacoco-version-conflict"

  # guards:restored previously keyed on gradle's exit code alone — the precise trap _mutate
  # refuses above: rc=0 cannot distinguish "ran green" from "did not run", and its -q log
  # carried neither a test count nor BUILD SUCCESSFUL, so the committed artifact could not
  # support the row (round-5 S3). Same discipline as _mutate now: clear, run, copy the JUnit XML
  # into $OUT, and grade fresh-XML presence + counts from the copy, not the exit code alone.
  # The clear is rc-checked for the same reason as _mutate's: a stale green surviving a failed rm
  # could impersonate the restore run.
  if ! rm -rf basquin-maven-injector/build/test-results; then
    bad "guards:restored" "UNMEASURED: could not clear stale test-results before the restore run — a stale green could impersonate it"
    return
  fi
  ./gradlew :basquin-maven-injector:test --no-daemon --console=plain > "$OUT/guard-restore.log" 2>&1
  local rrc=$?
  local rxml="$OUT/guard-restore-junit"
  rm -rf "$rxml"; mkdir -p "$rxml"
  find basquin-maven-injector/build/test-results -name '*.xml' -type f -exec cp {} "$rxml/" \; 2>/dev/null
  local rcounts; rcounts="$(xml_counts "$rxml")"
  local rtot="${rcounts% *}" rfail="${rcounts#* }"
  if [ "$(find "$rxml" -name '*.xml' -type f | wc -l)" -eq 0 ]; then
    bad "guards:restored" "UNMEASURED: no fresh JUnit XML after the restore run (gradle rc=$rrc) — cannot tell 'ran green' from 'never ran'; see guard-restore.log"
  elif [ "$rrc" -eq 0 ] && [ "$rfail" -eq 0 ] && [ "$rtot" -gt 0 ]; then
    ok "guards:restored" "source restored, module suite green ($rtot tests, 0 failures per guard-restore-junit/)"
  else
    bad "guards:restored" "suite not green after restore ($rtot tests, $rfail failures, gradle rc=$rrc) — CHECK $src against $backup"
  fi
}

run_jvm() {
  log "jvm — rest-villains instrumented with zero edits to its source (spec §5.2, half 1)"
  local app="${APP_DIR:-$REPO_ROOT/../quarkus-super-heroes/rest-villains}"
  if [ ! -d "$app" ]; then
    skip "jvm" "target clone not found at $app — set APP_DIR"; return
  fi
  app="$(cd "$app" && pwd)"

  # Refuse rather than revert: the tree is not ours to clean up. And refuse to GRADE a tree git
  # cannot report on: `git status --porcelain` in a non-repo prints NOTHING and exits 128, so a
  # stdout-emptiness test alone waves the gate open having measured nothing (round-5 B2 — the
  # defect class named at the top of this file, in the gate guarding PR-3's headline claim).
  # rc=0 is not enough either — but the first follow-up over-corrected: it demanded the work-tree
  # root EQUAL $app, and that refuses the GENUINE acceptance target, because rest-villains is a
  # module INSIDE the quarkus-super-heroes repository (the normal shape for a Maven module; its
  # root is the enclosing repo, not itself). Root-equality was a proxy for two distinct hazards,
  # each now gated on the thing it actually guards:
  #   * WHICH tree answered: $app nested in THIS repo makes git grade basquin's own status
  #     (measured: an ignored dir inside this repo gives rc=0 and the ENCLOSING repo's status).
  #     So the discovered root must not be the basquin root — physical-path compared so a
  #     symlinked APP_DIR can neither dodge the refusal nor be spuriously caught by it.
  #   * WHETHER git can SEE edits under $app: an unpacked ZIP under some unrelated checkout,
  #     ignored by it, also gets rc=0 — and a status scoped to that dir prints nothing FOREVER,
  #     edits included, so "pristine" there would be vacuous. `git ls-files -- .` non-empty
  #     proves git tracks content under $app, making a clean scoped status mean "checked and
  #     clean" rather than "never looked".
  # Both status queries (this preflight and jvm:zero-edits) are scoped with `-- .` so the verdict
  # grades the TARGET directory: dirt elsewhere in the enclosing repo neither blocks the stage
  # nor gets billed to rest-villains, while any edit inside $app is still a reported line.
  # `git -C "$app"` rather than `(cd "$app" && git status ...)`: the earlier shape ran the
  # command inside a `cd`'d subshell with the `2>` redirect INSIDE it, so the relative path
  # $OUT/jvm-git-preflight-stderr.txt resolved against $app, not the repo — on a non-repo target
  # this threw a spurious "No such file or directory" from the shell itself, and on a real clone
  # it would have written the stderr file INTO the target tree, which the very next check
  # (jvm:zero-edits) would then report as a self-inflicted dirty-tree failure. `git -C` never
  # changes the shell's cwd, so the redirect resolves in REPO_ROOT regardless, and $? is still
  # git's own exit status (no subshell, no `&&` chain to obscure it).
  local dirty rc top basquin_root
  dirty="$(git -C "$app" status --porcelain -- . 2>"$OUT/jvm-git-preflight-stderr.txt")"; rc=$?
  top="$(cd -P "$app" && git rev-parse --show-toplevel 2>/dev/null)"
  basquin_root="$(cd -P "$REPO_ROOT" && pwd)"
  if [ "$rc" -ne 0 ] || [ -z "$top" ]; then
    skip "jvm" "UNMEASURED: git cannot report on $app (git status rc=$rc, work-tree root: ${top:-<none>}) — zero-edits could never be graded there, so the stage refuses to run (see jvm-git-preflight-stderr.txt)"
    return
  fi
  if [ "$top" = "$basquin_root" ]; then
    skip "jvm" "UNMEASURED: $app resolves inside the basquin repo itself (work-tree root: $top) — git would grade THIS repo's status, not the target's"
    return
  fi
  if [ -z "$(git -C "$app" ls-files -- . 2>/dev/null | head -1)" ]; then
    skip "jvm" "UNMEASURED: git tracks nothing under $app (work-tree root: $top) — a clean status there would mean 'never looked', not 'checked and clean'"
    return
  fi
  if [ -n "$dirty" ]; then
    printf '%s\n' "$dirty" > "$OUT/jvm-target-dirty.txt"
    skip "jvm" "target tree is dirty — commit/revert it yourself, then re-run (see jvm-target-dirty.txt)"
    return
  fi
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    skip "jvm" "docker unavailable"; return
  fi

  if ! purge_basquin "$HOME/.m2/repository" "jvm-purge-proof.txt"; then
    bad "jvm" "com/basquin SURVIVED the purge of ~/.m2/repository — the build could resolve from the stale copy and measure nothing (jvm-purge-proof.txt)"; return
  fi
  if ! serve_pages jvm; then bad "jvm" "could not publish/serve the scratch Pages repo (publish-jvm.log)"; return; fi

  if ! stage_injector_jar jvm; then
    bad "jvm" "UNMEASURED: could not stage a fresh injector jar (no non-sources jar under build/libs, or the copy failed) — the build would have ridden whatever stale bytes sat in the staging dir"
    stop_server; return
  fi
  local stage="$STAGE_DIR" jar="$STAGE_JAR"

  # Its own postgres, its own network, all named verify-pr3-* so cleanup can never touch anything else.
  docker network create verify-pr3-net >/dev/null 2>&1
  docker run -d --name verify-pr3-db --network verify-pr3-net \
    -e POSTGRES_USER=superbad -e POSTGRES_PASSWORD=superbad -e POSTGRES_DB=villains_database \
    postgres:16 >/dev/null 2>&1
  sleep 8

  local bs="bench-results/dd043-pr3-restvillains-2026-07-26/build.sh"
  if [ ! -x "$bs" ] && [ ! -f "$bs" ]; then
    bad "jvm" "expected $bs (the EXTRA_DOCKER_ARGS-capable copy) — not found"; return
  fi

  APP_DIR="$app" \
  EXTRA_DOCKER_ARGS="--network host -v $stage:/inj" \
  EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/$jar -Dbasquin.inject.repo.url=http://localhost:$PORT/" \
    bash "$bs" clean package -DskipTests > "$OUT/jvm-build.log" 2>&1

  grep -q "BUILD SUCCESS" "$OUT/jvm-build.log" \
    && ok "jvm:build" "BUILD SUCCESS" || bad "jvm:build" "see jvm-build.log"
  grep -q "\[basquin-injector\] instrumented" "$OUT/jvm-build.log" \
    && ok "jvm:participant-ran" "$(grep -o '\[basquin-injector\] instrumented.*' "$OUT/jvm-build.log" | head -1)" \
    || bad "jvm:participant-ran" "no injector log line — check the jar's sisu index; Maven ignores a bad ext.class.path SILENTLY"

  # The injector prints its "instrumented" line on BOTH paths — the already-declares path falls
  # through to it reporting effective = declared — so participant-ran above cannot attribute the
  # instrumentation to INJECTION. Only the absence of the "already declares" line can: if the target
  # pom carried its own basquin-quarkus declaration (DD-044's whole territory), the injector would
  # print it, the build would look identical everywhere else, and §5.2's "injection did this" claim
  # would be confounded while every other row here still passed. Sentinel: the injector's own
  # instrumented line, so a build where the participant never ran reports UNMEASURED, not PASS.
  assert_absent "$OUT/jvm-build.log" \
    "\[basquin-injector\] .* already declares" \
    "\[basquin-injector\] instrumented" \
    "jvm:injected-not-predeclared" \
    "injector ran and never took the already-declares path — the dependency came from injection, not the pom"

  # The unconfounded evidence: no pom anywhere names the deployment artifact. Counted from the
  # JVM stage's OWN access log — see the round-5 B1 note on serve_pages for why the file is
  # per-stage. (A missing/empty log makes the grep fail, so that shape reports FAIL, not PASS.)
  grep -q "basquin-quarkus-deployment" "$OUT/http-access-jvm.log" \
    && ok "jvm:deployment-from-injected-repo" "$(grep -c 'basquin-quarkus-deployment' "$OUT/http-access-jvm.log") GET(s) in http-access-jvm.log" \
    || bad "jvm:deployment-from-injected-repo" "not fetched from the injected repo (see http-access-jvm.log)"
  # Provenance, not build success, is what attributes this half of §5.2 to injection — see
  # assert_resolved_from_injected for the sentinel doctrine and the two prior sentinels that failed it.
  assert_resolved_from_injected "$OUT/jvm-build.log" "jvm:not-from-central"

  APP_CONTAINER=verify-pr3-app DB_CONTAINER=verify-pr3-db DB_NETWORK=verify-pr3-net APP_DIR="$app" \
    bash bench-results/dd043-pr2-restvillains-2026-07-26/run-app.sh > "$OUT/jvm-run-app.log" 2>&1
  sleep 18
  docker logs verify-pr3-app > "$OUT/jvm-app-startup.log" 2>&1
  grep -o "Installed features: \[[^]]*\]" "$OUT/jvm-app-startup.log" | head -1 > "$OUT/jvm-banner.txt"
  grep -q "basquin" "$OUT/jvm-banner.txt" \
    && ok "jvm:banner" "$(cat "$OUT/jvm-banner.txt")" \
    || bad "jvm:banner" "banner does not list basquin (see jvm-app-startup.log)"

  local id="verify-$TS"
  curl -s -H "X-Basquin-Req: $id" "http://localhost:8084/api/villains" >/dev/null 2>&1
  # `-f`: a non-2xx response (proxy fault, 500, a control-surface 403) must FAIL this row outright
  # rather than let its error body ride through as if it were the boundary's own output.
  local poll_rc
  curl -sf "http://localhost:8084/__basquin/result?id=$id" \
    > "$OUT/jvm-result-poll.txt" 2>"$OUT/jvm-result-poll-stderr.txt"
  poll_rc=$?
  # Shape, not truthiness: a non-empty, non-"miss" body used to be the pass — but `curl -s` without
  # `-f` writes error bodies too, so a 404 page or a diagnostic string satisfied it just as well as a
  # real cost line (approver finding 13). boundary_poll_shape_ok (above) holds the derivation from
  # ResultStore.format and rejects error pages, "miss", empty and truncated bodies while accepting
  # ANY hop count >= 1. Each run's actual poll body is written to jvm-result-poll.txt in that run's
  # own directory; deliberately NOT quoted here. A specific run's value in this comment has now been
  # carried across a repoint twice (round 9 finding 1, then again in its own fix — the second time
  # caught by scripts/check-citations.py, not by a human). The wire shape is one line per hop:
  # costCsv="latencyMs,heapDeltaKb,threadDelta", then invariantCount, detail, leak.
  # The shape is justified by ResultStore.format, never by any one measurement —
  # so if a supersession ever repoints that citation, the quoted value MUST be re-read from the file
  # at the new path (PR #103 round 9, blocking 1: a repoint carried the superseded run's value into a
  # citation whose own file said otherwise, and the verification used was `ls` — the path's
  # existence, not its text).
  local poll hops
  poll="$(tr -d '\r' < "$OUT/jvm-result-poll.txt" | paste -sd' ' -)"
  if [ "$poll_rc" -eq 0 ] && boundary_poll_shape_ok "$OUT/jvm-result-poll.txt"; then
    hops="$(awk 'END{print NR}' "$OUT/jvm-result-poll.txt")"
    ok "jvm:boundary" "poll returned $hops formatted hop line(s): $poll"
  else
    bad "jvm:boundary" "poll returned '${poll:-<empty>}' (curl rc=$poll_rc) — not the boundary's wire shape (one costCsv|invariantCount|detail|leak line per hop), so the filter either never saw the request or the response was not its own output (see jvm-result-poll.txt, jvm-result-poll-stderr.txt)"
  fi

  # The actual claim: zero edits AFTER the build, not merely before — and "pristine" must come
  # from a git that actually ANSWERED. `git status --porcelain` exits 128 with EMPTY stdout in a
  # non-repo (or if the build destroyed .git, or git left PATH), and the old stdout-only test
  # printed PASS off exactly that (round-5 B2), on the row carrying PR-3's headline claim. Two
  # things bind it now: rc must be 0, and the verdict is graded from a captured artifact whose
  # POSITIVE sentinel — porcelain v2's `# branch.oid` header, printed even on a clean tree —
  # proves git saw a repository, which v1's empty output never could. File-change lines in v2
  # never start with '#' (tracked '1'/'2'/'u', untracked '?'), so non-'#' lines are the edits.
  # Scoped `-- .` like the preflight: $app is a module inside its repository, so an unscoped
  # status would bill any concurrent change elsewhere in quarkus-super-heroes to rest-villains.
  # The pathspec confines the file-change lines to $app's subtree (edits inside it still appear,
  # repo-root-relative) while the branch headers — the sentinel — print regardless of pathspec.
  local statusfile="$OUT/jvm-target-status-after.txt" after arc
  (cd "$app" && git status --porcelain=v2 --branch -- .) > "$statusfile" 2>&1; arc=$?
  after="$(grep -v '^#' "$statusfile")"
  if [ "$arc" -ne 0 ]; then
    bad "jvm:zero-edits" "UNMEASURED: git could not report on $app (rc=$arc) — 'pristine' would be vacuous (see jvm-target-status-after.txt)"
  elif ! grep -q '^# branch\.oid' "$statusfile"; then
    bad "jvm:zero-edits" "UNMEASURED: rc=0 but no branch header in jvm-target-status-after.txt — git answered nothing gradeable"
  elif [ -z "$after" ]; then
    ok "jvm:zero-edits" "target tree still pristine after the build (jvm-target-status-after.txt: branch headers only)"
  else
    bad "jvm:zero-edits" "the build modified the target tree — PR-3's central claim FAILS (see jvm-target-status-after.txt)"
  fi
  stop_server
}

run_native() {
  log "native — the Phase-0 fixture as a native image (spec §5.2, half 2)"
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    skip "native" "docker unavailable"; return
  fi
  local fx=bench-results/dd043-spikes-2026-07-24/fixture
  # The predeclaration preflight must distinguish THREE outcomes, not two: the old bare `if grep`
  # sent a missing pom, an unreadable pom, and a declaration its fixed string happened not to match
  # all down the same branch as "verified absent" (approver finding 5, round 8) — and a predeclared
  # fixture still builds green with the injector printing `instrumented` (the already-declares path
  # falls through to addRepository and the instrumented line), so every later row would certify
  # injection that never happened. rc=0 → declared, refuse; rc=1 on a readable, non-empty pom →
  # genuinely absent; anything else → UNMEASURED, refuse. A pattern miss (a reformatted element) is
  # still possible here, which is why attribution is ALSO bound post-build by
  # native:injected-not-predeclared below — this preflight only exists to refuse cheaply, before
  # the 15-minute compile, when the confound is already visible in the pom.
  if [ ! -s "$fx/pom.xml" ]; then
    skip "native" "UNMEASURED: $fx/pom.xml is missing or empty — cannot verify the fixture does not predeclare basquin-quarkus, so a pass could not be attributed to injection"
    return
  fi
  grep -q "artifactId>basquin-quarkus<" "$fx/pom.xml"
  local prerc=$?
  if [ "$prerc" -eq 0 ]; then
    skip "native" "the fixture pom still DECLARES basquin-quarkus — a pass could not be attributed to injection"
    return
  elif [ "$prerc" -ne 1 ]; then
    skip "native" "UNMEASURED: grep could not read $fx/pom.xml (rc=$prerc) — cannot verify the fixture does not predeclare basquin-quarkus"
    return
  fi
  echo "  (native compilation is serialized on a mutex and takes 15+ minutes; nothing else CPU-heavy should run)"

  if ! purge_basquin "$REPO_ROOT/bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository" "native-purge-proof.txt"; then
    bad "native" "com/basquin SURVIVED the purge of the fixture's local repo — the build could resolve from the stale copy and measure nothing (native-purge-proof.txt)"; return
  fi
  if ! serve_pages native; then bad "native" "could not publish/serve the scratch Pages repo (publish-native.log)"; return; fi

  if ! stage_injector_jar native; then
    bad "native" "UNMEASURED: could not stage a fresh injector jar (no non-sources jar under build/libs, or the copy failed) — the build would have ridden whatever stale bytes sat in the staging dir"
    stop_server; return
  fi
  local stage="$STAGE_DIR" jar="$STAGE_JAR"

  EXTRA_DOCKER_ARGS="--network host -v $stage:/inj" \
  EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/$jar -Dbasquin.inject.repo.url=http://localhost:$PORT/" \
    bash bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests -Dnative \
    > "$OUT/native-build.log" 2>&1

  grep -q "BUILD SUCCESS" "$OUT/native-build.log" \
    && ok "native:build" "BUILD SUCCESS" || { bad "native:build" "see native-build.log"; stop_server; return; }

  # Finding 5's binding half: the pom preflight above can miss (a reformatted element) and cannot
  # see what the build actually consumed; only the injector's own log can. On the already-declares
  # path it prints `already declares` and STILL injects the repository and prints `instrumented`,
  # so only the ABSENCE of that line — sentinel-bound to the instrumented line proving the
  # participant ran at all — attributes THIS build to injection. Same row, same helper, same
  # doctrine as jvm:injected-not-predeclared.
  assert_absent "$OUT/native-build.log" \
    "\[basquin-injector\] .* already declares" \
    "\[basquin-injector\] instrumented" \
    "native:injected-not-predeclared" \
    "injector ran and never took the already-declares path — the dependency came from injection, not the fixture pom"

  # The row PR #103's approver found missing (finding 3): without it, every remaining check in this
  # stage still PASSes off a stale local repo or a mirror capture — greens that establish nothing
  # about injection, which is the entire point of this half of §5.2. Same doctrine, same helper,
  # same binding as jvm:not-from-central, graded from THIS stage's own build log.
  assert_resolved_from_injected "$OUT/native-build.log" "native:not-from-central"

  local bin="$fx/target/fixture-1.0.0-SNAPSHOT-runner"
  if [ ! -x "$bin" ]; then bad "native:binary" "no runner binary at $bin"; stop_server; return; fi

  # Run on the host, as spike S4 established for this artifact — no container, no glibc surprise.
  "$bin" > "$OUT/native-app-startup.log" 2>&1 &
  local npid=$!
  sleep 6
  curl -sf -o /dev/null "http://localhost:8080/ok" \
    && ok "native:serves" "served /ok" || bad "native:serves" "did not serve /ok"
  grep -o "Installed features: \[[^]]*\]" "$OUT/native-app-startup.log" | head -1 > "$OUT/native-banner.txt"
  grep -q "basquin" "$OUT/native-banner.txt" \
    && ok "native:banner" "$(cat "$OUT/native-banner.txt")" \
    || bad "native:banner" "banner does not list basquin"

  # Round 9, should-fix 6 (approver finding 4): the native half of §5.2 had NO functional evidence —
  # nothing in any committed artifact ever polled /__basquin/result on a native binary, so "survives
  # AOT" was measured only up to LOAD (native:banner), and a native image whose basquin-core was
  # stripped or evicted from the runtime classpath would still green every row above. Same
  # request-then-poll protocol and same shape grader as jvm:boundary, but against THIS stage's own
  # binary — a PASS here is the native cell's functional half, not an inference from the jvm cell's.
  local nid="verify-native-$TS" npoll_rc npoll nhops
  curl -s -H "X-Basquin-Req: $nid" "http://localhost:8080/ok" >/dev/null 2>&1
  curl -sf "http://localhost:8080/__basquin/result?id=$nid" \
    > "$OUT/native-result-poll.txt" 2>"$OUT/native-result-poll-stderr.txt"
  npoll_rc=$?
  npoll="$(tr -d '\r' < "$OUT/native-result-poll.txt" | paste -sd' ' -)"
  if [ "$npoll_rc" -eq 0 ] && boundary_poll_shape_ok "$OUT/native-result-poll.txt"; then
    nhops="$(awk 'END{print NR}' "$OUT/native-result-poll.txt")"
    ok "native:boundary" "poll returned $nhops formatted hop line(s): $npoll"
  else
    bad "native:boundary" "poll returned '${npoll:-<empty>}' (curl rc=$npoll_rc) — the binary serves but its boundary never measured the tagged request, so native instrumentation is NOT functionally proven (see native-result-poll.txt, native-result-poll-stderr.txt)"
  fi
  kill "$npid" 2>/dev/null
  stop_server
}

for s in "${STAGES[@]}"; do "run_$s"; done

if [ "$RESTORE_M2" -eq 1 ]; then
  log "restoring ~/.m2"
  ./gradlew -q --no-daemon :basquin-core:publishToMavenLocal \
    :basquin-quarkus:runtime:publishToMavenLocal \
    :basquin-quarkus:deployment:publishToMavenLocal >/dev/null 2>&1 \
    && echo "  republished the chain into ~/.m2" || echo "  republish FAILED — run publishToMavenLocal yourself"
fi

{
  # Every reader-visible line that could be skimmed in isolation — the H1, the DIRTY/UNMEASURED
  # banner, and the pass/fail headline itself — carries the same NON-CITABLE marker below. A dirty
  # or unmeasured full run only ever reaches this point via --allow-dirty (the gate above refuses
  # it otherwise), so the stamp also records that it was a deliberate opt-in, not an accident.
  TITLE_TAG=""; PASSFAIL_TAG=""
  if [ "$TREE_STATE" != "clean" ]; then
    TITLE_TAG=" — NON-CITABLE"
    PASSFAIL_TAG=" — NON-CITABLE (see warning above)"
  fi
  echo "# DD-043 PR-3 verification — $TS$TITLE_TAG"
  echo
  echo "Commit: \`$GIT_COMMIT\` on \`$GIT_BRANCH\` — tree $TREE_STATE at run start (\`git-status.txt\`)"
  echo "Tree: \`$GIT_TREE\` — HEAD's tree, which survives a squash+prune where the commit above"
  echo "does not. On a CITABLE (clean) run this is the tree that was measured; on a NON-CITABLE"
  echo "one it is not, because the run read a dirty working tree. Check with: git rev-parse <ref>^{tree}"
  echo "Stages run: ${STAGES[*]}"
  if [ "$TREE_STATE" = "DIRTY" ]; then
    echo
    echo "**NON-CITABLE — DIRTY: tracked files differed from \`$GIT_COMMIT\` when this run started"
    if [ "$IS_FULL" -eq 1 ]; then
      echo "(full run — proceeded only because \`--allow-dirty\` was passed):**"
    else
      echo "(see \`git-status.txt\`):**"
    fi
    echo
    echo '```'
    grep -v '^??' "$OUT/git-status.txt"
    echo '```'
    echo
    echo "**These results are NOT reproducible from that commit alone — do not cite this run against it.**"
  elif [ "$TREE_STATE" != "clean" ]; then
    echo
    echo "**NON-CITABLE — UNMEASURED: \`git status\` FAILED at run start (rc=$GIT_STATUS_RC, see \`git-status-stderr.txt\`) —"
    echo "the state of the tree that produced these results is unknown. Do not cite this run against \`$GIT_COMMIT\`.**"
  fi
  echo
  echo "**$PASS passed, $FAIL failed, $SKIP skipped.$PASSFAIL_TAG**"
  echo
  echo "| Result | Check | Detail (derived from this directory's artifacts) |"
  echo "|---|---|---|"
  for r in "${ROWS[@]}"; do
    IFS='|' read -r res chk det <<< "$r"
    echo "| $res | \`$chk\` | ${det//|/\\|} |"
  done
  echo
  echo "## What a pass here does and does not establish"
  echo
  echo "- \`jvm\` proves the injector instruments a real third-party Quarkus application with zero edits"
  echo "  to its source, **in JVM mode, on one target**, resolving over localhost HTTP."
  echo "- \`native\` proves the same mechanism survives AOT, **on the Phase-0 fixture** — not on a real"
  echo "  product. Whether \`rest-villains\` builds native at all is still unmeasured. Native"
  echo "  instrumentation is FUNCTIONALLY established only by a PASSing \`native:boundary\` row in this"
  echo "  run's own table — \`native:banner\` is a LOAD check, blind to a stripped or classpath-evicted"
  echo "  \`basquin-core\`. A table with no \`native:boundary\` PASS leaves the native half of §5.2"
  echo "  functionally unmeasured."
  echo "- Neither exercises the real GitHub Pages HTTPS repository; both serve over localhost HTTP."
  echo "  Pages cannot be tested until the first \`v*\` tag populates \`docs/maven/\`."
  echo "- \`guards\` proves each fail-loudly guard's test can actually fail, not that the guards cover"
  echo "  every way injection could be defeated."
  for pf in jvm-result-poll.txt native-result-poll.txt; do
    [ -s "$OUT/$pf" ] || continue
    ptag="${pf%-result-poll.txt}"
    echo
    echo "## Note on the \`$ptag\` boundary poll"
    echo
    echo "Poll returned \`$(tr -d '\r' < "$OUT/$pf" | paste -sd' ' -)\` (\`$pf\`; per hop line:"
    echo "latencyMs, heapDeltaKb, threadDelta \\| invariant count \\| detail \\| leak)."
    # Derived, never asserted: the old note stated unconditionally that a negative heap delta "is
    # expected to appear", hand-written prose about a figure in the one document whose header
    # promises every figure is derived — and the run of record's delta is positive (PR #103 round 9,
    # should-fix 10). The sign is read from the artifact; only the branch it supports is emitted.
    neg="$(awk -F'[,|]' '$2 ~ /^-[0-9]+$/ {print $2}' "$OUT/$pf" | paste -sd' ' -)"
    if [ -n "$neg" ]; then
      echo "**This poll carries negative heap delta(s) (\`$neg\`) — a known gap, not a bug in this run:**"
      echo "the spec assigns negative heap deltas to PR-5 as an \`UNMEASURED\` producer (the PR-5 row of"
      echo "\`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md\`). Do not read the heap"
      echo "figure as a clean measurement."
    else
      echo "Every heap delta in this poll is non-negative (sign derived from \`$pf\`, not hand-typed)."
      echo "Negative deltas remain a known gap the spec assigns to PR-5 as an \`UNMEASURED\` producer (the"
      echo "PR-5 row of \`docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md\`) — the heap"
      echo "figure is still not a clean measurement until PR-5's controls land."
    fi
  done
} > "$OUT/RESULTS.md"

log "results"
echo "  $OUT/RESULTS.md"
echo "  $PASS passed, $FAIL failed, $SKIP skipped"
# FAIL alone used to decide the exit status, and the skip paths never set FAIL — so `all` on a
# docker-less machine printed "N passed, 0 failed, 2 skipped" and exited 0 with both §5.2
# acceptance halves unmeasured; anything gating on this exit status read green off stages that
# never ran (approver finding 18 — the same class as every other fix in round 8: a requested
# check that did not happen must not report as a pass). SKIP now fails the exit status too; the
# rows still distinguish SKIP from FAIL for the reader.
[ "$FAIL" -eq 0 ] && [ "$SKIP" -eq 0 ]
