# S3 — request boundary hook semantics

Question: does `addEndHandler` fire on errors, 3xx, and client disconnects, and
does `addHeadersEndHandler` survive a response rewrite (i.e. does the header it
writes actually reach the client)? JVM mode only, per Task 2's scope — no
native build was run.

## Method

`BoundaryProbe` (`fixture/src/main/java/com/basquin/spike/BoundaryProbe.java`)
registers a `Filters` observer at order 100 that, on every request:

- stamps a per-request id via `rc.put("basquin.id", id)`
- captures the request path at filter entry (`rc.request().path()`, before
  any handler can mutate it)
- registers `addHeadersEndHandler` to write `X-Basquin-Req: <id>` on the
  response
- registers `addEndHandler` to log `[PROBE] id=... path=... status=...
  succeeded=... cause=... ms=...` using the `AsyncResult`'s
  `succeeded()`/`cause()` — the `path` field is a review fix (see "Evidence
  regenerated with path-labelled output" below); `id`, `status`, `succeeded`,
  `cause`, `ms` are unchanged from the original probe.

Built via `env/build.sh package -DskipTests` (container JDK 25, host JDK
never touched — `env/build-jvm.log` confirms `javac ... release 25`; this
round's own build transcript is `build.log`). Run as a container from the
fixture's own `target/quarkus-app` with `--entrypoint java` (required —
the image's default `ENTRYPOINT` is `native-image`; see "Deviation from the
brief" below), startup banner captured as `startup.log`. `/ok`, `/boom`,
`/redirect` exercised with plain `curl -si`, `/slow` (5s sleep handler)
exercised with `curl -s --max-time 1` to force a client-side abort, followed
by a `sleep 6` before reading `docker logs` so the handler has time to
actually finish and fire `addEndHandler` before the log is captured.

Raw evidence: `curl.txt` (client-side view, headers only), `probe.log`
(server-side `[PROBE]` lines from `docker logs`, filtered), `build.log`
(Maven/Quarkus build transcript), and `startup.log` (container startup
banner) — all round 2 (current). Round 1's `curl.txt`/`probe.log` are
preserved as `curl-round1.txt`/`probe-round1.log`.

## Deviation from the brief (recorded, not worked around)

The brief's Step 2 `docker run` command (`java -jar /app/quarkus-run.jar` with
no entrypoint override) does not run the JVM under this image. The Mandrel
builder image's `ENTRYPOINT` is `native-image` (documented in Task 1's
`env/ENVIRONMENT.md`, deviation 1) — without `--entrypoint`, the words
`java -jar /app/quarkus-run.jar` are handed to `native-image` as CLI flags,
and the container actually starts a *native image build* of the fixture
instead of running the pre-built jar. First attempt failed with a
`native-image` `UnsupportedFeatureException` about `LogManager` heap
initialization, confirming this diagnosis (not a filter/registration
problem — ambiguity #4's warning didn't apply here since the failure mode is
visibly a native-image build log, not a Quarkus startup log). Fix used:
`--entrypoint java` on the `docker run`, then `-jar /app/quarkus-run.jar` as
the container command. With that fix, the container logs show the expected
banner and `Installed features: [cdi, rest, smallrye-context-propagation,
vertx]` line, matching the brief's expectation.

## Observed results

**Round 2 (current, path-labelled).** The `[PROBE]` line now carries the
request path captured at filter entry (`rc.request().path()`), so each row
below is cited straight from its own self-identifying log line — no inference
from curl ordering, id-matching, or timing is needed to know which line is
`/slow`.

| Route | HTTP status | `X-Basquin-Req` present? | `addEndHandler` fired? | `ar.succeeded()` |
|---|---|---|---|---|
| `/ok` | 200 | yes (`X-Basquin-Req: probe-8489103752045`, matches `path=/ok` row) | yes (`path=/ok status=200 succeeded=true`) | `true` |
| `/boom` | 500 | yes (`X-Basquin-Req: probe-8489192943266`, matches `path=/boom` row) | yes (`path=/boom status=500 succeeded=true`) | `true` |
| `/redirect` | 302 | yes (`X-Basquin-Req: probe-8489236492382`, matches `path=/redirect` row) | yes (`path=/redirect status=302 succeeded=true`) | `true` |
| `/slow` (client disconnect) | none received by client (`curl --max-time 1` aborted before any response line) | no (client never received a response) | yes, after a ~1s delay (`path=/slow status=200 succeeded=false`) | `false` (`cause=io.vertx.core.http.HttpClosedException: Connection was closed`) |

Corresponding `[PROBE]` log lines (from `s3-boundary/probe.log`, round 2):

```
[PROBE] id=probe-8489103752045 path=/ok status=200 succeeded=true cause=- ms=77
[PROBE] id=probe-8489192943266 path=/boom status=500 succeeded=true cause=- ms=31
[PROBE] id=probe-8489236492382 path=/redirect status=302 succeeded=true cause=- ms=22
[PROBE] id=probe-8489265923197 path=/slow status=200 succeeded=false cause=io.vertx.core.http.HttpClosedException: Connection was closed ms=1006
```

Note on the `/slow` row's status: `rc.response().getStatusCode()` reads `200`
at the time the end handler fires — that is Vert.x's default status code on
an `HttpServerResponse` that was never actually written to the wire, not a
claim that the client received a 200. The client (`curl.txt`) received
nothing at all before its own 1s timeout fired ("client aborted"); the
`succeeded=false`/`cause=HttpClosedException` pair is the reliable signal for
this disposition, not the status field. Previously this row's identity as
`/slow` was established only by inference (curl invocation order, id-matching
against `curl.txt`, and the ~1s timing); it is now stated directly in the log
line as `path=/slow`.

## Evidence regenerated with path-labelled output (review fix round 1)

A review of round 1's evidence found that the `/slow` row — the one row that
matters for the client-disconnect finding driving the DD-043 spec change —
was identified only by inference, not stated on its face. `BoundaryProbe.java`
was changed to capture the request path at filter entry
(`rc.request().path()`, before any handler can rewrite state) and include it
in the `[PROBE]` printf as `path=%s`, alongside the unchanged `id`, `status`,
`succeeded`, `cause`, and `ms` fields. The now-unused `RoutingContext` import
was also removed. The spike was then rebuilt and re-run end to end (fresh
`docker build`/`docker run`, fresh `curl` exercise of all four routes) to
produce round-2 evidence that is self-describing without cross-referencing
`curl.txt`. Round 1's `probe.log`/`curl.txt` are preserved unmodified as
`probe-round1.log`/`curl-round1.txt` for comparison; see "Round 1 vs round 2"
below — the two runs agree on every disposition, so this is a labelling fix,
not a correction of the underlying finding.

## Round 1 vs round 2 (flakiness check)

Round 2 was compared line-for-line against the preserved round-1 evidence
(`probe-round1.log`, `curl-round1.txt`), matching rows by response order
(round 1) against `path=` (round 2) since round 1 has no path field. Every
disposition agrees: same HTTP status per route, same `X-Basquin-Req`
presence, same `ar.succeeded()` value per route (`true, true, true, false`
for `/ok, /boom, /redirect, /slow` in both runs), and the same `cause`
(`io.vertx.core.http.HttpClosedException: Connection was closed`) on `/slow`
in both runs. `ms` values differ slightly between runs (e.g. `/ok`: 74ms round
1 vs 77ms round 2; `/slow`: 1005ms round 1 vs 1006ms round 2) but are the same
order of magnitude and not a disposition-changing difference — expected
run-to-run jitter, not flakiness. **No disagreement found between the two
runs.**

## Verdict: CONFIRMED

All four dispositions show `addEndHandler` firing (visible as `[PROBE]` lines
for all four request ids), and the `/slow` disconnect row shows
`succeeded=false` with a populated `cause`. `X-Basquin-Req` is present on all
three completed responses, including `/boom` — so §4.4's claim that writing
the id via the headers-end-handler path is "always safe" for completed
responses holds under this test. It does *not* extend to the disconnect case:
`/slow`'s `addHeadersEndHandler` still fired (the header would have been on
the response object), but that response object never reached the client, so
"the header is present" is not the same guarantee as "the header is
observable" for a disposition where the connection dies before the response
is flushed.

## Spec implication

§4.3/§6.5 can rely on `addEndHandler` + `ar.succeeded()` as the completion/
disconnect signal across all four dispositions tested (200, 500, 302,
mid-request disconnect) in JVM mode. Two things worth carrying into the spec
text:

1. `ar.succeeded()` — not the response status code — is the correct
   discriminator for "did this request actually complete." The status code
   read from `rc.response().getStatusCode()` inside the end handler is
   meaningless for a disposition where the response was never written (it
   reports whatever default/last-set value the `HttpServerResponse` object
   held, in this case `200`, for a request the client never saw resolve at
   all).
2. On disconnect there is a real gap between "the end handler fired" (~1s
   after the client gave up, bounded here by the server's own timeout/close
   detection, not by the client's `--max-time`) and "the client observed
   anything." Any latency metric keyed off `addEndHandler` timing for a
   disconnected request is measuring server-side detection latency, not
   client-observed latency — those are different numbers and should not be
   conflated in the eventual instrumentation.

This spike ran JVM mode only, per Task 2's scope; native-mode behavior of
`addEndHandler` is Task 3's question, not answered here.
