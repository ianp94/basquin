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
        existing.setVersion("0.3.0");
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
}
