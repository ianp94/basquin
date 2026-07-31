# DD-044 §8.1 — what a native image does with the operator's injected `JAVA_TOOL_OPTIONS`

**The question.** DD-044's design argued that applying a `BasquinTarget` to a build-time-instrumented
**native** target is hazardous, because `buildAgentArgs` (`operator/internal/controller/injection.go:108-115`)
appends `-javaagent:`, `-Xbootclasspath/a:` and `-Dbasquin.boundary=agent` unconditionally. Two outcomes
were possible and the spec labelled the claim **inferred**: the binary silently ignores them, or it
**fails to start** — which would mean the current operator hard-breaks any native target the moment a
target CR is applied.

**Verdict: it starts, serves, and stays instrumented. The flags are inert on SubstrateVM.**

> **Read `rerun.md` for the evidence that actually supports this.** PR #103's approver found the first
> run's artifacts could not carry the claim: a 9-line startup log (`wc -l startup.log` → 9, not 8) shows
> nothing about whether `JAVA_TOOL_OPTIONS` was applied at all, and the flag string omitted
> **`-agentpath`** — which `threadTracker` defaults to `true`
> (`operator/api/v1alpha1/basquintarget_types.go:65-66`), so it is in the operator's *default* injection
> (`operator/internal/controller/injection.go:104-105`). The re-run captures
> `env | grep JAVA_TOOL_OPTIONS` as proof of application and includes `-agentpath`. Same outcome:
> HTTP 200, process alive, `basquin` in the banner. The conclusion held; the first attempt's evidence for
> it did not.
>
> **Neither run used the operator's byte-exact string, though.** `agentsMountPath` is `/basquin`, not
> `/basquin/agents` (`operator/internal/controller/injection.go:61`; the substrings the controller test
> asserts against — `operator/internal/controller/basquintarget_controller_test.go:148-149` — confirm
> there is no `/agents` segment). Both this README's original string below and `rerun.md`'s "corrected"
> one pointed `-agentpath`/`-javaagent` at `/basquin/agents/...`, a path the operator never constructs.
> This is corrected below. It does not change the verdict — the agent files are absent from this host
> under *either* path (see "What this does NOT establish"), and the property being measured is whether
> SubstrateVM tolerates `-agentpath`/`-javaagent` pointing at a file that is not there, which is
> insensitive to which nonexistent path string is used. But it does mean a run against the operator's
> literal byte-exact string has still never been executed — that gap stays open, not closed by this note.

## Result

The string `buildAgentArgs` actually builds for a target with `threadTracker` at its default
(`operator/internal/controller/injection.go:102-115`, mount path `operator/internal/controller/injection.go:61`,
default confirmed at `operator/api/v1alpha1/basquintarget_types.go:65-66`) is:

```
JAVA_TOOL_OPTIONS=-agentpath:/basquin/libbasquinjvmti.so -javaagent:/basquin/basquin-agent.jar -Xbootclasspath/a:/basquin/basquin-agent.jar -Dbasquin.boundary=agent
```

The run actually recorded below (`rerun.md`) used
`-agentpath:/basquin/agents/libbasquinjvmti.so -javaagent:/basquin/agents/basquin-agent.jar -Xbootclasspath/a:/basquin/agents/basquin-agent.jar -Dbasquin.boundary=agent`
instead — present and in the right order, but with an extra `/agents` segment the operator does not add.
See the callout above for why this does not undermine the result below, and why it is still an open gap.

| Check | Result |
|---|---|
| Process survives startup | **yes** |
| `GET /ok` | **HTTP 200** |
| Banner | `Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]` |
| Any warning/error about the options | **none logged** |

The binary is the one PR-3's native acceptance built:
`bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner`.

## What this changes in the design — the hazard is narrower than claimed, and differently located

DD-044 §1 called `-Dbasquin.boundary=agent` "actively wrong" for these targets. For a **native** target
that is **too strong**, and this measurement corrects it: the property is *set* but nothing reads it.
`basquin.boundary` is consumed only by `agent/BoundaryInstaller.java`, `agent/Agent.java` and
`agent/TomcatBoundaryAdvice.java` — the JVM agent. Neither `basquin-quarkus` nor `basquin-core` reads it
(verified by grep across both). On a native image the agent that would consult it is never loaded at all.

**But the hazard is real for a JVM-mode build-time-instrumented target, which is the case the spec
conflated with this one.** There `-javaagent` *does* load the agent, the agent *does* read
`basquin.boundary=agent`, and the result is **two** boundaries on one request path — the extension's filter
and the agent's ByteBuddy-installed one. That is a genuine double-instrumentation conflict, and it is the
case DD-044's guard must actually protect.

So the design still stands; its justification moves. The reason not to patch a pre-instrumented target is
**not** that native images break — they do not — it is (a) double instrumentation on JVM-mode
pre-instrumented targets, and (b) the status dishonesty of reporting `Injected` for an app the operator
did not modify, which is unaffected by this result and remains the primary motivation.

## What this does NOT establish

- Only the **fixture** was tested, and only in **native** mode. A JVM-mode pre-instrumented Quarkus app
  with the same options was **not** run, so the double-boundary conflict above is reasoned from which code
  reads the property, not measured end-to-end.
- The agent jar path does not exist on this host, so this measures "SubstrateVM ignores `-javaagent`
  pointing at an absent file". A **present** agent jar was not tested; SubstrateVM has no JVMTI attach, so
  the outcome should not differ, but that is inference.
- Nothing about the initContainer and volume the operator also adds — this covers only the JVM-opts append.
- The recorded run used `/basquin/agents/...` paths, not the operator's real `/basquin/...` (see the
  callout above and `rerun.md`). A rerun with the byte-exact string has not been executed.

## Reproduce

```bash
BIN=bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner
export JAVA_TOOL_OPTIONS="-agentpath:/basquin/libbasquinjvmti.so -javaagent:/basquin/basquin-agent.jar -Xbootclasspath/a:/basquin/basquin-agent.jar -Dbasquin.boundary=agent"
"$BIN" > /tmp/native-jto.log 2>&1 &
sleep 8
curl -sf -o /dev/null -w "/ok -> %{http_code}\n" http://localhost:8080/ok
grep -o "Installed features: \[[^]]*\]" /tmp/native-jto.log
kill %1
```
