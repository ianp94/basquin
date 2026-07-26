package com.basquin.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.project.MavenProject;

/**
 * DD-043 PR-3 — adds the Basquin Quarkus extension to a target application's build without editing
 * a single file in the application tree.
 *
 * <p>Activated by putting this jar on {@code -Dmaven.ext.class.path}. Maven loads it as a core
 * extension and calls {@link #afterProjectsRead} once, before per-project execution plans are
 * computed.
 *
 * <p><b>Discovery depends on {@code src/main/resources/META-INF/sisu/javax.inject.Named}</b>, which
 * must name this class. Maven's container finds components on {@code maven.ext.class.path} through
 * that index rather than by scanning for {@code @Named}; both measured spike probes carry one
 * (S4 findings, Method section). Delete it and this participant silently never runs — the build
 * still succeeds and the application ships uninstrumented.
 *
 * <p><b>Two injections, and the second has a trap.</b> Adding the {@code <dependency>} is the
 * obvious half and was measured by spike S4. Making it <i>resolvable</i> is the other half, measured
 * by spike S5, and a {@link Repository} added to the {@link org.apache.maven.model.Model} alone
 * does nothing: by {@code afterProjectsRead} the project's effective repository lists have already
 * been computed during project building. The participant must additionally call
 * {@link MavenProject#setRemoteArtifactRepositories}, whose implementation refreshes the Aether
 * {@code remoteProjectRepositories} list that both Maven's dependency resolution and
 * quarkus-maven-plugin's {@code ${project.remoteProjectRepositories}} parameter actually read.
 * Omitting it yields a build that fails to resolve — or, with a populated local repository, one that
 * silently succeeds for the wrong reason.
 *
 * <p>Evidence: {@code bench-results/dd043-spikes-2026-07-24/s4-injection/} and
 * {@code bench-results/dd043-s5-repo-injection-2026-07-26/}.
 */
@Named("basquin-injector")
@Singleton
public class BasquinInjector extends AbstractMavenLifecycleParticipant {

    static final String GROUP_ID = "com.basquin";
    static final String ARTIFACT_ID = "basquin-quarkus";
    static final String REPO_ID = "basquin-injected";
    static final String DEFAULT_REPO_URL = "https://ianp94.github.io/basquin/maven/";

    static final String PROP_SKIP = "basquin.inject.skip";
    static final String PROP_REPO_URL = "basquin.inject.repo.url";
    static final String PROP_VERSION = "basquin.inject.version";

    private static final String LOG = "[basquin-injector] ";

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        inject(session.getProjects(), System.getProperties());
    }

    /**
     * The testable seam. {@code getProjects()} is the only thing read from the session, so taking the
     * list directly keeps every behaviour unit-testable without standing up a Plexus container.
     */
    void inject(List<MavenProject> projects, Properties props) throws MavenExecutionException {
        String version = orDefault(props.getProperty(PROP_VERSION), InjectorVersion.value());
        String url = orDefault(props.getProperty(PROP_REPO_URL), DEFAULT_REPO_URL);

        for (MavenProject p : projects) {
            // Every model object below is allocated fresh inside this loop. Maven's model objects are
            // mutable; hoisting an allocation would alias one instance across the whole reactor, so a
            // later in-place mutation on one module would bleed into all the others. A single-module
            // build cannot detect that, which is why this is a written constraint and not taste.
            addDependency(p, version);
            addRepository(p, url);
            System.out.println(LOG + "instrumented " + p.getArtifactId()
                    + " (" + GROUP_ID + ":" + ARTIFACT_ID + ":" + version + " from " + url + ")");
        }
    }

    private void addDependency(MavenProject p, String version) {
        Dependency d = new Dependency();
        d.setGroupId(GROUP_ID);
        d.setArtifactId(ARTIFACT_ID);
        d.setVersion(version);
        // MavenProject.getDependencies() delegates to getModel().getDependencies(), so adding to both
        // would add the same object twice to one list.
        p.getModel().getDependencies().add(d);
    }

    private void addRepository(MavenProject p, String url) {
        Repository r = new Repository();
        r.setId(REPO_ID);
        r.setUrl(url);
        RepositoryPolicy releases = new RepositoryPolicy();
        releases.setEnabled(true);
        RepositoryPolicy snapshots = new RepositoryPolicy();
        snapshots.setEnabled(false);
        r.setReleases(releases);
        r.setSnapshots(snapshots);
        p.getModel().getRepositories().add(r);

        // The load-bearing half — see the class comment. Without this the model entry above is inert.
        ArtifactRepository ar = new MavenArtifactRepository(
                REPO_ID,
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
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
