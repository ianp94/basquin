#!/usr/bin/env python3
"""Whole-tree citation-integrity check (DD-045 item 1).

A repo-wide audit (bench-results/dd043-pr3-citation-audit-2026-07-30/) found 135 wrong citations
out of 547 checked — 1 in 4 — and wrong citations were review findings in five consecutive rounds
of PR #103. This tool detects the three failure modes observed there:

  1. DEAD PATH      — the cited file/directory is not in the tracked tree.
  2. STALE LINE     — a cited line number is past end-of-file, a pinned `file:line: expected-text`
                      row no longer matches, or a value quoted beside a line citation exists in
                      the file but NOT at the cited line(s).
  3. CARRIED VALUE  — prose quotes a backticked figure (cost CSV, timestamp, commit hash, large
                      count) next to a citation, and the figure appears NOWHERE in any file that
                      passage cites: the quote came from an older, regenerated artifact. This is
                      the mode hand-checking kept missing — it requires reading the cited file's
                      content, not checking its existence.

SCOPE — deliberate, not incidental. WHOLE-TREE, never diff-scoped: a diff-scoped run in PR #103
round 7 missed four dead citations because they lived in files the commit did not touch. "Whole
tree" means every tracked file in these classes:

  * every tracked *.md                 (prose claims);
  * every tracked */citations.txt     (the pinned format that re-verified 66/66 in the audit;
                                       checked strictly, row by row);
  * '#'-comment lines of tracked *.sh and .github/workflows/* (the observed carried-value defect
    was a shell-script comment, so comments are claims; CODE lines are operational and skipped).

Consciously EXCLUDED — citations there are NOT checked, so do not claim they were:
  * other *.txt (captured run output/corpus data: evidence of a past run, not claims);
  * comments in *.java/*.py/*.go/*.gradle, and *.json/*.xml/*.html;
  * untracked/gitignored files (invisible on a fresh clone anyway);
  * bare `:N` references whose file is a prose antecedent sentences away (resolving those needs
    a human; the audit's fix is migrating them to pinned or full-path form, not guessing here);
  * citing files matching a `frozen` allowlist entry (historical plans/specs whose stale
    citations the 2026-07-30 audit deliberately left: repointing them would launder false
    statements — see the allowlist for the per-entry reasons).

MECHANICS. Prose is grouped into logical units — markdown paragraphs (blank-line delimited;
each table row its own unit) and contiguous comment blocks — because a wrapped sentence puts
the quoted value and its citation on adjacent physical lines. A quoted value passes if it
appears in ANY file the unit cites (at a cited line where lines are given). Only strong value
shapes are checked (pipe/comma cost CSVs, >=4-digit numbers, thousands-separated numbers,
UTC stamps, digit-bearing hex hashes >=7); short prose numbers like `200` are not treated as
quotes — that single rule removed most mode-3 false positives during tuning.

FALSE-POSITIVE CLASSES handled (each one was hit while tuning against the real tree):
  * fenced code blocks (a `docker run -v` path in a recipe is not a citation) and inline code
    spans that are commands rather than a lone path token;
  * prose ellipses (`dd043-spikes-…/`) and brace/star globs (`s1b-t{0,1,2}-after-*.xml`);
  * trailing sentence punctuation (`campaigns.json.`), wrapping quotes/parens;
  * URLs, `owner/repo@vN` refs, absolute machine-local paths (/tmp, /mnt, ~), env vars;
  * generated build output (`/build/`, `/target/`, `/.m2/`, `/bin/`, `/node_modules/`) —
    correctly absent from the tracked tree;
  * multi-segment tokens whose head is not a real top-level dir (`jvm/native`, `and/or`);
  * comma line-lists (`Agent.java:96,126`) — every listed line is checked;
  * disclosed absences: a dead path is not reported when the surrounding line (+/-1) says so
    ("does not exist", "no longer exists", "deleted", "never produced", "not in the tree") —
    prose EXPLAINING a dead path is not itself a defect. Applies to dead-path findings only,
    never to value checks (an entry disclosing one dead run must not shield a carried value);
  * shorthand basenames: `redirect-session-carry.md` resolves to the dated plan file via
    unique suffix match; ambiguous bare names (`pom.xml`, `README.md` with several unrelated
    matches and none beside the citing file) are reported as non-failing UNVERIFIABLE notes,
    since the true target (often in an upstream repo) is not mechanically decidable;
  * deliberately-absent or external paths via scripts/check-citations-allowlist.txt — one
    reason per entry, three kinds: cited-path exemptions, `frozen` citing-file prefixes, and
    `reported` per-finding suppressions for real defects in files owned by other agents
    (visible in output, pending their owner's fix, non-failing so the gate can land).

Exit 0 when clean (allowlisted/frozen/reported/unverifiable items are printed but do not fail);
exit 1 with a file:line report of every finding otherwise.

Run: python3 scripts/check-citations.py
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWLIST_FILE = ROOT / "scripts" / "check-citations-allowlist.txt"

CITED_EXTS = (
    "md|txt|log|java|sh|py|go|gradle|xml|yml|yaml|json|csv|properties|html|svg|bat|kts|exec"
)
TOKEN_RE = re.compile(
    r"[A-Za-z0-9_][A-Za-z0-9_.…{},*?/-]*\.(?:" + CITED_EXTS + r")(?::[\d,-]+)?"
)
DIR_TOKEN_RE = re.compile(r"[A-Za-z0-9_.][A-Za-z0-9_.…{},*?-]*(?:/[A-Za-z0-9_.…{},*?-]+)+/?")
INLINE_CODE_RE = re.compile(r"`([^`]+)`")
LINELIST_RE = re.compile(r"^(.*?):(\d+(?:[,-]\d+)*)$")
# Strong value shapes only — see docstring. Escaped pipes (`\|` in md tables) are normalised
# before matching. Git hashes are deliberately NOT a value shape: a hash names repo history,
# not the cited file's content (`Commit: 6e67ca7 ... (git-status.txt)` is true while the hash
# appears in no artifact, and upstream target-app pins are not in our object DB at all) —
# checking hashes against cited files produced 13 false positives and zero real catches.
VALUE_RE = re.compile(
    r"^(?:"
    r"-?\d[\d,.]*(?:,-\d[\d,.]*)*\|[\d,.|-]*"  # cost CSV incl. negative deltas: 782,-767,9|0||
    r"|-?\d{4,}"                          # large plain number: 1088, -16456
    r"|-?\d{1,3}(?:,\d{3})+"              # thousands-separated: 48,655,416
    r"|\d{8}T\d{6}Z"                      # UTC stamp: 20260730T102725Z
    r")$"
)
NEG_RE = re.compile(
    r"(?:no longer exist|does not exist|do not exist|don't exist|never (?:produced|created|"
    r"committed|existed)|not (?:in|part of) the (?:repo|tree)|deleted|removed|\bNo `)",
    re.IGNORECASE,
)
GENERATED_SEGS = {"build", "target", ".m2", "bin", "node_modules"}
PINNED_RE = re.compile(r"^([A-Za-z0-9_./-]+):(\d+): (.*)$")

_file_cache: dict[str, list[str] | None] = {}


def read_lines(rel: str) -> list[str] | None:
    if rel not in _file_cache:
        try:
            _file_cache[rel] = (ROOT / rel).read_text(
                encoding="utf-8", errors="replace").splitlines()
        except OSError:
            _file_cache[rel] = None
    return _file_cache[rel]


def load_allowlist():
    """Three entry kinds, one reason each (a reason is mandatory — an unexplained exemption is
    exactly the drift this tool exists to stop):
      <cited-path-or-prefix/> <reason>      exempt a cited path (exact, or prefix if it ends /)
      frozen <citing-prefix> <reason>       do not scan this citing file/dir at all
      reported <citing-prefix> <needle> <reason>
                                            suppress (but print) findings in <citing-prefix>
                                            whose message contains <needle> — for real defects
                                            in files owned by other agents, pending their fix."""
    cited, frozen, reported = [], [], []
    if ALLOWLIST_FILE.exists():
        for n, raw in enumerate(
                (ALLOWLIST_FILE.read_text(encoding="utf-8")).splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            kind = parts[0]
            if kind == "frozen":
                sub = parts[1].split(None, 1) if len(parts) > 1 else []
                if len(sub) < 2:
                    sys.exit(f"allowlist:{n}: frozen entry needs <prefix> <reason>")
                frozen.append((sub[0], sub[1]))
            elif kind == "reported":
                sub = parts[1].split(None, 2) if len(parts) > 1 else []
                if len(sub) < 3:
                    sys.exit(f"allowlist:{n}: reported entry needs <citing> <needle> <reason>")
                reported.append((sub[0], sub[1], sub[2]))
            else:
                if len(parts) < 2:
                    sys.exit(f"allowlist:{n}: entry has no reason: {line!r}")
                cited.append((parts[0], parts[1]))
    return cited, frozen, reported


def main() -> int:
    tracked_set = {
        t for t in subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-z"],
            capture_output=True, text=True, check=True).stdout.split("\0") if t
    }
    top_dirs = {t.split("/", 1)[0] for t in tracked_set if "/" in t}
    tracked_dirs = set()
    for t in tracked_set:
        parts = t.split("/")
        for i in range(1, len(parts)):
            tracked_dirs.add("/".join(parts[:i]))
    by_basename: dict[str, list[str]] = {}
    for t in tracked_set:
        by_basename.setdefault(t.rsplit("/", 1)[-1].lower(), []).append(t)

    allow_cited, frozen, reported = load_allowlist()
    findings: list[str] = []
    allowed_out: list[str] = []
    reported_out: list[str] = []
    unverifiable: list[str] = []
    n_citations = 0

    def cited_allowed(path: str) -> str | None:
        for pat, reason in allow_cited:
            if path == pat or path.rstrip("/") == pat.rstrip("/") or (
                    pat.endswith("/") and path.startswith(pat)):
                return reason
        return None

    def emit(citing: str, msg: str) -> None:
        for cite_pre, needle, reason in reported:
            if citing.startswith(cite_pre) and needle in msg:
                reported_out.append(f"{msg}\n        [reported, pending owner fix: {reason}]")
                return
        findings.append(msg)

    def resolve(citing: str, path: str):
        """-> (candidates, ambiguous). Root-relative, citing-dir-relative, then unique
        basename / unique '-'|'_'-suffix match (case-insensitive) for bare names."""
        if path in tracked_set or path in tracked_dirs:
            return [path], False
        cite_dir = citing.rsplit("/", 1)[0] if "/" in citing else ""
        rel = f"{cite_dir}/{path}" if cite_dir else path
        if rel in tracked_set or rel in tracked_dirs:
            return [rel], False
        if "/" not in path:
            low = path.lower()
            cands = list(by_basename.get(low, []))
            if not cands:
                cands = [t for t in tracked_set
                         if t.lower().endswith(("-" + low, "_" + low, "/" + low))]
            # Prefer matches under the citing file's own directory subtree: an evidence README
            # citing `driver-summary.txt` means the copy in its run directory, not another run's.
            if cite_dir:
                near = [c for c in cands if c.startswith(cite_dir + "/")]
                if near:
                    cands = near
            if len(cands) == 1:
                return cands, False
            if len(cands) > 1:
                return cands, True
        return [], False

    def parse_token(token: str):
        token = token.strip("\"'")
        while token and token[-1] in ".,;)]}'\"":
            if token[-1] == "." and TOKEN_RE.fullmatch(token):
                break
            token = token[:-1]
        if (not token or "…" in token or "..." in token
                or any(c in token for c in "{}*?$")
                or token.startswith(("/", "~", "http:", "https:")) or "@" in token):
            return None
        m = LINELIST_RE.fullmatch(token)
        if m:
            path, spec = m.group(1), m.group(2)
            lines: list[int] = []
            for part in spec.split(","):
                if "-" in part:
                    a, b = part.split("-", 1)
                    lines.extend(range(int(a), int(b) + 1))
                else:
                    lines.append(int(part))
        else:
            path, lines = token, []
        if "/" in path:
            if path.split("/", 1)[0] not in top_dirs:
                return None
            if any(seg in GENERATED_SEGS for seg in path.split("/")):
                return None
            # An extension-less multi-segment token is a citation only when written with a
            # trailing slash (`bench-results/verify-20260730T102725Z/`). Without it, such
            # tokens are overwhelmingly prose shorthand for component pairs (`operator/CRD`,
            # `agent/coverage`, `native/AOT` — 16 false positives in one tuning run).
            # CONSEQUENCE, documented: a dead DIRECTORY citation written without its trailing
            # slash is not caught; the repo convention is to write directories with one.
            if not token.endswith("/") and not TOKEN_RE.fullmatch(token):
                return None
        elif "." not in path:
            return None
        return path, lines

    def check_unit(citing: str, unit: list[tuple[int, str]]) -> None:
        """unit: [(physical line number, text)] — one paragraph / table row / comment block."""
        nonlocal n_citations
        citations = []  # (lineno, raw, path, [lines])
        values = []     # (lineno, normalised value)
        for lineno, text in unit:
            spans = set()
            for m in INLINE_CODE_RE.finditer(text):
                span = m.group(1).strip()
                spans.add(span)
                if TOKEN_RE.fullmatch(span) or DIR_TOKEN_RE.fullmatch(span):
                    parsed = parse_token(span)
                    if parsed:
                        citations.append((lineno, span, *parsed))
                        continue
                norm = span.replace("\\|", "|")
                if VALUE_RE.fullmatch(norm):
                    values.append((lineno, norm))
            bare = INLINE_CODE_RE.sub(" ", text)
            for m in TOKEN_RE.finditer(bare):
                tok = m.group(0)
                if "/" in tok and tok not in spans:
                    parsed = parse_token(tok)
                    if parsed:
                        citations.append((lineno, tok, *parsed))
            for m in DIR_TOKEN_RE.finditer(bare):
                tok = m.group(0)
                if tok not in spans and not TOKEN_RE.fullmatch(tok.rstrip("/")):
                    parsed = parse_token(tok)
                    if parsed and "/" in parsed[0]:
                        citations.append((lineno, tok, *parsed))

        unit_text = {ln: txt for ln, txt in unit}
        resolved: list[tuple[str, list[int]]] = []
        seen = set()
        for lineno, raw, path, lns in citations:
            key = (path, tuple(lns))
            if key in seen:
                continue
            seen.add(key)
            n_citations += 1
            reason = cited_allowed(path)
            if reason is not None:
                allowed_out.append(f"{citing}:{lineno}: `{raw}` — {reason}")
                continue
            cands, ambiguous = resolve(citing, path.rstrip("/"))
            if ambiguous:
                unverifiable.append(
                    f"{citing}:{lineno}: `{raw}` — bare name matches {len(cands)} unrelated "
                    f"tracked files, none beside the citing file; target (possibly in an "
                    f"upstream repo) not mechanically decidable")
                continue
            if not cands:
                window = [unit_text.get(lineno - 1, ""), unit_text[lineno],
                          unit_text.get(lineno + 1, "")]
                if any(NEG_RE.search(w) for w in window):
                    continue  # prose explicitly discloses the absence
                # Not tracked — but maybe on disk. A gitignored citation is a hard failure
                # (invisible on every fresh clone, the audit's should-fix 12); a merely
                # untracked one is usually a file about to be committed alongside the citing
                # doc, or another agent's in-flight run directory — noted, not failed.
                p = path.rstrip("/")
                cite_dir = citing.rsplit("/", 1)[0] if "/" in citing else ""
                on_disk = next((c for c in (p, f"{cite_dir}/{p}" if cite_dir else p)
                                if (ROOT / c).exists()), None)
                if on_disk is not None:
                    ignored = subprocess.run(
                        ["git", "-C", str(ROOT), "check-ignore", "-q", on_disk],
                        capture_output=True).returncode == 0
                    if ignored:
                        emit(citing, f"{citing}:{lineno}: DEAD PATH `{raw}` — exists on disk "
                                     f"but is GITIGNORED, so it is invisible on a fresh clone")
                    else:
                        unverifiable.append(
                            f"{citing}:{lineno}: `{raw}` — exists on disk but is not yet "
                            f"tracked; must be committed with the citing doc or it dies")
                    continue
                emit(citing, f"{citing}:{lineno}: DEAD PATH `{raw}` — no tracked file or "
                             f"directory matches (root-relative, citing-dir-relative, or by "
                             f"basename/suffix)")
                continue
            file_cands = [c for c in cands if c in tracked_set]
            if lns and file_cands:
                ok = [c for c in file_cands
                      if (read_lines(c) or []) and max(lns) <= len(read_lines(c) or [])]
                if not ok:
                    lens = ", ".join(f"{c}: {len(read_lines(c) or [])} lines"
                                     for c in file_cands)
                    emit(citing, f"{citing}:{lineno}: STALE LINE `{raw}` — cited line "
                                 f"{max(lns)} is past end of file ({lens})")
                    continue
                file_cands = ok
            resolved.append((raw, file_cands, lns, lineno))

        for vline, val in values:
            variants = {val, val.replace(",", "")} if re.fullmatch(
                r"-?\d{1,3}(,\d{3})+", val) else {val}
            found_at_cited_line = found_somewhere = False
            checked_files = []
            for raw, file_cands, lns, _ in resolved:
                for c in file_cands:
                    lines = read_lines(c)
                    if lines is None:
                        continue
                    checked_files.append(f"{c}" + (f":{','.join(map(str, lns))}" if lns else ""))
                    norm_lines = [ln.replace("\\|", "|") for ln in lines]
                    hit_lines = [i + 1 for i, ln in enumerate(norm_lines)
                                 if any(v in ln for v in variants)]
                    if hit_lines:
                        found_somewhere = True
                        if not lns or set(hit_lines) & set(lns):
                            found_at_cited_line = True
            if not checked_files:
                continue
            if not found_somewhere and citing.startswith("bench-results/"):
                # An evidence directory is a self-contained run record: its README quotes its
                # own committed artifacts throughout, and a given paragraph may cite some OTHER
                # run only for comparison. A value backed by any tracked file in the citing
                # doc's own directory is not carried — its provenance is beside it.
                own_dir = citing.rsplit("/", 1)[0] + "/"
                for sib in tracked_set:
                    if sib.startswith(own_dir) and sib != citing:
                        sl = read_lines(sib)
                        if sl and any(v in ln.replace("\\|", "|")
                                      for ln in sl for v in variants):
                            found_somewhere = found_at_cited_line = True
                            break
            if not found_somewhere:
                emit(citing, f"{citing}:{vline}: CARRIED VALUE `{val}` — quoted beside "
                             f"citation(s) of {', '.join(sorted(set(checked_files)))} but "
                             f"appears nowhere in them; the figure came from somewhere else "
                             f"(an older run?)")
            elif not found_at_cited_line:
                emit(citing, f"{citing}:{vline}: STALE LINE — quoted value `{val}` exists in "
                             f"the cited file(s) but not at the cited line(s) "
                             f"({', '.join(sorted(set(checked_files)))})")

    def frozen_reason(citing: str) -> str | None:
        for pre, reason in frozen:
            if citing.startswith(pre):
                return reason
        return None

    citing_md = sorted(t for t in tracked_set if t.endswith(".md"))
    citing_pinned = sorted(t for t in tracked_set
                           if t.endswith("/citations.txt") or t == "citations.txt")
    citing_comments = sorted(t for t in tracked_set
                             if t.endswith(".sh") or t.startswith(".github/workflows/"))

    frozen_files = 0
    for citing in citing_md:
        if frozen_reason(citing):
            frozen_files += 1
            continue
        lines = read_lines(citing) or []
        fenced = False
        unit: list[tuple[int, str]] = []
        for n, line in enumerate(lines, 1):
            if line.lstrip().startswith("```"):
                fenced = not fenced
                if unit:
                    check_unit(citing, unit)
                    unit = []
                continue
            if fenced:
                continue
            if not line.strip():
                if unit:
                    check_unit(citing, unit)
                    unit = []
            elif line.lstrip().startswith("|"):
                if unit:
                    check_unit(citing, unit)
                    unit = []
                check_unit(citing, [(n, line)])  # each table row is its own unit
            else:
                unit.append((n, line))
        if unit:
            check_unit(citing, unit)

    for citing in citing_pinned:
        if frozen_reason(citing):
            frozen_files += 1
            continue
        cite_dir = citing.rsplit("/", 1)[0] if "/" in citing else ""
        for n, line in enumerate(read_lines(citing) or [], 1):
            if not line.strip() or line.startswith("#"):
                continue
            pm = PINNED_RE.match(line)
            if not pm:
                emit(citing, f"{citing}:{n}: UNPARSEABLE pinned row: {line[:90]!r}")
                continue
            n_citations += 1
            path, lno, expected = pm.group(1), int(pm.group(2)), pm.group(3)
            target = f"{cite_dir}/{path}" if cite_dir else path
            if target not in tracked_set:
                target = path
            if target not in tracked_set:
                emit(citing, f"{citing}:{n}: DEAD PATH `{path}` in pinned row")
                continue
            tlines = read_lines(target)
            if tlines is None or lno > len(tlines):
                emit(citing, f"{citing}:{n}: STALE LINE pinned `{path}:{lno}` — file has "
                             f"{len(tlines or [])} lines")
            elif expected.strip() and expected.strip() not in tlines[lno - 1]:
                emit(citing, f"{citing}:{n}: STALE LINE pinned `{path}:{lno}` — expected "
                             f"{expected.strip()[:60]!r}, line is "
                             f"{tlines[lno - 1].strip()[:60]!r}")

    for citing in citing_comments:
        if frozen_reason(citing):
            frozen_files += 1
            continue
        unit = []
        for n, line in enumerate(read_lines(citing) or [], 1):
            txt = line.strip()
            if txt.startswith("#") and not txt.startswith("#!"):
                unit.append((n, txt.lstrip("#").strip()))
            else:
                if unit:
                    check_unit(citing, unit)
                    unit = []
        if unit:
            check_unit(citing, unit)

    print(f"check-citations: {n_citations} citations checked across {len(citing_md)} md + "
          f"{len(citing_pinned)} pinned + {len(citing_comments)} comment-scanned files "
          f"({frozen_files} frozen by allowlist, with reasons)")
    for title, rows in (("allowlisted (deliberate absences/externals; reasons in "
                         "scripts/check-citations-allowlist.txt)", allowed_out),
                        ("UNVERIFIABLE (ambiguous bare name; not failed)", unverifiable),
                        ("REPORTED to owning agent, pending their fix (not failed)",
                         reported_out)):
        if rows:
            print(f"\n{len(rows)} {title}:")
            for r in rows:
                print(f"  NOTE {r}")
    if findings:
        print(f"\n{len(findings)} FINDINGS:")
        for f in findings:
            print(f"  FAIL {f}")
        return 1
    print("\nOK — no dead paths, stale lines, or carried values detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
