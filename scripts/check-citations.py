#!/usr/bin/env python3
"""Whole-tree citation-integrity check (DD-045 item 1).

A repo-wide audit (bench-results/dd043-pr3-citation-audit-2026-07-30/) found 135 wrong citations
out of 547 checked — 1 in 4 — and wrong citations were review findings in five consecutive rounds
of PR #103. This tool detects the three failure modes observed there:

  1. DEAD PATH      — the cited file/directory is not in the tracked tree.
  2. STALE LINE     — a cited line number is past end-of-file, a pinned `file:line: expected-text`
                      row no longer matches, or a value quoted beside a line citation exists in
                      the file but NOT at the cited line(s).
  3. CARRIED VALUE  — prose quotes a figure (cost CSV, timestamp, large count) next to a
                      citation, and the figure appears NOWHERE in any file that passage cites:
                      the quote came from an older, regenerated artifact. This is the mode
                      hand-checking kept missing — it requires reading the cited file's
                      content, not checking its existence. Round 11: both round-10 blocking
                      findings lived in value FORMS this check did not look at (a **bold**
                      figure; a small attribute-quoted count `tests="7"`), so the examined
                      forms are now enumerated below and every unexamined digit-bearing form
                      beside a citation is COUNTED in the summary.

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

OUTPUT CONTRACT (round-10 blocking fix: this tool must never claim wider than it examined).
Every citation it parses lands in EXACTLY ONE disposition —

    verified            existence (and any cited lines) checked against a confident resolution:
                        exact root-/citing-dir-relative path, unique same-directory basename,
                        or a passing pinned row;
    FAILED              a finding: dead path, gitignored path, stale line, carried value;
    reported            a FAILED finding suppressed (but printed) via a `reported` allowlist
                        entry: a real defect in a file owned by another agent, pending their fix.
                        A `reported` entry that never fires across a whole run is itself a
                        FINDING (unused-suppression, DD-045 defect-class audit finding 13: two
                        such entries sat live on merged main, matching nothing, while this tool
                        printed `0 reported to owners` and exited 0) — its self-expiry text says
                        to remove it, so an unused entry is exactly the drift this tool exists to
                        catch, now in its own input;
    allowlisted         cited path exempted by the allowlist, one written reason each;
    UNCHECKED-ambiguous several tracked files match; the target is not mechanically decidable;
    UNCHECKED-guessed   exactly one tracked file matches by basename/suffix/path-tail but NOT at
                        the written path and NOT beside the citing file: a guess. Printed with
                        the guessed target; NEVER counted verified, line/value checks NOT run
                        (an earlier version counted 239 such guesses as "checked", some provably
                        wrong — the count must mean something);
    disclosed-absence   the surrounding prose says the path is gone/ignored/untracked, which is
                        an explanation, not a defect;
    untracked-on-disk   exists in the worktree but not in git: usually a file about to be
                        committed with the citing doc — noted, not failed —

and the run aborts (exit 2) if the dispositions do not sum to the parsed total, so a citation
cannot be silently dropped. Non-citation token classes that are skipped (extension-less prose
pairs, URL-shaped tokens, generated build output) are COUNTED and named in the summary. The
final OK line claims "verified clean" only for the verified count and names everything it did
not verify: a zero in this tool's output means checked-and-clean, never unexamined.

RUN-OF-RECORD POINTER (DD-045 item 2). `bench-results/RUN-OF-RECORD` is a tracked pointer FILE
naming the current run-of-record directory (one non-comment line; not a symlink — this
checkout has core.symlinks=false, so a tracked symlink would materialise as a plain-text file
containing its target path). `resolve()` substitutes a cited `bench-results/RUN-OF-RECORD/...`
prefix — INCLUDING the bare form `bench-results/RUN-OF-RECORD/`, nothing after the slash,
which names the directory itself rather than a sub-path within it — with that directory before
matching against the tracked tree, so a supersession edits one file instead of repointing
every citation. `resolve()` checks the substitution FIRST, through a single helper —
`tracked_or_pointer()` — shared by EVERY citation shape it is willing to call an exact match:
the literal, root-relative path, AND its citing-dir-relative normalisation (`../RUN-OF-RECORD/`
cited from a file one level under bench-results/, or bare `RUN-OF-RECORD/` cited from a file
directly under it). Both go through that one helper, in that order, ahead of the generic
exact-path short-circuit (`cand in tracked_set or cand in tracked_dirs`) inside it — for
EITHER shape, not just the literal one — because the caller strips a citation's trailing slash
before calling resolve(), so a bare citation's literal path, or its citing-dir-relative
normalisation, is then IDENTICAL to `bench-results/RUN-OF-RECORD` — the pointer FILE's own
tracked path. An earlier version of this tool checked the generic branch first for the literal
path; it matched the pointer's own tracked-ness and returned "exact" without
`resolve_run_of_record()` ever running, so a dangling, missing, or malformed pointer verified
clean for that one citation shape (fixed in 793c18e). One round later, the citing-dir-relative
branch a few lines further down still ran its own, separately-ordered copy of "check
tracked_set first" — the identical bug, one branch later, because the two shapes were two
independent copies of the ordering instead of one shared, correctly-ordered check; folding both
through `tracked_or_pointer()` is the fix, so a third citation shape reaching the tracked tree
by some future route gets the correct ordering for free rather than needing its own patch. That
was a real, review-found gap for the literal shape: several citations in TODO.md and
docs/ROADMAP.md name only the bare form, and not one of them was flagged when the pointer was
deliberately pointed at a nonexistent directory, while sub-path citations of the same broken
pointer were. (Those sites were originally listed here by `file:line`; the list is deliberately
gone. Two of the three line numbers were stale within one commit, because the same commit
inserted lines earlier in TODO.md — a citation rotting inside the commit that fixed citation
rot. Nothing scans this file: the corpus is *.md, */citations.txt and the '#'-comments of *.sh
and workflow files, so a Python docstring's own citations are unguarded and must not carry line
numbers. Find them with: git grep -nE '`bench-results/RUN-OF-RECORD/`' -- '*.md') For the citing-dir-relative shape no citation was
ever written that way — but `bench-results/dd043-pr3-restvillains-2026-07-26/README.md` already
cites the run of record from one level under bench-results/, so it was one edit away. A bare
citation (literal or citing-dir-relative) now resolves to the target directory itself — a
tracked DIRECTORY, never the pointer FILE — so the `file_cands` computed from it (candidates
that are tracked FILES) is empty exactly as it is for any other directory citation: a quoted
value beside a bare RUN-OF-RECORD/ citation has nothing to check against, rather than being
silently checked against the pointer file's own one-line text. A missing pointer, a pointer
with zero or more than one non-comment line, or a pointer naming a directory that is not
tracked (dangling) is never a silent pass or a guess for any RUN-OF-RECORD/ citation
`resolve()` treats as an exact match — literal or citing-dir-relative, bare or with a sub-path:
`resolve_run_of_record()` reports it, and it is emitted as a FAILED DEAD PATH exactly like any
other broken citation, so it fails the run (exit 1) whenever such a citation exists to need it.
This does NOT cover every conceivable way a citation could name the pointer — a basename/tail
GUESS match could in principle land on `bench-results/RUN-OF-RECORD` too, but never does in
practice: those branches only fire for a path with no "/", and `RUN-OF-RECORD` alone, without
`bench-results/` and without a recognised extension, never matches TOKEN_RE or DIR_TOKEN_RE, so
it is never extracted as a citation token at all (see `check_unit()`/`parse_token()`).
  Scope boundary, stated so it is not mistaken for coverage: only `resolve()` substitutes the
pointer. The separate pinned-row resolver that reads `*/citations.txt` does NOT, so a
`bench-results/RUN-OF-RECORD/...` path written into a citations.txt would be treated as a
literal path and reported DEAD rather than resolved. That is moot as written — no tracked
citations.txt names the run of record (both under bench-results/dd043-pr3-r4-guard-measurement-
2026-07-29/ and bench-results/dd043-pr3-r7-managed-scope-2026-07-30/ contain zero such
references) — and it fails closed rather than open, which is the safe direction. Teach the
pinned resolver the same substitution before writing the first such citation.

COMMIT-SHA REACHABILITY (DD-045 item 4B). This repo squash-merges every PR, which makes every
branch-side commit SHA unreachable the moment its branch is pruned — pruning after merge is the
documented routine, so a citation to a branch-only SHA is dead on any fresh clone the day the
prune happens, sometimes sooner. A hash citation is a DIFFERENT claim from a file/line citation —
it names repo HISTORY, not a cited file's CONTENT — so it gets its own token class, its own
resolution, and its own disposition ledger, balanced and asserted exactly like the citation ledger
above: a SHA token cannot be silently dropped either.

  Parse: a backticked or **bold** hex token, 7-40 characters, containing at least one [a-f] letter
  AND at least one digit — drops English hex-words ("defaced") and all-digit run IDs/timestamps,
  the same false-positive concern VALUE_RE already handles for numbers. A bare (unbackticked,
  non-bold) hex-shaped token is NOT parsed as a SHA — counted per class as not-examined instead,
  the same disclosed-blind-spot treatment short numbers get (see V_UNEX_SHA_BARE).

  Resolve: prefix-match against `git rev-list origin/main`, computed once per run. Dispositions:
    verified (reachable from origin/main)   the token prefixes a commit on origin/main's history;
    FAILED UNREACHABLE COMMIT                matches nothing there — a branch-only SHA whose
                                              branch was pruned, or a foreign (upstream-repo) SHA
                                              never in this repo's object DB at all;
    allowlisted (upstream/foreign SHA)       a `sha <prefix> <reason>` allowlist entry (new kind,
                                              below) — for upstream-repo commits, which will never
                                              resolve here and are not this repo's history to fix;
    disclosed absence                        the +/-1-line window says the commit is gone (reuses
                                              NEG_RE, the same disclosed-absence rule dead paths
                                              already get);
    historical (bench-results evidence)      counted, never failed, for any citing file under
                                              bench-results/: an evidence README naming the commit
                                              it ran against is a record of a past run, not a live
                                              claim — the same rationale this docstring already
                                              applies to captured *.txt. A run-of-record's own
                                              `Commit:` line names its own (eventually branch-only)
                                              SHA by design and lands here, not in FAILED.
  A citing file under bench-results/ gets the historical disposition from its PATH alone, before
  reachability is even computed — correct even for a SHA this repo's object DB no longer has at
  all, once the branch is actually pruned rather than merely unreachable from origin/main.

  Degrade honestly: when `origin/main` cannot be resolved locally (an offline clone with no
  network-fetched remote-tracking ref), EVERY parsed SHA token in the run becomes a counted
  `UNCHECKED — no baseline ref` — never silently verified, never silently failed. In CI the job
  checks out with `fetch-depth: 0`, so the baseline always exists there.

  Allowlist kind: `sha <prefix> <reason>` — exempts a SHA token whose text starts with <prefix>.
  One entry per upstream-repo SHA, the same one-reason-each discipline as every other kind here.

  What this class deliberately does NOT do: keep a pruned SHA resolvable via a tag or git-notes
  pointer. That would make the citation "resolve" again to history no surviving branch or ref
  contains — laundering a dead reference into a live-looking one, the same move the 2026-07-30
  citation audit refused when it left STALE-BY-REFACTOR citations unrepointed rather than faking
  them current. The fix for a citation this class fails is always one of: repoint to the
  squash-merge commit that carries the change on `main`, reword to disclose that the derivation
  predates the squash and is not re-runnable, or cite content (a file, a running count in code, a
  tree hash) instead of a commit.

MECHANICS. Prose is grouped into logical units — markdown paragraphs (blank-line delimited;
each table row its own unit) and contiguous comment blocks — because a wrapped sentence puts
the quoted value and its citation on adjacent physical lines. A quoted value passes if it
appears in ANY file the unit cites (at a cited line where lines are given).

VALUE FORMS EXAMINED — the round-11 boundary, explicit because two consecutive review rounds
found blocking defects in forms the tool did not look at:
  * strong shapes (pipe/comma cost CSVs, >=4-digit plain numbers, thousands-separated
    numbers, UTC stamps) in backticks OR in **bold** — this repo writes its headline figures
    in bold, which is exactly the class that matters most (round-10 blocking finding 2,
    `**1,207**`, was invisible when only backticked spans were read);
  * thousands-separated numbers and UTC stamps EMBEDDED in a bold phrase (`**1,235
    findings**`), except figures preceded by ~ / ≈ / ± — an approximation is a derived
    number, legitimately absent from every artifact, and checking those was the largest
    false-positive class in round-11 tuning;
  * numeric attribute quotes — `` `tests="8"` `` or **bold** equivalent — checked as the
    LITERAL string in the cited file: the attribute name anchors the match, so even a
    1-digit value is strong (round-10 blocking finding 1 quoted `tests="7"` from an XML
    reading `tests="8"`; the bare digit 7 was below the old size floor);
  * bare (unformatted) thousands-separated numbers and UTC stamps, ONLY on a physical line
    that itself carries a citation — bare prose is too noisy for unit-level pairing;
  * a >=4-digit figure absent from every cited file's TEXT still verifies when it equals a
    cited file's line count, exactly or minus a header row — "(1,041 rows)" beside a
    1,042-line CSV is a count OF the file, not a quote FROM it, and is checked as one.
A value found ONLY in cited line-less hand-authored prose (.md outside bench-results/) is
NEVER counted verified: that is a claim corroborating a claim. Round 11 caught ROADMAP's
carried run-total "verifying" against the identical hand-typed figure in TODO.md — itself a
FAILED carried value. Such hits are printed as circular-corroboration NOTEs. This tool's own
source and allowlist are excluded from value backing entirely: they quote figures in order
to discuss or suppress them (a carried value under indictment briefly "verified" against the
allowlist entry written to report it).

VALUE FORMS NOT EXAMINED — counted per class in the summary when they sit in a unit beside a
citation, so the size of the blind spot is printed, not implied:
  * short numbers (1-3 digits, no separator) in backticks or bold (`**7**`, `200`):
    substring-matching a 1-3-digit number against a file is meaningless, so a wrong small
    count in prose ("30 module tests") is NOT caught unless attribute-quoted;
  * other digit-bearing code spans with no strong shape (versions `0.3.0`, expressions,
    command flags);
  * digit-bearing bold phrases containing no strong-shaped figure (`**8 operator guards**`);
  * bare unseparated 4+-digit numbers — indistinguishable from years/dates (`2026`);
  * a parenthesised comma-group directly after a backticked citation — this repo's line-LIST
    idiom (`` `RequestGrammar.java` (82,105) `` means lines 82 and 105, not 82,105).
NOT examined and NOT countable (no reliable extractor; named here so the gap is disclosed,
not silent): bare short prose numbers ("8 guards", "23 + 8" arithmetic), spelled-out figures,
percentages, and italic/single-asterisk emphasis.

FALSE-POSITIVE CLASSES handled (each one was hit while tuning against the real tree):
  * fenced code blocks (a `docker run -v` path in a recipe is not a citation) and inline code
    spans that are commands rather than a lone path token;
  * prose ellipses (`dd043-spikes-…/`) and brace/star globs (`s1b-t{0,1,2}-after-*.xml`);
  * trailing sentence punctuation (`campaigns.json.`), wrapping quotes/parens;
  * URLs, `owner/repo@vN` refs, absolute machine-local paths (/tmp, /mnt, ~), env vars; in
    bare prose a token preceded by `/ : . $ ~ \\` is a fragment of one of those, not a citation;
  * generated build output (`/build/`, `/target/`, `/.m2/`, `/bin/`, `/node_modules/`,
    `/quarkus-app/`) — correctly absent from the tracked tree (counted in the summary);
  * extension-less multi-segment tokens without a trailing slash (`jvm/native`, `and/or`,
    `operator/CRD`) are prose pairs, not citations, and are counted in the summary. Tokens
    that DO carry a recognised extension (or a trailing slash) are checked even when their
    head is not a tracked top-level directory — the pre-round-10 head filter silently
    swallowed dead citations like `cmd/basquin/status.go` and `.superpowers/sdd/*.md`;
  * comma line-lists (`Agent.java:96,126`) — every listed line is checked;
  * disclosed absences: a dead path is not reported when the surrounding line (+/-1) says so
    ("does not exist", "no longer exists", "deleted", "never produced", "not in the tree",
    "gitignored", "untracked", "not present", "does not survive") — prose EXPLAINING a dead
    path is not itself a defect; counted and printed. Applies to dead-path findings only,
    never to value checks (an entry disclosing one dead run must not shield a carried value);
  * shorthand basenames: a unique match inside the citing file's own directory subtree
    verifies (`driver-summary.txt` in a run README is that run's copy). A unique match
    anywhere ELSE — including a unique path-tail match for multi-segment shorthand like
    `env/build.sh` — is a GUESS, reported UNCHECKED as above;
  * a quoted value absent from every cited file but present in a tracked file of the citing
    README's OWN bench-results run directory is counted and printed as SIBLING-BACKED, not
    failed and no longer silently passed — see the narrow rationale at the check site;
  * deliberately-absent or external paths via scripts/check-citations-allowlist.txt — one
    reason per entry, three kinds: cited-path exemptions, `frozen` citing-file prefixes, and
    `reported` per-finding suppressions for real defects in files owned by other agents
    (visible in output, pending their owner's fix, non-failing so the gate can land).

Exit 0 when no FAILED finding remains (allowlisted/frozen/reported/unchecked items are printed
and counted but do not fail); exit 1 with a file:line report of every finding otherwise; exit 2
if the disposition ledger does not balance.

Run: python3 scripts/check-citations.py
"""

import collections
import pathlib
import posixpath
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWLIST_FILE = ROOT / "scripts" / "check-citations-allowlist.txt"
# The tool and its allowlist QUOTE figures in order to discuss or suppress them — they are
# never evidence FOR those figures. Round 11: two carried values under indictment briefly
# "verified" against the allowlist entry written to report them. Excluded from value backing.
SELF_FILES = {"scripts/check-citations.py", "scripts/check-citations-allowlist.txt"}
# DD-045 item 2: bench-results/RUN-OF-RECORD is a tracked pointer FILE (not a symlink — this
# checkout has core.symlinks=false, so a tracked symlink materialises as a plain-text file
# containing its target path rather than resolving, on a clone with that setting) naming the
# current run-of-record directory. A cited path beginning `bench-results/RUN-OF-RECORD/` is
# resolved (see resolve()) against the directory it names, so a supersession edits one file
# instead of repointing every citation. See resolve_run_of_record() for the three failure
# modes this substitution must fail loudly on rather than silently pass or guess.
RUN_OF_RECORD_FILE = "bench-results/RUN-OF-RECORD"
RUN_OF_RECORD_PREFIX = RUN_OF_RECORD_FILE + "/"

CITED_EXTS = (
    "md|txt|log|java|sh|py|go|gradle|xml|yml|yaml|json|csv|tsv|properties|html|svg|bat|kts|exec"
)
TOKEN_RE = re.compile(
    r"[A-Za-z0-9_][A-Za-z0-9_.…{},*?/-]*\.(?:" + CITED_EXTS + r")(?::[\d,-]+)?"
)
DIR_TOKEN_RE = re.compile(r"[A-Za-z0-9_.][A-Za-z0-9_.…{},*?-]*(?:/[A-Za-z0-9_.…{},*?-]+)+/?")
INLINE_CODE_RE = re.compile(r"`([^`]+)`")
LINELIST_RE = re.compile(r"^(.*?):(\d+(?:[,-]\d+)*)$")
EXT_END_RE = re.compile(r"\.(?:" + CITED_EXTS + r")$")
URLISH_HEAD_RE = re.compile(r"(?:[A-Za-z0-9-]+\.)+(?:com|org|io|net|dev|ai|co|edu)$")
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
BOLD_RE = re.compile(r"\*\*([^*\n]+?)\*\*")
# Numeric attribute quote (`tests="8"`): checked as the LITERAL string in the cited file.
# The attribute name anchors the match, so a 1-digit value is still a strong claim — this is
# the round-10 blocking-finding-1 shape. Values are restricted to digits/commas/dots: a
# non-numeric attribute value is prose-paraphrase-prone and stays unexamined (counted).
ATTR_VALUE_RE = re.compile(r'[A-Za-z_][A-Za-z0-9_.:-]*="-?\d[\d,.]*"')
# Embedded/bare strong shapes. The lookbehind excludes ~ / ≈ / ± — a figure written as an
# approximation is DERIVED (`~1,048,576` is 2x the 524,288 quantum, `≈3,254,201` an
# extrapolation; both real examples from the s2 findings), legitimately in no artifact, so
# checking approximations can only produce false positives.
THOUSANDS_RE = re.compile(r"(?<![\d,.\w~≈±])-?\d{1,3}(?:,\d{3})+(?![\d,]|\.\d)")
STAMP_RE = re.compile(r"(?<![\dT])\d{8}T\d{6}Z")
SHORT_NUM_RE = re.compile(r"-?\d{1,3}")
BARE4_RE = re.compile(r"(?<![\d,.\w])\d{4,}(?!\d)")
# `` `RequestGrammar.java` (82,105) `` is this repo's line-LIST idiom (lines 82 and 105), not
# the number 82,105 — but only when the parenthesised group directly follows a backticked
# citation, which is checked per line; a parenthesised figure elsewhere is still a value.
LINELIST_IDIOM_RE = re.compile(r"`\s*\((-?\d{1,3}(?:,\d{3})+)\)")
NEG_RE = re.compile(
    r"(?:no longer exist|does not exist|do not exist|don't exist|never (?:produced|created|"
    r"committed|existed)|not (?:in|part of) the (?:repo|tree)|not present|does not survive|"
    r"\bgitignored?\b|\buntracked\b|\bignored\b|deleted|removed|\bNo `)",
    re.IGNORECASE,
)
GENERATED_SEGS = {"build", "target", ".m2", "bin", "node_modules", "quarkus-app"}
PINNED_RE = re.compile(r"^([A-Za-z0-9_./-]+):(\d+): (.*)$")
# In bare (unbackticked) prose, a token preceded by one of these is a fragment of a URL,
# an absolute machine-local path, or a shell/env expansion — not a citation.
FRAGMENT_PRECEDERS = ":/.$~\\"

# Commit-SHA reachability (DD-045 item 4B) — see the docstring's COMMIT-SHA REACHABILITY section.
# SHA-shaped requires >=1 [a-f] letter AND >=1 digit (word-bounded so it never matches a substring
# of a longer identifier): drops English hex-words ("defaced", "cafebabe") and all-digit run
# IDs/dates, the same VALUE_RE concern for numbers.
SHA_SHAPE_RE = re.compile(r"\b[0-9a-f]{7,40}\b")


def is_sha_shaped(s: str) -> bool:
    return bool(SHA_SHAPE_RE.fullmatch(s)) and any(c in "abcdef" for c in s) and any(
        c.isdigit() for c in s)


# Value forms the tool does NOT examine, counted per class wherever they sit in a unit that
# also carries a citation (see the docstring's VALUE FORMS NOT EXAMINED). A disclosed gap is
# a known limit; a silent one is the defect this tool exists to prevent.
V_UNEX_SHORT = ("values NOT examined: short number (<4 digits, no separator) in code/bold — "
                "substring match is meaningless")
V_UNEX_CODE = ("values NOT examined: digit-bearing code span with no strong shape "
               "(version, expression, flag)")
V_UNEX_BOLD = ("values NOT examined: digit-bearing bold phrase with no strong-shaped figure")
V_UNEX_BARE = ("values NOT examined: bare unseparated 4+-digit number (year/date-shaped)")
V_UNEX_PAREN = ("values NOT examined: parenthesised group straight after a backticked "
                "citation (line-list idiom `file` (82,105))")
V_UNEX_SHA_BARE = ("values NOT examined: bare hex token outside backticks/bold (not parsed as "
                   "a commit SHA)")

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
    """Six entry kinds, one reason each (a reason is mandatory — an unexplained exemption is
    exactly the drift this tool exists to stop):
      <cited-path-or-prefix/> <reason>      exempt a cited path (exact, or prefix if it ends /)
      frozen <citing-prefix> <reason>       do not scan this citing file/dir at all
      reported <citing-prefix> <needle> <reason>
                                            suppress (but print) findings in <citing-prefix>
                                            whose message contains <needle> — for real defects
                                            in files owned by other agents, pending their fix.
      sha <prefix> <reason>                 DD-045 item 4B: exempt a commit-SHA token (prefix
                                            match) unreachable from origin/main — upstream-repo
                                            commits, which will never resolve here.
      removed-ok <path> <reason>            DD-045 item 4A (scripts/check-removed-deps.py): a
                                            removed path that is fine to still be cited. Not this
                                            tool's concern — parsed here only so it does not fall
                                            through to the generic cited-path branch below and get
                                            misread as one.
      non-citable <path> <reason>           DD-045 item 6a (scripts/check-evidence-complete.py):
                                            a tracked RESULTS.md that is stamped NON-CITABLE
                                            anyway. Not this tool's concern either — parsed here
                                            for the same reason removed-ok is."""
    cited, frozen, reported, sha = [], [], [], []
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
                reported.append((sub[0], sub[1], sub[2], n))
            elif kind == "sha":
                sub = parts[1].split(None, 1) if len(parts) > 1 else []
                if len(sub) < 2:
                    sys.exit(f"allowlist:{n}: sha entry needs <prefix> <reason>")
                sha.append((sub[0], sub[1]))
            elif kind == "removed-ok":
                sub = parts[1].split(None, 1) if len(parts) > 1 else []
                if len(sub) < 2:
                    sys.exit(f"allowlist:{n}: removed-ok entry needs <path> <reason>")
            elif kind == "non-citable":
                sub = parts[1].split(None, 1) if len(parts) > 1 else []
                if len(sub) < 2:
                    sys.exit(f"allowlist:{n}: non-citable entry needs <path> <reason>")
            else:
                if len(parts) < 2:
                    sys.exit(f"allowlist:{n}: entry has no reason: {line!r}")
                cited.append((parts[0], parts[1]))
    return cited, frozen, reported, sha


def main() -> int:
    tracked_set = {
        t for t in subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-z"],
            capture_output=True, text=True, check=True).stdout.split("\0") if t
    }
    tracked_dirs = set()
    for t in tracked_set:
        parts = t.split("/")
        for i in range(1, len(parts)):
            tracked_dirs.add("/".join(parts[:i]))
    by_basename: dict[str, list[str]] = {}
    for t in tracked_set:
        by_basename.setdefault(t.rsplit("/", 1)[-1].lower(), []).append(t)

    allow_cited, frozen, reported, sha_allow = load_allowlist()
    # DD-045 defect-class audit finding 13: a `reported` entry whose needle matches nothing is a
    # claim about another file's content ("a defect is pending there") that nobody checks — live
    # on merged main, self-expiry instructions unfollowed, while this tool printed the tell
    # (`0 reported to owners`) and exited 0. Track per-entry usage across the whole run; an entry
    # that fires zero times is itself a finding below, not a silent pass.
    reported_used = [False] * len(reported)
    findings: list[str] = []
    allowed_out: list[str] = []
    reported_out: list[str] = []
    ambiguous_out: list[str] = []
    guess_rows: list[tuple[str, str, str, int]] = []
    disclosed_out: list[str] = []
    untracked_out: list[str] = []
    sibling_out: list[str] = []
    circular_out: list[str] = []
    sha_allowed_out: list[str] = []
    sha_disclosed_out: list[str] = []
    stats: collections.Counter = collections.Counter()

    # Citation dispositions — every parsed citation lands in exactly one; the sum is asserted
    # against the parsed total before printing, so nothing can be silently dropped.
    K_EXACT = "verified (exact/relative path)"
    K_NEAR = "verified (unique same-dir basename)"
    K_PIN = "verified (pinned row)"
    K_FAIL = "FAILED"
    K_REP = "reported to owning agent (suppressed FAIL)"
    K_ALLOW = "allowlisted cited path"
    K_AMBIG = "UNCHECKED — ambiguous name"
    K_GUESS = "UNCHECKED — resolved only by guess"
    K_DISC = "disclosed absence"
    K_DISK = "untracked-on-disk (not failed)"
    DISPOSITIONS = (K_EXACT, K_NEAR, K_PIN, K_FAIL, K_REP, K_ALLOW,
                    K_AMBIG, K_GUESS, K_DISC, K_DISK)

    # Commit-SHA reachability dispositions (DD-045 item 4B) — a SEPARATE ledger from the
    # citation dispositions above: a SHA token is a different claim (repo history, not cited-file
    # content), so it gets its own balance-asserted ledger rather than overloading the citation
    # one. See the docstring's COMMIT-SHA REACHABILITY section.
    S_OK = "verified (reachable from origin/main)"
    S_FAIL = "FAILED UNREACHABLE COMMIT"
    S_ALLOW = "allowlisted (upstream/foreign SHA)"
    S_DISC = "disclosed absence (commit pruned, prose says so)"
    S_HIST = "historical (bench-results evidence)"
    S_NOBASE = "UNCHECKED — no baseline ref"
    SHA_DISPOSITIONS = (S_OK, S_FAIL, S_ALLOW, S_DISC, S_HIST, S_NOBASE)

    main_shas: set[str] = set()
    no_baseline = False
    rl = subprocess.run(["git", "-C", str(ROOT), "rev-list", "origin/main"],
                        capture_output=True, text=True)
    if rl.returncode != 0 or not rl.stdout.strip():
        no_baseline = True
    else:
        main_shas = set(rl.stdout.split())

    def sha_reachable(token: str) -> bool:
        return any(full.startswith(token) for full in main_shas)

    def sha_allowed(token: str) -> str | None:
        for pat, reason in sha_allow:
            if token.startswith(pat) or pat.startswith(token):
                return reason
        return None

    def cited_allowed(path: str) -> str | None:
        for pat, reason in allow_cited:
            if path == pat or path.rstrip("/") == pat.rstrip("/") or (
                    pat.endswith("/") and path.startswith(pat)):
                return reason
        return None

    def emit(citing: str, msg: str) -> str:
        """-> 'reported' if suppressed by a reported allowlist entry, else 'failed'."""
        for i, (cite_pre, needle, reason, _n) in enumerate(reported):
            if citing.startswith(cite_pre) and needle in msg:
                reported_used[i] = True
                reported_out.append(f"{msg}\n        [reported, pending owner fix: {reason}]")
                return "reported"
        findings.append(msg)
        return "failed"

    def cite_emit(citing: str, msg: str) -> None:
        stats[K_REP if emit(citing, msg) == "reported" else K_FAIL] += 1

    def resolve_run_of_record():
        """Resolve the bench-results/RUN-OF-RECORD pointer to the run directory it names.
        -> (target_dir, None) on success, (None, reason) on any of the three failure modes
        this pointer must not silently swallow: the pointer file is missing; it has zero or
        more than one non-comment, non-blank line; or the directory it names is not in the
        tracked tree (a dangling pointer). Called lazily, only when a citation actually needs
        it, via resolve()'s RUN_OF_RECORD_PREFIX branch below — so a broken pointer with no
        RUN-OF-RECORD/ citations in the tree does not, by itself, fail the run."""
        lines = read_lines(RUN_OF_RECORD_FILE)
        if lines is None:
            return None, f"{RUN_OF_RECORD_FILE} is missing"
        content = [ln.strip() for ln in lines
                   if ln.strip() and not ln.strip().startswith("#")]
        if len(content) != 1:
            return None, (f"{RUN_OF_RECORD_FILE} has {len(content)} non-comment, non-blank "
                          f"line(s) (expected exactly 1): {content!r}")
        target_dir = f"bench-results/{content[0]}"
        if target_dir not in tracked_dirs:
            return None, f"{RUN_OF_RECORD_FILE} names {target_dir!r}, not a tracked directory"
        return target_dir, None

    def resolve(citing: str, path: str):
        """-> (candidates, kind).
             exact — the literal path, OR its citing-dir-relative normalisation (incl. ../
                     normalised), is a tracked file/dir, OR the RUN-OF-RECORD pointer
                     resolves either of them (see tracked_or_pointer()): verified;
             near  — unique basename/suffix match inside the citing file's own directory
                     subtree (an evidence README citing `driver-summary.txt` means the copy
                     in its run directory): verified;
             guess — unique basename/suffix match elsewhere, or unique path-tail match for a
                     multi-segment token (`env/build.sh`, `cmd/basquin/status.go`): the
                     written path names nothing, so this is reported UNCHECKED, not verified;
             ambiguous — several matches, target not mechanically decidable;
             pointer-error — the literal path, or its citing-dir-relative normalisation, is
                     bench-results/RUN-OF-RECORD (bare) or bench-results/RUN-OF-RECORD/<rest>
                     but the pointer is missing, malformed, or dangling: reported as a FAILED
                     DEAD PATH, never a guess (see resolve_run_of_record());
             none  — nothing matches anywhere."""
        cite_dir = citing.rsplit("/", 1)[0] if "/" in citing else ""
        rel = posixpath.normpath(f"{cite_dir}/{path}") if cite_dir else None

        def tracked_or_pointer(cand: str):
            """Validate ONE candidate path — called once below for the literal `path` and
            once for its citing-dir-relative normalisation `rel` — against the RUN-OF-RECORD
            pointer substitution, checked FIRST, ahead of the generic "cand in tracked_set"
            short-circuit. Not after it, for EITHER candidate: this is the one place that
            ordering is expressed, so a citation shape added later gets it automatically
            instead of needing its own copy that can fall out of sync. That already happened
            once: 793c18e fixed this ordering for the literal path alone; the citing-dir-
            relative branch a few lines below it, in the pre-refactor code, still ran
            `rel in tracked_set` with no pointer check at all — the identical bug, one branch
            further down, because the two shapes were two copies of "check tracked_set" that
            drifted independently instead of one shared, correctly-ordered check.
              RUN_OF_RECORD_FILE is itself a tracked FILE (the pointer), and the caller has
            already stripped a bare citation's trailing slash before calling resolve(), so a
            bare `bench-results/RUN-OF-RECORD/` citation — or, one level further, a citing-
            dir-relative citation that NORMALISES to the same path (`../RUN-OF-RECORD/` cited
            from a file one level under bench-results/, or bare `RUN-OF-RECORD/` cited from a
            file directly under it) — arrives here with `cand == RUN_OF_RECORD_FILE`,
            identical to that tracked file's own path. Checking tracked_set first would match
            the pointer's own tracked-ness and return "exact" without
            resolve_run_of_record() ever running, so a dangling/missing/malformed pointer
            would verify clean for that citation shape specifically. That was not
            hypothetical for the literal shape: three citations (TODO.md:1200, TODO.md:1293,
            docs/ROADMAP.md:102) name only the bare `bench-results/RUN-OF-RECORD/` form, and
            none of the three was flagged when the pointer was deliberately pointed at a
            nonexistent directory, while sub-path citations of the same broken pointer were.
            (The basename/tail-guess branches further down resolve() can never hit this same
            trap by accident: they only fire for a `path` with no "/", and `RUN-OF-RECORD`
            alone — without `bench-results/` and without a recognised extension — never
            matches TOKEN_RE or DIR_TOKEN_RE, so it is never extracted as a citation token in
            the first place; see check_unit().)
            -> (candidates, kind) if `cand` resolves — pointer substitution or a plain
               tracked-tree hit — else None, meaning `cand` isn't in the tracked tree at all
               so the caller falls through to basename/tail matching."""
            if cand == RUN_OF_RECORD_FILE or cand.startswith(RUN_OF_RECORD_PREFIX):
                target_dir, err = resolve_run_of_record()
                if err is not None:
                    return [err], "pointer-error"
                if cand == RUN_OF_RECORD_FILE:
                    # A bare `RUN-OF-RECORD/` citation names the DIRECTORY the pointer points
                    # to, not the pointer file's own one-line text — the same reading a
                    # sub-path citation gets, just with an empty remainder. Resolve to
                    # target_dir itself: a tracked DIRECTORY, never the pointer FILE, so the
                    # caller's `file_cands = [c for c in cands if c in tracked_set]` comes back
                    # empty, exactly as it does for every other directory citation (directories
                    # are never in tracked_set). Deliberate, not incidental: the alternative —
                    # resolving to RUN_OF_RECORD_FILE itself so `cands` is non-empty — would put
                    # the pointer file back into file_cands, and a quoted value in the same
                    # prose unit would then be silently checked against the pointer's own
                    # one-line content instead of correctly having nothing to check against.
                    return [target_dir], "exact"
                sub = target_dir + cand[len(RUN_OF_RECORD_FILE):]
                if sub in tracked_set or sub in tracked_dirs:
                    return [sub], "exact"
                return ([f"{RUN_OF_RECORD_FILE} resolves to {target_dir!r}, but {sub!r} is not "
                         f"tracked"], "pointer-error")
            if cand in tracked_set or cand in tracked_dirs:
                return [cand], "exact"
            return None

        hit = tracked_or_pointer(path)
        if hit is not None:
            return hit
        if rel is not None:
            hit = tracked_or_pointer(rel)
            if hit is not None:
                return hit
        if "/" not in path:
            low = path.lower()
            cands = list(by_basename.get(low, []))
            if not cands:
                cands = [t for t in tracked_set
                         if t.lower().endswith(("-" + low, "_" + low, "/" + low))]
            if not cands:
                # Single-segment DIRECTORY shorthand (`crds/.` -> "crds"): before round 11
                # these could never resolve — only files were candidates — so a tracked
                # chart directory cited by its own name failed as a dead path.
                cands = [d for d in tracked_dirs
                         if d.lower() == low or d.lower().endswith("/" + low)]
            if cite_dir:
                near = [c for c in cands if c.startswith(cite_dir + "/")]
                if len(near) == 1:
                    return near, "near"
                if len(near) > 1:
                    return near, "ambiguous"
            if len(cands) == 1:
                return cands, "guess"
            if len(cands) > 1:
                return cands, "ambiguous"
            return [], "none"
        low = "/" + path.lower()
        tails = [t for t in tracked_set if ("/" + t.lower()).endswith(low)]
        if not tails:
            tails = [d for d in tracked_dirs if ("/" + d.lower()).endswith(low)]
        if len(tails) == 1:
            return tails, "guess"
        if len(tails) > 1:
            return tails, "ambiguous"
        return [], "none"

    def parse_token(token: str):
        token = token.strip("\"'")
        # Ellipsis must be rejected BEFORE trailing-punctuation stripping: a truncated
        # `../corpus/...` otherwise loses its dots and masquerades as the real path
        # `../corpus/` (a round-11 false positive in a shell comment).
        if "…" in token or "..." in token:
            return None
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
            # No head filter here. Pre-round-10 this dropped any token whose first segment
            # was not a tracked top-level directory — which silently swallowed DEAD citations
            # (`cmd/basquin/status.go`, `.superpowers/sdd/*.md`) along with the prose pairs
            # it was aimed at. Prose pairs are excluded by the extension rule below instead,
            # and every skip is counted.
            if re.fullmatch(r"(?:\.\./)+", path):
                stats["skipped tokens: pure relative-dir prose (`../../../`)"] += 1
                return None
            if URLISH_HEAD_RE.fullmatch(path.split("/", 1)[0]):
                stats["skipped tokens: URL-shaped (domain head)"] += 1
                return None
            if any(seg in GENERATED_SEGS for seg in path.split("/")):
                stats["skipped tokens: generated build output"] += 1
                return None
            # An extension-less multi-segment token is a citation only when written with a
            # trailing slash (`bench-results/verify-20260730T102725Z/`). Without it, such
            # tokens are overwhelmingly prose shorthand for component pairs (`operator/CRD`,
            # `agent/coverage`, `jvm/native` — 16 false positives in one tuning run).
            # CONSEQUENCE, documented: a dead DIRECTORY citation written without its trailing
            # slash is not caught; the repo convention is to write directories with one.
            # These skips are counted in the summary.
            if not token.endswith("/") and not EXT_END_RE.search(path):
                stats["skipped tokens: extension-less prose pair (jvm/native, A/B)"] += 1
                return None
        elif "." not in path:
            return None
        return path, lines

    def check_unit(citing: str, unit: list[tuple[int, str]]) -> None:
        """unit: [(physical line number, text)] — one paragraph / table row / comment block."""
        citations = []  # (lineno, raw, path, [lines])
        values = []     # (lineno, normalised value)
        shas: list[tuple[int, str]] = []  # (lineno, hex token) — DD-045 item 4B
        unex: collections.Counter = collections.Counter()  # unexamined value forms, per class
        bold_line: list[tuple[int, list[str]]] = []
        plain_line: list[tuple[int, str]] = []
        idiom_vals: set[str] = set()  # `file` (82,105) — line lists, not thousands figures
        for lineno, text in unit:
            idiom_vals.update(LINELIST_IDIOM_RE.findall(text))
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
                if VALUE_RE.fullmatch(norm) or ATTR_VALUE_RE.fullmatch(norm):
                    values.append((lineno, norm))
                elif is_sha_shaped(norm):
                    shas.append((lineno, norm))
                elif SHORT_NUM_RE.fullmatch(norm):
                    unex[V_UNEX_SHORT] += 1
                elif any(ch.isdigit() for ch in norm):
                    unex[V_UNEX_CODE] += 1
            bare = INLINE_CODE_RE.sub(" ", text)
            for m in TOKEN_RE.finditer(bare):
                tok = m.group(0)
                # Round-10 fix: bare single-segment tokens (an unbackticked `agents.md's`,
                # markdown link targets like `](USAGE.md)`) are citations too — the old
                # `"/" in tok` gate made every slash-less bare token invisible.
                if tok in spans:
                    continue
                if m.start() and bare[m.start() - 1] in FRAGMENT_PRECEDERS:
                    continue
                nxt = bare[m.end():m.end() + 1]
                if nxt and (nxt.isalnum() or nxt == "_"):
                    continue  # prefix of a longer word: `pom.sh` inside `pom.sha1`
                parsed = parse_token(tok)
                if parsed:
                    citations.append((lineno, tok, *parsed))
            for m in DIR_TOKEN_RE.finditer(bare):
                tok = m.group(0)
                if (tok not in spans and not TOKEN_RE.fullmatch(tok.rstrip("/"))
                        and not (m.start() and bare[m.start() - 1] in FRAGMENT_PRECEDERS)):
                    parsed = parse_token(tok)
                    if parsed and "/" in parsed[0]:
                        citations.append((lineno, tok, *parsed))
            bold_line.append((lineno, BOLD_RE.findall(bare)))
            # Bold spans and citation tokens are stripped before the bare-prose scan so the
            # same occurrence is never extracted (or counted unexamined) twice.
            plain_line.append((lineno, DIR_TOKEN_RE.sub(
                " ", TOKEN_RE.sub(" ", BOLD_RE.sub(" ", bare)))))

        # Round-11 value forms: bold spans (whole-span strong shape or attribute quote, else
        # embedded thousands/stamps), then bare prose on citation-carrying lines only.
        cit_lines = {c[0] for c in citations}
        for lineno, bolds in bold_line:
            for span in bolds:
                norm = span.strip().strip(" .,;:!").replace("\\|", "|")
                if VALUE_RE.fullmatch(norm) or ATTR_VALUE_RE.fullmatch(norm):
                    values.append((lineno, norm))
                    continue
                if is_sha_shaped(norm):
                    shas.append((lineno, norm))
                    continue
                strong = []
                for mm in THOUSANDS_RE.finditer(norm):
                    if mm.group(0) in idiom_vals:
                        unex[V_UNEX_PAREN] += 1
                    else:
                        strong.append(mm.group(0))
                strong += [mm.group(0) for mm in STAMP_RE.finditer(norm)]
                if strong:
                    values.extend((lineno, s) for s in strong)
                elif SHORT_NUM_RE.fullmatch(norm):
                    unex[V_UNEX_SHORT] += 1
                elif any(ch.isdigit() for ch in norm):
                    unex[V_UNEX_BOLD] += 1
        for lineno, plain in plain_line:
            if lineno not in cit_lines:
                continue
            for mm in THOUSANDS_RE.finditer(plain):
                if mm.group(0) in idiom_vals:
                    unex[V_UNEX_PAREN] += 1
                else:
                    values.append((lineno, mm.group(0)))
            for mm in STAMP_RE.finditer(plain):
                values.append((lineno, mm.group(0)))
            unex[V_UNEX_BARE] += len(BARE4_RE.findall(STAMP_RE.sub(" ", plain)))
            unex[V_UNEX_SHA_BARE] += sum(
                1 for mm in SHA_SHAPE_RE.finditer(plain) if is_sha_shaped(mm.group(0)))
        # The unexamined counts are meaningful only where a value COULD have been paired
        # with a citation — count them for citation-bearing units, so the printed number is
        # the size of the actual blind spot, not tree-wide digit noise.
        if citations:
            for k, v in unex.items():
                stats[k] += v

        unit_text = {ln: txt for ln, txt in unit}
        resolved: list[tuple[str, list[str], list[int], int]] = []
        seen = set()
        for lineno, raw, path, lns in citations:
            key = (path, tuple(lns))
            if key in seen:
                continue
            seen.add(key)
            stats["citations"] += 1
            reason = cited_allowed(path)
            if reason is not None:
                stats[K_ALLOW] += 1
                allowed_out.append(f"{citing}:{lineno}: `{raw}` — {reason}")
                continue
            cands, kind = resolve(citing, path.rstrip("/"))
            if kind == "ambiguous":
                stats[K_AMBIG] += 1
                ambiguous_out.append(
                    f"{citing}:{lineno}: `{raw}` — matches {len(cands)} tracked paths, none "
                    f"decisively; target (possibly in an upstream repo) not mechanically "
                    f"decidable")
                continue
            if kind == "guess":
                stats[K_GUESS] += 1
                guess_rows.append((raw, cands[0], citing, lineno))
                continue
            if kind == "pointer-error":
                # A dangling/missing/malformed RUN-OF-RECORD pointer is a FAILED DEAD PATH,
                # never a guess or a silent pass — see resolve_run_of_record().
                cite_emit(citing, f"{citing}:{lineno}: DEAD PATH `{raw}` — {cands[0]}")
                continue
            if kind == "none":
                window = [unit_text.get(lineno - 1, ""), unit_text[lineno],
                          unit_text.get(lineno + 1, "")]
                if any(NEG_RE.search(w) for w in window):
                    stats[K_DISC] += 1
                    disclosed_out.append(
                        f"{citing}:{lineno}: `{raw}` — surrounding prose discloses the absence")
                    continue
                # Not tracked — but maybe on disk. A gitignored citation is a hard failure
                # (invisible on every fresh clone, the audit's should-fix 12); a merely
                # untracked one is usually a file about to be committed alongside the citing
                # doc, or another agent's in-flight run directory — noted, not failed.
                p = path.rstrip("/")
                cite_dir = citing.rsplit("/", 1)[0] if "/" in citing else ""
                on_disk = next(
                    (c for c in (p, posixpath.normpath(f"{cite_dir}/{p}") if cite_dir else p)
                     if not c.startswith("..") and (ROOT / c).exists()), None)
                if on_disk is not None:
                    ignored = subprocess.run(
                        ["git", "-C", str(ROOT), "check-ignore", "-q", on_disk],
                        capture_output=True).returncode == 0
                    if ignored:
                        cite_emit(citing, f"{citing}:{lineno}: DEAD PATH `{raw}` — exists on "
                                          f"disk but is GITIGNORED, so it is invisible on a "
                                          f"fresh clone")
                    else:
                        stats[K_DISK] += 1
                        untracked_out.append(
                            f"{citing}:{lineno}: `{raw}` — exists on disk but is not yet "
                            f"tracked; must be committed with the citing doc or it dies")
                    continue
                cite_emit(citing, f"{citing}:{lineno}: DEAD PATH `{raw}` — no tracked file or "
                                  f"directory matches (root-relative, citing-dir-relative, or "
                                  f"by unique name/tail)")
                continue
            file_cands = [c for c in cands if c in tracked_set]
            if lns and file_cands:
                ok = [c for c in file_cands
                      if (read_lines(c) or []) and max(lns) <= len(read_lines(c) or [])]
                if not ok:
                    lens = ", ".join(f"{c}: {len(read_lines(c) or [])} lines"
                                     for c in file_cands)
                    cite_emit(citing, f"{citing}:{lineno}: STALE LINE `{raw}` — cited line "
                                      f"{max(lns)} is past end of file ({lens})")
                    continue
                file_cands = ok
            stats[K_EXACT if kind == "exact" else K_NEAR] += 1
            resolved.append((raw, file_cands, lns, lineno))

        for vline, val in values:
            variants = {val, val.replace(",", "")} if re.fullmatch(
                r"-?\d{1,3}(,\d{3})+", val) else {val}
            found_at_cited_line = found_somewhere = pinned_hit = False
            checked_files = []
            found_files: list[str] = []
            for raw, file_cands, lns, _ in resolved:
                for c in file_cands:
                    if c in SELF_FILES:
                        continue  # this tool's own text is never evidence for a figure
                    lines = read_lines(c)
                    if lines is None:
                        continue
                    checked_files.append(f"{c}" + (f":{','.join(map(str, lns))}" if lns else ""))
                    norm_lines = [ln.replace("\\|", "|") for ln in lines]
                    hit_lines = [i + 1 for i, ln in enumerate(norm_lines)
                                 if any(v in ln for v in variants)]
                    if hit_lines:
                        found_somewhere = True
                        found_files.append(c)
                        if not lns or set(hit_lines) & set(lns):
                            found_at_cited_line = True
                        if lns and set(hit_lines) & set(lns):
                            pinned_hit = True
            if not checked_files:
                stats["values: beside no verified citation (not checked)"] += 1
                continue
            if not found_somewhere and re.fullmatch(r"\d{4,}", val.replace(",", "")):
                # "(1,041 rows)" beside a 1,042-line CSV: the figure is a COUNT OF the cited
                # file, not a quote FROM it — verifiable directly against len(lines), exact
                # or one less (a header row). Four digits minimum, so a coincidental
                # equality is vanishingly unlikely.
                n = int(val.replace(",", ""))
                if any(c not in SELF_FILES
                       and (rl := read_lines(c)) is not None and len(rl) in (n, n + 1)
                       for _, fc, _, _ in resolved for c in fc):
                    stats["values: verified as line/row count of a cited file"] += 1
                    continue
            if not found_somewhere and citing.startswith("bench-results/"):
                # Round-10 fix (b) — NARROW, REPORTED exemption, no longer a silent pass.
                # An evidence run directory is a self-contained record: a README paragraph
                # may cite some OTHER run's file for comparison while quoting its own run's
                # figure, whose provenance is a committed artifact beside it. When the figure
                # is absent from every cited file but present in a tracked file inside the
                # citing README's OWN directory, that is an imprecise citation rather than a
                # carried value — and it is COUNTED and PRINTED as SIBLING-BACKED below, so
                # the miss is visible instead of being converted into a clean verdict.
                own_dir = citing.rsplit("/", 1)[0] + "/"
                sib_hit = next(
                    (sib for sib in sorted(tracked_set)
                     if sib.startswith(own_dir) and sib != citing
                     and (sl := read_lines(sib)) is not None
                     and any(v in ln.replace("\\|", "|") for ln in sl for v in variants)),
                    None)
                if sib_hit is not None:
                    stats["values: SIBLING-BACKED (bench-results; noted, not failed)"] += 1
                    sibling_out.append(
                        f"{citing}:{vline}: value `{val}` is in NO cited file "
                        f"({', '.join(sorted(set(checked_files)))}) but is in same-run "
                        f"sibling {sib_hit} — imprecise citation; cite the sibling")
                    continue
            if not found_somewhere:
                stats["values: FAILED" if emit(
                    citing, f"{citing}:{vline}: CARRIED VALUE `{val}` — quoted beside "
                            f"citation(s) of {', '.join(sorted(set(checked_files)))} but "
                            f"appears nowhere in them; the figure came from somewhere else "
                            f"(an older run?)") == "failed"
                    else "values: reported (suppressed FAIL)"] += 1
            elif not found_at_cited_line:
                stats["values: FAILED" if emit(
                    citing, f"{citing}:{vline}: STALE LINE — quoted value `{val}` exists in "
                            f"the cited file(s) but not at the cited line(s) "
                            f"({', '.join(sorted(set(checked_files)))})") == "failed"
                    else "values: reported (suppressed FAIL)"] += 1
            elif not pinned_hit and all(
                    f.endswith(".md") and not f.startswith("bench-results/")
                    for f in found_files):
                # Found ONLY in cited hand-authored prose (line-less .md outside
                # bench-results/): that is corroboration by another CLAIM, not by an
                # artifact — round 11 caught docs/ROADMAP.md's carried run-total "verifying"
                # against the identical hand-typed figure in TODO.md, which was itself a
                # FAILED carried value. Noted, never counted verified.
                stats["values: found only in cited prose .md (circular; noted, "
                      "not verified)"] += 1
                circular_out.append(
                    f"{citing}:{vline}: value `{val}` is backed only by prose "
                    f"{', '.join(sorted(set(found_files)))} — another claim, not an "
                    f"artifact; cite the artifact or pin a line")
            else:
                stats["values: verified in cited file(s)"] += 1

        # Commit-SHA reachability (DD-045 item 4B) — independent of the citation/value
        # resolution above: a SHA names repo history, not this unit's cited files, so it is
        # judged purely against origin/main, the sha allowlist, the same NEG_RE disclosure
        # window, and the citing path (bench-results/ is historical evidence, never FAILED).
        for sline, sha_tok in shas:
            stats["sha_tokens"] += 1
            if no_baseline:
                stats[S_NOBASE] += 1
                continue
            if citing.startswith("bench-results/"):
                stats[S_HIST] += 1
                continue
            if sha_reachable(sha_tok):
                stats[S_OK] += 1
                continue
            reason = sha_allowed(sha_tok)
            if reason is not None:
                stats[S_ALLOW] += 1
                sha_allowed_out.append(f"{citing}:{sline}: `{sha_tok}` — {reason}")
                continue
            window = [unit_text.get(sline - 1, ""), unit_text.get(sline, ""),
                      unit_text.get(sline + 1, "")]
            if any(NEG_RE.search(w) for w in window):
                stats[S_DISC] += 1
                sha_disclosed_out.append(
                    f"{citing}:{sline}: `{sha_tok}` — surrounding prose discloses the absence")
                continue
            findings.append(
                f"{citing}:{sline}: FAILED UNREACHABLE COMMIT `{sha_tok}` — not reachable from "
                f"origin/main (branch pruned, or a foreign repo's SHA); repoint to the "
                f"squash-merge commit that carries this on main, reword to disclose the "
                f"derivation predates the squash, or cite content instead of a commit")
            stats[S_FAIL] += 1

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
                stats["pinned rows unparseable (FAILED, not counted as citations)"] += 1
                continue
            stats["citations"] += 1
            path, lno, expected = pm.group(1), int(pm.group(2)), pm.group(3)
            target = f"{cite_dir}/{path}" if cite_dir else path
            if target not in tracked_set:
                target = path
            if target not in tracked_set:
                cite_emit(citing, f"{citing}:{n}: DEAD PATH `{path}` in pinned row")
                continue
            tlines = read_lines(target)
            if tlines is None or lno > len(tlines):
                cite_emit(citing, f"{citing}:{n}: STALE LINE pinned `{path}:{lno}` — file has "
                                  f"{len(tlines or [])} lines")
            elif expected.strip() and expected.strip() not in tlines[lno - 1]:
                cite_emit(citing, f"{citing}:{n}: STALE LINE pinned `{path}:{lno}` — expected "
                                  f"{expected.strip()[:60]!r}, line is "
                                  f"{tlines[lno - 1].strip()[:60]!r}")
            else:
                stats[K_PIN] += 1

    # Comment scanning — '#'-comment lines of tracked *.sh and .github/workflows/* files,
    # grouped into contiguous blocks (a comment block is one claim unit, like a paragraph).
    # RESTORED in round 11: the round-10 rewrite dropped this loop while the summary line
    # kept printing "N comment-scanned files" — the tool's own claim was wider than its
    # check, the precise defect class it exists to catch, silently for one full round.
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

    # Unused `reported` allowlist entries (DD-045 defect-class audit finding 13) — a needle that
    # matched nothing anywhere in this run is a stale suppression: the defect it named is fixed
    # and the entry's own text says to remove it, so leaving it in is itself the claim/check
    # drift this tool exists to catch, in its own input.
    for i, (cite_pre, needle, reason, n) in enumerate(reported):
        if not reported_used[i]:
            findings.append(
                f"scripts/check-citations-allowlist.txt:{n}: unused `reported` entry — needle "
                f"{needle!r} matches nothing in citing files starting {cite_pre!r}; its own "
                f"self-expiry instruction says remove it now that the defect it named is fixed "
                f"(reason on file: {reason})")

    # The ledger must balance: every parsed citation has exactly one disposition. If this
    # trips, the tool has a silent-drop bug — the round-10 blocking defect class — and no
    # verdict it prints can be trusted, so abort loudly.
    n_cit = stats["citations"]
    disposed = sum(stats[k] for k in DISPOSITIONS)
    if disposed != n_cit:
        print(f"check-citations: INTERNAL ERROR — {n_cit} citations parsed but only "
              f"{disposed} dispositioned; a citation was silently dropped. No verdict.")
        return 2

    # Same discipline for the SHA ledger (DD-045 item 4B) — a separate token class, a separate
    # balance check, so it cannot be silently dropped either.
    n_sha = stats["sha_tokens"]
    sha_disposed = sum(stats[k] for k in SHA_DISPOSITIONS)
    if sha_disposed != n_sha:
        print(f"check-citations: INTERNAL ERROR — {n_sha} commit-SHA tokens parsed but only "
              f"{sha_disposed} dispositioned; a SHA token was silently dropped. No verdict.")
        return 2

    n_verified = stats[K_EXACT] + stats[K_NEAR] + stats[K_PIN]
    n_unchecked = stats[K_AMBIG] + stats[K_GUESS]
    print(f"check-citations: {n_cit} citations parsed across {len(citing_md)} md + "
          f"{len(citing_pinned)} pinned + {len(citing_comments)} comment-scanned files "
          f"({frozen_files} citing files frozen by allowlist, with reasons)")
    print("  citation dispositions (each citation lands in exactly one; sum equals total):")
    for k in DISPOSITIONS:
        print(f"    {stats[k]:5d}  {k}")
    print(f"  commit-SHA reachability (DD-045 item 4B; {n_sha} backticked/bold hex token(s) "
          f"with >=1 letter & >=1 digit, each lands in exactly one; sum equals total):")
    for k in SHA_DISPOSITIONS:
        print(f"    {stats[k]:5d}  {k}")
    print("  value quotes beside citations (backticked, **bold**, attribute-quoted, and "
          "bare separated numbers/stamps on citation lines):")
    for k in sorted(k for k in stats if k.startswith("values:")):
        print(f"    {stats[k]:5d}  {k}")
    print("  value forms NOT examined, sitting beside citations (counted, per class; "
          "see docstring):")
    for k in (V_UNEX_SHORT, V_UNEX_CODE, V_UNEX_BOLD, V_UNEX_BARE, V_UNEX_PAREN, V_UNEX_SHA_BARE):
        print(f"    {stats[k]:5d}  {k}")
    print("        (not countable, also unexamined: bare short prose numbers, spelled-out "
          "figures, percentages, arithmetic)")
    print("  non-citation tokens skipped (counted, per class):")
    for k in sorted(k for k in stats if k.startswith("skipped tokens:")):
        print(f"    {stats[k]:5d}  {k}")
    for k in sorted(k for k in stats if k.startswith("pinned rows unparseable")):
        print(f"    {stats[k]:5d}  {k}")

    guess_out = []
    agg: dict[tuple[str, str], list[str]] = {}
    for raw, target, g_citing, g_line in guess_rows:
        agg.setdefault((raw, target), []).append(f"{g_citing}:{g_line}")
    for (raw, target), sites in sorted(agg.items()):
        extra = f" and {len(sites) - 1} more site(s)" if len(sites) > 1 else ""
        guess_out.append(f"`{raw}` — written path names nothing; unique guess {target} "
                         f"({sites[0]}{extra})")

    for title, rows in (("allowlisted (deliberate absences/externals; reasons in "
                         "scripts/check-citations-allowlist.txt)", allowed_out),
                        ("UNCHECKED — ambiguous name (target not decidable; NOT verified)",
                         ambiguous_out),
                        ("UNCHECKED — resolved only by guess (existence of the written path "
                         "NOT verified; line/value checks NOT run)", guess_out),
                        ("disclosed absences (prose says the path is gone/ignored; dead-path "
                         "check skipped)", disclosed_out),
                        ("untracked-on-disk (must be committed with the citing doc; not "
                         "failed)", untracked_out),
                        ("SIBLING-BACKED values (bench-results run dir; imprecise citation, "
                         "not failed)", sibling_out),
                        ("values backed ONLY by cited prose .md (circular corroboration — "
                         "a claim, not an artifact; not verified, not failed)", circular_out),
                        ("REPORTED to owning agent, pending their fix (not failed)",
                         reported_out),
                        ("allowlisted commit SHAs (upstream/foreign; reasons in "
                         "scripts/check-citations-allowlist.txt)", sha_allowed_out),
                        ("disclosed absent commit SHAs (prose says the commit is "
                         "gone/pruned)", sha_disclosed_out)):
        if rows:
            print(f"\n{len(rows)} {title}:")
            for r in rows:
                print(f"  NOTE {r}")
    if findings:
        print(f"\n{len(findings)} FINDINGS:")
        for f in findings:
            print(f"  FAIL {f}")
        return 1
    n_unex = sum(stats[k] for k in (V_UNEX_SHORT, V_UNEX_CODE, V_UNEX_BOLD, V_UNEX_BARE,
                                    V_UNEX_PAREN, V_UNEX_SHA_BARE))
    n_rep = stats[K_REP] + stats["values: reported (suppressed FAIL)"]
    print(f"\nOK — {n_verified} of {n_cit} citations verified clean (dead paths, stale "
          f"lines, carried values); {n_unchecked} UNCHECKED, {stats[K_ALLOW]} allowlisted, "
          f"{n_rep} reported to owners, {stats[K_DISC]} disclosed absences, "
          f"{stats[K_DISK]} untracked-on-disk — all listed above. Values checked only in "
          f"backticks, **bold**, attribute quotes, and bare separated-number/stamp forms on "
          f"citation lines; {n_unex} digit-bearing tokens beside citations were in forms NOT "
          f"examined (short numbers, non-strong code spans/bold phrases, year-shaped bare "
          f"numbers, bare hex tokens — counted above), and bare short prose numbers, "
          f"spelled-out figures, percentages and arithmetic are never examined. Commit-SHA "
          f"reachability (item 4B): {stats[S_OK]} of {n_sha} backticked/bold hex token(s) "
          f"verified reachable from origin/main; {stats[S_ALLOW]} allowlisted, "
          f"{stats[S_DISC]} disclosed, {stats[S_HIST]} historical (bench-results evidence), "
          f"{stats[S_NOBASE]} unchecked (no baseline ref). No claim is made about anything "
          f"not counted as verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
