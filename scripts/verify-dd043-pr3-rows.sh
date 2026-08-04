#!/usr/bin/env bash
# DD-045 item 3 — proves scripts/verify-dd043-pr3.sh's OWN rows can go red, not only the
# injector's guards. See docs/superpowers/specs/2026-07-31-dd045-item3-harness-mutation-design.md
# for the full design; this script implements its "core tier" (scenarios C0-C7).
#
# Mechanism: seed one mechanical defect per targeted row into a throwaway git worktree AT HEAD
# (never the working tree — basquin-maven-injector/src/** here stays untouched), run the
# harness's own stage subset there, and assert the targeted row(s) came back FAIL by exact label
# in that run's OWN RESULTS.md, with untargeted rows staying PASS as embedded controls. The verdict
# is read from a COPY of that run's directory, never from the harness's exit code alone — the same
# discipline the harness's own `_mutate` applies one level down (scripts/verify-dd043-pr3.sh:
# "Gradle's exit code alone cannot say the test task did not execute").
#
# This script does not modify scripts/verify-dd043-pr3.sh; it only observes runs of the worktree's
# copy of it. If a row's behavior looks wrong, that is a harness bug to report, not to patch here.
#
# Usage:
#   bash scripts/verify-dd043-pr3-rows.sh                 # all 8 core scenarios (C0-C7), ~7 CI min
#   bash scripts/verify-dd043-pr3-rows.sh C4 C6            # only the named scenarios (no coverage
#                                                           # check — that only applies to a full run)
#   bash scripts/verify-dd043-pr3-rows.sh --allow-dirty    # every scenario's worktree baseline
#                                                           # becomes HEAD plus the main tree's
#                                                           # modified tracked files overlaid on
#                                                           # top (not bare HEAD), for an
#                                                           # exploratory run against in-progress
#                                                           # edits
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

WT_REL="build/tmp/verify-rows-wt"
WT="$REPO_ROOT/$WT_REL"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
META_OUT="$REPO_ROOT/bench-results/verify-rows-$TS"
ALLOW_DIRTY=0
SELECT=()

for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    *) SELECT+=("$arg") ;;
  esac
done

mkdir -p "$META_OUT"

# ---- main-tree dirty gate: mirrors the harness's own full-run-gate doctrine one level up. The
# worktree below is created AT HEAD, so a dirty main tree would silently certify "each row can
# fail" against a tree that is not what HEAD actually holds, unless the operator opts in. ----
git status --porcelain > "$META_OUT/main-git-status.txt" 2>"$META_OUT/main-git-status-stderr.txt"
GSRC=$?
if [ "$GSRC" -ne 0 ]; then
  echo "REFUSED: git status failed at run start (rc=$GSRC, see $META_OUT/main-git-status-stderr.txt)" >&2
  echo "— the main tree's state cannot even be determined." >&2
  exit 3
fi
DIRTY_TRACKED="$(grep -v '^??' "$META_OUT/main-git-status.txt" || true)"

# ---- provenance for the meta's OWN RESULTS.md header, same shape as scripts/verify-dd043-pr3.sh's
# header (:107-116, :926-958): the commit the isolation worktree below is created AT, plus an
# unmistakable DIRTY marker when --allow-dirty let a dirty main tree through the gate above.
# Without this, a full --allow-dirty run grading in-progress edits mints a RESULTS.md that is
# byte-indistinguishable from a clean-HEAD run's. Captured here (same point as the gate above, and
# before the worktree is even created) so it reflects the tree that was actually measured; exported
# so the python scenario engine below — the one place RESULTS.md is written — can read it.
GIT_COMMIT="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ -n "$DIRTY_TRACKED" ]; then TREE_STATE="DIRTY"; else TREE_STATE="clean"; fi
export GIT_COMMIT GIT_BRANCH TREE_STATE

if [ -n "$DIRTY_TRACKED" ] && [ "$ALLOW_DIRTY" -ne 1 ]; then
  {
    echo "REFUSED: the main tree has tracked-file modifications — the isolation worktree below is"
    echo "created AT HEAD, so a dirty main tree would grade a tree that is not what HEAD holds."
    echo
    echo "$DIRTY_TRACKED"
    echo
    echo "Commit or stash your changes, or pass --allow-dirty to overlay them into the worktree"
    echo "for a deliberate exploratory run."
  } >&2
  exit 3
fi

# ---- isolate: throwaway worktree at HEAD, fixed path, leftovers removed first ----
git worktree remove --force "$WT_REL" >/dev/null 2>&1 || true
rm -rf "$WT"
git worktree prune >/dev/null 2>&1 || true
if ! git worktree add --detach "$WT_REL" HEAD >"$META_OUT/worktree-add.log" 2>&1; then
  echo "REFUSED: could not create the isolation worktree at $WT_REL — see $META_OUT/worktree-add.log" >&2
  exit 3
fi

cleanup() {
  git worktree remove --force "$WT_REL" >/dev/null 2>&1
  rm -rf "$WT"
  git worktree prune >/dev/null 2>&1
}
trap cleanup EXIT

chmod +x "$WT/gradlew" 2>/dev/null || true

# ---- overlay list: paths of tracked files modified relative to HEAD, to be reapplied onto the
# worktree every time reset_worktree() (python, below) restores it — so a scenario's baseline is
# HEAD-plus-overlay, not bare HEAD, for the whole run (not just once at setup, which a mid-run
# `git checkout -- .` would otherwise silently discard). Written even when --allow-dirty is off
# or the tree is clean (empty file), so the python side never special-cases "no overlay" apart
# from "empty overlay". Deletions/pure adds are not overlaid — only paths git reports as modified
# relative to HEAD (tracked, present in both trees).
OVERLAY_LIST="$META_OUT/overlay-files.txt"
: > "$OVERLAY_LIST"
if [ "$ALLOW_DIRTY" -eq 1 ] && [ -n "$DIRTY_TRACKED" ]; then
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    [ -f "$REPO_ROOT/$path" ] || continue
    echo "$path" >> "$OVERLAY_LIST"
  done < <(git diff --name-only HEAD -- .)
fi

# ---- the scenario engine: one data table, one seed-apply helper, one grading helper. Python for
# real data structures and regex (the harness's own _mutate is embedded python3 for the same
# reason); this outer script only does CLI/gate/worktree lifecycle. ----
python3 - "$WT" "$META_OUT" "$REPO_ROOT" "${SELECT[@]}" <<'PY'
import glob, os, re, shutil, subprocess, sys, time

WT, META_OUT, REPO_ROOT = sys.argv[1], sys.argv[2], sys.argv[3]
SELECT = set(sys.argv[4:])

# Provenance for the meta's own RESULTS.md header (§ near main()'s `lines` build, below) —
# exported by the bash preamble above, captured at the same point as the dirty gate. "clean" or
# "DIRTY" only: a `git status` failure exits 3 from bash before this python ever runs.
GIT_COMMIT = os.environ.get("GIT_COMMIT", "<unknown>")
GIT_BRANCH = os.environ.get("GIT_BRANCH", "<unknown>")
TREE_STATE = os.environ.get("TREE_STATE", "clean")

def _load_overlay_files():
    """Paths (relative to REPO_ROOT/WT) of main-tree tracked files to overlay onto the worktree
    baseline on every reset_worktree() call — see OVERLAY_LIST above. Empty unless --allow-dirty
    was passed and the main tree had tracked modifications."""
    path = os.path.join(META_OUT, "overlay-files.txt")
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return [line.strip() for line in fh if line.strip()]

OVERLAY_FILES = _load_overlay_files()
OVERLAY_SET = set(OVERLAY_FILES)

def _p(rel):
    return os.path.join(WT, rel)

def _read_raw(rel):
    with open(_p(rel), "rb") as fh:
        return fh.read().decode("utf-8")

def _write_raw(rel, text):
    with open(_p(rel), "wb") as fh:
        fh.write(text.encode("utf-8"))

def apply_seed(rel, old, new):
    """One fixed-string replacement, _mutate's exact anchor discipline
    (scripts/verify-dd043-pr3.sh:508-516): anchor not found -> caller must fail loudly, never
    guess. Preserves the target's own line-ending convention: most tracked files here checkout
    CRLF (.gitattributes forces LF only for *.sh/*.py/gradlew) — the exact defect class
    jar:baked-version once had — so `old`/`new` are authored with plain \\n and converted to
    \\r\\n here if the file itself is CRLF, rather than silently flattening it on write."""
    text = _read_raw(rel)
    crlf = "\r\n" in text
    old_eff = old.replace("\n", "\r\n") if crlf else old
    new_eff = new.replace("\n", "\r\n") if crlf else new
    if old_eff not in text:
        return False
    _write_raw(rel, text.replace(old_eff, new_eff, 1))
    return True

def delete_seed(rel):
    p = _p(rel)
    if not os.path.isfile(p):
        return False
    os.remove(p)
    return True

_TEST_METHOD_RE = re.compile(
    r'@Test\s*\n\s*public\s+void\s+\w+\s*\([^)]*\)\s*(?:throws\s+[\w.]+(?:\s*,\s*[\w.]+)*\s*)?\{'
)

def neuter_all_tests(rel, expected_count):
    """C2's compound seed: insert `if (true) return;` right after every @Test method's opening
    `{`, so each guard's own test can no longer fail no matter how the guard is neutered. Asserts
    the substitution count against the file's actual @Test count (23 for
    BasquinInjectorGuardsTest.java, 8 for BasquinInjectorTest.java) — same anchor discipline as
    apply_seed: a wrong count means the subject's shape drifted and this scenario must fail loud,
    not seed a partial mutant silently."""
    text = _read_raw(rel)
    new_text, n = _TEST_METHOD_RE.subn(lambda m: m.group(0) + " if (true) return;", text)
    if n != expected_count:
        return False, n
    _write_raw(rel, new_text)
    return True, n

def reset_worktree():
    """Restore the worktree to this run's baseline: HEAD, plus OVERLAY_FILES reapplied on top
    when --allow-dirty is in effect. `git checkout -- .` alone would restore bare HEAD and
    silently discard the overlay (the bug this two-step sequence fixes: overlaying once at setup
    is not enough, because every scenario calls this at its start). Drift detection then excludes
    OVERLAY_FILES themselves — they are *expected* to differ from HEAD by design — so it still
    catches any tracked drift left behind by a scenario's seeds or the harness run."""
    subprocess.run(["git", "-C", WT, "checkout", "--", "."], check=True,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for rel in OVERLAY_FILES:
        src = os.path.join(REPO_ROOT, rel)
        if not os.path.isfile(src):
            continue
        dst = os.path.join(WT, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(src, dst)
    rc = subprocess.run(["git", "-C", WT, "status", "--porcelain", "--", "."],
                         capture_output=True, text=True, check=True)
    dirty = [l for l in rc.stdout.splitlines()
             if not l.startswith("??") and l[3:] not in OVERLAY_SET]
    if dirty:
        raise RuntimeError(f"worktree reset left tracked drift behind: {dirty}")

def snapshot_run_dirs():
    return set(glob.glob(os.path.join(WT, "bench-results", "verify-*")))

def invoke_harness(stage_args):
    before = snapshot_run_dirs()
    t0 = time.time()
    proc = subprocess.run(["bash", "scripts/verify-dd043-pr3.sh"] + list(stage_args),
                           cwd=WT, capture_output=True, text=True)
    dt = time.time() - t0
    new_dirs = sorted(snapshot_run_dirs() - before)
    return proc, dt, new_dirs

_ROW_RE = re.compile(r"^\| (PASS|FAIL|SKIP) \| `([^`]+)` \| (.*) \|$", re.MULTILINE)
_HEADLINE_RE = re.compile(r"\*\*(\d+) passed, (\d+) failed, (\d+) skipped\.")

def grade(scn, copy_dir, proc, dt, new_dirs):
    """Every discriminator from the design doc's §5, in order: (1) exactly one fresh run dir,
    graded from a COPY of it, never the exit code alone; (2) row assertions by exact label,
    presence-based — absence of an expected FAIL is a meta failure, never a vacuous pass; (3) the
    tally line must agree with the counted rows; (4) exit status must agree with the tallies, per
    the harness's own rule (scripts/verify-dd043-pr3.sh:1013-1019); (5) branch pinning, only where
    the scenario table asks for it."""
    problems = []
    if len(new_dirs) != 1:
        problems.append(f"expected exactly 1 fresh run dir, found {len(new_dirs)}: {new_dirs}")
        return problems
    results_path = os.path.join(copy_dir, "RESULTS.md")
    if scn.get("is_gate"):
        if os.path.exists(results_path):
            problems.append("RESULTS.md unexpectedly present for a gate-refusal scenario")
        if not os.path.isfile(os.path.join(copy_dir, "git-status.txt")):
            problems.append("git-status.txt missing from the refused run's directory")
        if "REFUSED" not in proc.stderr:
            problems.append("stderr does not contain REFUSED")
        if proc.returncode != scn["expect_exit"]:
            problems.append(f"exit={proc.returncode}, expected {scn['expect_exit']}")
        return problems
    if not os.path.isfile(results_path):
        problems.append("RESULTS.md missing from the copied run directory")
        return problems
    text = open(results_path, encoding="utf-8").read()
    m = _HEADLINE_RE.search(text)
    if not m:
        problems.append("could not parse the '**P passed, F failed, S skipped.**' headline")
        return problems
    p, f, s = int(m.group(1)), int(m.group(2)), int(m.group(3))
    rows = _ROW_RE.findall(text)
    if len(rows) != p + f + s:
        problems.append(f"counted {len(rows)} table rows but the headline tallies to {p+f+s} ({p}+{f}+{s})")
    expect_total = scn.get("expect_total_rows")
    if expect_total is not None and len(rows) != expect_total:
        problems.append(f"counted {len(rows)} table rows, expected {expect_total}")
    fail_labels = {label for (res, label, det) in rows if res == "FAIL"}
    pass_labels = {label for (res, label, det) in rows if res == "PASS"}
    for label in scn.get("expect_fail", ()):
        if label not in fail_labels:
            problems.append(f"expected FAIL row `{label}` not present (FAIL rows: {sorted(fail_labels)})")
    for label in scn.get("expect_pass", ()):
        if label not in pass_labels:
            problems.append(f"expected PASS row `{label}` not present (PASS rows: {sorted(pass_labels)})")
    for label, needle in scn.get("branch_pins", {}).items():
        # `needle` is a regex, matched with re.search — not a plain substring `in`. Most pins are
        # safe as plain text either way (C2's `guard is dead` is a literal phrase with no adjacent
        # digits to be swallowed by), but a pin built around a count is a substring-containment
        # hazard: the OLD shape's "0 tests" is `in` "360 tests, 1 failures" too, because 360 itself
        # ends in the digit "0" — the exact branch the pin exists to EXCLUDE would silently satisfy
        # it. `^`-anchoring (C7's pin, below) closes that: a detail string only STARTS WITH "0"
        # when the count really is zero, never as the trailing digit of a larger number.
        match = next((det for (res, lbl, det) in rows if lbl == label and res == "FAIL"), None)
        if match is None:
            problems.append(f"branch pin: no FAIL row `{label}` to pin against")
        elif not re.search(needle, match):
            problems.append(f"branch pin: FAIL row `{label}` detail doesn't match /{needle}/: {match!r}")
    expect_exit = scn.get("expect_exit")
    if expect_exit is not None and proc.returncode != expect_exit:
        problems.append(f"exit={proc.returncode}, expected {expect_exit}")
    # Exit-status agreement with the parsed tallies — the only kill available for the exit-status
    # line itself (scripts/verify-dd043-pr3.sh:1019: `[ "$FAIL" -eq 0 ] && [ "$SKIP" -eq 0 ]`).
    should_be_zero = (f == 0 and s == 0)
    if should_be_zero and proc.returncode != 0:
        problems.append(f"tallies say clean (F=0,S=0) but exit={proc.returncode}")
    if not should_be_zero and proc.returncode == 0:
        problems.append(f"tallies say F={f} S={s} but exit=0")
    return problems

# ---- the 13 green-run row labels (scripts/verify-dd043-pr3.sh emits exactly these on a green
# `unit jar guards` run). A label with no scenario below that targets it is a meta-run failure —
# computed at the end from the scenario table itself, never by parsing the harness's bash. ----
GREEN_RUN_LABELS = [
    "unit",
    "jar:sisu-index", "jar:baked-version", "jar:gradle-init-version",
    "guards:skip", "guards:managed-version", "guards:managed-exclusions", "guards:managed-scope",
    "guards:declared-version", "guards:declaration-usability", "guards:sibling-scope",
    "guards:sibling-version",
    "guards:restored",
]

GUARDS_PASS_CONTROLS = [
    "guards:skip", "guards:managed-version", "guards:managed-exclusions", "guards:managed-scope",
    "guards:declared-version", "guards:declaration-usability", "guards:sibling-scope",
    "guards:sibling-version",
]

BIT = "basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorTest.java"
BGT = "basquin-maven-injector/src/test/java/com/basquin/maven/BasquinInjectorGuardsTest.java"
INJ_BUILD_GRADLE = "basquin-maven-injector/build.gradle"

SCENARIOS = [
    dict(
        # The gate this scenario targets (scripts/verify-dd043-pr3.sh:133-154) fires on ANY
        # tracked-file diff, before any stage reads the file's content, so the seed's target only
        # needs to be *some* tracked file. It is deliberately pointed at this very workflow file —
        # already on both `paths:` lists below — rather than a file outside them (e.g. README.md,
        # the original choice): that removes a filter dependency instead of adding one to track.
        # This file is also never read by the invoked harness process (`bash
        # scripts/verify-dd043-pr3.sh`), so the edit cannot influence anything but git's dirty
        # check either way — belt and suspenders on top of the gate already firing pre-stage.
        name="C0", desc="full-run dirty gate, exit 3",
        stage_args=["all"], is_gate=True, expect_exit=3,
        seeds=[lambda: apply_seed(
            ".github/workflows/verify-dd043-pr3-rows.yml",
            "          if-no-files-found: warn",
            "          if-no-files-found: warn\n# DD-045 item 3 C0 seed",
        )],
    ),
    dict(
        name="C1", desc="unit fail branch + guards:restored not-green branch (one invocation)",
        stage_args=["unit", "guards"], expect_exit=1,
        expect_fail=["unit", "guards:restored"], expect_pass=list(GUARDS_PASS_CONTROLS),
        expect_total_rows=10,
        seeds=[lambda: apply_seed(
            BIT,
            '        assertTrue("ours is appended after", BasquinInjector.REPO_ID.equals(effective.get(1).getId()));\n'
            '    }\n}',
            '        assertTrue("ours is appended after", BasquinInjector.REPO_ID.equals(effective.get(1).getId()));\n'
            '    }\n\n'
            '    @Test\n'
            '    public void dd045Item3SeededAlwaysFailingTest() {\n'
            '        org.junit.Assert.fail("DD-045 item 3 seeded failure -- proves the unit row can go red");\n'
            '    }\n}',
        )],
    ),
    dict(
        name="C2", desc="all eight guards:<label> guard-is-dead branch",
        stage_args=["guards"], expect_exit=1,
        expect_fail=list(GUARDS_PASS_CONTROLS), expect_pass=["guards:restored"],
        expect_total_rows=9,
        branch_pins={label: "guard is dead" for label in GUARDS_PASS_CONTROLS},
        seeds=[
            lambda: neuter_all_tests(BIT, 8)[0],
            lambda: neuter_all_tests(BGT, 23)[0],
        ],
    ),
    dict(
        name="C3", desc="jar stage-refusal row (build fails via verifyGradleInitScriptVersion)",
        stage_args=["jar"], expect_exit=1,
        expect_fail=["jar"], expect_total_rows=1,
        seeds=[lambda: apply_seed(
            "basquin-init.gradle",
            "System.getProperty('basquin.inject.version') ?: '0.3.0'",
            "System.getProperty('basquin.inject.version') ?: '9.9.9'",
        )],
    ),
    dict(
        name="C4", desc="jar:baked-version (single- vs double-quoted version literal)",
        stage_args=["jar"], expect_exit=1,
        expect_fail=["jar:baked-version"],
        expect_pass=["jar:sisu-index", "jar:gradle-init-version"],
        expect_total_rows=3,
        seeds=[lambda: apply_seed(INJ_BUILD_GRADLE, "version = '0.3.0'", 'version = "0.3.0"')],
    ),
    dict(
        name="C5", desc="jar:gradle-init-version UNMEASURED branch (sentinel prefix drift)",
        stage_args=["jar"], expect_exit=1,
        expect_fail=["jar:gradle-init-version"],
        expect_pass=["jar:sisu-index", "jar:baked-version"],
        expect_total_rows=3,
        seeds=[lambda: apply_seed(
            INJ_BUILD_GRADLE,
            'logger.lifecycle("verifyGradleInitScriptVersion: ',
            'logger.lifecycle("verifyGradleInitScriptVersionRENAMED: ',
        )],
    ),
    dict(
        name="C6", desc="jar:sisu-index MISSING branch (index deleted + first line of defense severed)",
        stage_args=["jar"], expect_exit=1,
        expect_fail=["jar:sisu-index"],
        expect_pass=["jar:baked-version", "jar:gradle-init-version"],
        expect_total_rows=3,
        seeds=[
            lambda: delete_seed("basquin-maven-injector/src/main/resources/META-INF/sisu/javax.inject.Named"),
            lambda: apply_seed(
                INJ_BUILD_GRADLE,
                "tasks.named('jar') { finalizedBy verifyInjectorIsDiscoverable }",
                "tasks.named('jar') { }",
            ),
        ],
    ),
    dict(
        name="C7", desc="unit anti-vacuity branch (tot > 0): all Test tasks disabled repo-wide",
        stage_args=["unit"], expect_exit=1,
        expect_fail=["unit"], expect_total_rows=1,
        # Anchored to the START of the detail string (scripts/verify-dd043-pr3.sh:398-400 emits
        # "$tot tests, $f failures (gradle rc=$rc) — see gradle-check.log"): pins tot==0 AND
        # f==0 AND rc==0 together, the exact "no tests ran" branch. An unanchored "0 tests" would
        # also match e.g. "360 tests, 1 failures" (360 ends in "0"), which is the branch this pin
        # exists to exclude — see grade()'s branch_pins comment.
        branch_pins={"unit": r"^0 tests, 0 failures \(gradle rc=0\)"},
        seeds=[lambda: apply_seed(
            "build.gradle",
            "tasks.named('check') {\n    dependsOn 'runExampleProper', 'runRunnerProper'\n}",
            "tasks.named('check') {\n    dependsOn 'runExampleProper', 'runRunnerProper'\n}\n\n"
            "allprojects { tasks.withType(Test).configureEach { it.enabled = false } }",
        )],
    ),
]

def run_scenario(scn):
    name = scn["name"]
    print(f"== {name} — {scn['desc']}", flush=True)
    reset_worktree()
    for seed in scn["seeds"]:
        ok = seed()
        if ok is False:
            print(f"   UNPROVEN: a seed's anchor was not found in the subject — the subject's shape drifted")
            return "UNPROVEN", ["seed anchor not found"], 0.0, None
    proc, dt, new_dirs = invoke_harness(scn["stage_args"])
    scenario_out = os.path.join(META_OUT, name)
    os.makedirs(scenario_out, exist_ok=True)
    with open(os.path.join(scenario_out, "invoke-stdout.log"), "w", encoding="utf-8") as fh:
        fh.write(proc.stdout)
    with open(os.path.join(scenario_out, "invoke-stderr.log"), "w", encoding="utf-8") as fh:
        fh.write(proc.stderr)
    with open(os.path.join(scenario_out, "invoke-meta.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"stage_args: {scn['stage_args']}\nexit: {proc.returncode}\nwallclock_s: {dt:.1f}\n"
                 f"new_run_dirs: {new_dirs}\n")
    copy_dir = os.path.join(scenario_out, "run")
    if len(new_dirs) == 1:
        shutil.copytree(new_dirs[0], copy_dir)
        shutil.rmtree(new_dirs[0])
    elif new_dirs:
        for i, d in enumerate(new_dirs):
            shutil.copytree(d, os.path.join(scenario_out, f"run-{i}"))
            shutil.rmtree(d)
    problems = grade(scn, copy_dir, proc, dt, new_dirs)
    reset_worktree()
    status = "PROVEN" if not problems else "FAILED"
    print(f"   {status} (exit={proc.returncode}, {dt:.1f}s)" + (f" -- {'; '.join(problems)}" if problems else ""),
          flush=True)
    return status, problems, dt, proc.returncode

def main():
    scenarios = [s for s in SCENARIOS if not SELECT or s["name"] in SELECT]
    if SELECT and len(scenarios) != len(SELECT):
        missing = SELECT - {s["name"] for s in SCENARIOS}
        print(f"unknown scenario name(s): {sorted(missing)}", file=sys.stderr)
        return 2
    t0 = time.time()
    outcomes = {}
    for scn in scenarios:
        status, problems, dt, exit_code = run_scenario(scn)
        outcomes[scn["name"]] = (status, problems, dt, scn, exit_code)
    total_dt = time.time() - t0

    coverage_problems = []
    if not SELECT:
        killed_by = {}
        for name, (status, problems, dt, scn, exit_code) in outcomes.items():
            if status == "PROVEN":
                for label in scn.get("expect_fail", ()):
                    if label in GREEN_RUN_LABELS:
                        killed_by.setdefault(label, []).append(name)
        for label in GREEN_RUN_LABELS:
            if label not in killed_by:
                coverage_problems.append(f"green-run row `{label}` has no killing scenario")

    # ---- provenance header, same shape as scripts/verify-dd043-pr3.sh's own RESULTS.md header
    # (:107-116, :926-958): stamp the commit the isolation worktree was created AT, and mark the
    # page unmistakably when the main tree was dirty. Without this, a full --allow-dirty run
    # grading in-progress edits produces a RESULTS.md byte-indistinguishable from a clean-HEAD
    # run's — this meta-check's own version of the defect its C0 scenario exists to catch one
    # level down. GIT_COMMIT/GIT_BRANCH/TREE_STATE are exported by the bash preamble above,
    # captured at the same point as the dirty gate — from the untracked, run-only capture file
    # this run's own META_OUT writes it to (same status as every verify-* run directory: never
    # committed). TREE_STATE is only ever "clean" or "DIRTY" here — a `git status` failure
    # (UNMEASURED one level down) already exits 3 from the bash preamble before this python ever
    # runs, so there is no third state to stamp.
    lines = []
    title_tag = " — NON-CITABLE" if TREE_STATE != "clean" else ""
    lines.append(f"# DD-045 item 3 meta-check run{title_tag}")
    lines.append("")
    lines.append(f"Commit: `{GIT_COMMIT}` on `{GIT_BRANCH}` — main tree {TREE_STATE} at run start "
                 f"(`main-git-status.txt`).")
    if TREE_STATE != "clean":
        lines.append("")
        lines.append(f"**NON-CITABLE — DIRTY: tracked files in the main tree differed from "
                     f"`{GIT_COMMIT}` when this run started (reached here only because "
                     f"`--allow-dirty` was passed — the dirty gate above refuses otherwise; the "
                     f"isolation worktree is HEAD with those files overlaid on top, see "
                     f"`overlay-files.txt`):**")
        lines.append("")
        lines.append("```")
        status_path = os.path.join(META_OUT, "main-git-status.txt")
        if os.path.isfile(status_path):
            with open(status_path, encoding="utf-8") as fh:
                for raw in fh:
                    if not raw.startswith("??"):
                        lines.append(raw.rstrip("\n"))
        lines.append("```")
        lines.append("")
        lines.append(f"**This meta-run's verdicts are NOT reproducible from `{GIT_COMMIT}` alone — "
                     f"do not cite this run as proving anything about that commit.**")
    lines.append("")
    lines.append(f"Scenarios run: {', '.join(outcomes)}  \n")
    lines.append(f"Wall-clock: {total_dt:.1f}s\n")
    lines.append("")
    # Exit column shows the OBSERVED exit code the run actually produced, not merely the
    # scenario's expected one — an UNPROVEN scenario (seed anchor not found) never invokes the
    # harness at all, so printing only `expect_exit` there would display an exit that never
    # happened. The expected exit is still shown alongside, clearly labelled, for reference.
    lines.append("| Scenario | Status | Exit (observed; expect) | Wall-clock (s) | Problems |")
    lines.append("|---|---|---|---|---|")
    all_ok = True
    for name, (status, problems, dt, scn, exit_code) in outcomes.items():
        if status != "PROVEN":
            all_ok = False
        observed = str(exit_code) if exit_code is not None else "n/a (harness never invoked)"
        lines.append(f"| {name} | {status} | {observed}; {scn.get('expect_exit', '?')} | {dt:.1f} | {'; '.join(problems) or '-'} |")
    if not SELECT:
        lines.append("")
        lines.append("## Green-run row coverage (13 labels)")
        lines.append("")
        if coverage_problems:
            all_ok = False
            for cp in coverage_problems:
                lines.append(f"- FAILURE: {cp}")
        else:
            lines.append("all 13 green-run row labels are killed by at least one PROVEN scenario.")
    lines.append("")
    lines.append("## Honest limit")
    lines.append("")
    lines.append("Each scenario proves its targeted row can go red against ONE representative")
    lines.append("defect, not against every defect the row claims to catch — the same bounded claim")
    lines.append("the harness's own epilogue makes for its guards stage.")
    with open(os.path.join(META_OUT, "RESULTS.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    print()
    print(f"  {META_OUT}/RESULTS.md")
    print(f"  {sum(1 for s,_,_,_,_ in outcomes.values() if s == 'PROVEN')} proven, "
          f"{sum(1 for s,_,_,_,_ in outcomes.values() if s != 'PROVEN')} failed/unproven, "
          f"wall-clock {total_dt:.1f}s")
    return 0 if (all_ok and not coverage_problems) else 1

sys.exit(main())
PY
rc=$?

echo
echo "meta results: $META_OUT/RESULTS.md"
exit "$rc"
