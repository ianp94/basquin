#!/usr/bin/env bash
# DD-043 §8.1 evidence capture — pins the README's load-bearing inputs to files.
#
# Re-run to re-derive. Outputs land next to this script. Every figure quoted in
# ../README.md must be copied out of one of these files.
#
# Requires: gh (authenticated, read-only scopes suffice), curl, jq.
# Network use is read-only: GitHub REST reads + raw.githubusercontent.com GETs.

set -uo pipefail
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CAPTURE_DATE_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---- Pinned refs -------------------------------------------------------------
# main@23159df is the ref the original README pinned (2026-07-25). Kept so the
# main-line conclusion stays re-checkable at the ref it was first checked at.
APIC_MAIN_PINNED=23159df62ef7a0935f55b8423c3b0d458773ecfc

resolve() { # repo ref -> "<sha>\t<committer-date>"
  gh api "repos/$1/commits/$2" --jq '[.sha,.commit.committer.date]|@tsv'
}

APIC_26X_ROW="$(resolve Apicurio/apicurio-registry 2.6.x)"
APIC_MAIN_ROW="$(resolve Apicurio/apicurio-registry main)"
HONO_ROW="$(resolve eclipse-hono/hono master)"
DBZS_ROW="$(resolve debezium/debezium-server main)"
DBZ_ROW="$(resolve debezium/debezium main)"

APIC_26X="${APIC_26X_ROW%%$'\t'*}"
APIC_MAIN_HEAD="${APIC_MAIN_ROW%%$'\t'*}"
HONO="${HONO_ROW%%$'\t'*}"
DBZS="${DBZS_ROW%%$'\t'*}"
DBZ="${DBZ_ROW%%$'\t'*}"

{
  echo "# DD-043 §8.1 capture manifest"
  printf 'capture_date_utc\t%s\n' "${CAPTURE_DATE_UTC}"
  printf 'gh_version\t%s\n' "$(gh --version | head -1)"
  echo
  printf '# repo\tref\tresolved_sha\tcommitter_date\n'
  printf 'Apicurio/apicurio-registry\t2.6.x\t%s\n' "$APIC_26X_ROW"
  printf 'Apicurio/apicurio-registry\tmain (head at capture)\t%s\n' "$APIC_MAIN_ROW"
  printf 'Apicurio/apicurio-registry\tmain (README-pinned)\t%s\t2026-07-25 (per README:11)\n' "$APIC_MAIN_PINNED"
  printf 'eclipse-hono/hono\tmaster\t%s\n' "$HONO_ROW"
  printf 'debezium/debezium-server\tmain\t%s\n' "$DBZS_ROW"
  printf 'debezium/debezium\tmain\t%s\n' "$DBZ_ROW"
} > "$OUT/00-manifest.tsv"

# ---- 1. Apicurio 2.6.x app/pom.xml native profile ----------------------------
RAW=https://raw.githubusercontent.com
# fetch <repo> <ref> <path>
# Prints the file's raw content on stdout and returns 0 ONLY on HTTP 200 (curl
# -f: a non-2xx response prints nothing and the function returns 1). This is
# the round-5 fix for S1: the previous `curl -sSL` (no -f, no status check)
# turned a 404 into a zero-byte-different "404: Not Found" body, and every
# `grep -c`/`grep -n` over it silently read as a genuine zero — indistinguishable
# from "checked and clean". The binding invariant is that a reported zero must
# mean "checked and clean", never "the check failed", so every call site below
# that treats an empty/zero grep result as evidence checks fetch's own exit
# status first (`if body="$(fetch ...)"; then ... else FETCH FAILED ... fi`)
# rather than piping straight into grep.
fetch() {
  local tmp status rc
  tmp="$(mktemp)"
  status="$(curl -sS -L -f -o "$tmp" -w '%{http_code}' "$RAW/$1/$2/$3")"
  rc=$?
  if [ $rc -ne 0 ]; then
    echo "FETCH-FAILED repo=$1 ref=$2 path=$3 http=${status:-?} curl_exit=$rc" >&2
    rm -f "$tmp"
    return 1
  fi
  cat "$tmp"
  rm -f "$tmp"
  return 0
}

{
  echo "# SOURCE: $RAW/Apicurio/apicurio-registry/$APIC_26X/app/pom.xml"
  echo "# ref: refs/heads/2.6.x @ $APIC_26X   captured: $CAPTURE_DATE_UTC"
  echo "#"
  if body="$(fetch Apicurio/apicurio-registry "$APIC_26X" app/pom.xml)"; then
    echo "# fetch: OK (HTTP 200)"
    echo "#"
    echo "# grep -n for 'native':"
    printf '%s\n' "$body" | grep -n 'native' | sed 's/^/#   /'
    echo "#"
    echo "# verbatim lines 585-625 (file is 625 lines):"
    printf '%s\n' "$body" | grep -n '' | sed -n '585,625p'
  else
    echo "# FETCH FAILED — see stderr. No content captured; nothing below is evidence of anything."
  fi
} > "$OUT/10-apicurio-2.6.x-app-pom-native-profile.txt"

{
  echo "# Apicurio 2.6.x version facts, ref 2.6.x @ $APIC_26X, captured $CAPTURE_DATE_UTC"
  echo
  echo "## pom.xml (root) — grep -n 'quarkus.version|<version>' head"
  if body="$(fetch Apicurio/apicurio-registry "$APIC_26X" pom.xml)"; then
    printf '%s\n' "$body" | grep -n 'quarkus.version\|^    <version>'
  else
    echo "FETCH FAILED — see stderr"
  fi
  echo
  echo "## app/pom.xml — grep -n 'resteasy'"
  if body="$(fetch Apicurio/apicurio-registry "$APIC_26X" app/pom.xml)"; then
    printf '%s\n' "$body" | grep -n 'resteasy'
  else
    echo "FETCH FAILED — see stderr"
  fi
  echo
  echo "## tags matching ^2\\.6\\. (gh api repos/Apicurio/apicurio-registry/tags --paginate)"
  gh api repos/Apicurio/apicurio-registry/tags --paginate --jq '.[].name' \
    | grep -E '^v?2\.6\.' | sort -Vr | head -20 | while read -r t; do
      s="$(gh api "repos/Apicurio/apicurio-registry/git/ref/tags/$t" --jq '.object.sha' 2>/dev/null)"
      d="$(gh api "repos/Apicurio/apicurio-registry/commits/$s" --jq '.commit.committer.date' 2>/dev/null)"
      printf '%s\t%s\t%s\n' "$t" "${s:0:8}" "$d"
    done
} > "$OUT/11-apicurio-2.6.x-versions.txt"

# ---- 2. Apicurio main: absence of native (re-check at the README-pinned ref) --
{
  echo "# Apicurio main @ $APIC_MAIN_PINNED (the ref README:11 pins), captured $CAPTURE_DATE_UTC"
  echo
  for f in app/pom.xml pom.xml; do
    echo "## $f — count of the string 'native'"
    if body="$(fetch Apicurio/apicurio-registry "$APIC_MAIN_PINNED" "$f")"; then
      n="$(printf '%s\n' "$body" | grep -c 'native')"
      echo "grep -c native $f = $n   [fetch OK, HTTP 200 — verified]"
      echo "## $f — grep -n 'quarkus.version'"
      printf '%s\n' "$body" | grep -n 'quarkus.version' || echo "(none) [fetch OK, HTTP 200 — verified absence]"
    else
      echo "grep -c native $f = FETCH FAILED — NOT a verified zero, see stderr"
      echo "## $f — grep -n 'quarkus.version'"
      echo "FETCH FAILED — NOT a verified absence, see stderr"
    fi
    echo
  done
  echo "## same counts at main HEAD @ $APIC_MAIN_HEAD (drift check)"
  if body="$(fetch Apicurio/apicurio-registry "$APIC_MAIN_HEAD" app/pom.xml)"; then
    n="$(printf '%s\n' "$body" | grep -c 'native')"
    echo "grep -c native app/pom.xml = $n   [fetch OK, HTTP 200 — verified]"
  else
    echo "grep -c native app/pom.xml = FETCH FAILED — NOT a verified zero, see stderr"
  fi
} > "$OUT/12-apicurio-main-no-native.txt"

# ---- 2b. §1.2 and §1.5 sources (were read from the sparse clone, now pinned) --
{
  echo "# Apicurio main @ $APIC_MAIN_PINNED — sources for README §1.2 and §1.5"
  echo "# captured $CAPTURE_DATE_UTC"
  echo
  if clipom="$(fetch Apicurio/apicurio-registry "$APIC_MAIN_PINNED" cli/pom.xml)"; then
    echo "## cli/pom.xml — grep -n 'native|cliSkipNative'  [fetch OK, HTTP 200]"
    printf '%s\n' "$clipom" | grep -n 'native\|cliSkipNative\|Native'
    echo
    echo "## cli/pom.xml — verbatim lines 14-30 (default-native block)"
    printf '%s\n' "$clipom" | grep -n '' | sed -n '14,30p'
    echo
    echo "## cli/pom.xml — verbatim around <id>cli-skip-native</id>"
    printf '%s\n' "$clipom" | grep -n '' \
      | sed -n "$(printf '%s\n' "$clipom" | grep -n '<id>cli-skip-native</id>' | cut -d: -f1 | awk '{print $1-2","$1+24"p"}')"
  else
    echo "## cli/pom.xml — FETCH FAILED, see stderr; sections below skipped"
  fi
  echo
  echo "## README.md (repo root) — grep -n 'cliSkipNative'"
  if readme="$(fetch Apicurio/apicurio-registry "$APIC_MAIN_PINNED" README.md)"; then
    printf '%s\n' "$readme" | grep -n 'cliSkipNative' || echo "(no match) [fetch OK, HTTP 200 — verified absence]"
  else
    echo "FETCH FAILED — NOT a verified absence, see stderr"
  fi
  echo
  echo "## app/src/main/resources/application.properties — grep -n storage/datasource keys"
  if appprops="$(fetch Apicurio/apicurio-registry "$APIC_MAIN_PINNED" app/src/main/resources/application.properties)"; then
    printf '%s\n' "$appprops" | grep -n 'apicurio.storage.kind\|apicurio.storage.sql.kind\|apicurio.datasource.url'
  else
    echo "FETCH FAILED, see stderr"
  fi
} > "$OUT/15-apicurio-main-cli-pom-and-appprops.txt"

# ---- 3. §4 addendum spot-check: Dockerfile.native presence -------------------
# Original observation (README:166) was an HTTP 200 on 2.6.x / 404 on main.
# Re-run here against pinned SHAs rather than branch names.
{
  echo "# Dockerfile.native reachability spot-check, captured $CAPTURE_DATE_UTC"
  echo "# method: curl -sSL -o /dev/null -w '%{http_code}' \$RAW/<repo>/<sha>/<path>"
  echo
  printf '%-10s %-46s %-58s %s\n' REF SHA PATH HTTP
  for pair in "2.6.x:$APIC_26X" "main:$APIC_MAIN_PINNED" "main-head:$APIC_MAIN_HEAD"; do
    ref="${pair%%:*}"; sha="${pair##*:}"
    for p in distro/docker/src/main/docker/Dockerfile.native \
             distro/docker/src/main/docker/Dockerfile.native-scratch \
             distro/docker/src/main/docker/Dockerfile.jvm; do
      code="$(curl -sSL -o /dev/null -w '%{http_code}' "$RAW/Apicurio/apicurio-registry/$sha/$p")"
      printf '%-10s %-46s %-58s %s\n' "$ref" "$sha" "$p" "$code"
    done
  done
} > "$OUT/30-dockerfile-native-http-status.txt"

# ---- 4. Actions tallies ------------------------------------------------------
# Each tally is derived at JOB level, not workflow level: the load-bearing
# question is "did the native job pass", and a workflow conclusion mixes in
# unrelated jobs (and, for debezium-server, marks the native job `skipped`).

tally_runs() { # repo workflow_id branch_filter n_runs job_name_regex outfile label
  local repo="$1" wf="$2" branch="$3" n="$4" jobre="$5" out="$6" label="$7"
  local q="repos/$repo/actions/workflows/$wf/runs?per_page=$n&status=completed"
  [ -n "$branch" ] && q="${q}&branch=$branch"
  local jqjobs="[.jobs[] | select(.name|test(\"$jobre\";\"i\")) | \"\(.name) => \(.conclusion)\"] | join(\"; \")"
  local rows="$OUT/.rows.$$"
  gh api "$q" --jq '.workflow_runs[] | [.id,.head_branch,.head_sha[0:8],.conclusion,.created_at] | @tsv' \
  | while IFS=$'\t' read -r id br sha concl created; do
      jobs="$(gh api "repos/$repo/actions/runs/$id/jobs?per_page=100" --jq "$jqjobs")"
      [ -z "$jobs" ] && jobs="(NO JOB MATCHING FILTER)"
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$id" "$br" "$sha" "$concl" "$created" "$jobs"
    done > "$rows"
  {
    echo "# $label"
    echo "# query:      gh api \"$q\""
    echo "# job filter: job name matches /$jobre/i  (per-run: gh api repos/$repo/actions/runs/<id>/jobs)"
    echo "# captured:   $CAPTURE_DATE_UTC"
    echo "# total_count reported by the API for THIS query (branch filter applies; GitHub also ages out old runs): $(gh api "$q" --jq '.total_count')"
    echo
    printf '%-14s %-36s %-9s %-15s %-22s %s\n' RUN_ID BRANCH RUN_SHA WORKFLOW_CONCL CREATED_AT NATIVE_JOB
    while IFS=$'\t' read -r id br sha concl created jobs; do
      printf '%-14s %-36s %-9s %-15s %-22s %s\n' "$id" "$br" "$sha" "$concl" "$created" "$jobs"
    done < "$rows"
    echo
    echo "# ---- DERIVED TALLY over the $n runs above (window $(tail -1 "$rows" | cut -f5 | cut -dT -f1) .. $(head -1 "$rows" | cut -f5 | cut -dT -f1)) ----"
    echo "# runs listed:            $(wc -l < "$rows")"
    echo "# workflow conclusions:"
    cut -f4 "$rows" | sort | uniq -c | sed 's/^/#     /'
    echo "# native-job conclusions (one line per matching job across all runs listed):"
    cut -f6 "$rows" | tr ';' '\n' | sed 's/^ *//' | grep -o '=> *[a-z_]*$' | sed 's/=> *//' \
      | sort | uniq -c | sed 's/^/#     /'
    echo "# runs with NO matching native job at all: $(cut -f6 "$rows" | grep -c 'NO JOB MATCHING FILTER')"
  } > "$out"
  rm -f "$rows"
}

tally_runs Apicurio/apicurio-registry 1200448 2.6.x 10 'native' \
  "$OUT/20-apicurio-2.6.x-verify-runs.txt" \
  "Apicurio Registry — workflow 'Verify' (.github/workflows/verify.yaml, id 1200448), branch 2.6.x"

tally_runs eclipse-hono/hono 19566723 '' 15 'native' \
  "$OUT/21-hono-native-image-runs.txt" \
  "Eclipse Hono — workflow 'Build native images and run integration tests' (.github/workflows/native-images-tests.yml, id 19566723)"

tally_runs debezium/debezium-server 46636201 '' 12 'native build' \
  "$OUT/22-debezium-server-cross-maven-runs.txt" \
  "Debezium Server — workflow 'Cross Maven CI' (.github/workflows/cross-maven.yml, id 46636201), job 'Verify native build'"

# ---- 5. Step-level attribution for failing native jobs -----------------------
# Answers: did the *native image build* fail, or a downstream integration leg?
{
  echo "# Failing-native-job step attribution, captured $CAPTURE_DATE_UTC"
  echo "# For every run in 21-/22- whose native job concluded 'failure', the"
  echo "# per-step conclusions of that job."
  echo
  for spec in "eclipse-hono/hono:19566723:15:native" "debezium/debezium-server:46636201:12:native build"; do
    repo="${spec%%:*}"; rest="${spec#*:}"; wf="${rest%%:*}"; rest="${rest#*:}"
    n="${rest%%:*}"; jobre="${rest#*:}"
    echo "=== $repo workflow $wf (last $n runs) ==="
    gh api "repos/$repo/actions/workflows/$wf/runs?per_page=$n&status=completed" --jq '.workflow_runs[].id' \
    | while read -r id; do
        gh api "repos/$repo/actions/runs/$id/jobs?per_page=100" --jq "
            .jobs[] | select(.name|test(\"$jobre\";\"i\")) | select(.conclusion==\"failure\") |
            \"run $id  job \\\"\(.name)\\\"  started \(.started_at)  url \(.html_url)\n\" +
            ([.steps[] | \"    step \(.number). \(.name) -> \(.conclusion)\"] | join(\"\n\"))"
      done
    echo
  done
} > "$OUT/23-native-job-failure-steps.txt"

# ---- 6. Native-CI declarations in the two substitutes ------------------------
{
  echo "# Eclipse Hono native declarations, master @ $HONO, captured $CAPTURE_DATE_UTC"
  echo
  echo "## bom/pom.xml — grep -n 'quarkus.platform.version'"
  if body="$(fetch eclipse-hono/hono "$HONO" bom/pom.xml)"; then
    printf '%s\n' "$body" | grep -n 'quarkus.platform.version'
  else
    echo "FETCH FAILED — see stderr"
  fi
  echo
  for f in adapters/parent/pom.xml services/parent/pom.xml; do
    echo "## $f — verbatim around the build-native-image profile"
    if body="$(fetch eclipse-hono/hono "$HONO" "$f")"; then
      printf '%s\n' "$body" | grep -n '' \
        | sed -n "$(printf '%s\n' "$body" | grep -n '<id>build-native-image</id>' | cut -d: -f1 | awk '{print $1-2","$1+8"p"}')"
    else
      echo "FETCH FAILED — see stderr"
    fi
    echo
  done
  echo "## .github/workflows/native-images-tests.yml — schedule + job names"
  if body="$(fetch eclipse-hono/hono "$HONO" .github/workflows/native-images-tests.yml)"; then
    printf '%s\n' "$body" | grep -n 'name:\|cron:\|schedule:\|on:\|workflow_dispatch' | head -20
  else
    echo "FETCH FAILED — see stderr"
  fi
} > "$OUT/40-hono-native-declarations.txt"

{
  echo "# Debezium Server native declarations, captured $CAPTURE_DATE_UTC"
  echo "# debezium-server main @ $DBZS ; debezium core main @ $DBZ"
  echo
  echo "## debezium core pom.xml — grep -n 'quarkus.version'"
  if body="$(fetch debezium/debezium "$DBZ" pom.xml)"; then
    printf '%s\n' "$body" | grep -n 'quarkus.version'
  else
    echo "FETCH FAILED — see stderr"
  fi
  echo
  echo "## debezium-server .github/workflows/cross-maven.yml — triggers (lines 12-24)"
  if body="$(fetch debezium/debezium-server "$DBZS" .github/workflows/cross-maven.yml)"; then
    printf '%s\n' "$body" | grep -n '' | sed -n '12,24p'
  else
    echo "FETCH FAILED — see stderr"
  fi
  echo
  echo "## debezium-server .github/workflows/cross-maven.yml — native-build job"
  if body="$(fetch debezium/debezium-server "$DBZS" .github/workflows/cross-maven.yml)"; then
    printf '%s\n' "$body" | grep -n '' \
      | sed -n "$(printf '%s\n' "$body" | grep -n '^  native-build:' | cut -d: -f1 | awk '{print $1","$1+21"p"}')"
  else
    echo "FETCH FAILED — see stderr"
  fi
} > "$OUT/41-debezium-native-declarations.txt"

# ---- 7. Build-step tally: did the NATIVE IMAGE BUILD itself pass? ------------
# The sharpest figure for row-5 selection. A job can fail on a downstream
# integration leg while the native image built fine; only the build step
# answers "can this product be built native today".
tally_build_step() { # repo wf branch n job_regex step_regex outfile label
  local repo="$1" wf="$2" branch="$3" n="$4" jobre="$5" stepre="$6" out="$7" label="$8"
  local q="repos/$repo/actions/workflows/$wf/runs?per_page=$n&status=completed"
  [ -n "$branch" ] && q="${q}&branch=$branch"
  local rows="$OUT/.steps.$$"
  # NOTE ON "NO STEP DATA": GitHub prunes .steps[] on old runs (returns []).
  # So absence of step data has two causes; the emitted JOB column distinguishes
  # them — "JOB ABSENT" vs a named job with steps=0.
  gh api "$q" --jq '.workflow_runs[] | [.id,.head_sha[0:8],.created_at] | @tsv' \
  | while IFS=$'\t' read -r id sha created; do
      jobinfo="$(gh api "repos/$repo/actions/runs/$id/jobs?per_page=100" --jq "
        [.jobs[] | select(.name|test(\"$jobre\";\"i\"))
                 | \"\(.name)=\(.conclusion) steps=\(.steps|length)\"] | join(\"; \")")"
      [ -z "$jobinfo" ] && jobinfo="JOB ABSENT"
      res="$(gh api "repos/$repo/actions/runs/$id/jobs?per_page=100" --jq "
        [.jobs[] | select(.name|test(\"$jobre\";\"i\"))
                 | .steps[]? | select(.name|test(\"$stepre\";\"i\"))
                 | \"\(.name) => \(.conclusion)\"] | join(\"; \")")"
      [ -z "$res" ] && res="NO STEP DATA"
      printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$sha" "$created" "$jobinfo" "$res"
    done > "$rows"
  {
    echo "# $label"
    echo "# job filter:  /$jobre/i     step filter: /$stepre/i"
    echo "# query:       gh api \"$q\""
    echo "#              then gh api repos/$repo/actions/runs/<id>/jobs  (.steps[])"
    echo "# captured:    $CAPTURE_DATE_UTC"
    echo
    printf '%-14s %-9s %-22s %-58s %s\n' RUN_ID RUN_SHA CREATED_AT JOB BUILD_STEP
    while IFS=$'\t' read -r id sha created jobinfo res; do
      printf '%-14s %-9s %-22s %-58s %s\n' "$id" "$sha" "$created" "$jobinfo" "$res"
    done < "$rows"
    echo
    echo "# ---- DERIVED TALLY (window $(tail -1 "$rows" | cut -f3 | cut -dT -f1) .. $(head -1 "$rows" | cut -f3 | cut -dT -f1)) ----"
    echo "# runs listed:                                $(wc -l < "$rows")"
    echo "# runs where the matching job is absent:      $(cut -f4 "$rows" | grep -c 'JOB ABSENT')"
    echo "# runs with step-level data for that job:     $(cut -f5 "$rows" | grep -vc 'NO STEP DATA')"
    echo "# runs whose job exists but has steps=0"
    echo "#   (GitHub pruned the step list — old run):  $(cut -f4,5 "$rows" | grep 'NO STEP DATA' | grep -vc 'JOB ABSENT')"
    echo "# build-step conclusions (over runs with step data):"
    cut -f5 "$rows" | tr ';' '\n' | grep -o '=> *[a-z_]*$' | sed 's/=> *//' | sort | uniq -c | sed 's/^/#     /'
  } > "$out"
  rm -f "$rows"
}

tally_build_step eclipse-hono/hono 19566723 '' 15 'native' 'Build native images' \
  "$OUT/24-hono-native-build-step.txt" \
  "Eclipse Hono — conclusion of the 'Build native images' STEP, last 15 runs of native-images-tests.yml"

tally_build_step debezium/debezium-server 46636201 '' 12 'native build' 'Debezium Server Native' \
  "$OUT/25-debezium-native-build-step.txt" \
  "Debezium Server — conclusion of the 'Maven build Debezium Server Native' STEP, last 12 runs of cross-maven.yml"

tally_build_step Apicurio/apicurio-registry 1200448 2.6.x 10 'In Memory native images' '.' \
  "$OUT/26-apicurio-2.6.x-native-build-step.txt" \
  "Apicurio Registry 2.6.x — steps of the 'Build and Test In Memory native images' job, last 10 verify.yaml runs on branch 2.6.x"

# ---- 7b. Why debezium-server's native job gets skipped -----------------------
# 22- shows `Verify native build => skipped` on some runs. `native-build` has
# `needs: build` (see 41-), so the explanation should be an upstream `build`
# failure. This dumps EVERY job of every sampled run so that is checkable
# rather than asserted.
{
  echo "# Debezium Server — full job list per run, last 12 runs of cross-maven.yml"
  echo "# query:    gh api \"repos/debezium/debezium-server/actions/workflows/46636201/runs?per_page=12&status=completed\""
  echo "#           then gh api repos/debezium/debezium-server/actions/runs/<id>/jobs"
  echo "# captured: $CAPTURE_DATE_UTC"
  echo "# Purpose: attribute the 'skipped' native builds to their upstream cause."
  echo
  gh api "repos/debezium/debezium-server/actions/workflows/46636201/runs?per_page=12&status=completed" \
    --jq '.workflow_runs[] | [.id,.conclusion,.created_at] | @tsv' \
  | while IFS=$'\t' read -r id concl created; do
      echo "=== run $id  workflow=$concl  $created"
      gh api "repos/debezium/debezium-server/actions/runs/$id/jobs?per_page=100" \
        --jq '.jobs[] | "    \(.name) => \(.conclusion)"'
    done
  echo
  echo "# ---- DERIVED: conclusion of the 'build' job on runs where 'Verify native build' was skipped ----"
  gh api "repos/debezium/debezium-server/actions/workflows/46636201/runs?per_page=12&status=completed" \
    --jq '.workflow_runs[].id' \
  | while read -r id; do
      pair="$(gh api "repos/debezium/debezium-server/actions/runs/$id/jobs?per_page=100" --jq '
        {nat: ([.jobs[]|select(.name=="Verify native build")|.conclusion]|first // "absent"),
         bld: ([.jobs[]|select(.name=="build")|.conclusion]|first // "absent")}
        | "\(.nat)\t\(.bld)"')"
      printf '%s\t%s\n' "$id" "$pair"
    done | awk -F'\t' '$2=="skipped"{c[$3]++} END{for(k in c) printf "#   native=skipped & build=%s : %d run(s)\n", k, c[k]}'
} > "$OUT/27-debezium-skip-attribution.txt"

# ---- 8. Apicurio main: every workflow file, grepped for native -----------------
grep_workflows() { # repo sha outfile label
  local repo="$1" sha="$2" out="$3" label="$4"
  {
    echo "# $label"
    echo "# repo $repo @ $sha, captured $CAPTURE_DATE_UTC"
    echo "# method: gh api repos/$repo/contents/.github/workflows?ref=<sha> to enumerate,"
    echo "#         then GET each file raw and 'grep -n native'."
    echo
    local files
    files="$(gh api "repos/$repo/contents/.github/workflows?ref=$sha" --jq '.[].path' 2>/dev/null)"
    if [ -z "$files" ]; then echo "(.github/workflows not found at this ref)"; return; fi
    echo "# workflow files found: $(printf '%s\n' "$files" | wc -l)"
    echo
    local total=0 failed=0
    while read -r f; do
      local content hits
      if content="$(fetch "$repo" "$sha" "$f")"; then
        hits="$(printf '%s\n' "$content" | grep -n 'native' || true)"
        if [ -n "$hits" ]; then
          echo "## $f  ($(printf '%s\n' "$hits" | wc -l) hit(s))"
          printf '%s\n' "$hits" | sed 's/^/    /'
          total=$((total + $(printf '%s\n' "$hits" | wc -l)))
        else
          echo "## $f  (0 hits, fetch OK — verified)"
        fi
      else
        echo "## $f  (FETCH FAILED — NOT a verified zero, see stderr)"
        failed=$((failed + 1))
      fi
    done <<< "$files"
    echo
    echo "# total 'native' hits across all workflow files: $total"
    echo "# files where fetch failed (excluded above, NOT verified zeros): $failed"
  } > "$out"
}

grep_workflows Apicurio/apicurio-registry "$APIC_MAIN_PINNED" \
  "$OUT/13-apicurio-main-workflows-native-grep.txt" \
  "Apicurio Registry main — every .github/workflows file grepped for 'native'"

grep_workflows Apicurio/apicurio-registry "$APIC_26X" \
  "$OUT/14-apicurio-2.6.x-workflows-native-grep.txt" \
  "Apicurio Registry 2.6.x — every .github/workflows file grepped for 'native'"

# ---- 9. Screened-out candidates (§2 tail) ------------------------------------
# The README screened these out from untracked sparse clones on 2026-07-26.
# Re-derive at pinned default-branch heads so the screen-out is re-checkable.
{
  echo "# Screened-out candidates — native references in CI workflows"
  echo "# captured $CAPTURE_DATE_UTC"
  echo "# method: resolve each repo's default branch head, enumerate"
  echo "#         .github/workflows via the contents API, grep each file for 'native'."
  echo
  for repo in apache/polaris projectnessie/nessie quarkusio/code.quarkus.io apache/incubator-kie-kogito-apps; do
    db="$(gh api "repos/$repo" --jq '.default_branch' 2>/dev/null)"
    row="$(resolve "$repo" "$db" 2>/dev/null)"
    sha="${row%%$'\t'*}"; dt="${row##*$'\t'}"
    echo "=== $repo  default_branch=$db  head=$sha  ($dt)"
    files="$(gh api "repos/$repo/contents/.github/workflows?ref=$sha" --jq '.[].path' 2>/dev/null)"
    if [ -z "$files" ]; then echo "    (.github/workflows not readable)"; echo; continue; fi
    echo "    workflow files: $(printf '%s\n' "$files" | wc -l)"
    hitfiles=0
    failfiles=0
    while read -r f; do
      if content="$(fetch "$repo" "$sha" "$f")"; then
        h="$(printf '%s\n' "$content" | grep -c 'native' || true)"
        if [ "${h:-0}" -gt 0 ]; then
          echo "    HIT $f  ($h line(s) containing 'native')  [fetch OK]"
          printf '%s\n' "$content" | grep -n 'native' | sed 's/^/        /'
          hitfiles=$((hitfiles+1))
        fi
      else
        echo "    FETCH FAILED for $f — NOT a verified zero, see stderr"
        failfiles=$((failfiles+1))
      fi
    done <<< "$files"
    echo "    workflow files containing 'native': $hitfiles"
    echo "    workflow files where fetch failed (excluded, NOT verified zeros): $failfiles"
    echo
  done
} > "$OUT/50-screened-out-candidates.txt"

echo "capture complete: $CAPTURE_DATE_UTC"
ls -1 "$OUT"
