# The Basquin runner image. The operator's campaign driver Job (docs/CAMPAIGN-DESIGN.md, DD-025)
# runs this as the "driver" container: it executes the coverage-guided runner against an instrumented
# target, reads coverage from the target's headless coverage Service (P3), and writes its end-of-run
# summary to /dev/termination-log for the operator to read back into campaign status.
#
# All runtime configuration arrives via JAVA_TOOL_OPTIONS (set by the operator on the driver
# container) — baseURL, coverage endpoint, coverage classes dir, duration/summary/status props. The
# optional positional arg is the iteration count.
#
# Contents (staged by build.sh from the Gradle `runnerJar` task):
#   basquin-runner.jar  -> the coverage source set + main + JaCoCo, Main-Class CoverageGuidedRun
FROM eclipse-temurin:17-jre
WORKDIR /basquin
COPY basquin-runner.jar /basquin/basquin-runner.jar
# JAVA_TOOL_OPTIONS (props) is applied by the JVM automatically; any args become the iteration count.
ENTRYPOINT ["java", "-jar", "/basquin/basquin-runner.jar"]
