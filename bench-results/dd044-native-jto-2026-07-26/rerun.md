# Re-run 2026-07-26 — the first attempt's evidence did not support its claim

## Proof the environment variable was actually applied to this process
```
$ export JAVA_TOOL_OPTIONS="-agentpath:/basquin/agents/libbasquinjvmti.so -javaagent:/basquin/agents/basquin-agent.jar -Xbootclasspath/a:/basquin/agents/basquin-agent.jar -Dbasquin.boundary=agent"
$ env | grep JAVA_TOOL_OPTIONS
JAVA_TOOL_OPTIONS=-agentpath:/basquin/agents/libbasquinjvmti.so -javaagent:/basquin/agents/basquin-agent.jar -Xbootclasspath/a:/basquin/agents/basquin-agent.jar -Dbasquin.boundary=agent
```

Includes `-agentpath` this time: threadTracker defaults to **true**, so the operator's DEFAULT
injection contains it, and the first run omitted it entirely.

**Correction (PR #103 review round 4):** the string above still is not byte-exact. `agentsMountPath` is
`/basquin`, not `/basquin/agents` (`operator/internal/controller/injection.go:61`, confirmed by the
substring assertions at `operator/internal/controller/basquintarget_controller_test.go:148-149`). The
transcript above is left as actually captured; see `README.md`'s "Neither run used the operator's
byte-exact string" callout for why this does not change the result below (both paths are absent from
this host either way) and for the corrected string.

## Result
```
GET /ok -> HTTP 200
process alive after 9s: yes
Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]
startup.log lines: 9
```
