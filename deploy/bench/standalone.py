#!/usr/bin/env python3
"""Emit a single self-contained copy of the benchmark page.

docs/benchmarks.html links site.css and a favicon, which is right for the docs site and
wrong for anywhere else — a copy mailed to someone, opened off a phone, or published to a
host that blocks external requests renders unstyled. This inlines the stylesheet, drops the
site chrome that only makes sense inside the docs nav, and writes one file that stands alone.

  deploy/bench/render_page.py      # first — regenerate the page from bench-results/
  deploy/bench/standalone.py       # then — bench-results/benchmark-report.html
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "benchmarks.html"
CSS = ROOT / "docs" / "site.css"
OUT = ROOT / "bench-results" / "benchmark-report.html"


def main() -> int:
    html = SRC.read_text(encoding="utf-8")
    css = CSS.read_text(encoding="utf-8")

    # Inline the stylesheet in place of the link, so the page keeps its own <style> block
    # (which defines the chart series tokens) cascading after it, exactly as on the site.
    html = html.replace('<link rel="stylesheet" href="site.css">', f"<style>\n{css}\n</style>")
    html = re.sub(r'<link rel="icon"[^>]*>', "", html)

    # The nav and footer link to sibling pages that won't exist beside this file.
    html = re.sub(r"<header class=\"nav\">.*?</header>", "", html, flags=re.S)
    html = re.sub(r"<footer class=\"footer\">.*?</footer>", "", html, flags=re.S)

    OUT.write_text(html, encoding="utf-8")
    kb = OUT.stat().st_size / 1024
    print(f"wrote {OUT.relative_to(ROOT)}  ({kb:.0f} KB, self-contained)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
