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
     Ancestor directories that VANISH are searched too (see vanished_dirs) — a bare directory
     citation on a code line was previously invisible to this check AND to item 1 at once. ALSO
     DISCLOSED: the removal set is name-status based, so a path removed and re-added at the same
     name with gutted content is an `M`, never enters the set, and is never checked — a dependent
     whose content assumption broke is invisible here. Detecting that needs content comparison,
     deliberately not built.
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
            raise Aborted(f"allowlist:{n}: removed-ok entry needs <path> <reason>")
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
        raise Aborted(f"git diff failed: {diff.stderr.strip()}")
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
    return removed + vanished_dirs(removed, tree_ref), tree_ref


def vanished_dirs(removed: list[str], tree_ref: str | None) -> list[str]:
    """Ancestor directories of removed files that NO LONGER EXIST — returned with a trailing
    slash, so grep_hits() searches for the bare directory citation too.

    Why: the removal set is file-granular (`git diff --name-status` gives files), and grep_hits()
    searches for the literal file path. A dependent citing only the parent directory —
    `local dir="bench-results/some-run-2026-07-26/"`, no filename — can never match, because that
    string does not contain the removed file's full path as a substring. That is worse than the
    basename gap disclosed above: there the search runs and misses, here the search term is never
    constructed. And such a line is invisible to item 1 as well, which skips code lines entirely,
    so a code line citing a bare removed directory was unreachable by BOTH checks at once — the
    exact question this tool exists to answer. Found by review on PR #107.

    Only VANISHED directories are searched. A directory that still holds tracked files after the
    removal has not stopped existing, so a citation to it is not a broken dependency, and flagging
    it would be noise — the false-positive shape that makes a check get disabled."""
    if not removed:
        return []
    if tree_ref is None:
        proc = git("ls-files", "--cached")
    else:
        proc = git("ls-tree", "-r", "--name-only", tree_ref)
    if proc.returncode != 0:
        raise Aborted(f"could not list tracked files to test directory survival: "
                      f"{proc.stderr.strip()}")
    survivors = proc.stdout.splitlines()
    out, seen = [], set()
    for path in removed:
        parts = path.split("/")[:-1]
        for i in range(len(parts), 0, -1):
            d = "/".join(parts[:i])
            if d in seen:
                continue
            seen.add(d)
            prefix = d + "/"
            if not any(s.startswith(prefix) for s in survivors):
                out.append(prefix)
    return out


class Aborted(Exception):
    """The check could not run — a git command failed, or the allowlist is malformed. Raised, not
    `sys.exit("msg")`: that prints and exits 1, which this script's own contract reserves for "a
    surviving dependent was found". Every abort path here was written that way, so "git diff failed
    and I computed no removal set at all" reported itself as a clean-but-for-findings run. Same
    defect PR #107's review found in check-row-label-coverage.py; both are fixed together, because
    a second copy of one rule drifting is exactly what this repo keeps paying for."""


def grep_hits(path: str, tree_ref: str | None) -> list[tuple[str, int, str]]:
    """git grep -nF -- "<path>" against the index (tree_ref is None) or a specific tree-ish
    (tree_ref given) — the same tree removed_paths() computed the removal set against.
    -> [(file, lineno, content), ...]."""
    if tree_ref is None:
        proc = git("grep", "--cached", "-nF", "-e", path)
    else:
        proc = git("grep", "-nF", "-e", path, tree_ref)
    if proc.returncode not in (0, 1):  # 0 = matches, 1 = no matches, else a real error
        raise Aborted(f"git grep failed on {path!r}: {proc.stderr.strip()}")
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
        print("usage: check-removed-deps.py --staged | <base> <head>", file=sys.stderr)
        return 2

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
    try:
        sys.exit(main())
    except Aborted as e:
        print(f"check-removed-deps: ABORTED — {e}", file=sys.stderr)
        sys.exit(2)
