# DD-039 acceptance run — Apache Roller, 2026-07-23 (PASSED)

Evidence for the `**Verified.**` / Task-7 block of DD-039 in `docs/DESIGN-DECISIONS.md`. Every number
in that block is derived here, from the artifacts in this directory, with the command that produced
it. This is the acceptance run DD-039's record was explicitly gated on: no unit test can prove that
carrying the session across a method-rewritten redirect closes the gap on a *real* session-rotating
app. Repo invariant 3: a figure has an artifact.

The run **passed** both acceptance criteria. Mirrors the structure of the DD-040 acceptance dir next
to it (which failed at 11.8%), so the two are diff-able.

## The campaign

`roller-explore-dd039`, mode `explore`, 5m, single-replica `roller` target instrumented with
`basquin/agents:0.2.0-dd039`, driver `basquin/runner:0.2.0-dd039`, both built from
`feat/dd039-session-carry` (`75c4103`). Spec as applied in
[`roller-explore-dd039/campaign.yaml`](roller-explore-dd039/campaign.yaml).

## Criterion 1 — a DB row is written by `login_publish` (the criterion no unit test can prove)

Roller's publish path requires an authenticated session, and Spring Security rotates the `JSESSIONID`
on the login's 302 — the exact POST→302→GET case DD-039 fixes. Before DD-039, `login_publish` had
**never** written a row: the login 302's `Set-Cookie` was eaten, so every publish ran anonymous and
was bounced by the salt interceptor.

| | value | source |
|---|---|---|
| `weblogentry` rows written in-window (`updatetime >= 20:02:13Z`) | **84** | `roller-explore-dd039/db-login_publish-rows.txt` |
| distinct `id` / distinct `anchor` | **84 / 84** | same |
| `creator` | **basquin** (authenticated, single) | same |
| pre-run baseline (`Fuzz %`, before the window) | **1** (from 09:05Z, unrelated) | queried before the run |

Each row carries a distinct per-fire `<nonce>` (DD-038):
`Fuzz 1784836936291xroller-explore-dd039-driver-n68l6x1-0`, `…-2`, … — 84 distinct authenticated
publishes spanning `20:02:17Z → 20:07:10Z`. **84 > 0 ⇒ the cookie carry works.**

## Criterion 2 — reported violations match the pod log for the same window

| | value | source |
|---|---|---|
| Reported violations (`targetViolations`, `/__basquin/violations` delta) | **1,656** | `roller-explore-dd039/driver-summary.txt` |
| Target-pod `[Basquin][Invariant]` lines, same window | **1,704** | `roller-explore-dd039/target-invariants-window.log` |
| **Raw gap** | **48 = 2.8% of 1,704** | 1704 − 1656 |
| measured kubelet-probe violations in-window (idle rate ~1.8/min × 5m) | **~8–10** | idle slices, below |
| residual after probe subtraction | **~38** | |
| `reportMisses` | **0** | driver-summary / terminal JSON |
| `crossOriginRedirects` | **not committed as a counter** — inferred 0 (see note) | — |
| Retained corpus (cost-ranked) | **81** (cap 1000; not degenerate) | `CoverageGuidedRun done: corpus=81` |

> `crossOriginRedirects` is not exposed in the summary JSON and its run-time log line only prints when
> the counter is **non-zero** (`CoverageGuidedRun` prints nothing at 0), so its absence from the
> committed `driver-summary.txt` is consistent with — but not proof of — 0. What the committed
> artifacts DO show rules out the degradation this counter exists to flag (every redirect refused →
> DD-039 silently reduced to pre-DD-039 behaviour): `reportMisses=0` and 30.4% coverage over a
> session-carrying run mean redirects were followed, not refused. Treat `crossOriginRedirects=0` as an
> indirect inference, not a committed figure.

**DD-040's gap was 189 (11.8%); DD-039's is 48 (2.8%)** — a 75% reduction in the raw gap. Re-stamping
every hop with `X-Basquin-Req` (and `Cookie`) means the target now *counts* the redirect-hop
violations it previously logged-but-dropped, so the reported count rose to nearly match the log.

### The window

Driver container `startedAt=2026-07-23T20:02:16Z → finishedAt=20:07:16Z`. The window is
`20:02:16 <= ts < 20:07:17`. The target pod (`roller-6dbfd8b9c6-ccmbf`) was rolled fresh for the new
agent and has `restartCount: 0`, so its whole log is one process and the boundary is a timestamp
comparison, not an assumption. Outside the window it carried **4** lines before (bootstrap warm-up)
and **5** after (post-run idle) — both excluded by timestamp.

Reproduce the pod-side count from the committed slice:

```
$ wc -l roller-explore-dd039/target-invariants-window.log
1704
```

### Probe noise was measured, not assumed

The kubelet probe hits `GET /` (unstamped by design), so its violations appear in the pod log and
never in `targetViolations`. Its idle **violation** rate (not request rate) was read from the live
log tail after the campaign ended:

```
20:07:52.646  heapDelta 4313KB
20:08:27.646  heapDelta 4285KB
20:09:02.645  heapDelta 4446KB
20:09:37.645  heapDelta 4275KB     -> ~1 every 35s (~1.7/min), fixed phase .645
```

**These four tail lines are post-window and are NOT committed to this directory** (the committed
`target-invariants-window.log` is window-only), so this breakdown is an estimate, not a checkable
figure — `reconcile.py` deliberately does not assert it. The only asserted, re-derivable gap figure is
the raw **48 (2.8%)**. Taking the estimate at face value, ~8–10 of the 48 would be probe noise, leaving
a ~38 residual the same shape as DD-040's but an order of magnitude smaller; even the raw 48, with no
probe subtraction at all, is already far below DD-040's 189. (The `~12/min` figure quoted at run time
is JSPWiki's request rate, not Roller's violation rate.)

## Reproduce

`python3 bench-results/dd039-acceptance-2026-07-23/reconcile.py` re-derives every figure above from
the files in this directory only — no cluster, no network — and exits nonzero if any has drifted from
what the DD-039 record claims.

## What is NOT recoverable

- The `BasquinCampaign` object and driver `summary.json`: the campaign was deleted in the run's
  cleanup (single-node cluster hygiene), so the status figures survive only in the driver log's final
  ticks and the pod's terminal message (`driver-terminal.json`), which agree with them.
- The live login-302 reproduction was an ad-hoc in-pod `curl` (302 + rotated `Set-Cookie` + `Location: /`);
  reproducible on demand, not committed as a file.

## Redaction

`driver-summary.txt` and `driver-terminal.json` were passed through `deploy/bench/collect.py`'s
`_redact` (the same redactor, not a hand-rolled one). Corpus recipes (`${{@nonce}}`, `${{salt}}`) are
left intact — a substitution placeholder is DD-036 evidence, not a secret. The grammar's throwaway
bench login (`j_username=basquin&j_password=basquin`) grants access to nothing but a disposable
kind-local Roller and is already on `main`.
