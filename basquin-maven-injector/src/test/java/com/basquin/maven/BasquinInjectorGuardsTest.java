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
