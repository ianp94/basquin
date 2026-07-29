package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

/** DD-043 PR-3 — spec §5.1: the injector fails loudly, never silently. */
public class BasquinInjectorGuardsTest {

    private static MavenProject project(String artifactId) {
        MavenProject p = new MavenProject();
        p.setArtifactId(artifactId);
        p.setRemoteArtifactRepositories(new ArrayList<>());
        return p;
    }

    private static void manage(MavenProject p, String artifactId, String version) {
        DependencyManagement dm = new DependencyManagement();
        Dependency managed = new Dependency();
        managed.setGroupId(BasquinInjector.GROUP_ID);
        managed.setArtifactId(artifactId);
        managed.setVersion(version);
        dm.addDependency(managed);
        p.getModel().setDependencyManagement(dm);
    }

    /**
     * A BOM pinning our group to another version wins over what we inject for anything resolved
     * transitively (basquin-core). Silently building against a different core is precisely the
     * "succeeds but is wrong" outcome §5.1 forbids.
     */
    @Test
    public void failsLoudlyWhenDependencyManagementPinsOurGroupToADifferentVersion() {
        MavenProject p = project("app");
        manage(p, "basquin-core", "0.0.1-conflicting");

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("expected MavenExecutionException — a conflicting managed version must not be silent");
        } catch (MavenExecutionException e) {
            String m = e.getMessage();
            assertTrue("message must name the artifact: " + m, m.contains("basquin-core"));
            assertTrue("message must name the conflicting version: " + m,
                    m.contains("0.0.1-conflicting"));
            assertTrue("message must name the version we inject: " + m,
                    m.contains(InjectorVersion.value()));
        }
    }

    /**
     * The managed half of the exclusions hazard, and the second of the two shapes §5.2's banner
     * acceptance cannot detect. Managed exclusions apply to the dependency THIS INJECTOR adds, so a
     * dependencyManagement entry carrying {@code <exclusions>} can strip basquin-core exactly as a
     * declared one can — and the extension still loads, so Installed features still lists it.
     *
     * <p>Without this test only {@code failOnConflictingManagedVersion}'s version branch was covered:
     * deleting the exclusions branch it gained in {@code a61a90c} failed nothing.
     */
    @Test
    public void failsLoudlyWhenDependencyManagementCarriesExclusions() {
        MavenProject p = project("app");
        DependencyManagement dm = new DependencyManagement();
        Dependency managed = new Dependency();
        managed.setGroupId(BasquinInjector.GROUP_ID);
        managed.setArtifactId(BasquinInjector.ARTIFACT_ID);
        managed.setVersion(InjectorVersion.value());   // agreeing version: only the exclusions branch can fire
        Exclusion ex = new Exclusion();
        ex.setGroupId(BasquinInjector.GROUP_ID);
        ex.setArtifactId("basquin-core");
        managed.addExclusion(ex);
        dm.addDependency(managed);
        p.getModel().setDependencyManagement(dm);

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("managed exclusions can strip basquin-core from the injected dependency, so they must "
                    + "not be accepted");
        } catch (MavenExecutionException e) {
            String m = e.getMessage();
            assertTrue("message must name exclusions: " + m, m.contains("exclusion"));
            assertTrue("message must name dependencyManagement as the path: " + m,
                    m.contains("dependencyManagement"));
            assertTrue("message must say the banner cannot detect this: " + m, m.contains("banner"));
        }
    }

    /** A managed entry that AGREES with us is harmless and must not fail the build. */
    @Test
    public void acceptsDependencyManagementThatAgreesWithTheInjectedVersion() throws Exception {
        MavenProject p = project("app");
        manage(p, "basquin-core", InjectorVersion.value());

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /** Management of an unrelated group is none of our business. */
    @Test
    public void ignoresDependencyManagementOfOtherGroups() throws Exception {
        MavenProject p = project("app");
        DependencyManagement dm = new DependencyManagement();
        Dependency other = new Dependency();
        other.setGroupId("io.quarkus");
        other.setArtifactId("quarkus-rest");
        other.setVersion("3.37.3");
        dm.addDependency(other);
        p.getModel().setDependencyManagement(dm);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /**
     * If the pom already declares basquin-quarkus, do not add a second copy — but DO still inject the
     * repository. A declared dependency is not necessarily a resolvable one, and the repository is
     * what makes it resolvable.
     */
    @Test
    public void doesNotDuplicateAnAlreadyDeclaredDependencyButStillAddsTheRepository()
            throws Exception {
        MavenProject p = project("app");
        Dependency existing = new Dependency();
        existing.setGroupId(BasquinInjector.GROUP_ID);
        existing.setArtifactId(BasquinInjector.ARTIFACT_ID);
        // Derived, not typed: this test is about the no-duplicate rule, so the declared version must
        // AGREE with the injected one. A literal would silently start exercising the
        // conflicting-declared-version guard instead the first time the project version is bumped,
        // and this test would fail for a reason that has nothing to do with duplication.
        existing.setVersion(InjectorVersion.value());
        p.getModel().getDependencies().add(existing);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals("must not duplicate", 1, p.getModel().getDependencies().size());
        assertEquals("the repository is still required", 1, p.getRemoteArtifactRepositories().size());
    }

    /** The control-cell switch: a baseline build with the injector still on the classpath. */
    @Test
    public void injectsNothingWhenSkipIsSet() throws Exception {
        MavenProject p = project("app");
        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_SKIP, "true");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals(0, p.getModel().getDependencies().size());
        assertEquals(0, p.getModel().getRepositories().size());
        assertEquals(0, p.getRemoteArtifactRepositories().size());
    }

    /**
     * Skip is opt-in: injection stays on unless the property parses as true. Note
     * {@code Boolean.parseBoolean} is case-insensitive, so {@code TRUE} skips as well.
     */
    @Test
    public void skipIsOffUnlessExplicitlyTrue() throws Exception {
        MavenProject p = project("app");
        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_SKIP, "false");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /**
     * The declared-dependency counterpart of the dependencyManagement guard. An explicit declared
     * version wins over anything we inject, so the build would use it while the driver expects ours —
     * and that skew surfaces as {@code /__basquin/result} polls returning {@code "miss"}, not as a build
     * error. Same hazard as a conflicting managed pin, so it fails the same way; treating one as fatal
     * and the other as a log line would be an inconsistency rather than a policy.
     */
    @Test
    public void failsLoudlyWhenTheProjectDeclaresOurArtifactAtADifferentVersion() {
        MavenProject p = project("app");
        Dependency existing = new Dependency();
        existing.setGroupId(BasquinInjector.GROUP_ID);
        existing.setArtifactId(BasquinInjector.ARTIFACT_ID);
        existing.setVersion("0.0.1-different");
        p.getModel().getDependencies().add(existing);

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("expected MavenExecutionException — a declared version that differs from the injected "
                    + "one must not be accepted with only a log line");
        } catch (MavenExecutionException e) {
            String m = e.getMessage();
            assertTrue("message must name the declared version: " + m, m.contains("0.0.1-different"));
            assertTrue("message must name the version we supply: " + m,
                    m.contains(InjectorVersion.value()));
            assertTrue("message must name an escape hatch: " + m,
                    m.contains(BasquinInjector.PROP_VERSION) || m.contains(BasquinInjector.PROP_SKIP));
        }
    }

    private static void declareAtScope(MavenProject p, String scope) {
        Dependency d = new Dependency();
        d.setGroupId(BasquinInjector.GROUP_ID);
        d.setArtifactId(BasquinInjector.ARTIFACT_ID);
        d.setVersion(InjectorVersion.value());
        d.setScope(scope);
        p.getModel().getDependencies().add(d);
    }

    /**
     * The fourth silent bypass, found by review of PR #103. A declaration at a scope that cannot carry the
     * extension onto the application's classpath reads as "already declared" to a groupId+artifactId
     * match, so the dependency is skipped, augmentation never sees the extension, and the build succeeds
     * UNINSTRUMENTED — with no version conflict for the other guards to catch.
     */
    @Test
    public void failsLoudlyWhenOurArtifactIsDeclaredAtAnUnusableScope() {
        for (String scope : new String[] {"test", "provided", "system", "import"}) {
            MavenProject p = project("app");
            declareAtScope(p, scope);
            try {
                new BasquinInjector().inject(Arrays.asList(p), new Properties());
                fail("scope '" + scope + "' cannot carry the extension onto the classpath, so it must "
                        + "not be accepted as an existing declaration");
            } catch (MavenExecutionException e) {
                assertTrue("message must name the offending scope: " + e.getMessage(),
                        e.getMessage().contains(scope));
                assertTrue("message must say the build would be uninstrumented: " + e.getMessage(),
                        e.getMessage().contains("UNINSTRUMENTED"));
            }
        }
    }

    /**
     * The FIFTH bypass, found by PR #103's approver, which reproduced it against inject() and confirmed
     * all four then-existing guards stayed silent. A <type>pom</type> declaration at the matching version
     * has a usable scope, so the scope guard passes — but Maven resolves the POM and never the jar, so no
     * extension classes arrive and the build succeeds UNINSTRUMENTED. A classifier is the same hole.
     */
    @Test
    public void failsLoudlyWhenOurArtifactIsDeclaredWithAnUnusableTypeOrClassifier() {
        Object[][] cases = {
            {"pom", null, "type"},
            {"test-jar", null, "type"},
            {null, "sources", "classifier"},
            {null, "tests", "classifier"},
        };
        for (Object[] c : cases) {
            MavenProject p = project("app");
            Dependency d = new Dependency();
            d.setGroupId(BasquinInjector.GROUP_ID);
            d.setArtifactId(BasquinInjector.ARTIFACT_ID);
            d.setVersion(InjectorVersion.value());   // matching version: the version guards cannot fire
            if (c[0] != null) {
                d.setType((String) c[0]);
            }
            if (c[1] != null) {
                d.setClassifier((String) c[1]);
            }
            p.getModel().getDependencies().add(d);
            try {
                new BasquinInjector().inject(Arrays.asList(p), new Properties());
                fail("type=" + c[0] + " classifier=" + c[1] + " cannot carry the extension, so it must "
                        + "not be accepted as an existing declaration");
            } catch (MavenExecutionException e) {
                assertTrue("message must name the offending attribute (" + c[2] + "): " + e.getMessage(),
                        e.getMessage().contains((String) c[2]));
                assertTrue("message must say the build would be uninstrumented: " + e.getMessage(),
                        e.getMessage().contains("UNINSTRUMENTED"));
            }
        }
    }

    /**
     * One of the TWO shapes §5.2's banner acceptance cannot detect, found by PR #103's independent
     * approver. Exclusions can strip the extension's own transitive dependencies — notably basquin-core —
     * leaving the extension jar present and the feature still listed in Installed features, while the
     * boundary cannot work. No acceptance run would fail, so a guard is the only thing standing in front
     * of it: {@code failOnUnusableDeclaration} for this, the DECLARED path, and
     * {@code failOnConflictingManagedVersion}'s exclusions branch for the managed one, pinned by
     * {@link #failsLoudlyWhenDependencyManagementCarriesExclusions}. Neither covers the other's path.
     */
    @Test
    public void failsLoudlyWhenTheDeclarationCarriesExclusions() {
        MavenProject p = project("app");
        Dependency d = new Dependency();
        d.setGroupId(BasquinInjector.GROUP_ID);
        d.setArtifactId(BasquinInjector.ARTIFACT_ID);
        d.setVersion(InjectorVersion.value());   // matching version, usable scope/type: only this can fire
        Exclusion ex = new Exclusion();
        ex.setGroupId(BasquinInjector.GROUP_ID);
        ex.setArtifactId("basquin-core");
        d.addExclusion(ex);
        p.getModel().getDependencies().add(d);

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("a declaration carrying exclusions can strip basquin-core, so it must not be accepted");
        } catch (MavenExecutionException e) {
            assertTrue("message must name exclusions: " + e.getMessage(),
                    e.getMessage().contains("exclusions"));
            assertTrue("message must say the banner cannot detect this: " + e.getMessage(),
                    e.getMessage().contains("banner"));
        }
    }

    /** A plain jar-type, unclassified declaration is what the injector itself would add — accept it. */
    @Test
    public void acceptsAPlainJarTypeUnclassifiedDeclaration() throws Exception {
        MavenProject p = project("app");
        Dependency d = new Dependency();
        d.setGroupId(BasquinInjector.GROUP_ID);
        d.setArtifactId(BasquinInjector.ARTIFACT_ID);
        d.setVersion(InjectorVersion.value());
        d.setType("jar");
        p.getModel().getDependencies().add(d);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
        assertEquals(1, p.getRemoteArtifactRepositories().size());
    }

    /**
     * {@code optional=true} is the one shape the usability whitelist deliberately accepts even though it is
     * NOT identical to what the injector would add (which sets no optional flag). It is accepted on a
     * measurement, not on an argument: rest-villains built with {@code <optional>true</optional>} and this
     * injector on {@code maven.ext.class.path} put both basquin jars in {@code quarkus-app/lib/main/},
     * banner-listed {@code basquin} in Installed features, and answered {@code /__basquin/result} with a
     * cost line rather than {@code "miss"}. The captured output is in the tree at
     * {@code bench-results/dd043-pr3-optional-declaration-2026-07-29/}, cited line by line from
     * {@code failOnUnusableDeclaration}'s javadoc. This test exists so that accepting it stays a decision:
     * if someone later adds an {@code isOptional()} branch to the guard, this fails and sends them to that
     * evidence first. It measures a <b>direct</b> optional only; a transitive one is unmeasured.
     */
    @Test
    public void acceptsAnOptionalDeclarationBecauseADirectOptionalStillReachesTheClasspath()
            throws Exception {
        MavenProject p = project("app");
        Dependency d = new Dependency();
        d.setGroupId(BasquinInjector.GROUP_ID);
        d.setArtifactId(BasquinInjector.ARTIFACT_ID);
        d.setVersion(InjectorVersion.value());   // matching version, so only the usability guard can fire
        d.setOptional(true);
        p.getModel().getDependencies().add(d);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals("must not duplicate the declaration", 1, p.getModel().getDependencies().size());
        assertEquals("the repository is still required", 1, p.getRemoteArtifactRepositories().size());
        assertTrue("the injector must not silently rewrite the declaration it accepted",
                p.getModel().getDependencies().get(0).isOptional());
    }

    /** The two usable scopes must still be accepted, or the guard breaks legitimate targets. */
    @Test
    public void acceptsCompileAndRuntimeScopedDeclarations() throws Exception {
        for (String scope : new String[] {"compile", "runtime"}) {
            MavenProject p = project("app");
            declareAtScope(p, scope);

            new BasquinInjector().inject(Arrays.asList(p), new Properties());

            assertEquals("scope '" + scope + "': no duplicate added",
                    1, p.getModel().getDependencies().size());
            assertEquals("scope '" + scope + "': the repository is still required",
                    1, p.getRemoteArtifactRepositories().size());
        }
    }

    /** An absent {@code <scope>} means compile in Maven, so it must be accepted like an explicit one. */
    @Test
    public void acceptsADeclarationWithNoExplicitScope() throws Exception {
        MavenProject p = project("app");
        declareAtScope(p, null);

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals(1, p.getModel().getDependencies().size());
    }

    /** A declared com.basquin artifact that is NOT basquin-quarkus — the S2 path. */
    private static Dependency declareSibling(MavenProject p, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(BasquinInjector.GROUP_ID);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        p.getModel().getDependencies().add(d);
        return d;
    }

    /**
     * The SEVENTH bypass, found by PR #103's round-4 approver as S2. {@code declaredDependency} matches on
     * groupId AND artifactId, so a declared {@code com.basquin:basquin-core} was seen by no guard at all,
     * while the identical shape in {@code dependencyManagement} hard-fails — an asymmetry, not a policy.
     *
     * <p>Measured against a real Maven 3.9.15 build with the shipped injector, not argued: the declared
     * conflicting version won nearest-wins over the extension's own transitive core, the resolved set held
     * {@code basquin-core:jar:0.0.1-conflicting:compile} beside {@code basquin-quarkus:jar:0.3.0:compile},
     * and the build SUCCEEDED — {@code bench-results/dd043-pr3-r4-guard-measurement-2026-07-29/}
     * {@code logs/dcv-stock-list.log:111-112,208}, cited line by line from
     * {@code failOnUnusableSiblingDeclaration}'s javadoc.
     */
    @Test
    public void failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAConflictingVersion() {
        MavenProject p = project("app");
        declareSibling(p, "basquin-core", "0.0.1-conflicting");

        try {
            new BasquinInjector().inject(Arrays.asList(p), new Properties());
            fail("a declared basquin-core at a conflicting version wins over the extension's own "
                    + "transitive core, so it must not be accepted silently");
        } catch (MavenExecutionException e) {
            String m = e.getMessage();
            assertTrue("message must name the artifact: " + m, m.contains("basquin-core"));
            assertTrue("message must name the conflicting version: " + m, m.contains("0.0.1-conflicting"));
            assertTrue("message must name the version we supply: " + m,
                    m.contains(InjectorVersion.value()));
            assertTrue("message must name an escape hatch: " + m,
                    m.contains(BasquinInjector.PROP_VERSION) || m.contains(BasquinInjector.PROP_SKIP));
        }
    }

    /**
     * The other measured sibling hazard. A direct declaration wins the SCOPE for that artifact, so a
     * test- or provided-scoped basquin-core is off the application's runtime classpath while the
     * extension this injector adds is still at compile — measured as
     * {@code basquin-core:jar:0.3.0:test} ({@code logs/dsc-stock-list.log:12}) and
     * {@code basquin-core:jar:0.3.0:provided} ({@code logs/dprov-stock-list.log:12}), both with the build
     * succeeding. The version guard cannot fire here: the version agrees exactly.
     */
    @Test
    public void failsLoudlyWhenAnotherBasquinArtifactIsDeclaredAtAnUnusableScope() {
        for (String scope : new String[] {"test", "provided", "system", "import"}) {
            MavenProject p = project("app");
            declareSibling(p, "basquin-core", InjectorVersion.value()).setScope(scope);
            try {
                new BasquinInjector().inject(Arrays.asList(p), new Properties());
                fail("scope '" + scope + "' keeps basquin-core off the application's runtime classpath, "
                        + "so it must not be accepted");
            } catch (MavenExecutionException e) {
                assertTrue("message must name the offending scope: " + e.getMessage(),
                        e.getMessage().contains(scope));
                assertTrue("message must name the artifact: " + e.getMessage(),
                        e.getMessage().contains("basquin-core"));
                assertTrue("message must say the build would be uninstrumented: " + e.getMessage(),
                        e.getMessage().contains("UNINSTRUMENTED"));
            }
        }
    }

    /**
     * A sibling at the version we supply is what the extension would have resolved anyway — accept it, and
     * still inject. Measured: {@code basquin-core:jar:0.3.0:compile} beside
     * {@code basquin-quarkus:jar:0.3.0:compile}, build succeeded ({@code logs/dag-stock-list.log:12-13}).
     */
    @Test
    public void acceptsAnotherBasquinArtifactDeclaredAtTheInjectedVersion() throws Exception {
        MavenProject p = project("app");
        declareSibling(p, "basquin-core", InjectorVersion.value());

        new BasquinInjector().inject(Arrays.asList(p), new Properties());

        assertEquals("the sibling stays and basquin-quarkus is added beside it",
                2, p.getModel().getDependencies().size());
        assertEquals("the repository is still required", 1, p.getRemoteArtifactRepositories().size());
    }

    /**
     * The three sibling shapes the guard deliberately does NOT reject, and it rejects nothing else, because
     * each was measured harmless rather than reasoned about. {@code type} and {@code classifier} are part of
     * the resolution key, so such a declaration is a different node and the plain transitive core still
     * arrives — 96 resolved artifacts against the control's 95, with BOTH the deviant node and
     * {@code basquin-core:jar:0.3.0:runtime} present ({@code logs/dty-stock-list.log:13,15},
     * {@code logs/dcl-stock-list.log:56,58}); an {@code <exclusions>} on a declared basquin-core strips that
     * node's own transitives, of which basquin-core has none, and the core still resolved at compile
     * ({@code logs/dex-stock-list.log:12}).
     *
     * <p>This test exists so that accepting them stays a decision: if someone later widens this guard to
     * {@code failOnUnusableDeclaration}'s full four-field whitelist, this fails and sends them to that
     * evidence first — because widening it would hard-fail three builds that demonstrably work.
     */
    @Test
    public void acceptsTheSiblingShapesMeasuredHarmless() throws Exception {
        for (String shape : new String[] {"type", "classifier", "exclusions"}) {
            MavenProject p = project("app");
            Dependency d = declareSibling(p, "basquin-core", InjectorVersion.value());
            if ("type".equals(shape)) {
                d.setType("pom");
            } else if ("classifier".equals(shape)) {
                d.setClassifier("tests");
            } else {
                Exclusion ex = new Exclusion();
                ex.setGroupId("*");
                ex.setArtifactId("*");
                d.addExclusion(ex);
            }

            new BasquinInjector().inject(Arrays.asList(p), new Properties());

            assertEquals(shape + ": must be accepted and basquin-quarkus added beside it",
                    2, p.getModel().getDependencies().size());
            assertEquals(shape + ": the repository is still required",
                    1, p.getRemoteArtifactRepositories().size());
        }
    }

    /**
     * The sibling guard's message advertises the same version override the declared-artifact guard does, so
     * it must actually resolve the conflict — a guard that tells an operator to do something that does not
     * help is worse than a bare failure.
     */
    @Test
    public void theAdvertisedVersionOverrideResolvesTheSiblingVersionConflict() throws Exception {
        MavenProject p = project("app");
        declareSibling(p, "basquin-core", "0.0.1-different");

        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_VERSION, "0.0.1-different");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals("the sibling stays and basquin-quarkus is added beside it",
                2, p.getModel().getDependencies().size());
        assertEquals("the injector must supply the version the operator aligned on",
                "0.0.1-different", p.getModel().getDependencies().get(1).getVersion());
    }

    /**
     * The escape hatch the failure message advertises must actually work — otherwise the guard tells
     * an operator to do something that does not help, which is worse than a bare failure.
     */
    @Test
    public void theAdvertisedVersionOverrideResolvesTheDeclaredVersionConflict() throws Exception {
        MavenProject p = project("app");
        Dependency existing = new Dependency();
        existing.setGroupId(BasquinInjector.GROUP_ID);
        existing.setArtifactId(BasquinInjector.ARTIFACT_ID);
        existing.setVersion("0.0.1-different");
        p.getModel().getDependencies().add(existing);

        Properties props = new Properties();
        props.setProperty(BasquinInjector.PROP_VERSION, "0.0.1-different");

        new BasquinInjector().inject(Arrays.asList(p), props);

        assertEquals("no duplicate added", 1, p.getModel().getDependencies().size());
        assertEquals("the repository is still required", 1, p.getRemoteArtifactRepositories().size());
    }
}
