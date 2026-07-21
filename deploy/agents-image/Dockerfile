# The Basquin agents image. The operator's injected initContainer (docs/OPERATOR-DESIGN.md §4,
# DD-024) runs `sh -c "cp -r /agents/. /basquin/"` from this image into a shared emptyDir, so the
# final image only needs a shell + cp (busybox) plus the agent artifacts under /agents/.
#
# MULTI-ARCH (DD-027): the only arch-specific artifact is the native JVMTI agent (libbasquinjvmti.so);
# the jars and the busybox base are arch-independent. Stage 1 compiles the .so for the *target*
# platform, so `docker buildx --platform linux/amd64,linux/arm64` produces a correct .so per arch
# (natively on amd64, QEMU-emulated on arm64) from the same portable C source — no cross-toolchain.
#
# Contents (jars staged by build.sh from the Gradle build into agents/; .so compiled in stage 1):
#   basquin-agent.jar   -> -javaagent + -Xbootclasspath/a (the invariant/leak oracle)
#   jacocoagent.jar        -> -javaagent tcpserver (coverage-over-HTTP, DD-012/DD-023)
#   libbasquinjvmti.so     -> -agentpath (native JVMTI thread/leak tracker, DD-004)  [per-arch]
#   basquin-valve.jar   -> Tomcat valve (server-side invariants; valve mounting is deferred, but
#                             the jar ships so P2.x can wire it without a new image)

# Stage 1: compile the native JVMTI agent for the TARGET platform. A JDK (not JRE) base is required
# for jvmti.h/jni.h; temurin ships no C toolchain, so install build-essential (gcc + libc6-dev + make
# — plain gcc alone lacks the libc headers, e.g. stdio.h). The Makefile derives JAVA_HOME from `java`
# on PATH and writes ../build/native/libbasquinjvmti.so.
FROM eclipse-temurin:17-jdk AS native
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/*
COPY native/ /src/native/
RUN cd /src/native && make CC=gcc && test -f /src/build/native/libbasquinjvmti.so

# Stage 2: the shipped image — busybox + the arch-independent jars (staged by build.sh) + the
# per-arch .so from stage 1.
FROM busybox:1.36
COPY agents/ /agents/
COPY --from=native /src/build/native/libbasquinjvmti.so /agents/libbasquinjvmti.so
# Sanity: fail the build if any expected artifact is missing.
RUN test -f /agents/basquin-agent.jar \
 && test -f /agents/jacocoagent.jar \
 && test -f /agents/libbasquinjvmti.so \
 && test -f /agents/basquin-valve.jar
