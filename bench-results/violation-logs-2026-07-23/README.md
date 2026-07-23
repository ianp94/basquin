# Recovered invariant violations, 2026-07-23

These are the violations the driver never saw.

The boundary evaluates every invariant inside the target JVM and logs it, then tries to attach
`X-Basquin-Invariant-Count` to the response — and the driver learns about violations *only* from
that header. On a response that had already committed, the header is skipped and the finding is
lost (see `TODO.md` and `../header-loss-2026-07-23/evidence.txt`). Evaluation was never the problem,
so the violations are all still here, in the target pods' own logs.

Snapshotted before the pods' logs rotate, because they are the only surviving copy.

| app | lines | heapDelta | latency |
|---|---|---|---|
| roller | 6143 | 5355 | 788 |
| jspwiki | 2880 | 2878 | 2 |
| jpetstore | 0 | — | — |

JPetStore's log has none left: its violations *were* reported through the header (it loses 0%), and
17 hours of traffic have since rotated the pod's log past them. Its counts in
`bench-results/jpetstore/` are the real ones and need no recovery.

Each line carries its iteration number:

```
[Basquin][Invariant] Iteration 1 violated 'latency': 719ms > 250ms
[Basquin][Invariant] Iteration 1 violated 'heapDelta': 28570KB > 512KB
```

so a campaign window can be re-scored per-input offline, without re-running anything. Roller's 788
latency violations are the ~530 ms searches its 250 ms budget was set to catch — the campaign that
produced them reported `{"latency":0,"heap":0,"thread":0}`.

Not a substitute for the fix (DD-040): the cost model consumes these numbers *during* a run to rank
the corpus, which is why Roller's retained corpus collapsed to 2 entries. Recovering them after the
fact restores the finding counts, not the exploration they should have guided.
