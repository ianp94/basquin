#!/usr/bin/env python3
"""Pin what _redact must and must NOT touch.

The must-not half is the point. A previous version matched any key containing "token" anywhere,
which turned the corpus recipe `X-XSRF-TOKEN=${{csrf}}` into `X-XSRF-TOKEN=<redacted>` — destroying
the DD-036 evidence these artifacts exist to preserve, while claiming to protect it. A redactor that
cannot tell a recipe from a secret is worse than no redactor at all.

Run: python3 deploy/bench/test_redact.py
"""
import importlib.util
import pathlib
import sys

spec = importlib.util.spec_from_file_location(
    "collect", pathlib.Path(__file__).with_name("collect.py"))
collect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collect)
redact = collect._redact

MUST_REDACT = [
    ("-Dbasquin.dashboard.token=e4e86b722d0f4ecd", "JVM property — how nine tokens were published"),
    ('"dashboardToken": "abc123def456"', "JSON field"),
    ('"api_key":"xyz789"', "JSON field, no spaces"),
    ("Authorization: Bearer abc123", "credential header"),
    ("Set-Cookie: JSESSIONID=5E9F79ABCD", "session cookie"),
    ("X-Basquin-Token: e4e86b722d0f4ecd", "the dashboard token as a header — collect.py redacts it, "
                                          "and nothing pinned that it must"),
]

MUST_NOT_TOUCH = [
    ("POST /Edit.jsp page=Main&X-XSRF-TOKEN=${{csrf}}&ok=Save", "DD-036 recipe: the placeholder IS the evidence"),
    ("/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN", "a capture recipe naming a token field"),
    ("/actions/Catalog.action?viewItem=&itemId=EST-22", "a fuzzer-generated input"),
    ('"requests": 35952, "throughputRps": "117.5"', "ordinary summary numbers"),
    ("[Basquin] load: 82 sequence(s), concurrency=8", "an ordinary log line"),
]

def main() -> int:
    bad = 0
    for text, why in MUST_REDACT:
        out = redact(text)
        ok = "<redacted>" in out
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} redacts: {why}\n         {out}")
    for text, why in MUST_NOT_TOUCH:
        out = redact(text)
        ok = out == text
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} preserves: {why}")
        if not ok:
            print(f"         corrupted to: {out}")
    print("\nFAILURES:", bad)
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
