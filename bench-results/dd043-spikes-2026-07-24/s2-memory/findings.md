# S2 — memory, GC, and monitoring behaviour under SubstrateVM

Question: does the heap-delta measurement primitive §6.1 needs — read
`Runtime.totalMemory()/freeMemory()` before and after a request, subtract —
behave sanely under SubstrateVM at all, and is a per-request delta
distinguishable from whatever the runtime does when nothing is happening?
This is the load-bearing question for whether the availability-defect heap
invariant can exist on native in any form.

## Method

`MemProbe` (`fixture/src/main/java/com/basquin/spike/MemProbe.java`, verbatim
from the brief) adds two routes to the existing fixture: `GET /mem/used`
returns `used,total,max` from `Runtime.getRuntime()`, and `GET /mem/gc`
returns `before,after` used-heap around a `System.gc()` call. The existing
`/ok`, `/boom`, `/redirect`, `/slow`, `/alloc` routes and `NeverCalled` were
not touched.

Built via `env/build.sh package -DskipTests -Dnative
-Dquarkus.native.monitoring=jfr,nmt` (container JDK 25 / Mandrel, host JDK
never touched). The resulting native binary
(`fixture/target/fixture-1.0.0-SNAPSHOT-runner`) was run directly on the host
per Task 3's established finding that these binaries have no loader problems
outside the container. Sequence: start the binary, `sleep 3`, one `curl
localhost:8080/mem/used` as a startup liveness check (visible as the single
`[PROBE] id=... path=/mem/used` line in `app.log`, timestamped before the
idle loop's first `sleep 1` — a minor procedural deviation from the brief's
literal script, called out here because it is one extra request against the
app before the idle series begins; it is 3 seconds before sample 1 and not
part of the 30-sample idle count), then the brief's Step 3 block executed
verbatim: 30 `/mem/used` polls at 1s spacing with no other traffic, one
`/alloc` bracketed by `/mem/used` before/after, 5 post-response `/mem/used`
polls, then `/mem/gc`. Raw output: `series.txt`. `pkill -f
fixture-1.0.0-SNAPSHOT-runner` afterward; `ss -ltnp | grep 8080` and `ps aux
| grep fixture-1.0.0` both confirm no stray process survived.

## Step 2 — does `quarkus.native.monitoring` accept `nmt`?

`-Dquarkus.native.monitoring=jfr,nmt` was tried first, per ambiguity #1.
**It worked on the first try** — `BUILD SUCCESS`, total Maven wall time 2:55,
`native-image` proper 47.4s (`build-native-nmt.log:104,108,110`). No config
error, no invalid-enum-value message anywhere in the log
(`grep -i "invalid\|unrecognized\|unknown"` → zero matches other than one
unrelated deprecation warning about `DynamicProxyConfigurationResources`,
line 33).

Confirmed the value actually reached the compiler, not just the Quarkus
config layer: the logged `native-image` invocation
(`build-native-nmt.log:32`) carries both `-J-Dquarkus.native.monitoring=jfr,nmt`
(the forwarded system property) **and** `--enable-monitoring=jfr,nmt,heapdump,threaddump`
(the actual native-image build flag Quarkus derived from it — `heapdump` and
`threaddump` are Quarkus defaults that ride along, not something this
invocation asked for separately). The build produced a working binary that
served requests correctly for the rest of this task, so `nmt` was accepted
at config-parse time *and* survived an actual native compile.

**The documented fallback (`-Dquarkus.native.monitoring=jfr` plus
`-Dquarkus.native.additional-build-args=--enable-monitoring=nmt`) was not
run** — per ambiguity #1, the fallback exists only for the case where the
first form fails, and this task should report which form works rather than
run every form regardless. It didn't fail, so the fallback build (another
~3 minutes of native compile) was skipped. `build-native-nmt-fallback.log`
does not exist for this reason.

## Step 3 — raw series

Full raw output: `series.txt`. All three `/mem/used` fields are
`used,total,max` in bytes; `total` and `max` were `13416005632` (≈12.495
GiB) on **every single sample across the whole run** — idle, before/after
`/alloc`, post-response, and both `/mem/gc` readings. Only `used` (`total −
free`) ever changed. The tables below extract `used` only.

### Idle series (30 samples, 1s apart, no other traffic)

| sample | used (bytes) | Δ from previous |
|---|---|---|
| 1–3 | 4,194,304 | — |
| 4–5 | 4,718,592 | +524,288 (at sample 4) |
| 6–22 | 5,242,880 | +524,288 (at sample 6) |
| 23–30 | 5,767,168 | +524,288 (at sample 23) |

29 gaps between 30 samples; only 3 are non-zero, and every non-zero jump is
**exactly 524,288 bytes (512 KiB)** — no partial or fractional jumps
anywhere in the series. This looks like SubstrateVM's heap-chunk/alignment
granularity showing up directly in `Runtime` accounting, not smooth
incremental growth.

### `/alloc` before/after

`before 5,767,168` → `after 10,485,760` — delta **4,718,592 bytes (4.5
MiB)**. `/alloc` itself allocates `64 × 64 KiB = 4,194,304` bytes
(`Probe.java`); the observed delta is exactly `4,194,304 + 524,288`, i.e. the
literal allocation plus **one more of the same 512 KiB quanta** seen in the
idle series — consistent with the same underlying granularity, not a
different mechanism.

### Post-response quiescence (5 samples after `/alloc`)

All 5 samples read `10,485,760` — flat, no further drift or decay in the 5
seconds after the response, i.e. the allocated arrays are simply retained
(they're still referenced by the fixture's request-scoped lifecycle in
SubstrateVM's default GC until the next collection) rather than being
reclaimed asynchronously.

### `System.gc()`

`before 10,485,760` → `after 3,670,016` — delta **−6,815,744 bytes (−6.5
MiB)**, a 65% reduction. Notably, the post-GC value (3,670,016) is **below
the pre-`/alloc` idle floor** (5,767,168, sample 30) by 2,097,152 bytes (2
MiB) — `System.gc()` didn't just undo the `/alloc` allocation, it also
reclaimed memory that had accumulated during the idle series itself. That
implies at least some of the idle "drift" documented above is transient
garbage (e.g. per-request handler objects, HTTP parsing buffers from the
`/mem/used` polls themselves), not permanently retained growth — a real GC
pass reclaims it.

## Answers to the brief's five questions

**1. Does `Runtime.totalMemory()/freeMemory()` return plausible, changing
values in native?** **CONFIRMED, with a caveat.** `used` (`total − free`)
tracked every event correctly and distinctly across the whole run: it rose
during idle polling (4,194,304 → 5,767,168, `series.txt` idle block), rose
sharply after `/alloc` (→ 10,485,760), stayed flat during quiescence, and
dropped after `System.gc()` (→ 3,670,016) — a fully plausible, monotonic-
where-expected signal. The caveat: `totalMemory()` itself never changed —
it equalled `maxMemory()` (13,416,005,632 bytes) on **every** sample in the
run, i.e. SubstrateVM reports the full reserved heap up front rather than a
currently-committed subset that grows the way HotSpot's `totalMemory()`
typically does. This differs from JVM-mode idiom but does not weaken the
signal the heap invariant actually needs, since that signal is `used`, not
`total`.

**2. Does the idle series drift, and by how much per minute?** **Yes, it
drifts, and it is not negligible.** Total drift over the 30-sample/29-second
window: 4,194,304 → 5,767,168 bytes = **1,572,864 bytes (1.5 MiB) in 29s**,
i.e. **≈54,237 bytes/s ⇒ ≈3,254,201 bytes/min ≈ 3.10 MiB/min (≈3.25 MB/min
decimal)**, extrapolating the observed rate. Structurally, this is not
smooth — it is three discrete 512 KiB jumps in 29 polls, not a continuous
climb. This drift is measured with `/mem/used` as the *only* traffic;
ambiguity #4 applies directly here — every sample in this series is itself
a request, so part of what's "drifting" is plausibly the cost of the HTTP
handling for the poll itself (consistent with the `System.gc()` finding
above, that some of this growth is reclaimable garbage, not a leak).

**3. Does `/alloc` produce a delta distinguishable from that floor?**
**CONFIRMED distinguishable, for this allocation size specifically.**
`/alloc`'s 4,718,592-byte delta is **9× the largest single-poll jump
observed anywhere in the 29-gap idle series** (524,288 bytes) and **3× the
idle series' entire 29-second cumulative drift** (1,572,864 bytes). Under
Basquin's actual measurement model — one request in flight at a time via
driver-side serialization, not a 1-minute aggregate window — the relevant
comparison is per-request-interval drift, not per-minute drift: over a
~1s inter-request gap the idle floor contributes on average ~54,237 bytes
and, in the worst observed case, up to one 524,288-byte quantum — still an
order of magnitude below `/alloc`'s signal. **The caveat that matters for
§6.1:** the noise floor here is quantized in 512 KiB steps, not smooth. A
request that allocates less than roughly one quantum (≲512 KiB) cannot be
reliably told apart from a single incidental idle-heap-growth event landing
inside its measurement window, because that's the same size as the noise
itself. This spike only tested one allocation size (~4.5 MiB observed); it
does not establish a lower bound on detectable deltas, only that this
particular fixture's `/alloc` — deliberately several times the quantum size
— clears the floor with room to spare.

**4. Does `System.gc()` reduce used heap?** **CONFIRMED.** 10,485,760 →
3,670,016 bytes, a 65% reduction, and notably below even the pre-`/alloc`
idle-floor reading (see "System.gc()" above) — meaning it also reclaimed
some of the idle drift, not just the deliberate allocation. `System.gc()`
is a real, effective collection under SubstrateVM in this build, not a
no-op — **DD-002's `gcBeforeMeasure` is usable on native.** One implication
worth carrying forward: if `System.gc()` reclaims idle drift as readily as
it reclaims deliberate allocation, calling it both immediately before *and*
immediately after a request's measurement window (rather than only before)
could substantially shrink the effective noise floor found in answer 2 —
untested here, but a direct, cheap follow-up suggested by this data.

**5. Which monitoring-flag form works?** `-Dquarkus.native.monitoring=jfr,nmt`
— the primary form, not the fallback. See "Step 2" above.

## Deferred (per the brief's scope limit)

Spec §7.1 also asks whether **post-response work quiesces on Hibernate
Reactive**. This fixture is a plain REST app with no database, so it cannot
answer that — connection-pool maintenance, the idle reaper, and deferred
`Uni` continuations only exist once a reactive datasource is present. This
is recorded here as **DEFERRED** to the `rest-heroes` JVM-mode cell in
PR-2, not as answered. The 5-sample post-response quiescence measured above
(flat at 10,485,760, no decay) says only that *this* fixture's heap doesn't
do anything interesting in the 5 seconds after a response with no
datasource involved — it says nothing about the polluter §6.1 actually
worries about.

## Verdict: CONFIRMED (scoped)

The memory-measurement primitives §6.1 depends on are real and usable under
SubstrateVM: `Runtime`'s `used` figure responds correctly to allocation,
idle background activity, and `System.gc()`; `System.gc()` performs a real
collection; and `-Dquarkus.native.monitoring=jfr,nmt` is accepted directly
by Quarkus 3.37.3 with no fallback needed. For this spike's specific
`/alloc` probe (~4.5 MiB), the per-request delta is unambiguously
distinguishable from the measured idle floor (9× the largest single noise
jump, 3× the total idle drift over the observation window).

This is **not** an unscoped CONFIRMED of §6.1's general heap invariant,
and the spec should not read it as one. Two findings narrow it:

1. **Idle drift is real and non-trivial** (≈3.1 MiB/min extrapolated,
   though it manifests as discrete 512 KiB jumps, not a smooth climb) — a
   heap-delta invariant on native cannot assume a flat, driftless floor
   the way Tomcat's lock-serialized JVM-mode measurement effectively can;
   it needs either a documented minimum-detectable-delta threshold or a
   drift-subtraction step (candidate: GC-before-and-after, see answer 4).
2. **The noise floor is quantized (~512 KiB here), not continuous.** Any
   request whose true allocation is smaller than roughly one quantum is,
   by this evidence, not reliably distinguishable from background heap
   growth — this spike did not test small allocations and cannot rule out
   that this threshold is common for real endpoints. §6.1 should state an
   explicit minimum-delta floor for native rather than presenting the
   invariant as applicable to requests of any size, the way it can be on
   Tomcat.

## Spec implication

§6.1's heap invariant is **viable on native for requests whose true
allocation clears the observed noise floor** (empirically ~512 KiB–1 MiB
here, on a database-free fixture) but is **not validated, by this spike,
for smaller per-request deltas** — that is a real gap, not a hedge. The
spec should either (a) scope the native heap invariant to a documented
minimum-delta threshold rather than treating it as universally applicable,
or (b) specify a drift-mitigation step (GC immediately before and after
the measurement window is the cheapest candidate this data suggests) before
claiming the invariant holds generally. The Hibernate Reactive
post-response question remains open per the DEFERRED note above — a real
datasource could plausibly push the noise floor higher than this
zero-dependency fixture shows, which would only tighten this caveat, not
loosen it.
