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
            Dependency existing = declaredDependency(p);
            String declared = (existing == null) ? null : existing.getVersion();
            String effective = version;
            if (existing != null) {
                // Usability before version: a declaration that cannot carry the extension is unusable
                // whatever its version, so checking the version first would report the lesser problem.
                failOnUnusableDeclaration(p, existing);
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



    /**
     * A declaration that cannot carry the extension into the application is a whole *class* of silent
     * bypass, and no version check can see any of it.
     *
     * <p>{@link #declaredDependency} matches on groupId and artifactId alone, so a project declaring
     * {@code com.basquin:basquin-quarkus} in any unusable shape reads as "already declared": the
     * dependency is not injected, only the repository is added, the extension never reaches the module's
     * classpath, augmentation excludes it, and the build <b>succeeds</b> producing an uninstrumented
     * application whose {@code /__basquin/result} polls return {@code "miss"}. The version guards cannot
     * fire, because the version does not conflict — it may even match exactly.
     *
     * <p><b>Four shapes, found one at a time, which is why this is written as a whitelist rather than a
     * list of known-bad cases:</b>
     * <ul>
     *   <li>{@code scope} — {@code test}/{@code provided}/{@code system}/{@code import} never reach the
     *       application. Found by PR #103's Claude review.</li>
     *   <li>{@code type} — {@code pom} resolves the POM and never the jar, so no classes arrive. Found by
     *       PR #103's approver, which reproduced it against {@code inject()} and confirmed all four
     *       then-existing guards stayed silent.</li>
     *   <li>{@code classifier} — a classified artifact is not the extension jar.</li>
     *   <li>{@code exclusions} — can strip the extension's own transitive dependencies, notably
     *       {@code basquin-core}, leaving the jar present but unusable. Found by PR #103's independent
     *       approver, and it is <b>one of the two</b> shapes §5.2's banner acceptance cannot detect: the
     *       feature still appears in {@code Installed features}, so no acceptance run would fail. The
     *       other is the same hazard one function away, on the <i>managed</i> path —
     *       {@code <exclusions>} on a {@code dependencyManagement} entry for {@code com.basquin}, closed
     *       by {@link #failOnConflictingManagedVersion} after review showed this fix had been half a fix.
     *       This guard covers the declared path only. An earlier self-review had judged exclusions
     *       harmless on the reasoning that "the extension jar still delivers" — true, and beside the
     *       point.</li>
     * </ul>
     *
     * <p>Each was found after the previous one was fixed, so enumerating bad values would have shipped the
     * next variant. This is therefore a whitelist, and it covers every field of {@link Dependency} that can
     * change whether the jar reaches the classpath: {@code scope}, {@code type}, {@code classifier} and
     * {@code exclusions} must match what {@link #addDependency} produces — a plain {@code compile}/{@code
     * runtime}, {@code jar}-type, unclassified, exclusion-free dependency — and anything else fails loudly
     * per spec §5.1. Of the model's remaining fields, {@code groupId}/{@code artifactId} are the match key
     * {@link #declaredDependency} uses, {@code version} is checked by
     * {@link #failOnConflictingDeclaredVersion}, and {@code systemPath} is only meaningful with
     * {@code scope=system}, which the scope branch already rejects.
     *
     * <p><b>{@code optional} is the one field this method deliberately does not reject, and it is the one
     * exception to "equivalent to what the injector would add"</b> — {@link #addDependency} sets no
     * {@code optional} flag, so an accepted {@code optional=true} declaration is not identical to it. The
     * mechanism: {@code <optional>} only stops a dependency being inherited by <i>downstream consumers</i>
     * of this module; it does not remove it from the declaring module's own classpath, which is the only
     * classpath instrumentation needs. In {@code maven-resolver-util-1.9.27},
     * {@code OptionalDependencySelector.selectDependency} is {@code depth < 2 || !isOptional()} and its
     * {@code deriveChildSelector} increments {@code depth} from 0, so a project's own direct dependencies
     * are judged at {@code depth == 1} and a direct optional is always collected;
     * {@code MavenRepositorySystemUtils.newSession} is what installs that selector.
     *
     * <p>That mechanism is only an argument, and this branch's rule is to measure once rather than resolve
     * a bypass by assumption — so the behaviour was measured end-to-end, not inferred, and the captured
     * output is in the tree at {@code bench-results/dd043-pr3-optional-declaration-2026-07-29/} (every
     * figure below is a {@code file:line} into it, not prose). Against quarkus-super-heroes
     * {@code rest-villains} (Quarkus 3.37.3, {@code build-optional-injector.log:19}; Maven 3.9.15,
     * {@code provenance.txt:57}) with {@code com.basquin:basquin-quarkus:0.3.0} declared
     * {@code <optional>true</optional>} as the only deviation from the upstream pom
     * ({@code pom-deviation.diff:7-12}) and this injector on {@code maven.ext.class.path}, the injector
     * took this accept path ({@code build-optional-injector.log:2} — "already declares
     * basquin-quarkus:0.3.0; adding the repository only") and the build succeeded
     * ({@code build-optional-injector.log:108}); {@code target/quarkus-app/lib/main/} contained both
     * {@code com.basquin.basquin-quarkus-0.3.0.jar} and {@code com.basquin.basquin-core-0.3.0.jar}
     * ({@code libmain-optional-injector.txt:4-5}) in a 228-file list identical to the non-optional
     * injected build's ({@code libmain-diff.txt:4,7-8}); the runtime banner listed {@code basquin} under
     * {@code Installed features} ({@code app-startup.log:25}, extracted to {@code banner.txt:1}); and
     * {@code /__basquin/result} answered a driven request with the cost line {@code 176,-12207,10|0||}
     * ({@code result-poll.txt:8}) while an undriven id on the same endpoint still returned {@code "miss"}
     * ({@code result-poll.txt:12}). An optional declaration therefore ships an instrumented application:
     * it is not a silent bypass, and guarding it would reject a build that demonstrably works.
     * (That directory's README records which captures are the original run and which are a later
     * re-capture of the runtime half, and what the run does not establish — notably that a
     * <i>transitive</i> optional, judged at {@code depth >= 2}, is unmeasured.)
     * {@code BasquinInjectorGuardsTest#acceptsAnOptionalDeclarationBecauseADirectOptionalStillReachesTheClasspath}
     * pins that this shape stays accepted.
     */
    private void failOnUnusableDeclaration(MavenProject p, Dependency d) throws MavenExecutionException {
        String scope = (d.getScope() == null || d.getScope().isBlank()) ? "compile" : d.getScope();
        String type = (d.getType() == null || d.getType().isBlank()) ? "jar" : d.getType();
        String classifier = d.getClassifier();

        String problem = null;
        if (!"compile".equals(scope) && !"runtime".equals(scope)) {
            problem = "scope '" + scope + "' (only 'compile' and 'runtime' reach the application)";
        } else if (!"jar".equals(type)) {
            problem = "type '" + type + "' (only 'jar' brings the extension's classes; a 'pom' type "
                    + "resolves the POM and never the jar)";
        } else if (classifier != null && !classifier.isBlank()) {
            problem = "classifier '" + classifier + "' (a classified artifact is not the extension jar)";
        } else if (d.getExclusions() != null && !d.getExclusions().isEmpty()) {
            problem = "exclusions (" + d.getExclusions().size() + "), which can strip the extension's own"
                    + " transitive dependencies — notably basquin-core — leaving the extension jar present"
                    + " but unusable. Exclusions are one of the TWO shapes §5.2's banner acceptance cannot"
                    + " detect — this one on the declared dependency, the other on a dependencyManagement"
                    + " entry for com.basquin — because the feature still appears in Installed features"
                    + " either way. No acceptance run would catch this declaration; only this guard does";
        }
        if (problem == null) {
            return;
        }
        throw new MavenExecutionException(
                "basquin-injector: " + p.getArtifactId() + " already declares " + GROUP_ID + ":"
                        + ARTIFACT_ID + " with " + problem + ". That declaration cannot carry the extension"
                        + " onto the application's classpath, so augmentation would not include it and the"
                        + " build would succeed UNINSTRUMENTED, with /__basquin/result returning \"miss\"."
                        + " Make the declaration a plain compile/runtime jar dependency, remove it and let"
                        + " this injector add it, or pass -D" + PROP_SKIP + "=true to leave this build"
                        + " uninstrumented deliberately.",
                p.getFile());
    }

    /** The existing declaration of our artifact, or {@code null} if there is none. */
    private Dependency declaredDependency(MavenProject p) {
        for (Dependency d : p.getModel().getDependencies()) {
            if (GROUP_ID.equals(d.getGroupId()) && ARTIFACT_ID.equals(d.getArtifactId())) {
                return d;
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
            // Sixth shape, found by PR #103's approver: a MANAGED entry carrying <exclusions> strips
            // basquin-core from the injector's own dependency, and — like the declared-path case — it
            // passes §5.2's banner, because the extension still loads. I had closed exclusions on the
            // declared path only. The same usability test must apply here or the fix was half a fix.
            if (managed.getExclusions() != null && !managed.getExclusions().isEmpty()) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + "'s dependencyManagement declares "
                                + GROUP_ID + ":" + managed.getArtifactId() + " with "
                                + managed.getExclusions().size() + " exclusion(s). Managed exclusions apply"
                                + " to the dependency this injector adds, so they can strip basquin-core and"
                                + " leave the extension present but unusable — and the Installed features"
                                + " banner still lists it, so no acceptance run would catch it. Remove the"
                                + " managed exclusions, or pass -D" + PROP_SKIP + "=true to leave this"
                                + " build uninstrumented deliberately.",
                        p.getFile());
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
