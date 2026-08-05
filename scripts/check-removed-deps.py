#!/usr/bin/env python3
"""Removed-path cross-reference (DD-045 item 4A) — "what depended on this?"

Item 1 (scripts/check-citations.py) is whole-tree, so the same push that deletes a file already
turns every prose/comment citation of it into a DEAD PATH finding — but its corpus is deliberately
narrow (every tracked *.md, */citations.txt, and '#'-comment lines of *.sh/workflow files; CODE
lines are consciously excluded, per that script's own docstring). This script closes the gap item
1 leaves open on purpose: CODE that reads a path a diff just removed. The gap is not hypothetical —
scripts/verify-dd043-pr3.sh's `jvm` stage reads
`local bs="bench-results/dd043-pr3-restvillains-2026-07-26/build.sh"` on a code line, invisible to
item 1's comment-only scan; deleting that evidence directory would merge green today without this
check.

MECHANISM.
  1. Compute the removal set: `git diff --name-status --find-renames <base>...<head>`, filtered to
     `D` rows and the OLD name of every `R` (rename) row.
       --staged   diffs the index against HEAD (`git diff --staged`) — the pre-commit pass the
                  TODO item asks for, runnable locally before every commit.
       <base> <head>   diffs base...head (merge-base diff, the same shape `git diff A...B` uses),
                  the CI invocation: on `pull_request`, base is the PR base ref; on `push`,
                  `github.event.before`..`after`. A `before` of the all-zero SHA (a new branch/ref
                  with no prior history) is a disclosed skip, not a silent one — see SKIPPED below.
  2. For each removed path, `git grep -nF -- "<path>"` across ALL tracked files — code included,
     precisely the corpus item 1 excludes. Full-path fixed-string matching only, no basename
     matching (a deleted README.md would otherwise light up the whole tree). CONSEQUENCE,
     disclosed: a dependent that references the removed file by basename alone is not caught.
  3. A hit is a surviving dependent -> FAILED, unless:
       (a) the hit line +/-1 discloses the absence — reusing NEG_RE by IMPORTING it from
           scripts/check-citations.py, one regex, not a second copy (that script's own
           tracked_or_pointer() history is what a drifting second copy of one rule costs);
       (b) a `removed-ok <path> <reason>` entry in scripts/check-citations-allowlist.txt exempts
           it (reason mandatory — the same one-reason-each discipline as every other kind in that
           file). This script parses ONLY that one kind out of the shared allowlist; every other
           kind (frozen/reported/sha/cited-path) is scripts/check-citations.py's concern.
  4. Prints every hit with file:line; exits nonzero on any non-exempt hit.

Exit 0: no removed path in range, or every hit disclosed/allowlisted, or the base is the zero SHA
        (skipped, counted, printed — never a silent pass).
Exit 1: at least one non-exempt surviving dependent.
Exit 2: a git command needed to compute the removal set or search the tree failed outright.

Run:
  python3 scripts/check-removed-deps.py --staged
  python3 scripts/check-removed-deps.py <base> <head>
"""
import importlib.util
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWLIST_FILE = ROOT / "scripts" / "check-citations-allowlist.txt"
ZERO_SHA = "0" * 40

# Reuse NEG_RE from scripts/check-citations.py instead of a second copy of the same rule (see
# module docstring). Safe to import as a module: its `if __name__ == "__main__"` guard means
# nothing runs on import beyond defining functions/regexes/constants.
_spec = importlib.util.spec_from_file_location(
    "check_citations", ROOT / "scripts" / "check-citations.py")
_check_citations = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_check_citations)
NEG_RE = _check_citations.NEG_RE


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)


def load_removed_ok() -> list[tuple[str, str]]:
    """Parse `removed-ok <path> <reason>` entries out of the shared allowlist file. Every other
    line (frozen/reported/sha/cited-path entries, comments, blanks) is not this script's concern
    and is skipped without validation — scripts/check-citations.py owns those grammars."""
    out = []
    if not ALLOWLIST_FILE.exists():
        return out
    for n, raw in enumerate(ALLOWLIST_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if parts[0] != "removed-ok":
            continue
        sub = parts[1].split(None, 1) if len(parts) > 1 else []
        if len(sub) < 2:
            sys.exit(f"allowlist:{n}: removed-ok entry needs <path> <reason>")
        out.append((sub[0], sub[1]))
    return out


def removed_paths(args: list[str]):
    """-> (list[str] removed paths, str|None tree_ref). tree_ref is None for --staged (grep the
    index); otherwise the head ref to grep against. -> (None, None) if the diff could not be
    computed at all (disclosed zero-SHA skip; already printed)."""
    if args == ["--staged"]:
        diff = git("diff", "--name-status", "--find-renames", "--staged")
        tree_ref = None
    else:
        base, head = args
        if set(base) == {"0"}:
            print(f"SKIPPED: base is the zero SHA ({base}) — a new branch/ref with no prior "
                  f"history to diff against; 0 removed paths checked (a disclosed gap, not a "
                  f"silent one)")
            return None, None
        diff = git("diff", "--name-status", "--find-renames", f"{base}...{head}")
        tree_ref = head
    if diff.returncode != 0:
        sys.exit(f"check-removed-deps: git diff failed: {diff.stderr.strip()}")
    removed = []
    for line in diff.stdout.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        status = parts[0]
        if status == "D":
            removed.append(parts[1])
        elif status.startswith("R"):
            removed.append(parts[1])  # old name — the identity that stopped existing
    return removed, tree_ref


def grep_hits(path: str, tree_ref: str | None) -> list[tuple[str, int, str]]:
    """git grep -nF -- "<path>" against the index (tree_ref is None) or a specific tree-ish
    (tree_ref given) — the same tree removed_paths() computed the removal set against.
    -> [(file, lineno, content), ...]."""
    if tree_ref is None:
        proc = git("grep", "--cached", "-nF", "-e", path)
    else:
        proc = git("grep", "-nF", "-e", path, tree_ref)
    if proc.returncode not in (0, 1):  # 0 = matches, 1 = no matches, else a real error
        sys.exit(f"check-removed-deps: git grep failed on {path!r}: {proc.stderr.strip()}")
    hits = []
    for line in proc.stdout.splitlines():
        if not line.strip():
            continue
        if tree_ref is None:
            f, ln, content = line.split(":", 2)
        else:
            _, f, ln, content = line.split(":", 3)
        hits.append((f, int(ln), content))
    return hits


def read_context(f: str, lineno: int, tree_ref: str | None) -> list[str]:
    """-> [line-1, line, line+1] text from the SAME tree grep_hits() searched, for the
    disclosed-absence NEG_RE window."""
    show_ref = f"{tree_ref}:{f}" if tree_ref is not None else f":{f}"
    proc = git("show", show_ref)
    if proc.returncode != 0:
        return ["", "", ""]
    lines = proc.stdout.splitlines()

    def at(i: int) -> str:
        return lines[i - 1] if 1 <= i <= len(lines) else ""

    return [at(lineno - 1), at(lineno), at(lineno + 1)]


def main() -> int:
    args = sys.argv[1:]
    if not args or (len(args) == 1 and args[0] != "--staged") or len(args) > 2:
        sys.exit("usage: check-removed-deps.py --staged | <base> <head>")

    removed, tree_ref = removed_paths(args)
    if removed is None:
        return 0
    if not removed:
        print("check-removed-deps: 0 removed path(s) in this range — nothing to cross-reference")
        return 0

    removed_ok = load_removed_ok()

    def allowed(path: str) -> str | None:
        for pat, reason in removed_ok:
            if path == pat:
                return reason
        return None

    findings: list[str] = []
    n_hits = n_disclosed = n_allowed = 0
    for path in removed:
        hits = grep_hits(path, tree_ref)
        if not hits:
            continue
        reason = allowed(path)
        for f, ln, content in hits:
            n_hits += 1
            if reason is not None:
                n_allowed += 1
                print(f"  NOTE {f}:{ln}: still cites removed path `{path}` — allowlisted: {reason}")
                continue
            window = read_context(f, ln, tree_ref)
            if any(NEG_RE.search(w) for w in window):
                n_disclosed += 1
                print(f"  NOTE {f}:{ln}: still cites removed path `{path}` — surrounding prose "
                      f"discloses the absence")
                continue
            findings.append(f"{f}:{ln}: DEPENDS ON REMOVED PATH `{path}` — {content.strip()}")

    print(f"check-removed-deps: {len(removed)} removed path(s) in this range; {n_hits} "
          f"tree-wide hit(s) ({n_disclosed} disclosed, {n_allowed} allowlisted, "
          f"{len(findings)} FAILED)")
    if findings:
        print(f"\n{len(findings)} FINDINGS:")
        for f in findings:
            print(f"  FAIL {f}")
        return 1
    print("OK — no surviving dependent found for any removed path in this range.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
