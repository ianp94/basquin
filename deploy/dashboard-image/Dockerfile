# The ClosureJVM dashboard image. The operator's campaign reconciler (docs/CAMPAIGN-DESIGN.md, DD-025,
# P5b) runs this as a per-campaign dashboard Deployment+Service: the standalone read-only DashboardServer
# (DD-013) that the driver Job pushes status/findings to (-Dclosurejvm.dashboard.push=<this Service>).
# It's owner-referenced to the campaign, so it's GC'd when the campaign CR is deleted.
#
# Bind 0.0.0.0 (the server defaults to 127.0.0.1 for safety when run on a laptop) so it's reachable
# through the ClusterIP Service; listens on the default port 7070.
#
# Contents (staged by build.sh from the Gradle `jar` task): the harness fat jar, which includes
# runner.util.DashboardServer + the dashboard UI assets.
FROM eclipse-temurin:17-jre
WORKDIR /closurejvm
COPY closurejvm.jar /closurejvm/closurejvm.jar
EXPOSE 7070
ENTRYPOINT ["java", "-Dclosurejvm.dashboard.bind=0.0.0.0", "-cp", "/closurejvm/closurejvm.jar", "runner.util.DashboardServer"]
