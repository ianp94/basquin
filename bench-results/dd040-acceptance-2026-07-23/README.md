# DD-040 acceptance run — Apache Roller, 2026-07-23 (FAILED, 11.8% residual)

Evidence for the `**Verified.**` block of DD-040 in `docs/DESIGN-DECISIONS.md`. Every number in
that block is derived here, from artifacts in this directory, with the exact command that produced
it. Before this directory existed the acceptance numbers were prose only — which is the same defect
class DD-040 exists to remove, one level up. Repo invariant 3: a figure has an artifact.

The run **did not pass** its acceptance criterion. These artifacts are committed so that the size
and the shape of the failure are checkable rather than asserted.

## The campaign

`roller-explore-dd040`, mode `explore`, 5m, single-replica `roller` target — spec as applied in
[`roller-explore-dd040/campaign.yaml`](roller-explore-dd040/campaign.yaml).

## The two numbers, and how each was counted

| | value | source |
|---|---|---|
| Reported violations (`targetViolations`) | **1,413** | `roller-explore-dd040/driver-summary.txt` |
| `reportMisses` | **0** | same line (`misses=0`) |
| Retained corpus | **99** (cap 1000) | `CoverageGuidedRun done: corpus=99` |
| Target-pod `[Basquin][Invariant]` lines, same window | **1,602** | `roller-explore-dd040/target-invariants-window.log` |
| **Gap** | **189 = 11.8% of 1,602** | 1602 − 1413 |

### The window

The driver container ran `started=2026-07-23T13:33:30Z` → `finished=2026-07-23T13:38:31Z`. The
window is that interval, **inclusive of both whole seconds** — i.e. a pod-log line counts when its
RFC3339 timestamp `ts` satisfies `13:33:30 <= ts < 13:38:32`.

The target pod (`roller-6569c6cfb-8qcsd`) was restarted immediately before the run and has
`restartCount: 0`, so its whole log is one process and the boundary is a timestamp comparison
rather than an assumption about what came before. Outside the window the pod carried **8** invariant
lines before it (from the 13:28:52Z start to 13:33:30Z) and **2** in the second immediately after
it; both sets are excluded by timestamp.

Reproduce the pod-side count from the committed slice:

```
$ wc -l roller-explore-dd040/target-invariants-window.log
1602
```

and from a raw pod log (this is how the slice was cut):

```
$ kubectl -n basquin-system logs <roller-pod> --timestamps \
    | awk '/\[Basquin\]\[Invariant\]/ { ts=substr($1,1,19);
           if (ts >= "2026-07-23T13:33:30" && ts < "2026-07-23T13:38:32") n++ } END {print n}'
1602
```

The slice was cut twice from two independent captures — the log snapshot taken at run time, and a
re-read of the same still-running pod ~2.5 h later — and the two byte-compare equal at 1,602 lines.

## Cross-checks that hold (the channel works; the residual is specific)

`roller-explore-dd040/findings-invariant-remote.tsv` is the driver's own saved findings, one row per
`Invariant-Remote` finding, with the route, the per-iteration violation `count`, and the first
`detail=` string.

**`reconcile.py` re-derives all of it, from these files only** — no cluster, no network — and exits
nonzero if any figure has drifted from what the DD record claims:

```
$ python3 bench-results/dd040-acceptance-2026-07-23/reconcile.py
ok   all 1235 reported detail= strings appear verbatim in the target log
  ok   reported                 = 1413
  ok   misses                   = 0
  ok   corpus                   = 99
  ok   pod_lines                = 1602
  ok   gap                      = 189
  ok   findings                 = 1235
  ok   finding_violations       = 1394
  ok   violating_iterations     = 1442
  ok   matched_iterations       = 1235
  ok   unmatched_iterations     = 207
  ok   unmatched_single_heap    = 206
  ok   unmatched_1_2mb          = 176

residual: 189/1602 = 11.8%
MISMATCHES: 0
```

What those lines mean:

- **1,235 findings**, `sum(count) = 1,394` — a snapshot taken ~10 s before the driver pod exited
  (its final count was 1,252 findings), so ≈1,413 extrapolated. The per-id side channel and the
  cumulative `/__basquin/violations` counter are the same number, independently read.
- **All 1,235 `detail=` strings appear verbatim** in `target-invariants-window.log`. Nothing
  reported was fabricated, rounded, or reworded.
- **Per-iteration shapes match one-for-one.** Reducing the pod log to (violation count, first
  violation) per iteration gives 1,442 violating iterations over 1,602 lines; 1,235 of them are
  accounted for by a finding with the same shape, including the iterations that violated twice.
- **The lost population's shape agrees with the root cause.** Of the 207 unmatched iterations
  (208 log lines), **206 are single `heapDelta` violations and 176 of those are in the 1–2 MB
  band** — cheap redirect targets, not big renders and not readiness probes.

The verbatim half is also checkable by hand; note the target log quotes the invariant name, so the
quote has to come out before comparing:

```
$ sed -E "s/.*violated '([^']+)': /\1: /" roller-explore-dd040/target-invariants-window.log | sort -u > /tmp/logged
$ cut -f4 roller-explore-dd040/findings-invariant-remote.tsv | tail -n +2 | sort -u > /tmp/reported
$ comm -23 /tmp/reported /tmp/logged        # reported but never logged
(no output)
```

## What is NOT recoverable

Recorded plainly rather than reconstructed:

- **The driver `summary.json` and the `BasquinCampaign` object.** The campaign was deleted as part
  of the run's cleanup, so `deploy/bench/collect.py` cannot be pointed at it: there is no campaign
  to `kubectl get` and no driver pod to read a terminal summary from. The status figures quoted in
  the DD record from the campaign object — `Completed`, coverage 25.2%, findings 1292, iterations
  1849, crashes 0, leaks 0 — survive only in the driver log's final status tick
  (`roller-explore-dd040/driver.log`, last `[Basquin] 00:05:00 iters=1849 …` line), which agrees
  with them; there is no independent second source for them and none was manufactured.
- **The live POST→302→GET reproduction** (the three-row probe table in the DD record) was done
  through an ad-hoc port-forward and produced no file. It is reproducible on demand but is not
  committed evidence; the DD record marks it as such.
- **The 200-polls-0-iterations perturbation check** likewise left no artifact.

## Redaction

`driver.log`, `driver-summary.txt`, `findings-invariant-remote.tsv` and
`target-invariants-window.log` were passed through `deploy/bench/collect.py`'s `_redact` — not a
second hand-rolled redactor — before being written here. The driver log's first line is the JVM
echoing every `-D` property it was given, including `-Dbasquin.dashboard.token=…`; it reads
`<redacted>` here. Corpus recipes (`${{salt}}`, `${{@nonce}}`, `${{csrf}}`) are deliberately left
intact: a substitution placeholder is DD-036 evidence, not a secret, and `deploy/bench/test_redact.py`
pins that distinction.

One string survives that looks credential-shaped and is not: the grammar's own
`POST /roller_j_security_check j_username=basquin&j_password=basquin` step. That is the bench
fixture's throwaway login, already committed on main in `examples/grammar/roller.grammar` and
created by `deploy/bench/roller/setup.sh`; it grants access to nothing but a disposable kind-local
Roller. Checked rather than assumed — `grep -iE 'jsessionid|authorization:|bearer|secret|api_?key'`
over all four artifacts is otherwise empty.
