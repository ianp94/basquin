package com.basquin.spike;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.project.MavenProject;

import javax.inject.Named;
import javax.inject.Singleton;

/**
 * DD-043 S2 (round-5 review) — the control cell S5 never ran.
 *
 * S5's README/findings claimed "a Model-level `<repository>` injection alone is a no-op" and
 * three places in the PR (BasquinInjector.java, docs/ROADMAP.md, the PR description) call that
 * "measured by spike S5". It was not: {@code RepoInjectProbe} applies both mutations
 * unconditionally, so no build was ever captured with only the model-level half applied.
 *
 * This class is the missing control: it performs step 1 (inject the dependency) and step 2a
 * (add the {@code Repository} to {@code p.getModel().getRepositories()}) from
 * {@code RepoInjectProbe}, verbatim, but deliberately OMITS step 2b
 * ({@code p.setRemoteArtifactRepositories(...)}) — the "effective-list" mutation S5 claimed was
 * load-bearing. Gated on a distinct system property so it can sit on the same ext classpath as
 * {@code RepoInjectProbe} without both firing on the same run: set
 * {@code -Dbasquin.inject.repo.control.url=...} (NOT {@code -Dbasquin.inject.repo.url}) to
 * activate this class instead of the treatment probe.
 *
 * Expected result if S5's claim is true: the build fails to resolve
 * {@code com.basquin:basquin-quarkus} (local repo purged of it, Central 404s, and the injected
 * repository is absent from the project's effective remote-repository list because only the
 * model list was touched) — a resolution failure, captured verbatim, not inferred from
 * maven-core bytecode.
 */
@Named("basquin-repo-inject-probe-model-only")
@Singleton
public class ModelOnlyRepoInjectProbe extends AbstractMavenLifecycleParticipant {

    @Override
    public void afterProjectsRead(MavenSession session) {
        String url = System.getProperty("basquin.inject.repo.control.url");
        if (url == null || url.isBlank()) {
            System.out.println("[REPO-INJECT-CONTROL] -Dbasquin.inject.repo.control.url not set; injecting nothing");
            return;
        }
        for (MavenProject p : session.getProjects()) {
            // -- 1. the dependency (same as RepoInjectProbe) --
            Dependency d = new Dependency();
            d.setGroupId("com.basquin");
            d.setArtifactId("basquin-quarkus");
            d.setVersion("0.3.0");
            p.getModel().getDependencies().add(d);

            // -- 2a. the repository, model level (same as RepoInjectProbe) --
            Repository r = new Repository();
            r.setId("basquin-injected");
            r.setUrl(url);
            RepositoryPolicy releases = new RepositoryPolicy();
            releases.setEnabled(true);
            RepositoryPolicy snapshots = new RepositoryPolicy();
            snapshots.setEnabled(false);
            r.setReleases(releases);
            r.setSnapshots(snapshots);
            p.getModel().getRepositories().add(r);

            // -- 2b. DELIBERATELY OMITTED. This is the control: no
            //    p.setRemoteArtifactRepositories(...) call, so the project's effective
            //    remote-repository list (what Maven resolution and the quarkus-maven-plugin's
            //    @Parameter("${project.remoteProjectRepositories}") actually consume) never
            //    learns about "basquin-injected".

            System.out.println("[REPO-INJECT-CONTROL] added dependency com.basquin:basquin-quarkus:0.3.0"
                    + " and repository basquin-injected=" + url + " to the MODEL ONLY (2b omitted) for "
                    + p.getArtifactId());
        }
    }
}
