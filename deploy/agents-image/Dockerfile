# The ClosureJVM agents image. The operator's injected initContainer (docs/OPERATOR-DESIGN.md §4,
# DD-024) runs `sh -c "cp -r /agents/. /closurejvm/"` from this image into a shared emptyDir, so the
# image only needs a shell + cp (busybox) plus the agent artifacts under /agents/.
#
# Contents (staged by build.sh from the Gradle build):
#   closurejvm-agent.jar   -> -javaagent + -Xbootclasspath/a (the invariant/leak oracle)
#   jacocoagent.jar        -> -javaagent tcpserver (coverage-over-HTTP, DD-012/DD-023)
#   libclosurejvmti.so     -> -agentpath (native JVMTI thread/leak tracker, DD-004)  [linux/amd64]
#   closurejvm-valve.jar   -> Tomcat valve (server-side invariants; valve mounting is deferred, but
#                             the jar ships so P2.x can wire it without a new image)
FROM busybox:1.36
COPY agents/ /agents/
# Sanity: fail the build if any expected artifact is missing.
RUN test -f /agents/closurejvm-agent.jar \
 && test -f /agents/jacocoagent.jar \
 && test -f /agents/libclosurejvmti.so \
 && test -f /agents/closurejvm-valve.jar
