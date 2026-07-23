#!/usr/bin/env python3
"""Render the benchmark page's charts as SVG, from collected run artifacts.

Why generated and not hand-drawn: docs/benchmarks.html used to carry hand-plotted
<rect> elements with pixel coordinates typed in by hand. Every re-run meant
re-deriving ~40 y/height pairs, and a figure could silently drift from the run it
claimed to show. These functions take the numbers straight out of
bench-results/campaigns.json and emit the geometry, so a figure cannot disagree
with its artifact.

Conventions (kept deliberately narrow — this is a chart *generator*, not a chart
library, and it only has to serve one page):

  * Colors are CSS custom properties defined by the page, so every figure themes
    itself with the site (light/dark) instead of baking hexes into the markup.
  * Every bar is direct-labeled. The palette's light-mode contrast sits under 3:1
    for the aqua/yellow slots, which is legal only with that secondary encoding —
    and a reader should never have to measure a bar against an axis anyway.
  * Bars get 4px rounded ends anchored to the baseline and a 2px surface-colored
    gap between neighbours.
  * A percentile axis is log-scaled when the series spans >2 decades (a load run's
    p50 is 2ms and its max is 12s; linear makes every bar but one invisible). Log
    axes are labeled as such, in the figure, not just the caption.
"""
from __future__ import annotations

import html
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESULTS = ROOT / "bench-results"

# Categorical slots, in fixed order — assigned per entity and never cycled or
# reassigned by rank, so a filtered chart can't repaint the survivors.
SERIES = ["var(--s1)", "var(--s2)", "var(--s3)", "var(--s4)"]


def esc(s) -> str:
    return html.escape(str(s), quote=True)


def fmt(n: float, unit: str = "") -> str:
    if n >= 1_000_000:
        s = f"{n/1_000_000:.1f}M"
    elif n >= 10_000:
        s = f"{n/1000:.0f}k"
    elif n >= 1000:
        s = f"{n:,.0f}"
    elif n >= 10:
        s = f"{n:.0f}"
    elif n == int(n):
        s = f"{int(n)}"
    else:
        s = f"{n:.1f}"
    return s + unit


# ── log-scaled horizontal bars ────────────────────────────────────────────────

def log_bars(rows: list[tuple[str, float, str]], *, title_id: str, unit: str,
             width: int = 640, row_h: int = 30, label_w: int = 116) -> str:
    """Horizontal bars on a log10 x-axis. rows = [(label, value, color), ...].

    Used for latency percentiles, where p50..max routinely spans four decades and
    a linear axis would render everything below p99 as a hairline.
    """
    vals = [v for _, v, _ in rows if v > 0]
    if not vals:
        return ""
    lo = 1.0
    hi = 10 ** math.ceil(math.log10(max(vals)))
    plot_w = width - label_w - 58
    height = len(rows) * row_h + 46

    def x(v: float) -> float:
        v = max(v, lo)
        return label_w + plot_w * (math.log10(v) - math.log10(lo)) / (math.log10(hi) - math.log10(lo))

    decades = [10 ** e for e in range(0, int(math.log10(hi)) + 1)]
    out = [f'<svg viewBox="0 0 {width} {height}" width="100%" role="img" '
           f'aria-labelledby="{title_id}">']
    # gridlines first so marks sit above them
    for d in decades:
        out.append(f'<line class="gl" x1="{x(d):.1f}" y1="18" x2="{x(d):.1f}" y2="{len(rows)*row_h+22}"/>')
        out.append(f'<text class="ax" x="{x(d):.1f}" y="{len(rows)*row_h+38}" text-anchor="middle">'
                   f'{fmt(d)}</text>')
    for i, (label, v, color) in enumerate(rows):
        y = 22 + i * row_h
        bh = row_h - 10          # the 2px+ gap between neighbouring bars
        w = max(x(v) - label_w, 2)
        out.append(f'<text class="ax lbl" x="{label_w-10}" y="{y+bh-3}" text-anchor="end">{esc(label)}</text>')
        out.append(f'<rect x="{label_w}" y="{y}" width="{w:.1f}" height="{bh}" rx="4" fill="{color}"/>')
        out.append(f'<text class="val" x="{label_w+w+7:.1f}" y="{y+bh-3}">{esc(fmt(v, unit))}</text>')
    out.append(f'<text class="ax" x="{width}" y="12" text-anchor="end">log scale · {esc(unit)}</text>')
    out.append("</svg>")
    return "\n".join(out)


# ── grouped vertical bars ─────────────────────────────────────────────────────

def grouped_bars(groups: list[str], series: list[tuple[str, list[float], str]], *,
                 title_id: str, unit: str = "", width: int = 640, height: int = 250,
                 bar_colors: list[str] | None = None, fmt_value=None) -> str:
    """Vertical grouped bars on a shared linear axis. series = [(name, values, color)].

    bar_colors overrides the per-GROUP color for a single-series chart, so a chart of one
    measure across entities still colors each bar by its entity rather than flattening
    them into one hue — color follows the entity, never the position.
    fmt_value overrides the label formatter (e.g. to keep a percentage's decimal).
    """
    top, bottom, left = 16, 40, 8
    plot_h = height - top - bottom
    peak = max((v for _, vs, _ in series for v in vs), default=0) or 1
    gw = (width - left * 2) / len(groups)
    bw = min(38, (gw - 22) / len(series))

    out = [f'<svg viewBox="0 0 {width} {height}" width="100%" role="img" aria-labelledby="{title_id}">']
    base = top + plot_h
    for frac in (0.25, 0.5, 0.75, 1.0):
        y = base - plot_h * frac
        out.append(f'<line class="gl" x1="{left}" y1="{y:.1f}" x2="{width-left}" y2="{y:.1f}"/>')
    out.append(f'<line class="gl axis-line" x1="{left}" y1="{base}" x2="{width-left}" y2="{base}"/>')
    for gi, g in enumerate(groups):
        cx = left + gw * gi + gw / 2
        span = bw * len(series) + 2 * (len(series) - 1)
        for si, (_, vals, color) in enumerate(series):
            v = vals[gi]
            if bar_colors and len(series) == 1:
                color = bar_colors[gi]
            bh = max(plot_h * (v / peak), 2)
            bx = cx - span / 2 + si * (bw + 2)      # 2px surface gap between bars
            out.append(f'<rect x="{bx:.1f}" y="{base-bh:.1f}" width="{bw:.1f}" height="{bh:.1f}" '
                       f'rx="4" fill="{color}"/>')
            out.append(f'<text class="val" x="{bx+bw/2:.1f}" y="{base-bh-5:.1f}" text-anchor="middle">'
                       f'{esc(fmt_value(v) if fmt_value else fmt(v, unit))}</text>')
        out.append(f'<text class="ax" x="{cx:.1f}" y="{base+17}" text-anchor="middle">{esc(g)}</text>')
    out.append("</svg>")
    return "\n".join(out)


# ── proportional stacked bar ──────────────────────────────────────────────────

def stacked_bar(parts: list[tuple[str, float, str, str]], *, title_id: str,
                width: int = 640, bar_h: int = 46) -> str:
    """One full-width bar split into shares. parts = [(label, value, color, note)].

    For composition-of-a-whole, where the *share* is the point — e.g. how a run's
    3xx responses divide between a successful write and a rejected one.
    """
    total = sum(v for _, v, _, _ in parts) or 1
    row_h = 21
    # A legend row is one line of text at ~6.2 units/char; a long note (e.g. Login.jsp's) ran 84-180
    # units past the 640 viewBox and was clipped on the published chart. Widen the box to whatever
    # the longest row actually needs rather than trusting it to fit.
    _need = max((16 + 8 + len(f"{lbl}{fmt(v)}{note}") * 6.2 + 40) for lbl, v, _, note in parts)
    width = max(width, int(_need))
    out = [f'<svg viewBox="0 0 {width} {bar_h + 20 + row_h*len(parts)}" width="100%" role="img" '
           f'aria-labelledby="{title_id}">']
    x = 0.0
    for _, v, color, _ in parts:
        w = width * v / total
        out.append(f'<rect x="{x:.1f}" y="0" width="{max(w-2,1):.1f}" height="{bar_h}" rx="4" fill="{color}"/>')
        x += w
    # The share is read off the legend, not printed inside the segment: in-bar text
    # would have to clear the fill it sits on, and two of the four slots are light
    # enough that neither white nor the ink color passes contrast on them. One
    # legend row per part (never two columns — a long note overflows the viewBox),
    # each carrying the absolute count as well as the share, so the reader never
    # has to turn a percentage back into a number.
    for i, (label, v, color, note) in enumerate(parts):
        ly = bar_h + 20 + i * row_h
        out.append(f'<rect x="0" y="{ly-9}" width="10" height="10" rx="2" fill="{color}"/>')
        out.append(f'<text class="ax lgd" x="16" y="{ly}">{esc(label)}'
                   f'<tspan class="val" dx="8">{esc(fmt(v))}</tspan>'
                   f'<tspan class="note" dx="6">{v/total*100:.0f}%</tspan>'
                   + (f'<tspan class="note" dx="8">· {esc(note)}</tspan>' if note else "") + "</text>")
    out.append("</svg>")
    return "\n".join(out)


# ── data access ───────────────────────────────────────────────────────────────

def load_records() -> list[dict]:
    p = RESULTS / "campaigns.json"
    return json.loads(p.read_text()) if p.exists() else []


def by_campaign(name: str) -> dict | None:
    return next((r for r in load_records() if r["campaign"] == name), None)


if __name__ == "__main__":
    recs = load_records()
    print(f"{len(recs)} collected campaign record(s):")
    for r in recs:
        extra = ""
        if r.get("load"):
            ld = r["load"]
            extra = (f"  {ld.get('requests')} req  {ld.get('throughputRps')} rps  "
                     f"p99 {ld.get('latencyMs', {}).get('p99')}ms")
        elif r.get("coveragePct") is not None:
            extra = f"  coverage {r['coveragePct']}%  findings {r.get('findings')}"
        print(f"  {r['app']:<10} {r['mode']:<8} {r['campaign']:<28} {r['phase']}{extra}")
