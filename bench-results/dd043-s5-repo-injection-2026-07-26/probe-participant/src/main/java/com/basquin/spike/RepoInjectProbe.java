package com.basquin.spike;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.project.MavenProject;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * DD-043 spike S5 — descendant of S4's InjectProbe. Injects BOTH halves of what PR-3's
 * basquin-maven-injector must inject, with zero edits to any file in the application tree:
 *
 *   1. the dependency  com.basquin:basquin-quarkus:0.3.0
 *   2. a repository    id=basquin-injected, url from -Dbasquin.inject.repo.url
 *
 * The repository half is the part S4 never tested (S4 pre-installed the artifact into the
 * local repository instead). Two mutations are required for the repository to take effect,
 * and the second is the one that is easy to miss:
 *
 *   (a) Model level: p.getModel().getRepositories().add(repo). This is what "injecting a
 *       <repository>" naively means, but at afterProjectsRead time it is TOO LATE for this
 *       alone to do anything — the project's effective repository lists were already computed
 *       during project building (DefaultProjectBuildingHelper), before the participant ran.
 *   (b) Effective-list level: append a MavenArtifactRepository to
 *       p.getRemoteArtifactRepositories() and call p.setRemoteArtifactRepositories(...).
 *       Verified against maven-core 3.9.16 bytecode: that setter also refreshes the Aether
 *       list (remoteProjectRepositories = RepositoryUtils.toRepos(...)), which is what both
 *       Maven dependency resolution and the quarkus-maven-plugin's
 *       @Parameter("${project.remoteProjectRepositories}") actually consume.
 *
 * Mirror note: repositories appended here bypass Maven's mirror/proxy/auth injection, which
 * runs at project-building time. In particular the maven-default-http-blocker mirror
 * (mirrorOf external:http:*, present in the wrapper distribution's conf/settings.xml) is not
 * applied to this list by Maven itself. Whether Quarkus's bootstrap resolver re-applies
 * settings mirrors to the list it receives is part of what this spike measures.
 *
 * Dependency note: MavenProject.getDependencies() delegates to getModel().getDependencies()
 * (verified in 3.9.16 bytecode), so the dependency is added to the model list only — S4's
 * probe added to both, which double-added the same object to one list.
 *
 * As in S4 (see InjectProbe's comment): every model object (Dependency, Repository,
 * ArtifactRepository) is allocated fresh INSIDE the per-project loop. Maven model objects are
 * mutable; hoisting one allocation would alias a single instance across the whole reactor.
 */
@Named("basquin-repo-inject-probe")
@Singleton
public class RepoInjectProbe extends AbstractMavenLifecycleParticipant {

    @Override
    public void afterProjectsRead(MavenSession session) {
        String url = System.getProperty("basquin.inject.repo.url");
        if (url == null || url.isBlank()) {
            System.out.println("[REPO-INJECT] -Dbasquin.inject.repo.url not set; injecting nothing");
            return;
        }
        for (MavenProject p : session.getProjects()) {
            // -- 1. the dependency (fresh instance per project; see class comment) --
            Dependency d = new Dependency();
            d.setGroupId("com.basquin");
            d.setArtifactId("basquin-quarkus");
            d.setVersion("0.3.0");
            p.getModel().getDependencies().add(d);

            // -- 2a. the repository, model level --
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

            // -- 2b. the repository, effective-list level (the load-bearing half) --
            ArtifactRepository ar = new MavenArtifactRepository(
                    "basquin-injected",
                    url,
                    new DefaultRepositoryLayout(),
                    new ArtifactRepositoryPolicy(false,
                            ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY,
                            ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
                    new ArtifactRepositoryPolicy(true,
                            ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY,
                            ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN));
            List<ArtifactRepository> repos = new ArrayList<>(p.getRemoteArtifactRepositories());
            repos.add(ar);
            p.setRemoteArtifactRepositories(repos);

            System.out.println("[REPO-INJECT] added dependency com.basquin:basquin-quarkus:0.3.0"
                    + " and repository basquin-injected=" + url + " to " + p.getArtifactId());
        }
    }
}
