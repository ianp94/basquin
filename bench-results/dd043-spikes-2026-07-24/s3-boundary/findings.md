# S3 — request boundary hook semantics

Question: does `addEndHandler` fire on errors, 3xx, and client disconnects, and
does `addHeadersEndHandler` survive a response rewrite (i.e. does the header it
writes actually reach the client)? JVM mode only, per Task 2's scope — no
native build was run.

## Method

`BoundaryProbe` (`fixture/src/main/java/com/basquin/spike/BoundaryProbe.java`)
registers a `Filters` observer at order 100 that, on every request:

- stamps a per-request id via `rc.put("basquin.id", id)`
- registers `addHeadersEndHandler` to write `X-Basquin-Req: <id>` on the
  response
- registers `addEndHandler` to log `[PROBE] id=... status=... succeeded=...
  cause=... ms=...` using the `AsyncResult`'s `succeeded()`/`cause()`

Built via `env/build.sh package -DskipTests` (container JDK 25, host JDK
never touched — `env/build-jvm.log` confirms `javac ... release 25`). Run as
a container from the fixture's own `target/quarkus-app`, `/ok`, `/boom`,
`/redirect` exercised with plain `curl -si`, `/slow` (5s sleep handler)
exercised with `curl -s --max-time 1` to force a client-side abort, followed
by a `sleep 6` before reading `docker logs` so the handler has time to
actually finish and fire `addEndHandler` before the log is captured.

Raw evidence: `curl.txt` (client-side view, headers only) and `probe.log`
(server-side `[PROBE]` lines from `docker logs`, filtered).

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

| Route | HTTP status | `X-Basquin-Req` present? | `addEndHandler` fired? | `ar.succeeded()` |
|---|---|---|---|---|
| `/ok` | 200 | yes (`X-Basquin-Req: probe-7604604051974`) | yes | `true` |
| `/boom` | 500 | yes (`X-Basquin-Req: probe-7604690294044`) | yes | `true` |
| `/redirect` | 302 | yes (`X-Basquin-Req: probe-7604736827795`) | yes | `true` |
| `/slow` (client disconnect) | none received by client (`curl --max-time 1` aborted before any response line) | no (client never received a response) | yes, after a ~1s delay | `false` (`cause=io.vertx.core.http.HttpClosedException: Connection was closed`) |

Corresponding `[PROBE]` log lines (from `probe.log`):

```
[PROBE] id=probe-7604604051974 status=200 succeeded=true cause=- ms=74
[PROBE] id=probe-7604690294044 status=500 succeeded=true cause=- ms=34
[PROBE] id=probe-7604736827795 status=302 succeeded=true cause=- ms=28
[PROBE] id=probe-7604772373312 status=200 succeeded=false cause=io.vertx.core.http.HttpClosedException: Connection was closed ms=1005
```

Note on the `/slow` row's status: `rc.response().getStatusCode()` reads `200`
at the time the end handler fires — that is Vert.x's default status code on
an `HttpServerResponse` that was never actually written to the wire, not a
claim that the client received a 200. The client (`curl.txt`) received
nothing at all before its own 1s timeout fired ("client aborted"); the
`succeeded=false`/`cause=HttpClosedException` pair is the reliable signal for
this disposition, not the status field.

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
