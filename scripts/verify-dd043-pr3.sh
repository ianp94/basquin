#!/usr/bin/env bash
# Sequentially verify DD-043 PR-3 (basquin-maven-injector) and write a fresh results directory.
#
# Usage:
#   bash scripts/verify-dd043-pr3.sh                 # fast stages only (unit, jar, guards) — no docker
#   bash scripts/verify-dd043-pr3.sh all             # everything, including the ~15-min native build
#   bash scripts/verify-dd043-pr3.sh unit jar        # named stages, in the order given
#   APP_DIR=/path/to/rest-villains bash scripts/verify-dd043-pr3.sh jvm
#
# Stages:
#   unit    ./gradlew check — whole-suite test count and failures, read from the JUnit XML
#   jar     the injector jar's three integrity properties: the Sisu index exists, it names a
#           class actually present in the jar, and the baked version matches build.gradle
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
STAGES=()

for arg in "$@"; do
  case "$arg" in
    --restore-m2) RESTORE_M2=1 ;;
    all)          STAGES=(unit jar guards jvm native) ;;
    unit|jar|guards|jvm|native) STAGES+=("$arg") ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done
[ ${#STAGES[@]} -eq 0 ] && STAGES=(unit jar guards)

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
GIT_COMMIT="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git status --porcelain > "$OUT/git-status.txt" 2>&1
if [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]; then
  TREE_STATE="DIRTY"
else
  TREE_STATE="clean"
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
failing_test_names() {
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
for p in glob.glob("basquin-maven-injector/build/test-results/**/*.xml", recursive=True):
    for tc in ET.parse(p).getroot().iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            print(tc.get("name"))
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
# (jvm:not-from-central applied this doctrine inline rather than through this helper: its failure
# condition is "downloaded from any id OTHER than basquin-injected", which a single ERE cannot express,
# and round 4 showed a sentinel loose enough to fit one regex here was loose enough to be satisfied by
# the injector's own stdout. Any future absence row that CAN bind with one regex should use this helper.)
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

purge_basquin() {  # $1 = a local maven repository root
  local repo="$1" proof="$OUT/$2"
  { echo "# purge proof — $(date -u +%FT%TZ)"; echo "# repository: $repo"; echo
    echo "## BEFORE:"; find "$repo/com/basquin" -maxdepth 1 -mindepth 1 2>&1 || true
    echo; echo "## PURGE: rm -rf $repo/com/basquin"
  } > "$proof"
  rm -rf "$repo/com/basquin"
  { echo; echo "## AFTER (a 'No such file' error here is the proof):"
    find "$repo/com/basquin" -type f 2>&1 || true
  } >> "$proof"
}

serve_pages() {  # publishes the chain to a scratch dir and serves it on 127.0.0.1
  local dir="$OUT/pages-repo"
  rm -rf "$dir"
  ./gradlew -q --no-daemon "-PbasquinPagesDir=$dir" \
    :basquin-core:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:runtime:publishAllPublicationsToPagesRepository \
    :basquin-quarkus:deployment:publishAllPublicationsToPagesRepository \
    > "$OUT/publish.log" 2>&1 || return 1
  ( cd "$dir" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 ) \
    > "$OUT/http-access.log" 2>&1 &
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
  # reads test-results before run_unit repopulates it.
  find . -type d -path '*/build/test-results' -prune -exec rm -rf {} +
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
  ./gradlew -q --no-daemon :basquin-maven-injector:jar > "$OUT/jar-build.log" 2>&1
  # Exclude -sources.jar / -javadoc.jar: build.gradle enables withSourcesJar(), and '-' (0x2D) sorts
  # BEFORE '.' (0x2E), so a plain `head -1` picks basquin-maven-injector-X-sources.jar when it exists —
  # which has no .class files and no baked properties, so both checks below would spuriously FAIL against
  # a perfectly good build. A false negative in the one stage that exists to catch silent non-discovery.
  local j; j="$(ls basquin-maven-injector/build/libs/basquin-maven-injector-*.jar 2>/dev/null \
        | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)"
  if [ -z "$j" ]; then bad "jar" "no jar produced"; return; fi

  local idx cls ver
  idx="$(unzip -p "$j" META-INF/sisu/javax.inject.Named 2>/dev/null | tr -d '\r' | head -1)"
  ver="$(unzip -p "$j" basquin-injector.properties 2>/dev/null | tr -d '\r' | sed -n 's/^version=//p')"
  { echo "jar: $j"; echo "sisu index: ${idx:-<MISSING>}"; echo "baked version: ${ver:-<MISSING>}"; } \
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
}

# Each guard is neutered one at a time and must fail ITS OWN named test. A guard test that cannot
# fail is worse than no test: it licenses the bug it claims to prevent.
run_guards() {
  log "guards — mutation checks"
  local src=basquin-maven-injector/src/main/java/com/basquin/maven/BasquinInjector.java
  local backup="$OUT/BasquinInjector.java.orig"
  cp "$src" "$backup"

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
    # a verdict out of a dir the run did not repopulate.
    rm -rf basquin-maven-injector/build/test-results
    ./gradlew :basquin-maven-injector:test --no-daemon -q > "$OUT/guard-$3.log" 2>&1
    local rc=$?
    local names; names="$(failing_test_names)"
    local fresh; fresh="$(find basquin-maven-injector/build/test-results -name '*.xml' -type f 2>/dev/null | wc -l)"
    cp "$backup" "$src"
    # Gradle's exit code alone cannot say "the test task did not execute": tests-ran-and-failed (the
    # expected outcome under a mutation) and never-compiled BOTH exit 1. Fresh XML is the discriminator.
    if [ "$fresh" -eq 0 ]; then
      bad "guards:$3" "UNMEASURED: no fresh JUnit XML (gradle rc=$rc) — the mutated suite never ran, so no verdict exists; see guard-$3.log"
    elif [ "$rc" -eq 0 ]; then
      bad "guards:$3" "module suite GREEN (rc=0) with the guard neutered — $2 cannot fail, the guard is dead"
    elif echo "$names" | grep -qx "$2"; then
      ok "guards:$3" "neutering it fails $2 (among the failures; other tests sharing the guard may fail too — not asserted exclusive)"
    else
      bad "guards:$3" "expected $2 to fail; got: ${names:-<none>}"
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

  ./gradlew :basquin-maven-injector:test --no-daemon -q > "$OUT/guard-restore.log" 2>&1 \
    && ok "guards:restored" "source restored, module suite green" \
    || bad "guards:restored" "suite not green after restore — CHECK $src against $backup"
}

run_jvm() {
  log "jvm — rest-villains instrumented with zero edits to its source (spec §5.2, half 1)"
  local app="${APP_DIR:-$REPO_ROOT/../quarkus-super-heroes/rest-villains}"
  if [ ! -d "$app" ]; then
    skip "jvm" "target clone not found at $app — set APP_DIR"; return
  fi
  app="$(cd "$app" && pwd)"

  # Refuse rather than revert: the tree is not ours to clean up.
  local dirty; dirty="$(cd "$app" && git status --porcelain 2>/dev/null)"
  if [ -n "$dirty" ]; then
    printf '%s\n' "$dirty" > "$OUT/jvm-target-dirty.txt"
    skip "jvm" "target tree is dirty — commit/revert it yourself, then re-run (see jvm-target-dirty.txt)"
    return
  fi
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    skip "jvm" "docker unavailable"; return
  fi

  purge_basquin "$HOME/.m2/repository" "jvm-purge-proof.txt"
  if ! serve_pages; then bad "jvm" "could not publish/serve the scratch Pages repo"; return; fi

  local stage="$REPO_ROOT/build/tmp/verify-pr3-inj"
  mkdir -p "$stage"; cp basquin-maven-injector/build/libs/basquin-maven-injector-*.jar "$stage/"
  local jar; jar="$(basename "$(ls "$stage"/*.jar | head -1)")"

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

  # The unconfounded evidence: no pom anywhere names the deployment artifact.
  grep -q "basquin-quarkus-deployment" "$OUT/http-access.log" \
    && ok "jvm:deployment-from-injected-repo" "$(grep -c 'basquin-quarkus-deployment' "$OUT/http-access.log") GET(s)" \
    || bad "jvm:deployment-from-injected-repo" "not fetched from the injected repo"
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
  local inj_dl foreign_dl
  inj_dl="$(grep -cE "Downloaded from basquin-injected: http://localhost:$PORT/com/basquin/" "$OUT/jvm-build.log" 2>/dev/null)"
  foreign_dl="$(grep -E 'Downloaded from [^:]+: [^ ]*/com/basquin/' "$OUT/jvm-build.log" 2>/dev/null \
                 | grep -v "Downloaded from basquin-injected: http://localhost:$PORT/")"
  if [ -n "$foreign_dl" ]; then
    bad "jvm:not-from-central" "com/basquin downloaded from a repository other than basquin-injected: $(echo "$foreign_dl" | head -1)"
  elif [ "${inj_dl:-0}" -gt 0 ]; then
    ok "jvm:not-from-central" "$inj_dl com/basquin download(s), every one from basquin-injected; none from central or any mirror id"
  else
    bad "jvm:not-from-central" "UNMEASURED: no com/basquin artifact was ever Downloaded from basquin-injected — cannot tell 'none came from central' from 'nothing resolved at all' (see jvm-build.log)"
  fi

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
  curl -s "http://localhost:8084/__basquin/result?id=$id" > "$OUT/jvm-result-poll.txt" 2>&1
  # A populated CSV cost line is the pass; "miss" is the documented sentinel for "the boundary never
  # published a result for this id", and an empty body means the control surface answered nothing at all.
  local poll; poll="$(tr -d '\r\n' < "$OUT/jvm-result-poll.txt")"
  if [ -n "$poll" ] && [ "$poll" != "miss" ]; then
    ok "jvm:boundary" "poll returned $poll"
  else
    bad "jvm:boundary" "poll returned '${poll:-<empty>}' — the filter did not see the request"
  fi

  # The actual claim: zero edits AFTER the build, not merely before.
  local after; after="$(cd "$app" && git status --porcelain)"
  if [ -z "$after" ]; then
    ok "jvm:zero-edits" "target tree still pristine after the build"
  else
    printf '%s\n' "$after" > "$OUT/jvm-target-dirty-after.txt"
    bad "jvm:zero-edits" "the build modified the target tree — PR-3's central claim FAILS"
  fi
  stop_server
}

run_native() {
  log "native — the Phase-0 fixture as a native image (spec §5.2, half 2)"
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    skip "native" "docker unavailable"; return
  fi
  local fx=bench-results/dd043-spikes-2026-07-24/fixture
  if grep -q "artifactId>basquin-quarkus<" "$fx/pom.xml" 2>/dev/null; then
    skip "native" "the fixture pom still DECLARES basquin-quarkus — a pass could not be attributed to injection"
    return
  fi
  echo "  (native compilation is serialized on a mutex and takes 15+ minutes; nothing else CPU-heavy should run)"

  purge_basquin "$REPO_ROOT/bench-results/dd043-spikes-2026-07-24/.m2/.m2/repository" "native-purge-proof.txt"
  if ! serve_pages; then bad "native" "could not publish/serve the scratch Pages repo"; return; fi

  local stage="$REPO_ROOT/build/tmp/verify-pr3-inj"
  mkdir -p "$stage"; cp basquin-maven-injector/build/libs/basquin-maven-injector-*.jar "$stage/"
  local jar; jar="$(basename "$(ls "$stage"/*.jar | head -1)")"

  EXTRA_DOCKER_ARGS="--network host -v $stage:/inj" \
  EXTRA_MAVEN_OPTS="-Dmaven.ext.class.path=/inj/$jar -Dbasquin.inject.repo.url=http://localhost:$PORT/" \
    bash bench-results/dd043-spikes-2026-07-24/env/build.sh clean package -DskipTests -Dnative \
    > "$OUT/native-build.log" 2>&1

  grep -q "BUILD SUCCESS" "$OUT/native-build.log" \
    && ok "native:build" "BUILD SUCCESS" || { bad "native:build" "see native-build.log"; stop_server; return; }

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
  echo "# DD-043 PR-3 verification — $TS"
  echo
  echo "Commit: \`$GIT_COMMIT\` on \`$GIT_BRANCH\` — tree $TREE_STATE at run start (\`git-status.txt\`)"
  echo "Stages run: ${STAGES[*]}"
  if [ "$TREE_STATE" = "DIRTY" ]; then
    echo
    echo "**DIRTY: tracked files differed from \`$GIT_COMMIT\` when this run started (see \`git-status.txt\`)."
    echo "These results are NOT reproducible from that commit alone — do not cite this run against it.**"
  fi
  echo
  echo "**$PASS passed, $FAIL failed, $SKIP skipped.**"
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
  echo "  product. Whether \`rest-villains\` builds native at all is still unmeasured."
  echo "- Neither exercises the real GitHub Pages HTTPS repository; both serve over localhost HTTP."
  echo "  Pages cannot be tested until the first \`v*\` tag populates \`docs/maven/\`."
  echo "- \`guards\` proves each fail-loudly guard's test can actually fail, not that the guards cover"
  echo "  every way injection could be defeated."
  if [ -s "$OUT/jvm-result-poll.txt" ]; then
    echo
    echo "## Note on the boundary poll"
    echo
    echo "Poll returned \`$(cat "$OUT/jvm-result-poll.txt")\`. The fields are latency, heap delta, threads."
    echo "**A negative heap delta is expected to appear and is a known gap, not a bug in this run:** the"
    echo "spec assigns negative deltas to PR-5 as an \`UNMEASURED\` producer, and they have now been"
    echo "observed on more than one code path. Do not read the heap figure as a clean measurement."
  fi
} > "$OUT/RESULTS.md"

log "results"
echo "  $OUT/RESULTS.md"
echo "  $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ]
