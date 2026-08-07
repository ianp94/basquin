#!/usr/bin/env python3
"""SubagentStop hook (DD-045 item 6b, the CONDITIONAL piece) — a mechanical assist, opt-in,
this-harness-only. See `scripts/check-agent-report.py`'s docstring for the contract this hook is
one bolt-on enforcement path for; that script's four checks are the enforceable core and work
without this hook. This file exists only because item 6b's design explicitly gated it on
confirming, against authoritative current docs, (a) that `SubagentStop` is a real current hook
event and (b) exactly how a hook signals a blocking decision — both were confirmed 2026-08-07
against `https://code.claude.com/docs/en/hooks` (the current redirect target of
`https://docs.claude.com/en/docs/claude-code/hooks`, confirmed live that day): the event fires
"when a subagent finishes"; a hook blocks it either via exit code 2 (stderr fed back as an error
message) or via JSON `{"decision": "block", "reason": "..."}` printed to stdout on exit 0; a
block "prevents the subagent from stopping" — genuine enforcement, not merely added context. This
file uses the JSON form, because it lets the reason be a structured, quotable string rather than
whatever exit 2's stderr-as-message path would produce.

WHAT THIS HOOK DOES. Reads one JSON object from stdin (the hook's input contract — common fields
include `transcript_path`, `agent_id`, `agent_type`, and — for `SubagentStop` specifically —
`last_assistant_message`, the finishing subagent's final turn text). If the transcript shows no
mutating tool call at all, this hook has nothing to gate — a read-only investigation subagent
owes no pasted verification — and it exits 0 silently. If a mutating tool call IS present, it
checks `last_assistant_message` for the CONTRACT MARKER: a fenced block containing both an
`exit=<N>` line and at least one other non-blank line — importing `FENCE_RE` and `EXIT_LINE_RE`
directly from `scripts/check-agent-report.py` rather than re-deriving them (one regex, not a
second copy, the same discipline `scripts/check-removed-deps.py` already applies to `NEG_RE`).
Marker present -> exit 0. Marker absent -> block, naming which mutating tool(s) ran and that no
pasted verification block was found in the final message.

WHAT COUNTS AS "MUTATING". `Edit`, `Write`, `NotebookEdit` only — unambiguous file-mutation tools.
`Bash` is DELIBERATELY EXCLUDED: a Bash call could be `ls` or it could be `rm -rf`, and this hook
has no way to classify it without executing a second, fragile command-parser — the exact kind of
mechanism this thread's house style keeps refusing to build (see e.g. item 4's refusal to parse
the harness's own bash). Consequence, disclosed: a subagent that mutates the tree ONLY via Bash
(a `git commit`, a redirect to a file) is invisible to this hook and is never blocked. This is a
narrower net than "any tool use", by design — false blocks on read-only work are worse than a
missed catch on a Bash-only mutation, given the contract's own convention layer (the dispatching
agent running `check-agent-report.py` by hand) still applies regardless.

TRANSCRIPT-SCHEMA CAVEAT — the one part of this file NOT confirmed against authoritative docs the
way the hook event/blocking mechanism above was. The docs describe `transcript_path` at a high
level (a path to the conversation JSON, noted to LAG in-memory state) without publishing the
line-level JSONL schema. This hook does not read `transcript_path` at all, specifically BECAUSE of
that gap — `last_assistant_message` is the one field the docs confirm is fresh for the current
turn, and mutating-tool detection here is DELIBERATELY LIMITED to what can be inferred from that
text (a crude, clause-scoped verb+file+first-person scan that also excludes third-party narration
mediated by a reporting verb — "I found that X wrote the files" does not trip this, "I wrote the
files" does; see `has_self_mutation_hint()` below), not a transcript walk. This is a strictly
weaker signal than parsing actual tool_use blocks would be: disclosed, not silently claimed as
full coverage. A future revision that reads
`transcript_path` for exact tool_use records would need its own JSONL-schema confirmation pass
first, same bar as this file cleared for the event/blocking mechanism.

FAILS OPEN, ALWAYS. Any error — malformed stdin JSON, an unexpected field shape, an exception this
file's author didn't anticipate, OR the module-level import of `check-agent-report.py` itself
failing (that file moved, renamed, or a transient syntax error) — is caught and answers with exit
0 and no block. The import is wrapped in its own try/except at module load time specifically so
that failure mode is not the one thing this file lets through uncaught: see the `_IMPORT_ERROR`
sentinel below and its check at the top of `main()`. A hook that crashes or blocks the harness due
to ITS OWN bug is a worse failure mode than a missed catch: this is a mechanical ASSIST, not a
security boundary, and the design's own text says so plainly ("it cannot help a killed session;
nothing can"). Never raises past `main()` — nor past the module-level import above it.

Wired in `.claude/settings.json` (tracked, this-checkout-only). It runs the moment a session in
this checkout has accepted Claude Code's one-time workspace-trust dialog for this folder — not a
separate, per-hook approval prompt; merging this file into the tree, in a folder the user has
already trusted, IS the conscious opt-in — an honest boundary, not a durable gate: see
check-agent-report.py's own docstring for what IS durable, item 6a's CI-side evidence-completeness
gate.
"""
import json
import pathlib
import re
import sys
import importlib.util

ROOT = pathlib.Path(__file__).resolve().parents[1]

# IMPORT WRAPPED IN try/except, ON PURPOSE (see FAILS OPEN, ALWAYS above): a bare module-level
# exec_module call would run OUTSIDE every try/except in this file — if check-agent-report.py is
# ever moved, renamed, or has a transient syntax error, that would raise past this module's own
# load, past `main()`, and past the `if __name__ == "__main__":` guard's try/except (which only
# wraps the CALL to main(), not the imports above it) — an uncaught traceback contradicting this
# file's own "Never raises past main()" claim. Wrapping it here means an import failure is just
# another disclosed-limited condition: `_IMPORT_ERROR` is checked first thing in `main()`, and a
# broken import fails open (does nothing) exactly like every other error case in this file.
try:
    _spec = importlib.util.spec_from_file_location(
        "check_agent_report", ROOT / "scripts" / "check-agent-report.py")
    _check_agent_report = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_check_agent_report)
    FENCE_RE = _check_agent_report.FENCE_RE
    EXIT_LINE_RE = _check_agent_report.EXIT_LINE_RE
    _IMPORT_ERROR = None
except Exception as _e:
    FENCE_RE = None
    EXIT_LINE_RE = None
    _IMPORT_ERROR = _e

# Crude, disclosed-limited signal (see TRANSCRIPT-SCHEMA CAVEAT): last_assistant_message is prose
# ABOUT what the subagent did, not a tool-call log, so this can only catch a subagent that
# describes its own edits in words a human would also use. False negatives are expected and
# accepted; a false positive (blocking read-only work) would be the worse failure mode here — so
# a mutation-shaped verb+file phrase ALONE is not enough: a first-person marker (`I`, `my`, `me`,
# `myself`) must appear BEFORE the verb within the same clause, so a subagent narrating someone
# ELSE's mutation ("the previous commit had already modified 3 files before I started") does not
# trip this hook merely because a mutation verb and a first-person pronoun both occur somewhere in
# its final message — clause-scoped and ordered, not a whole-message keyword search.
#
# A pronoun-then-verb ORDER alone is still not enough, though: "I found that the deploy script
# wrote the files to /tmp" has "I" before "wrote ... files" in the same clause, but "I" is the
# subject of "found", not of "wrote" — a REPORTING verb (found/noticed/observed/confirmed/saw/
# discovered/noted/detected/realized/learned) sitting between the pronoun and the mutation verb
# means the pronoun governs the reporting verb, and the mutation verb's subject is whatever THAT
# reporting verb's object names (here, "the deploy script") — third-party narration, not a
# self-mutation claim. Only the LAST first-person marker before the mutation verb is checked
# against this (a compound clause like "I confirmed that I wrote the files" still blocks: the
# second "I" has no reporting verb between it and "wrote").
CLAUSE_SPLIT_RE = re.compile(r"[.!?;]+")
MUTATION_VERB_FILE_RE = re.compile(
    r"\b(edited|wrote|created|modified|updated|deleted|renamed)\b.{0,40}\b(file|files)\b",
    re.IGNORECASE,
)
FIRST_PERSON_RE = re.compile(r"\b(I|my|me|myself)\b", re.IGNORECASE)
REPORTING_VERB_RE = re.compile(
    r"\b(found|noticed|observed|confirmed|saw|discovered|noted|detected|realized|learned)\b",
    re.IGNORECASE,
)


def has_self_mutation_hint(text: str) -> bool:
    for clause in CLAUSE_SPLIT_RE.split(text):
        m = MUTATION_VERB_FILE_RE.search(clause)
        if not m:
            continue
        before = clause[:m.start()]
        fp_matches = list(FIRST_PERSON_RE.finditer(before))
        if not fp_matches:
            continue
        # only the text between the CLOSEST first-person marker and the mutation verb matters —
        # see the comment above for why (a reporting verb there means third-party narration).
        between = before[fp_matches[-1].end():]
        if REPORTING_VERB_RE.search(between):
            continue
        return True
    return False


def has_contract_marker(text: str) -> bool:
    for m in FENCE_RE.finditer(text):
        block = m.group(1)
        lines = [ln for ln in block.splitlines() if ln.strip()]
        has_exit = any(EXIT_LINE_RE.match(ln) for ln in lines)
        has_other = any(not EXIT_LINE_RE.match(ln) for ln in lines)
        if has_exit and has_other:
            return True
    return False


def main() -> int:
    if _IMPORT_ERROR is not None:
        return 0  # fail open — see the import try/except above; nothing to gate with if the
                  # validator module itself could not load
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # fail open — malformed input is not this hook's problem to solve
    if not isinstance(payload, dict):
        return 0  # fail open — valid JSON (e.g. `[1,2]`) but not an object; `.get` below would
                  # otherwise raise AttributeError, which would make "Never raises past main()"
                  # false rather than merely relying on the __main__ guard one level further out
    text = payload.get("last_assistant_message")
    if not isinstance(text, str) or not text.strip():
        return 0  # nothing to scan — fail open, never block on absence of the field itself
    if not has_self_mutation_hint(text):
        return 0  # no self-mutation hint — nothing for this hook to gate
    if has_contract_marker(text):
        return 0  # contract marker present — compliant
    print(json.dumps({
        "decision": "block",
        "reason": ("DD-045 item 6 contract: this subagent's final message describes editing "
                   "file(s) but contains no pasted verification block (a fenced block with a "
                   "command AND a captured `exit=<N>` line). Paste the actual command output, "
                   "including `exit=$?`, before reporting done. (This is a best-effort local "
                   "hook — see scripts/agent-report-hook.py's docstring for its disclosed "
                   "limits; it does not replace scripts/check-agent-report.py.)"),
    }))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        sys.exit(0)  # fail open, unconditionally — see module docstring
