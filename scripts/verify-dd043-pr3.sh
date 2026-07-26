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

  local declared; declared="$(sed -n "s/^version *= *'\(.*\)'/\1/p" basquin-maven-injector/build.gradle | head -1)"
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

  _mutate() { # $1 = python replacement expr file content, $2 = expected failing test, $3 = label
    python3 - "$src" <<PY
import pathlib, sys
p = pathlib.Path(sys.argv[1]); t = p.read_text(encoding="utf-8")
old, new = $1
if old not in t:
    sys.exit("MUTATION TARGET NOT FOUND")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
PY
    if [ $? -ne 0 ]; then cp "$backup" "$src"; bad "guards:$3" "mutation target not found — the guard's code shape changed"; return; fi
    ./gradlew :basquin-maven-injector:test --no-daemon -q > "$OUT/guard-$3.log" 2>&1
    local names; names="$(failing_test_names)"
    cp "$backup" "$src"
    if echo "$names" | grep -qx "$2"; then
      ok "guards:$3" "neutering it fails exactly $2"
    else
      bad "guards:$3" "expected $2 to fail; got: ${names:-<none>}"
    fi
  }

  _mutate '("if (Boolean.parseBoolean(props.getProperty(PROP_SKIP)))", "if (false)")' \
          "injectsNothingWhenSkipIsSet" "skip"
  _mutate '("        DependencyManagement dm = p.getModel().getDependencyManagement();", "        DependencyManagement dm = null;")' \
          "failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion" "managed-version"
  _mutate '("if (declared == null || declared.equals(version))", "if (true)")' \
          "failsLoudlyWhenTheProjectDeclaresOurArtifactAtADifferentVersion" "declared-version"
  # The fourth guard. PR #103's approver found the header comment claimed to mutation-check "each"
  # fail-loudly guard while only three of the four had a _mutate call — the usability guard was added
  # after the script and never wired in. A claim wider than its check, in the script whose whole job is
  # checking claims.
  _mutate '("        if (problem == null) {", "        if (true) {")' \
          "failsLoudlyWhenOurArtifactIsDeclaredAtAnUnusableScope" "declaration-usability"

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
  # Sentinel is "Downloading from", which proves Maven attempted remote resolution in this log at all.
  # Without it, an empty log would have reported "nothing came from central" as a PASS.
  assert_absent "$OUT/jvm-build.log" "Downloaded from central.*com/basquin" "Downloading from" \
    "jvm:not-from-central" "no com/basquin artifact came from central"

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
  echo "Commit: \`$(git rev-parse --short HEAD)\` on \`$(git rev-parse --abbrev-ref HEAD)\`"
  echo "Stages run: ${STAGES[*]}"
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
