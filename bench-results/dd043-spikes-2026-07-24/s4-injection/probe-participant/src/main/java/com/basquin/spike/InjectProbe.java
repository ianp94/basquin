package com.basquin.spike;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import javax.inject.Named;
import javax.inject.Singleton;

@Named("basquin-inject-probe")
@Singleton
public class InjectProbe extends AbstractMavenLifecycleParticipant {
    // Addendum (DD-043 Task 3 follow-up): -Dbasquin.inject.local=true switches
    // the injected dependency from an already-published Central artifact to
    // com.basquin.spike.localonly:local-probe-dep:1.0, which exists only in
    // this spike's local repository (installed via `mvn install:install-file`,
    // never published anywhere). Default (property unset) is byte-identical
    // to Task 3's original behaviour, so Task 3's committed logs remain
    // reproducible by re-running its exact documented commands.
    @Override
    public void afterProjectsRead(MavenSession session) {
        boolean local = Boolean.getBoolean("basquin.inject.local");
        Dependency d = new Dependency();
        String logArtifactId;
        if (local) {
            d.setGroupId("com.basquin.spike.localonly");
            d.setArtifactId("local-probe-dep");
            d.setVersion("1.0");
            logArtifactId = "local-probe-dep";
        } else {
            d.setGroupId("io.quarkus");
            d.setArtifactId("quarkus-smallrye-openapi");
            d.setVersion("3.37.3");
            logArtifactId = "quarkus-smallrye-openapi";
        }
        for (MavenProject p : session.getProjects()) {
            p.getModel().getDependencies().add(d);
            p.getDependencies().add(d);
            System.out.println("[INJECT-PROBE] added " + logArtifactId + " to " + p.getArtifactId());
        }
    }
}
