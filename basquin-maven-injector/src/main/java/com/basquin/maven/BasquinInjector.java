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
import org.apache.maven.model.DependencyManagement;
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
        if (Boolean.parseBoolean(props.getProperty(PROP_SKIP))) {
            System.out.println(LOG + PROP_SKIP + "=true — injecting nothing (baseline build)");
            return;
        }
        String version = orDefault(props.getProperty(PROP_VERSION), InjectorVersion.value());
        String url = orDefault(props.getProperty(PROP_REPO_URL), DEFAULT_REPO_URL);

        for (MavenProject p : projects) {
            failOnConflictingManagedVersion(p, version);
            // Every model object below is allocated fresh inside this loop. Maven's model objects are
            // mutable; hoisting an allocation would alias one instance across the whole reactor, so a
            // later in-place mutation on one module would bleed into all the others. A single-module
            // build cannot detect that, which is why this is a written constraint and not taste.
            String declared = declaredVersion(p);
            String effective = version;
            if (declared != null) {
                failOnConflictingDeclaredVersion(p, declared, version);
                // Not a duplicate — but the repository is still required: a declared dependency is
                // not necessarily a resolvable one.
                System.out.println(LOG + p.getArtifactId() + " already declares "
                        + ARTIFACT_ID + ":" + declared + "; adding the repository only");
                // Report what the build will actually use, not what we would have injected.
                effective = declared;
            } else {
                addDependency(p, version);
            }
            addRepository(p, url);
            System.out.println(LOG + "instrumented " + p.getArtifactId()
                    + " (" + GROUP_ID + ":" + ARTIFACT_ID + ":" + effective + " from " + url + ")");
        }
    }

    /** The version this project already declares for our artifact, or {@code null} if it declares none. */
    private String declaredVersion(MavenProject p) {
        for (Dependency d : p.getModel().getDependencies()) {
            if (GROUP_ID.equals(d.getGroupId()) && ARTIFACT_ID.equals(d.getArtifactId())) {
                return d.getVersion();
            }
        }
        return null;
    }

    /**
     * The declared-dependency counterpart of {@link #failOnConflictingManagedVersion}, and it exists
     * for symmetry with it rather than as a separate idea.
     *
     * <p>An explicit direct version wins over both dependency management and anything we could inject,
     * so when a project already declares our artifact at a different version, <b>that</b> version is
     * what the build uses. The extension then differs from the one the operator's driver expects, and a
     * wire-format skew on {@code /__basquin/result} surfaces as polls returning {@code "miss"} — DD-040's
     * exact failure mode, arriving silently.
     *
     * <p>Treating this as a log line while {@link #failOnConflictingManagedVersion} hard-fails would be
     * an inconsistency, not a policy: both are the same hazard — the build resolving a different Basquin
     * than the tooling was built against. Spec §5.1 requires failing loudly, so both fail.
     *
     * <p>A project that genuinely means to pin its own version has two documented ways to say so, and
     * the message names them.
     */
    private void failOnConflictingDeclaredVersion(MavenProject p, String declared, String version)
            throws MavenExecutionException {
        if (declared == null || declared.equals(version)) {
            return;
        }
        throw new MavenExecutionException(
                "basquin-injector: " + p.getArtifactId() + " already declares " + GROUP_ID + ":"
                        + ARTIFACT_ID + " at version " + declared + ", but this injector supplies "
                        + version + ". An explicit declared version wins, so the build would use "
                        + declared + " while the driver expects " + version + " — a wire-format skew on"
                        + " /__basquin/result shows up as result polls returning \"miss\" rather than as"
                        + " a build error. Align the versions, or pass -D" + PROP_VERSION + "="
                        + declared + " to inject the declared version deliberately, or -D" + PROP_SKIP
                        + "=true to leave this build uninstrumented.",
                p.getFile());
    }

    /**
     * Spec §5.1: a strict dependencyManagement/BOM may pin something the extension needs, and a
     * managed version governs anything resolved transitively — notably basquin-core. Building
     * against a different core than the extension was compiled against is the "succeeds but is
     * silently wrong" outcome this design exists to prevent, so it is a hard failure.
     */
    private void failOnConflictingManagedVersion(MavenProject p, String version)
            throws MavenExecutionException {
        DependencyManagement dm = p.getModel().getDependencyManagement();
        if (dm == null) {
            return;
        }
        for (Dependency managed : dm.getDependencies()) {
            if (!GROUP_ID.equals(managed.getGroupId())) {
                continue;
            }
            String managedVersion = managed.getVersion();
            if (managedVersion != null && !managedVersion.equals(version)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + "'s dependencyManagement pins "
                                + GROUP_ID + ":" + managed.getArtifactId() + " to " + managedVersion
                                + ", but this injector supplies " + version + ". The managed version"
                                + " would win for transitively resolved Basquin artifacts, producing"
                                + " a build instrumented with a different core than the extension was"
                                + " compiled against. Align the versions, or set -D" + PROP_VERSION
                                + "=" + managedVersion + " if that is genuinely intended.",
                        p.getFile());
            }
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
