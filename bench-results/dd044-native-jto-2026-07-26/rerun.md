# Re-run 2026-07-26 — the first attempt's evidence did not support its claim

## Proof the environment variable was actually applied to this process
```
$ export JAVA_TOOL_OPTIONS="-agentpath:/basquin/agents/libbasquinjvmti.so -javaagent:/basquin/agents/basquin-agent.jar -Xbootclasspath/a:/basquin/agents/basquin-agent.jar -Dbasquin.boundary=agent"
$ env | grep JAVA_TOOL_OPTIONS
JAVA_TOOL_OPTIONS=-agentpath:/basquin/agents/libbasquinjvmti.so -javaagent:/basquin/agents/basquin-agent.jar -Xbootclasspath/a:/basquin/agents/basquin-agent.jar -Dbasquin.boundary=agent
```

Includes `-agentpath` this time: threadTracker defaults to **true**, so the operator's DEFAULT
injection contains it, and the first run omitted it entirely.

## Result
```
GET /ok -> HTTP 200
process alive after 9s: yes
Installed features: [basquin, cdi, rest, smallrye-context-propagation, vertx]
startup.log lines: 8
```
