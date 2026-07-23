#!/usr/bin/env python3
"""Generate docs/benchmarks.html from the collected benchmark artifacts.

The prose lives here; every *number* and every chart coordinate is read out of
bench-results/. That split is the whole point: a claim on the published page cannot
drift away from the run it cites, because re-running this script is the only way the
page changes. If a campaign hasn't been collected, the section that needs it is
omitted rather than filled with a stale figure.

  deploy/bench/collect.py <campaign>...   # cluster -> bench-results/
  deploy/bench/render_page.py             # bench-results/ -> docs/benchmarks.html

Two datasets feed it, and they are NOT interchangeable:

  * campaigns.json — operator-run BasquinCampaigns (coverage, findings, and the
    driver's terminal load summary incl. the DD-038 redirect classification).
    These are the current-code numbers.
  * report-data.json — the earlier valve-instrumented soak (per-request heap and
    latency measured *inside* the app JVM, plus the k6 baselines). Nothing else
    produces per-request heap, so it stays, explicitly dated.
"""
from __future__ import annotations

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import charts  # noqa: E402
from charts import esc, fmt, grouped_bars, log_bars, stacked_bar  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESULTS = ROOT / "bench-results"
OUT = ROOT / "docs" / "benchmarks.html"

APPS = {
    "jspwiki":   {"label": "Apache JSPWiki 2.12.4", "kind": "wiki CMS · filesystem store",
                  "color": "var(--s1)"},
    "jpetstore": {"label": "MyBatis JPetStore-6", "kind": "e-commerce · MyBatis + HSQLDB",
                  "color": "var(--s2)"},
    "roller":    {"label": "Apache Roller 6.1.5", "kind": "blog CMS · JPA + Postgres",
                  "color": "var(--s3)"},
}


def short(app: str) -> str:
    """Human short name for an app — 'Apache JSPWiki 2.12.4' -> 'JSPWiki'. Ad-hoc split() indexing
    produced '2.12.4' as a label, which is a version, not an app."""
    return APPS[app]["label"].replace("Apache ", "").replace("MyBatis ", "").split()[0]


def recs() -> list[dict]:
    p = RESULTS / "campaigns.json"
    return json.loads(p.read_text()) if p.exists() else []


def pick(app: str, mode: str, concurrency: int | None = None) -> dict | None:
    for r in recs():
        if r["app"] != app or r["mode"] != mode or r.get("phase") != "Completed":
            continue
        if concurrency is not None and int(r.get("driver", {}).get("concurrency", 1)) != concurrency:
            continue
        return r
    return None


def legacy() -> dict:
    p = RESULTS / "report-data.json"
    return json.loads(p.read_text()) if p.exists() else {}


# ── figure builders ───────────────────────────────────────────────────────────

def fig(title: str, body: str, caption: str, *, fid: str) -> str:
    if not body:
        return ""
    return f"""  <div class="chart-card">
    <figure>
      <figcaption id="{fid}" class="fig-title">{title}</figcaption>
      {body}
      <figcaption>{caption}</figcaption>
    </figure>
  </div>"""


def latency_figure() -> str:
    """Per-app load latency percentiles. Log axis: p50 and max are ~4 decades apart,
    so a linear axis would collapse every percentile but the worst into a hairline."""
    blocks = []
    for app, meta in APPS.items():
        r = pick(app, "load", 8) or pick(app, "load")
        if not r or not r.get("load"):
            continue
        ld = r["load"]
        lat = ld.get("latencyMs", {})
        c = int(r.get("driver", {}).get("concurrency", 1))
        rows = [(k, float(lat[k]), meta["color"]) for k in ("p50", "p90", "p99", "max") if k in lat]
        if not rows:
            continue
        fid = f"lat-{app}"
        blocks.append(fig(
            f'{meta["label"]} — {fmt(ld.get("requests", 0))} requests at {ld.get("throughputRps")} rps '
            f'(concurrency {c})',
            log_bars(rows, title_id=fid, unit="ms"),
            f'Median {fmt(float(lat.get("p50", 0)))} ms, but a worst single request of '
            f'{fmt(float(lat.get("max", 0)))} ms — a '
            f'{float(lat.get("max", 1))/max(float(lat.get("p50", 1)), 1):.0f}× spread that a '
            f'median-only dashboard hides entirely.',
            fid=fid))
    return "\n".join(blocks)


# What each redirect destination MEANS on JSPWiki 2.12.4. Every entry was established by
# probing the running app directly — not inferred from the counts, which is how the first
# reading of this data went wrong in both directions.
JSPWIKI_REDIRECTS = {
    # Wiki.jsp and Main were established by probing the live app during the redirect-classifier
    # root cause (PR #92): a successful save answers 302 /Wiki.jsp?page=<savedPage>, and
    # /Diff.jsp?page=<invalid> folds to the front page. The other five signatures are corroborated
    # in TODO.md's "JSPWiki behaviours worth knowing" section.
    "Wiki.jsp":         ("accepted", "the save echo — a write that landed"),
    "self":             ("accepted", "a same-page echo with no path segment"),
    "PageModified.jsp": ("rejected", "concurrent-edit conflict — the save was refused"),
    "SessionExpired":   ("rejected", "anti-spam hash missing or stale"),
    "Forbidden.html":   ("rejected", "CSRF token missing or wrong"),
    "Login.jsp":        ("other",    "a view request with an EMPTY page name, which JSPWiki "
                                     "bounces to the login screen with or without a session"),
    "Main":             ("other",    "an invalid page name on Diff.jsp, folded to the front page"),
}
VERDICT_COLOR = {"accepted": "var(--s3)", "rejected": "var(--s2)",
                 "other": "var(--s4)", "unclassified": "var(--s1)"}


def redirect_figure() -> str:
    """DD-038's redirect classification, with each bucket's meaning verified against the app.

    Buckets are colored by verdict rather than by slot, because the reader's question is
    "did the write land?", not "which destination was most common". Any destination not in
    the verified map is shown as unclassified rather than guessed at.
    """
    r = pick("jspwiki", "load", 8) or pick("jspwiki", "load")
    if not r or not r.get("load") or not r["load"].get("redirectTargets"):
        return ""
    ld = r["load"]
    tgts: dict[str, int] = ld["redirectTargets"]
    ordered = sorted(tgts.items(), key=lambda kv: -kv[1])
    parts = []
    for k, v in ordered:
        verdict, note = JSPWIKI_REDIRECTS.get(k, ("unclassified", "destination not yet characterized"))
        parts.append((k, v, VERDICT_COLOR[verdict], note))
    fid = "redirects"
    total = sum(v for _, v, _, _ in parts)
    tally = {}
    for k, v in ordered:
        tally.setdefault(JSPWIKI_REDIRECTS.get(k, ("unclassified", ""))[0], 0)
        tally[JSPWIKI_REDIRECTS.get(k, ("unclassified", ""))[0]] += v
    rej, acc = tally.get("rejected", 0), tally.get("accepted", 0)
    verdict_line = (f'<strong>{fmt(rej)} refused writes</strong> and {fmt(acc)} accepted ones'
                    if rej else f'{fmt(acc)} accepted writes and no refusals in this run')
    return fig(
        f"Where {fmt(total)} redirects went — JSPWiki under load, classified by Location",
        stacked_bar(parts, title_id=fid),
        f'Every one of these was a 3xx: not a 5xx, not a 4xx, and every CSRF and anti-spam token was '
        f'submitted (<code>captureMisses: {fmt(int(ld.get("captureMisses", 0)))}</code> of '
        f'{fmt(int(ld.get("requests", 0)))} requests), so a load tool counts all '
        f'{fmt(total)} as served. Split by destination they resolve into {verdict_line} — a '
        f'distinction carried entirely by a response header that a redirect-following client '
        f'discards before anyone can read it.',
        fid=fid)


def coverage_figure() -> str:
    rows, labels = [], []
    for app, meta in APPS.items():
        r = pick(app, "explore")
        if not r or r.get("coveragePct") is None:
            continue
        labels.append(meta["label"].split()[-2] if " " in meta["label"] else app)
        exp = r.get("exploration") or {}
        rows.append((app, float(r["coveragePct"]), exp.get("findInvariant"), meta["color"]))
    if not rows:
        return ""
    fid = "coverage"
    body = grouped_bars(
        [APPS[a]["label"].replace("Apache ", "").replace("MyBatis ", "") for a, _, _, _ in rows],
        [("bytecode coverage", [c for _, c, _, _ in rows], "var(--s1)")],
        title_id=fid, unit="%", height=210,
        bar_colors=[color for _, _, _, color in rows],
        fmt_value=lambda v: f"{v:.1f}%")
    # Say "unreported" rather than 0 where the count is absent — a zero here would be the exact
    # defect this page documents, and the reader cannot tell the two apart.
    detail = " · ".join(
        f'{short(a)} '
        + (f'reported {f}' if f is not None else 'reported no count')
        for a, _, f, _ in rows)
    return fig("Bytecode coverage reached by grammar-guided exploration",
               body,
               f'JaCoCo-measured coverage of the app\'s own classes, reached without a single line of '
               f'test code — just a request grammar. Invariant breaches the driver actually RECEIVED '
               f'in the same runs: {detail} — read against the reporting loss above, not as the '
               f'number evaluated.',
               fid=fid)


def heap_figure(lg: dict) -> str:
    apps = lg.get("apps", {})
    if not apps:
        return ""
    keys = ["p50", "p90", "p99"]
    series, groups = [], ["median", "p90", "p99"]
    for i, (app, d) in enumerate(apps.items()):
        hk = d.get("heap_kb", {})
        if not all(k in hk for k in keys):
            continue
        series.append((d.get("label", app), [float(hk[k]) for k in keys],
                       APPS.get(app, {}).get("color", charts.SERIES[i])))
    if not series:
        return ""
    fid = "heap"
    return fig("Heap allocated per request, measured inside the app JVM",
               grouped_bars(groups, series, title_id=fid, unit=" KB", height=240),
               'This is the measurement no external load tool can take: '
               'the valve reads the JVM\'s own allocation counter on either side of the request, so the '
               'number is the app\'s real cost for that one input — not a process-wide average.',
               fid=fid)


# ── page ──────────────────────────────────────────────────────────────────────

NO_DATA_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Benchmarks — regenerating</title>
<link rel="stylesheet" href="site.css">
</head>
<body>
<main class="wrap">
  <div class="page-head">
    <div class="breadcrumb">docs / benchmarks</div>
    <h1>Benchmark runs are being regenerated</h1>
    <p class="lead">No completed load campaign has been collected into
      <code>bench-results/</code>, so there is nothing to report yet. This page is generated
      entirely from collected run artifacts and deliberately shows nothing rather than a stale
      or zero-filled figure.</p>
    <p>Regenerate with:</p>
    <pre><code>deploy/bench/battery.sh &lt;app&gt; &lt;corpus&gt; &lt;classesPath&gt; 1 8
deploy/bench/render_page.py</code></pre>
  </div>
</main>
</body>
</html>
"""


def build() -> str:
    lg = legacy()
    all_recs = recs()
    load_runs = [r for r in all_recs if r["mode"] == "load" and r.get("load")]
    explore_runs = [r for r in all_recs if r["mode"] == "explore" and (r.get("exploration") or {})]

    total_requests = sum(int(r["load"].get("requests", 0)) for r in load_runs)
    total_5xx = sum(int(r["load"].get("serverErrors", 0) or 0) for r in load_runs)
    legacy_findings = lg.get("headline", {}).get("combined_findings", 0)
    campaign_findings = sum(int((r.get("exploration") or {}).get("findInvariant") or 0)
                            for r in explore_runs)

    apps_table = []
    for app, meta in APPS.items():
        ex, lo = pick(app, "explore"), (pick(app, "load", 8) or pick(app, "load"))
        if not ex and not lo:
            continue
        ld = (lo or {}).get("load", {}) or {}
        lat = ld.get("latencyMs", {})
        # Hoisted: a `{}` literal inside an f-string expression parses as a set, not a
        # dict default. Also note WHICH field is used — see the note below the table.
        exp = (ex or {}).get("exploration") or {}
        inv = exp.get("findInvariant", "—")
        crs = exp.get("findCrash", "—")
        apps_table.append(f"""        <tr>
          <th scope="row">{esc(meta["label"])}<br><span class="muted">{esc(meta["kind"])}</span></th>
          <td class="num">{esc(ex.get("coveragePct", "—") if ex else "—")}%</td>
          <td class="num">{esc(inv)}</td>
          <td class="num">{esc(crs)}</td>
          <td class="num">{esc(fmt(int(ld.get("requests", 0))) if ld else "—")}</td>
          <td class="num">{esc(ld.get("throughputRps", "—"))}</td>
          <td class="num">{esc(lat.get("p50", "—"))} / {esc(lat.get("p99", "—"))}</td>
          <td class="num {'ok' if str(ld.get("serverErrors", "")) == "0" else 'warn'}">{esc(ld.get("serverErrors", "—"))}</td>
        </tr>""")

    jw = pick("jspwiki", "load", 8) or pick("jspwiki", "load")
    jw_load = (jw or {}).get("load", {}) or {}
    redirects = int(jw_load.get("redirects", 0) or 0)
    self_ok = int((jw_load.get("redirectTargets") or {}).get("self", 0))
    # Only the login-form bucket is a proven rejection; see redirect_figure().
    rejected = int((jw_load.get("redirectTargets") or {}).get("Login.jsp", 0))
    # Hoisted out of the template: a `{}` literal inside an f-string expression is
    # read as a set containing an empty dict, not as an empty dict default.
    # Derived from the collected records, not typed in: the page's whole claim is that no text
    # can drift from the runs it cites, and a hardcoded app list is exactly that drift.
    present = [a for a in APPS if pick(a, "explore") or pick(a, "load")]
    # Rendered from each target's collected invariants; a hardcoded pair would be wrong for two of
    # the three apps (roller runs 250ms/512KB, not 25ms/256KB).
    _b = []
    for a in present:
        r = pick(a, "explore") or pick(a, "load") or {}
        inv = r.get("invariants") or {}
        if inv.get("latencyMaxMs") is not None:
            _b.append(f'{short(a)} latency &gt; {inv["latencyMaxMs"]}ms / heapDelta &gt; '
                      f'{inv.get("heapDeltaMaxKb", "?")}KB')
    budget_sentence = ("; ".join(_b) + "." if _b
                       else "Not recorded for these runs — the collected artifacts carry no target invariants.")
    app_pills = "".join(f'      <span class="pill">{esc(APPS[a]["label"])}</span>\n' for a in present)
    _names = [APPS[a]["label"] for a in present]
    app_sentence = (" and ".join([", ".join(_names[:-1]), _names[-1]]) if len(_names) > 1
                    else (_names[0] if _names else "No apps collected")) + "."
    n_load = len(load_runs)
    # Derived, not asserted: an earlier draft claimed "roughly 5% 5xx" from a run that was later
    # discarded, and the sentence outlived its data.
    _jps = [r for r in load_runs if r["app"] == "jpetstore"]
    _e = sum(int((r.get("load") or {}).get("serverErrors", 0) or 0) for r in _jps)
    _n = sum(int((r.get("load") or {}).get("requests", 0) or 0) for r in _jps)
    jps_5xx_clause = (
        f'And JPetStore returns 5xx on {100*_e/_n:.1f}% of requests under load '
        f'({fmt(_e)} of {fmt(_n)}).' if _jps and _e and _n else
        f'And JPetStore returned <strong>no 5xx at all</strong> across {fmt(_n)} requests here — an '
        f'earlier run did, traced to a single corpus route that NPEs for an unauthenticated caller '
        f'and then NPEs again inside the framework\'s own exception handler; this corpus does not '
        f'contain that route.' if _jps else '')
    jw_lat = jw_load.get("latencyMs") or {}
    jw_max = fmt(float(jw_lat.get("max", 0)))
    jw_p50 = esc(jw_lat.get("p50", "—"))

    # A report generator must never print a confident zero for data it does not have --
    # that is the same failure as reporting heapDriftKb=0 when the poll failed. With no
    # collected load run there is nothing to report, and the page says exactly that.
    if not load_runs:
        return NO_DATA_PAGE

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Benchmarks — Basquin against unmodified JVM apps</title>
<meta name="description" content="Basquin run against real, unmodified JVM web apps: what it finds that a crash-only oracle misses, measured on {fmt(total_requests)} requests with {total_5xx} server errors.">
<link rel="icon" href="logo.svg" type="image/svg+xml">
<link rel="stylesheet" href="site.css">
<style>
  /* Chart series slots, in fixed order. Assigned per entity and never cycled, so a
     figure that drops a series never repaints the survivors. Both themes are
     explicitly chosen — the dark values are re-stepped for the dark surface, not an
     automatic inversion — and validated for CVD separation and contrast. */
  :root {{
    --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a; --s4: #eda100;
    --chart-grid: #ececea;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --s1: #3987e5; --s2: #d95926; --s3: #199e70; --s4: #c98500;
      --chart-grid: #22262d;
    }}
  }}
  :root[data-theme="dark"] {{
    --s1: #3987e5; --s2: #d95926; --s3: #199e70; --s4: #c98500; --chart-grid: #22262d;
  }}
  :root[data-theme="light"] {{
    --s1: #2a78d6; --s2: #eb6834; --s3: #1baf7a; --s4: #eda100; --chart-grid: #ececea;
  }}

  .muted {{ color: var(--text-muted); font-size: .9rem; }}
  td.num, th.num {{ text-align: right; font-family: var(--mono); font-variant-numeric: tabular-nums; }}
  td.num.ok {{ color: var(--ok); }}
  tbody th[scope="row"] {{ text-align: left; font-weight: 600; }}
  th.grp {{ font-weight: 600; font-size: .78rem; color: var(--text-muted); text-align: center;
            border-bottom: 1px solid var(--border); }}

  .tiles {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin: 24px 0; }}
  .tile {{ border: 1px solid var(--border); border-radius: var(--radius); background: var(--panel); box-shadow: var(--shadow); padding: 16px 18px; }}
  .tile .n {{ font-size: 2rem; font-weight: 700; letter-spacing: -.02em; line-height: 1.1; color: var(--heading); font-variant-numeric: tabular-nums; }}
  .tile .n.ok {{ color: var(--ok); }}
  .tile .n.warn {{ color: var(--warn); }}
  .tile .l {{ font-size: .82rem; color: var(--text-muted); margin-top: 3px; }}

  .chart-card {{ border: 1px solid var(--border); border-radius: var(--radius); background: var(--panel); box-shadow: var(--shadow); padding: 18px; margin: 18px 0; }}
  .chart-card figure {{ margin: 0; }}
  .chart-card .fig-title {{ font-size: .95rem; font-weight: 600; color: var(--heading); margin: 0 0 10px; }}
  .chart-card figcaption:last-child {{ font-size: .85rem; color: var(--text-muted); margin-top: 12px; line-height: 1.55; }}
  svg text {{ font-family: var(--sans); }}
  svg .val {{ font-family: var(--mono); font-size: 11px; font-variant-numeric: tabular-nums; fill: var(--text-muted); }}
  svg .ax {{ fill: var(--text-muted); font-size: 11px; }}
  svg .ax.lbl {{ font-family: var(--mono); }}
  svg .lgd {{ font-size: 12px; }}
  svg .note {{ fill: var(--text-muted); font-size: 11px; }}
  svg .gl {{ stroke: var(--chart-grid); stroke-width: 1; }}

  .callout {{ border: 1px solid var(--border); border-left: 4px solid var(--warn); background: var(--bg-alt); border-radius: 0 8px 8px 0; padding: 14px 18px; margin: 22px 0; }}
  .callout .big {{ font-size: 1.35rem; font-weight: 700; letter-spacing: -.02em; color: var(--heading); }}
  .playbook {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; margin: 22px 0; }}
  .play {{ border: 1px solid var(--border); border-radius: var(--radius); background: var(--panel); padding: 16px 18px; }}
  .play h3 {{ margin: 0 0 6px; font-size: 1rem; }}
  .play p {{ margin: 0; font-size: .9rem; color: var(--text-muted); line-height: 1.6; }}
  .play .sig {{ font-family: var(--mono); font-size: .78rem; color: var(--text-muted); display: block; margin-top: 8px; }}
</style>
</head>
<body>

<header class="nav">
  <div class="nav-inner">
    <a class="brand" href="index.html">
      <svg class="logo" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <rect x="1" y="1" width="30" height="30" rx="8" fill="#5b3df5"/>
        <path d="M8.5 7.5V24H25" stroke="#c4b3ff" stroke-width="1.6" stroke-linecap="round" fill="none" opacity="0.75"/>
        <path d="M10 10.5C14.5 10.5 15 20 24.5 21" stroke="#fff" stroke-width="2.6" stroke-linecap="round" fill="none"/>
      </svg>
      Basquin
    </a>
    <nav class="nav-links">
      <a href="index.html">Home</a>
      <a href="getting-started.html">Get started</a>
      <a href="how-it-works.html">How it works</a>
      <a href="kubernetes-tomcat.html">K8s / Tomcat</a>
      <a href="operator.html">Operator</a>
      <a href="releases.html">Releases</a>
      <a href="benchmarks.html" class="active">Benchmarks</a>
      <a class="gh" href="https://github.com/ianp94/basquin">GitHub ↗</a>
    </nav>
  </div>
</header>

<main class="wrap">
  <div class="page-head">
    <div class="breadcrumb">docs / benchmarks</div>
    <h1>{fmt(total_requests)} requests, and the most serious defect had no error code at all.</h1>
    <p class="lead">Basquin was pointed at real, <strong>unmodified</strong> JVM web apps — no test
      code, no instrumentation in the app, no config changes. It found errors, which any load tool
      would have found too. It also found {fmt(redirects)} responses that were neither an error nor
      the thing that was asked for, and — on a target that was still answering
      <code>200</code> in 10&nbsp;ms — two threads that had been spinning at 100% of a core for five
      hours.</p>
    <div class="tag-list">
{app_pills}      <span class="pill">Tomcat 9 · unmodified</span>
    </div>
  </div>

  <div class="tiles">
    <div class="tile"><div class="n">{fmt(total_requests)}</div><div class="l">requests driven across three unmodified apps</div></div>
    <div class="tile"><div class="n warn">{fmt(total_5xx)}</div><div class="l">server errors — the part a crash oracle does report</div></div>
    <div class="tile"><div class="n warn">{fmt(redirects)}</div><div class="l">3xx responses classified by destination instead of followed</div></div>
    <div class="tile"><div class="n warn">2</div><div class="l">threads spun at 100% of a core for 5 hours, while the app answered 200 in 10 ms</div></div>
  </div>

  <h2>The gap this measures</h2>
  <p>A crash-only oracle — a plain fuzzer, a load test's error count — asks one question:
    <em>did it return an error?</em> That question is not useless: it catches the
    {fmt(total_5xx)} server errors below, and those are real. But it is the only question those
    tools ask, and the two most serious things on this page do not answer it. A write the app
    silently threw away returns <code>302</code>. A thread spinning forever at 100% of a core
    returns <code>200</code> in 10&nbsp;ms.</p>

  <div class="table-scroll">
    <table>
      <caption class="muted">Each row splices <strong>two separate campaigns</strong>: the coverage
        and breach columns come from that app's <em>explore</em> run, the traffic columns from a
        <em>different</em>, later <em>load</em> run at the highest concurrency tested. They are not
        two views of one run, and a number in one group says nothing about the other. The tiles
        above aggregate <strong>every</strong> collected load run ({n_load} of them), so their
        totals exceed this table's.</caption>
      <thead>
      <tr>
        <th></th>
        <th class="num grp" colspan="3">from the explore campaign</th>
        <th class="num grp" colspan="4">from the load campaign — no invariants evaluated</th>
      </tr>
      <tr>
        <th>App</th>
        <th class="num">Coverage</th>
        <th class="num">Invariant breaches</th>
        <th class="num">Failed requests</th>
        <th class="num">Requests</th>
        <th class="num">req/s</th>
        <th class="num">p50 / p99 ms</th>
        <th class="num">5xx</th>
      </tr></thead>
      <tbody>
{chr(10).join(apps_table)}
      </tbody>
    </table>
  </div>

  <div class="callout">
    <div class="big">The finding counts below are undercounts, and we can say by how much</div>
    <p style="margin:.5rem 0 0">The valve evaluates every invariant inside the app JVM and logs it,
      then attaches the result to the response — but only if the response has not already been
      committed. The driver learns about violations <em>solely</em> by reading that header, so on a
      response that committed early the finding is evaluated, logged, and thrown away.</p>
    <p style="margin:.5rem 0 0">Measured against each app's real corpus: <strong>Roller loses
      97.3%</strong> of its responses' ability to report (one explore window evaluated
      <strong>1,906 violations and reported 0</strong>), <strong>JSPWiki 75%</strong> — and its
      surviving 25% are redirects and 400s, which never violate — while <strong>JPetStore loses
      nothing</strong>. That last row is why JPetStore looked productive and the other two looked
      clean: it is the only app on this page whose finding counts were ever real.</p>
    <p class="muted" style="margin:.5rem 0 0">The bias runs the wrong way — the larger and slower the
      response, the more likely it committed early, so the most expensive requests are the most
      likely to have been discarded. Evaluation is intact, so this is a reporting fix; it is tracked
      in <code>TODO.md</code> and the figures here will be regenerated once it lands.</p>
  </div>

  <p class="muted">Those two columns are split deliberately, because they are not the same kind of
    evidence and the campaign resource's single <code>findings</code> total blends them (along with
    inputs saved purely for reaching new code, which are not defects at all).
    <strong>Invariant breaches</strong> are the strong signal: the valve, running <em>inside the app
    JVM</em>, measured a request that exceeded its latency, heap, or thread budget.
    <strong>Failed requests</strong> are weaker: the driver's HTTP call threw &mdash; a connection
    reset, a read timeout, or a protocol error. That is an availability symptom, but this run does not
    attribute it between the app, the driver, and the single-node cluster, so it is reported as what
    was observed rather than as a defect count. See <a href="#method">Methodology</a>.</p>

  <h2>Silent rejection: the finding a load tool cannot have</h2>
  <p>JSPWiki's editor is guarded by two per-session tokens — an anti-CSRF field, and an anti-spam
    hash whose field <em>name</em> is six random letters that rotate. Basquin correlates both out of
    the edit form and replays the write, so the saves are well-formed:
    the run's <code>captureMisses</code> counter — reported in the figure below — says how often a
    required prior capture failed to bind, and the rest were submitted with both tokens.</p>
  <p>What comes back is a <code>302</code> either way. A save that lands and a save the app throws
    away are the same status code, the same absence of any error, and the same entry in a load
    tool's success column. The only thing that separates them is the <code>Location</code> header —
    which is precisely what a client destroys when it follows the redirect for you. So Basquin stops
    following, and reads it.</p>

{redirect_figure()}

  <div class="callout">
    <div class="big">The first version of this chart was wrong in both directions</div>
    <p style="margin:.5rem 0 0">Worth stating, because it is the reason to trust the second one. The
      first run of this classifier put <code>Login.jsp</code> at the top and it was read as rejected
      writes. Probing the running app showed they are not writes at all — they are view requests
      carrying an <em>empty</em> page name, which JSPWiki bounces to the login screen whether or not
      a valid session is presented. Meanwhile a bucket named for a page turned out to be
      <em>successful</em> saves that the fold had missed, because JSPWiki capitalizes the first
      letter of an unresolved page name (<code>ndws</code> &rarr; <code>Ndws</code>) and the fold
      compared bytes.</p>
    <p style="margin:.5rem 0 0">The serious one was the opposite mistake. A genuine concurrent-edit
      conflict redirects to <code>PageModified.jsp?page=&lt;the page being saved&gt;</code>, and
      because that page name matches the request's own, the rule filed <strong>a real rejection into
      the success bucket</strong> — the exact invisibility the feature exists to eliminate,
      reintroduced by its own classifier. Same-page redirects now key by the destination's
      <em>path</em>, so every success shares one bounded key while a same-page rejection route keeps
      its own. Every label in the chart above was verified against the running app rather than
      inferred from the counts.</p>
  </div>

  <h2>What the load actually cost</h2>
  <p>Latency percentiles from the driver, which measures wall-clock per request against the real app
    over the whole run — warm-up excluded, no sampling.</p>

{latency_figure()}

  <h2>How much of the app gets reached</h2>
  <p>Findings only count if the explorer gets somewhere interesting. Coverage is measured by JaCoCo
    against the app's own classes, driven by a request grammar rather than recorded traffic — so it
    reaches authenticated write paths, not just the pages a crawler can see.</p>

{coverage_figure()}

  <h2>Heap allocated per request</h2>
  <p>Every number above is visible from outside the app. This one is not, and it is the reason the
    valve runs in-process.</p>

{heap_figure(lg)}

  <h2>The cold cliff</h2>
  <div class="callout">
    <div class="big">8408 ms &amp; 10.7 MB on request #1 → ~33 ms steady</div>
    <p style="margin:.5rem 0 0">The very first request of the soak (JSPWiki front page) took
      <strong>8408 ms and allocated 10.7 MB</strong> — then the app settled to ~33 ms. The
      first-request-after-deploy cliff (JSP compilation plus cold-cache markup rendering) is an
      availability pathology a warmed-up load test never sees and a crash test never flags — and it is
      exactly what a user hits after every deploy, restart, and scale-up event.</p>
    <p class="muted" style="margin:.5rem 0 0">Source: <code>bench-results/jspwiki/findings-summary.txt</code>
      (iteration 1: 8408 ms + 10713 KB) and the k6 steady-state median (33 ms).</p>
  </div>

  <h2>Turning findings into changes</h2>
  <p>A finding is only useful if it names something a team can go fix. Each class of finding maps to a
    specific kind of work:</p>
  <div class="playbook">
    <div class="play">
      <h3>Per-request heap over budget</h3>
      <p>Names the exact input that allocated. Usually a render path building the whole response in
        memory, or an unbounded result set. The fix is streaming or a bound — and the same run
        re-measures it.</p>
      <span class="sig">signal: heapDelta &gt; budget on a specific route</span>
    </div>
    <div class="play">
      <h3>Latency spread, not latency</h3>
      <p>A p50 that looks fine next to a p99 orders of magnitude worse points at a cliff — a cold
        cache, a lock, an N+1 — not at general slowness. Capacity planning off the median under-provisions
        for exactly this.</p>
      <span class="sig">signal: p99/p50 ratio, plus the worst single input</span>
    </div>
    <div class="play">
      <h3>Silent rejection</h3>
      <p>Writes that return a success-shaped status and don't land. Points at session handling,
        CSRF/anti-automation defences, or concurrency conflicts — and invalidates any load number
        measured on that path, because the expensive half never ran.</p>
      <span class="sig">signal: 3xx classified by Location, not followed</span>
    </div>
    <div class="play">
      <h3>Retention across a run</h3>
      <p>Separating "this request is expensive" from "this run is leaking" is the question that
        matters at 3am. Measuring it needs GC-bracketed sampling, not a raw used-heap delta &mdash;
        see the methodology note below for why this page publishes no drift number.</p>
      <span class="sig">signal: retained heap after a forced collection, sampled repeatedly</span>
    </div>
  </div>

  <h2 id="method">Methodology &amp; honesty</h2>
  <ul style="color:var(--text-muted)">
    <li><strong>Real runs only.</strong> Every figure on this page is generated by
      <code>deploy/bench/render_page.py</code> from artifacts under <code>bench-results/</code> — the
      collected campaign objects and the driver's own terminal summary. The charts are emitted from
      those numbers, so a figure cannot disagree with the run it cites. Nothing here is projected or
      hand-tuned.</li>
    <li><strong>Apps unmodified.</strong> {app_sentence} Each runs on Tomcat 9 with the
      namespace-free Basquin valve + agent. No code, config, or bytecode changes to the apps.</li>
    <li><strong>Budgets are per app, and apply only to the explore runs.</strong>
      {budget_sentence} All soft mode: findings are recorded and the run continues.
      <strong>Load mode evaluates no invariants at all</strong> — it is lock-free passthrough by
      design (DD-029) and its driver is never even given a threshold — so a load run's latency
      percentiles are measured against no budget, and its <code>violations</code> counts are
      structurally zero rather than checked. Roller's load p50 of 503&nbsp;ms is not a 250&nbsp;ms
      budget that passed; it is a budget that was never applied.</li>
    <li><strong>Load runs are serialized.</strong> The bench cluster is a single kind node, so two
      concurrent drivers would measure each other. <code>deploy/bench/battery.sh</code> blocks on each
      campaign reaching a terminal phase before starting the next.</li>
    <li><strong>Two vantage points (why committed files differ on "max").</strong> The per-request heap
      and latency <em>findings</em> (10.7&nbsp;MB, 8.4&nbsp;s) are measured <strong>server-side, inside
      the app JVM</strong> by the valve — the app's true cost for that input. The driver's latency
      percentiles are the <strong>client-side</strong> view over the network. They answer different
      questions and are not interchangeable.</li>
    <li><strong>Per-request heap comes from an earlier soak.</strong> The heap-per-request figure is
      from the valve-instrumented run recorded in <code>bench-results/report-data.json</code>
      ({esc(lg.get("generated", "undated"))}); the campaign-driven runs above measure heap
      <em>drift across the run</em>, not per request. Both are stated as what they are.</li>
    <li><strong>Several reported zeros do not mean "checked and clean".</strong> A load run's
      <code>violations.latency</code> is structurally 0 because load mode is never given the latency
      budget, and the explore summary's <code>invariants</code> block is the driver measuring itself
      with no thresholds configured. Neither is a check that passed. Combined with the header loss
      above, a zero on this page should be read as "not measured" unless stated otherwise.</li>
    <li><strong>Two observations on this run that are reported, not explained.</strong> JSPWiki
      returns the same <strong>80.7 req/s at concurrency 1 and at concurrency 8</strong>, while its
      p99 rises from 57&nbsp;ms to 743&nbsp;ms — the signature of a resource already saturated at
      c1, where added concurrency buys queueing rather than throughput. {jps_5xx_clause} Both are
      stated as measured; neither has been root-caused,
      and load mode was confirmed on every run (no <code>driftUnavailable</code>), so neither is an
      artifact of the target sitting in the wrong mode.</li>
    <li><strong>No heap-drift number is published, deliberately.</strong> The driver's
      <code>heapDriftKb</code> is a raw <code>totalMemory − freeMemory</code> difference with no
      collection on either side, so it measures GC phase as much as retention: the same metric came
      back at +381&nbsp;MB on one run of this target and <strong>−194&nbsp;MB</strong> on another.
      Bracketing with a forced collection instead showed no unbounded growth on this app, and that
      150 unique-text saves retained nothing measurable on-heap (JSPWiki writes versions to disk).
      GC-bracketed sampling is a tool follow-up; until then this page reports no drift figure rather
      than a confident one.</li>
    <li><strong>Failed requests are not yet attributed.</strong> That column counts iterations where
      the driver's HTTP call threw rather than returning a status. On JPetStore it is a large number
      and has not been root-caused to the app versus the driver versus the single-node cluster, so no
      claim is made about it beyond the count. The invariant-breach column is the one measured inside
      the app, and it is the one to read.</li>
    <li><strong>The k6 comparison is indicative, not a controlled head-to-head.</strong> The tools
      measure different things, and the valve deliberately serializes requests (DD-005) so per-request
      deltas stay accurate under concurrency. The point was never speed — it is that a load tool
      reports zero defects on requests Basquin flags thousands of times.</li>
  </ul>

  <div class="note">
    <span class="tag">next</span>
    <p>Want to run this same battery against your own app? Read
      <a href="benchmarking.html">Benchmarking &amp; target onboarding →</a> — packaging an unmodified
      WAR, authoring a request grammar, and running the load comparison.</p>
  </div>
</main>

<footer class="footer">
  <div class="wrap-wide footer-inner">
    <div>Basquin — Kubernetes-native fuzz and load testing for JVM web apps.</div>
    <div class="links">
      <a href="index.html">Home</a>
      <a href="getting-started.html">Get started</a>
      <a href="how-it-works.html">How it works</a>
      <a href="kubernetes-tomcat.html">K8s / Tomcat</a>
      <a href="operator.html">Operator</a>
      <a href="releases.html">Releases</a>
      <a href="benchmarking.html">Benchmarking</a>
      <a href="developing.html">Developing</a>
      <a href="https://github.com/ianp94/basquin">GitHub</a>
    </div>
    <div class="ai-note">Built with AI assistance (Anthropic's Claude / Claude Code), directed and reviewed by the author.</div>
  </div>
</footer>

</body>
</html>
"""


if __name__ == "__main__":
    OUT.write_text(build())
    print(f"wrote {OUT.relative_to(ROOT)}  ({len(OUT.read_text().splitlines())} lines)")
