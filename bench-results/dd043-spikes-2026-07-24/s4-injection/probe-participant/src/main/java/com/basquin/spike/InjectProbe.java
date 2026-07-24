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
    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject p : session.getProjects()) {
            Dependency d = new Dependency();
            d.setGroupId("io.quarkus");
            d.setArtifactId("quarkus-smallrye-openapi");
            d.setVersion("3.37.3");
            p.getModel().getDependencies().add(d);
            p.getDependencies().add(d);
            System.out.println("[INJECT-PROBE] added quarkus-smallrye-openapi to " + p.getArtifactId());
        }
    }
}
