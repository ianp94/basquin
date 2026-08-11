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
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
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

    // DD-043 PR-4, Task 2 (docs/superpowers/plans/2026-08-10-dd043-pr4-coverage.md). The
    // execution id/phase/goal are fixed, operator-visible constants — not properties — because
    // unlike the basquin-quarkus dependency (which a target may legitimately want to pin itself),
    // nothing about this execution is meant to be overridden: it exists solely so
    // basquin-quarkus's /__basquin/coverage route (A1) has real offline-instrumented classes and
    // preserved originals (target/generated-classes/jacoco) to serve and analyze against.
    static final String JACOCO_GROUP_ID = "org.jacoco";
    static final String JACOCO_ARTIFACT_ID = "jacoco-maven-plugin";
    static final String JACOCO_EXECUTION_ID = "basquin-injected-offline-instrument";
    static final String JACOCO_PHASE = "process-classes";
    static final String JACOCO_GOAL = "instrument";

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
        // DD-043 PR-4. Never a hand-typed literal — see JacocoVersion's javadoc: offline-instrumented
        // classes reference the version-specific shaded package org.jacoco.agent.rt.internal_<hash>,
        // so this must stay in lockstep with the extension's runtime dependency (A1) and the runner's
        // analyzer (Task 3), all three baked from the same root gradle.properties jacocoVersion.
        String jacocoVersion = JacocoVersion.value();

        for (MavenProject p : projects) {
            failOnConflictingManagedVersion(p, version);
            failOnUnusableSiblingDeclaration(p, version);
            failOnConflictingJacocoDeclaration(p, jacocoVersion);
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
            addJacocoInstrumentExecution(p, jacocoVersion);
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
     *       approver, and it belongs to the class of shapes §5.2's banner acceptance cannot detect: the
     *       extension jar still loads, so the feature still appears in {@code Installed features}, while
     *       basquin-core is missing or unusable at runtime, and no acceptance run would fail. The same
     *       undetectable state is reachable through {@code <exclusions>} or an unusable {@code <scope>}
     *       on a {@code dependencyManagement} entry for {@code com.basquin} (both closed by
     *       {@link #failOnConflictingManagedVersion} after reviews showed this fix had been half a fix,
     *       twice) and through an unusable scope on a declared {@code com.basquin} sibling (closed by
     *       {@link #failOnUnusableSiblingDeclaration}). This guard covers the declared
     *       {@code basquin-quarkus} path only. An earlier self-review had judged exclusions
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
                    + " but unusable. Exclusions are one of the shapes §5.2's banner acceptance cannot"
                    + " detect — whether basquin-core is stripped here, by a dependencyManagement entry"
                    + " for com.basquin, or by an unusable scope on a com.basquin entry, the feature"
                    + " still appears in Installed features either way. No acceptance run would catch"
                    + " this declaration; only this guard does";
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
     * managed entry governs anything resolved transitively — notably basquin-core. Building
     * against a different core than the extension was compiled against is the "succeeds but is
     * silently wrong" outcome this design exists to prevent, so it is a hard failure.
     *
     * <p><b>Which managed attributes reach which artifact is measured, not reasoned about — and it is a
     * PER-ARTIFACT question, because Maven 3.9's resolver draws a depth line through our own chain.</b>
     * This method runs after model building, so Maven's model-level dependencyManagement injection has
     * already passed over the model and cannot have touched the {@link Dependency} {@link #addDependency}
     * is about to add. What reaches that dependency and its transitives instead is the RESOLVER's
     * {@code ClassicDependencyManager}, which applies managed {@code version}/{@code scope}/{@code
     * optional} only from depth 2 down while its exclusions handling has no such depth gate. The
     * dependency this injector adds is a depth-1 node; the {@code basquin-core} it pulls in is a depth-2
     * node. So a cell that varies a managed attribute on {@code com.basquin:basquin-quarkus} answers the
     * depth-1 question ONLY, and an earlier revision of this comment generalized four such cells into
     * "managed scope cannot reach us" — a claim about the whole group that the cells never tested at
     * depth 2, where it is false. PR #103's earlier approver had reported the managed-scope gap and was
     * overruled by that misread; they were right. Captured output for both depths (every figure below is
     * a {@code file:line}, not prose): {@code bench-results/dd043-pr3-r4-guard-measurement-2026-07-29/}
     * (depth-1 cells, injected-artifact attributes, guard neutered via {@code injector-noguard.diff}) and
     * {@code bench-results/dd043-pr3-r7-managed-scope-2026-07-30/} (depth-2 cells on {@code basquin-core},
     * shipped 865ba35 injector), each with its own {@code provenance.txt} and {@code rerun.sh}:
     * <ul>
     *   <li><b>{@code <exclusions>} DO apply, even at depth 1.</b> A managed
     *       {@code com.basquin:basquin-quarkus} at the <i>agreeing</i> version carrying an exclusion of
     *       {@code basquin-core} resolved 94 artifacts with {@code basquin-quarkus:jar:0.3.0:compile}
     *       present and <b>no basquin-core at all</b> (r4 {@code logs/mx-noguard-list.log:12}), against
     *       the control's 95 with {@code basquin-core:jar:0.3.0:runtime}
     *       (r4 {@code logs/ctl-noguard-list.log:12-13}) — and the build SUCCEEDED
     *       (r4 {@code logs/mx-noguard-list.log:108}). The exclusions branch below guards a real hazard
     *       and its message is true.</li>
     *   <li><b>{@code <version>} DOES apply at depth 2.</b> A managed
     *       {@code com.basquin:basquin-core:0.0.1-conflicting} resolved
     *       {@code basquin-core:jar:0.0.1-conflicting:runtime} beside {@code basquin-quarkus:jar:0.3.0}
     *       and succeeded (r4 {@code logs/mcv-noguard-list.log:12-13,109}). With the guard restored, the
     *       same cell aborts in {@code Scanning for projects} (r4 {@code logs/mcv-stock-list.log:2}).</li>
     *   <li><b>{@code <scope>} DOES apply at depth 2, and it is the EIGHTH silent bypass.</b> A managed
     *       {@code com.basquin:basquin-core} at the agreeing version with {@code <scope>provided</scope>}
     *       resolved {@code basquin-core:jar:0.3.0:provided} (r7 {@code logs/mscore-stock-list.log:13})
     *       and with {@code <scope>test</scope>} resolved {@code :test}
     *       (r7 {@code logs/mtcore-stock-list.log:13}) — in both, {@code basquin-quarkus:jar:0.3.0:compile}
     *       is still present (line 12 of each), basquin-core is GONE from the runtime classpath (94
     *       entries with no basquin-core, against the control's 95 with it —
     *       r7 {@code logs/mscore-stock-runtime-cp-entries.txt}, {@code mtcore-…}, vs
     *       {@code ctl-stock-runtime-cp-entries.txt:2}), the build SUCCEEDED and the 865ba35 injector
     *       stayed silent. The extension jar still loads, so §5.2's banner acceptance cannot see it.
     *       This is the resolved state {@link #failOnUnusableSiblingDeclaration} already hard-fails when
     *       a <i>declared</i> sibling produces it, arrived at through {@code dependencyManagement}
     *       instead — the same usability test must apply here or that fix was half a fix, which is why
     *       the scope branch below uses the same compile/runtime whitelist. ({@code system} and
     *       {@code import} ride it unmeasured, for {@link #failOnUnusableSiblingDeclaration}'s recorded
     *       reasons; a consumed BOM import never survives into this effective model anyway.)</li>
     *   <li><b>{@code <optional>true</optional>} on {@code basquin-core} is measured HARMLESS, so it is
     *       deliberately not guarded.</b> The core resolves as {@code runtime (optional)}
     *       (r7 {@code logs/moptcore-stock-list.log:13}) and stays ON the runtime classpath
     *       (r7 {@code logs/moptcore-stock-runtime-cp-entries.txt:2}, 95 entries like the control).
     *       A guard would hard-fail a build that demonstrably works — the same mistake guarding a
     *       declared {@code optional=true} would have been.</li>
     *   <li><b>Managed {@code scope}/{@code type}/{@code classifier}/{@code optional} on the injected
     *       depth-1 {@code basquin-quarkus} itself do not reach it.</b> Each was varied alone at the
     *       agreeing version and each cell came out identical to the control — 95 artifacts,
     *       {@code basquin-quarkus} still {@code :compile}, {@code basquin-core} still {@code :runtime}
     *       (r4 {@code logs/msc-noguard-list.log:12-13}, {@code mty-…:12-13}, {@code mcl-…:12-13},
     *       {@code mopt-…:12-13}). That is a statement about the INJECTED artifact only; as the scope
     *       bullet above shows, it must not be read across the depth boundary.</li>
     * </ul>
     *
     * <p>The three branches below — exclusions, scope, version — are therefore the measured managed
     * hazards, not a measured-complete enumeration of every attribute that could ever reach the group:
     * completeness was only ever measured for the depth-1 injected artifact, and depth 2 is where
     * basquin-core lives. Anything added to {@link Dependency}, any unmeasured attribute at depth 2, or
     * any change to the resolver's depth rules (Maven 4 replaces {@code ClassicDependencyManager})
     * re-opens this, and the cells in the two directories' {@code rerun.sh} are how to settle it again.
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
            // Measured, not assumed — see this method's javadoc and mx-noguard-list.log:12 (94 resolved,
            // no basquin-core) against ctl-noguard-list.log:12-13 (95, basquin-core present).
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
            // The EIGHTH silent bypass, found by PR #103's round-6 approver and reproduced before this
            // branch was written: a managed <scope> on a com.basquin sibling reaches basquin-core at
            // depth 2 (see this method's javadoc — the earlier "managed scope cannot reach us" reading
            // measured only the depth-1 injected artifact). provided/test leave the core resolved but
            // off the runtime classpath while the extension still loads, so the banner still lists
            // basquin and the build is green — r7 logs/mscore-stock-list.log:13 (:provided) and
            // logs/mtcore-stock-list.log:13 (:test), runtime classpaths one jar short of the control.
            // Same compile/runtime whitelist as failOnUnusableSiblingDeclaration: the identical
            // resolved state one field away already hard-fails there. A null/blank managed scope
            // manages nothing and is accepted; usability before version, as recorded at inject()'s
            // declared-artifact call site.
            String managedScope = managed.getScope();
            if (managedScope != null && !managedScope.isBlank()
                    && !"compile".equals(managedScope) && !"runtime".equals(managedScope)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + "'s dependencyManagement pins "
                                + GROUP_ID + ":" + managed.getArtifactId() + " to scope '" + managedScope
                                + "'. A managed scope applies to the Basquin artifacts the extension"
                                + " resolves transitively — basquin-core above all — so it would keep"
                                + " them off the application's runtime classpath while the extension"
                                + " jar itself still loads: the build would succeed, Installed features"
                                + " would still list basquin, and the application would run"
                                + " UNINSTRUMENTED, with /__basquin/result returning \"miss\". Only"
                                + " 'compile' and 'runtime' reach the application. Remove the managed"
                                + " scope, or pass -D" + PROP_SKIP + "=true to leave this build"
                                + " uninstrumented deliberately.",
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

    /**
     * The seventh silent bypass, found by PR #103's round-4 approver as S2: everything above looks either
     * at {@code com.basquin:basquin-quarkus} specifically ({@link #declaredDependency} matches on
     * groupId <b>and</b> artifactId) or at {@code dependencyManagement}. A project that <i>declares</i>
     * some other {@code com.basquin} artifact directly — {@code basquin-core} above all — was seen by
     * nothing at all, while the identical shape one field away, in {@code dependencyManagement}, hard-fails
     * in {@link #failOnConflictingManagedVersion}. That asymmetry was a policy hole, not a policy.
     * DD-044 / PR-3.5 is specifically about targets that already carry Basquin, which makes it reachable
     * rather than theoretical.
     *
     * <p><b>Which sibling shapes are hazards is measured, not assumed</b>, and that is why this guard
     * checks two fields where {@link #failOnUnusableDeclaration} whitelists four. Captured output:
     * {@code bench-results/dd043-pr3-r4-guard-measurement-2026-07-29/} — a minimal jar project under
     * Maven 3.9.15 with the <i>shipped</i> injector on {@code maven.ext.class.path}, one declared
     * {@code com.basquin:basquin-core} deviation per cell, reading Maven's own resolved set:
     * <ul>
     *   <li><b>A conflicting version is fatal.</b> Declaring {@code basquin-core:0.0.1-conflicting}
     *       produced a resolved set holding {@code basquin-core:jar:0.0.1-conflicting:compile} beside
     *       {@code basquin-quarkus:jar:0.3.0:compile} and <b>BUILD SUCCESS</b>
     *       ({@code logs/dcv-stock-list.log:111-112,208}) — a nearest-wins direct declaration beating the
     *       extension's own transitive core, which is verbatim the outcome
     *       {@link #failOnConflictingManagedVersion} calls "succeeds but is silently wrong".</li>
     *   <li><b>A scope that does not reach the application is fatal.</b> {@code <scope>test</scope>} put
     *       {@code basquin-core:jar:0.3.0:test} ({@code logs/dsc-stock-list.log:12}) and
     *       {@code <scope>provided</scope>} put {@code basquin-core:jar:0.3.0:provided}
     *       ({@code logs/dprov-stock-list.log:12}) — in both, a direct declaration wins the scope for that
     *       node, so the core is off the runtime classpath while {@code basquin-quarkus} is still at
     *       {@code :compile}, and the build succeeds ({@code :109} of each).</li>
     *   <li><b>{@code type}, {@code classifier} and {@code exclusions} are NOT hazards here, so they are
     *       accepted.</b> {@code type} and {@code classifier} are part of the resolution key, so such a
     *       declaration is a <i>different node</i> and the plain transitive core still arrives: 96
     *       artifacts, one more than the control, with {@code basquin-core:pom:0.3.0:compile} <i>plus</i>
     *       {@code basquin-core:jar:0.3.0:runtime} ({@code logs/dty-stock-list.log:13,15}) and
     *       {@code basquin-core:jar:tests:0.3.0:compile} plus the same jar
     *       ({@code logs/dcl-stock-list.log:56,58}). An {@code <exclusions>} on a declared
     *       {@code basquin-core} strips that node's own transitives, of which
     *       {@code basquin-core-0.3.0.pom} declares none, and the core still resolved at {@code :compile}
     *       ({@code logs/dex-stock-list.log:12}). Copying {@link #failOnUnusableDeclaration}'s whole
     *       whitelist here would therefore hard-fail three shapes that demonstrably work — the mistake
     *       guarding {@code optional=true} would have been.</li>
     * </ul>
     *
     * <p>{@code system} and {@code import} are not measured. They ride the same compile/runtime whitelist
     * as {@code test}/{@code provided} for the same reason they do on the declared-{@code basquin-quarkus}
     * path: {@code system} is only meaningful with a {@code systemPath}, and {@code import} only inside
     * {@code dependencyManagement}.
     *
     * <p>Usability before version, for the reason {@link #inject} records at the declared-artifact call
     * site: a declaration at an unusable scope is unusable whatever its version, so reporting the version
     * first would report the lesser problem.
     */
    private void failOnUnusableSiblingDeclaration(MavenProject p, String version)
            throws MavenExecutionException {
        for (Dependency d : p.getModel().getDependencies()) {
            if (!GROUP_ID.equals(d.getGroupId()) || ARTIFACT_ID.equals(d.getArtifactId())) {
                // Our own artifact is failOnUnusableDeclaration's and failOnConflictingDeclaredVersion's.
                continue;
            }
            String coordinate = GROUP_ID + ":" + d.getArtifactId();
            String scope = (d.getScope() == null || d.getScope().isBlank()) ? "compile" : d.getScope();
            if (!"compile".equals(scope) && !"runtime".equals(scope)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + " declares " + coordinate
                                + " at scope '" + scope + "'. A direct declaration wins the scope for that"
                                + " artifact, so it would be kept off the application's runtime classpath"
                                + " while the extension this injector adds still expects it there — the"
                                + " build would succeed and the extension would be UNINSTRUMENTED at"
                                + " runtime, with /__basquin/result returning \"miss\". Only 'compile' and"
                                + " 'runtime' reach the application. Make it a compile/runtime dependency,"
                                + " remove it and let the extension bring it in transitively, or pass -D"
                                + PROP_SKIP + "=true to leave this build uninstrumented deliberately.",
                        p.getFile());
            }
            String declared = d.getVersion();
            if (declared != null && !declared.equals(version)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + " declares " + coordinate + " at version"
                                + " " + declared + ", but this injector supplies " + ARTIFACT_ID + ":"
                                + version + ". A declared direct version wins over the one the extension"
                                + " resolves transitively, so the build would run " + version
                                + " of the extension against " + declared + " of " + d.getArtifactId()
                                + " — a wire-format skew that shows up as /__basquin/result polls"
                                + " returning \"miss\" rather than as a build error. Align the versions,"
                                + " or pass -D" + PROP_VERSION + "=" + declared + " to inject the declared"
                                + " version deliberately, or -D" + PROP_SKIP + "=true to leave this build"
                                + " uninstrumented.",
                        p.getFile());
            }
        }
    }

    /**
     * DD-043 PR-4, decision D2 (docs/superpowers/plans/2026-08-10-dd043-pr4-coverage.md). This
     * injector is about to append its own {@code org.jacoco:jacoco-maven-plugin} {@code instrument}
     * execution ({@link #addJacocoInstrumentExecution}) to the project's build. A target that already
     * carries a jacoco declaration of its own is a hazard this method must catch loudly rather than let
     * the two compose silently — the same {@code failOn*} posture every other guard in this class takes,
     * per spec §5.1.
     *
     * <p><b>"Conflicting" is defined precisely as two independent conditions, checked per declared
     * {@code org.jacoco:jacoco-maven-plugin} entry in the ALREADY-MERGED effective model
     * ({@code p.getModel().getBuild().getPlugins()}):</b>
     * <ol>
     *   <li><b>An ACTIVE {@code instrument}-bound execution.</b> This injector's own execution runs
     *       {@code instrument} at {@code process-classes} against the module's compiled classes and
     *       preserves the pre-instrumentation originals under {@code target/generated-classes/jacoco}
     *       (the §8.2 spike's measured default behaviour). A second, independently declared
     *       {@code instrument} execution would run against the SAME classes — jacoco's instrumenter is
     *       not idempotent against already-instrumented bytecode, so the second pass can corrupt the
     *       classes or fail the build outright, and either way the coverage this injector's mechanism
     *       exists to serve cannot be trusted.</li>
     *   <li><b>A version collision.</b> Declaring {@code org.jacoco:jacoco-maven-plugin} at any version
     *       other than the one this injector supplies (baked in lockstep from the shared
     *       {@code gradle.properties jacocoVersion} property — {@link JacocoVersion#value()}) means two
     *       {@code <plugin>} entries for the same coordinate at different versions land in the merged
     *       model, which is unresolved Maven plugin-merge territory this injector does not rely on being
     *       safe. Even where it resolves, offline-instrumented classes reference the version-specific
     *       shaded package {@code org.jacoco.agent.rt.internal_<hash>}
     *       ({@code JacocoVersionLockstepTest}'s own reasoning) — a plugin/agent version skew silently
     *       breaks the coverage read rather than failing to compile.</li>
     * </ol>
     *
     * <p><b>A target's own {@code prepare-agent} execution is deliberately NOT, by itself, either
     * condition.</b> {@code prepare-agent} instruments classes ON-LINE, in the running JVM, via a Java
     * agent — a different mechanism entirely from this injector's OFF-LINE, on-disk {@code instrument}
     * goal, and the two do not compete for the same bytecode. A {@code prepare-agent}-only declaration AT
     * THE AGREEING version therefore trips neither branch above and is accepted; only an {@code
     * instrument} goal, or any version other than this injector's, does.
     *
     * <p><b>Profiles need no special handling here, because this method never sees them.</b> This
     * participant runs at {@code afterProjectsRead}, by which point Maven has already merged every
     * ACTIVATED profile's contributions into {@code p.getModel()} — the effective model this method
     * reads. A jacoco declaration living only in a profile that never activates for this build simply
     * never reaches {@code getModel().getBuild().getPlugins()} at all, so it is invisible to this guard
     * by construction, not by an explicit exclusion this method has to implement.
     */
    private void failOnConflictingJacocoDeclaration(MavenProject p, String jacocoVersion)
            throws MavenExecutionException {
        if (p.getModel().getBuild() == null) {
            return;
        }
        for (Plugin plugin : p.getModel().getBuild().getPlugins()) {
            if (!JACOCO_GROUP_ID.equals(plugin.getGroupId())
                    || !JACOCO_ARTIFACT_ID.equals(plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution exec : plugin.getExecutions()) {
                if (exec.getGoals() != null && exec.getGoals().contains(JACOCO_GOAL)) {
                    throw new MavenExecutionException(
                            "basquin-injector: " + p.getArtifactId() + " already declares an ACTIVE "
                                    + JACOCO_GROUP_ID + ":" + JACOCO_ARTIFACT_ID + " execution '"
                                    + exec.getId() + "' bound to goal '" + JACOCO_GOAL + "'. This"
                                    + " injector adds its own offline-instrument execution ("
                                    + JACOCO_EXECUTION_ID + ") at " + JACOCO_PHASE + ", and Maven would"
                                    + " then run BOTH instrument executions against the same compiled"
                                    + " classes — jacoco's instrumenter is not idempotent against"
                                    + " already-instrumented bytecode, so the second pass can corrupt"
                                    + " the classes or fail the build outright, and the coverage this"
                                    + " injector exists to serve cannot be trusted either way. Remove"
                                    + " the existing instrument execution, or pass -D" + PROP_SKIP
                                    + "=true to leave this build uninstrumented deliberately.",
                            p.getFile());
                }
            }
            String declared = plugin.getVersion();
            if (declared != null && !declared.equals(jacocoVersion)) {
                throw new MavenExecutionException(
                        "basquin-injector: " + p.getArtifactId() + " already declares "
                                + JACOCO_GROUP_ID + ":" + JACOCO_ARTIFACT_ID + " at version " + declared
                                + ", but this injector's offline-instrument execution supplies "
                                + jacocoVersion + ". Two declarations of the same plugin at different"
                                + " versions is unresolved Maven plugin-merge territory this injector"
                                + " does not rely on, and offline-instrumented classes reference the"
                                + " version-specific shaded package org.jacoco.agent.rt.internal_<hash>"
                                + " — a plugin/agent version skew silently breaks the coverage read"
                                + " rather than failing to compile. Align the target's "
                                + JACOCO_ARTIFACT_ID + " to " + jacocoVersion + ", or pass -D"
                                + PROP_SKIP + "=true to leave this build uninstrumented deliberately.",
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

    /**
     * DD-043 PR-4, Task 2. Adds the {@code jacoco-maven-plugin:<jacocoVersion>:instrument} execution
     * that gives {@code basquin-quarkus}'s {@code /__basquin/coverage} route (A1) real
     * offline-instrumented classes to serve execution data for, and preserves the pre-instrumentation
     * originals under {@code target/generated-classes/jacoco} for the driver's analyzer to check
     * against — the default {@code instrument} behaviour the §8.2 spike measured, with no explicit
     * configuration needed.
     *
     * <p>Deliberately does NOT add the jacoco runtime dependency: that arrives transitively through
     * {@code basquin-quarkus}'s own {@code implementation org.jacoco:org.jacoco.agent:runtime}
     * dependency (decision A1, Task 1) — one channel, so there is no second injected-artifact guard
     * surface for this method to own.
     *
     * <p><b>Fresh {@link Plugin}/{@link PluginExecution} objects, allocated inside this call.</b> The
     * same §5 aliasing rule {@link #addDependency} and {@link #addRepository} already follow: Maven's
     * model objects are mutable, so a shared instance hoisted above the per-project loop would alias
     * one {@code Plugin} across the whole reactor, and a later in-place mutation on one module's copy
     * would bleed into every other module's. A single-module reactor cannot detect that, which is why
     * {@code BasquinInjectorTest} pins this against a synthetic multi-project list.
     *
     * <p>{@code getModel().getBuild()} is {@code null} on a project whose model never had a
     * {@code <build>} element set (measured: a freshly constructed {@link MavenProject} in this
     * module's own test suite) — a fresh {@link Build} is allocated here if needed, same discipline as
     * {@link #addRepository} allocating a fresh policy pair rather than assuming one exists.
     */
    private void addJacocoInstrumentExecution(MavenProject p, String jacocoVersion) {
        if (p.getModel().getBuild() == null) {
            p.getModel().setBuild(new Build());
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(JACOCO_GROUP_ID);
        plugin.setArtifactId(JACOCO_ARTIFACT_ID);
        plugin.setVersion(jacocoVersion);
        PluginExecution exec = new PluginExecution();
        exec.setId(JACOCO_EXECUTION_ID);
        exec.setPhase(JACOCO_PHASE);
        exec.addGoal(JACOCO_GOAL);
        plugin.addExecution(exec);
        p.getModel().getBuild().getPlugins().add(plugin);
        System.out.println(LOG + "added " + JACOCO_GROUP_ID + ":" + JACOCO_ARTIFACT_ID + ":"
                + jacocoVersion + ":" + JACOCO_GOAL + " (" + JACOCO_EXECUTION_ID + ") to "
                + p.getArtifactId());
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
