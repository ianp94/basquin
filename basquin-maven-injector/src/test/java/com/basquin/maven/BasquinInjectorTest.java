package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

/**
 * DD-043 PR-3. Drives {@link BasquinInjector#inject(List, Properties)} directly rather than
 * {@code afterProjectsRead}: a real {@code MavenSession} needs a Plexus container, a repository
 * session and a full execution request, while {@code getProjects()} is the only thing the
 * participant reads from it.
 */
public class BasquinInjectorTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static MavenProject project(String artifactId) {
        MavenProject p = new MavenProject();
        p.setArtifactId(artifactId);
        p.setRemoteArtifactRepositories(new ArrayList<>());
        return p;
    }

    @Test
    public void injectsTheDependencyIntoEveryProjectInTheReactor() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (MavenProject p : reactor) {
            List<Dependency> deps = p.getModel().getDependencies();
            assertEquals("exactly one dependency injected into " + p.getArtifactId(), 1, deps.size());
            assertEquals(BasquinInjector.GROUP_ID, deps.get(0).getGroupId());
            assertEquals(BasquinInjector.ARTIFACT_ID, deps.get(0).getArtifactId());
        }
    }

    /**
     * The version injected is the injector's own, baked from {@code project.version} — never a
     * literal in Java source.
     */
    @Test
    public void injectedVersionIsTheInjectorsOwnBakedVersion() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        assertEquals(InjectorVersion.value(), p.getModel().getDependencies().get(0).getVersion());
    }

    /**
     * The load-bearing half. A {@code Repository} on the Model alone is a no-op at
     * {@code afterProjectsRead} — effective repository lists are already computed. The participant
     * must also refresh {@code getRemoteArtifactRepositories()}, which is what Maven resolution and
     * quarkus-maven-plugin consume. Measured in spike S5.
     */
    @Test
    public void injectsTheRepositoryAtBothTheModelAndEffectiveListLevels() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        List<Repository> modelRepos = p.getModel().getRepositories();
        assertEquals(1, modelRepos.size());
        assertEquals(BasquinInjector.REPO_ID, modelRepos.get(0).getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, modelRepos.get(0).getUrl());

        List<ArtifactRepository> effective = p.getRemoteArtifactRepositories();
        assertEquals("the effective list is the half that actually resolves", 1, effective.size());
        assertEquals(BasquinInjector.REPO_ID, effective.get(0).getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, effective.get(0).getUrl());
    }

    @Test
    public void repositoryUrlIsOverridable() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p),
                props(BasquinInjector.PROP_REPO_URL, "http://localhost:8000/"));

        assertEquals("http://localhost:8000/", p.getModel().getRepositories().get(0).getUrl());
        assertEquals("http://localhost:8000/", p.getRemoteArtifactRepositories().get(0).getUrl());
    }

    @Test
    public void injectedVersionIsOverridable() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props(BasquinInjector.PROP_VERSION, "9.9.9"));

        assertEquals("9.9.9", p.getModel().getDependencies().get(0).getVersion());
    }

    /**
     * Maven's model objects are mutable, so one hoisted allocation would alias a single instance
     * across the whole reactor and a later in-place mutation on one module would bleed into all the
     * others. A single-module reactor cannot catch this, which is exactly why the test uses three.
     */
    @Test
    public void allocatesFreshModelObjectsPerProject() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (int i = 0; i < reactor.size(); i++) {
            for (int j = i + 1; j < reactor.size(); j++) {
                assertNotSame("Dependency aliased across projects",
                        reactor.get(i).getModel().getDependencies().get(0),
                        reactor.get(j).getModel().getDependencies().get(0));
                assertNotSame("Repository aliased across projects",
                        reactor.get(i).getModel().getRepositories().get(0),
                        reactor.get(j).getModel().getRepositories().get(0));
                assertNotSame("ArtifactRepository aliased across projects",
                        reactor.get(i).getRemoteArtifactRepositories().get(0),
                        reactor.get(j).getRemoteArtifactRepositories().get(0));
            }
        }
    }

    /** Pre-existing repositories must survive — we append, never replace. */
    @Test
    public void preservesRepositoriesTheProjectAlreadyHad() throws Exception {
        MavenProject p = project("a");
        ArtifactRepository central = new org.apache.maven.artifact.repository.MavenArtifactRepository(
                "central", "https://repo.maven.apache.org/maven2",
                new org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout(), null, null);
        p.setRemoteArtifactRepositories(new ArrayList<>(Arrays.asList(central)));

        new BasquinInjector().inject(Arrays.asList(p), props());

        List<ArtifactRepository> effective = p.getRemoteArtifactRepositories();
        assertEquals(2, effective.size());
        assertSame("the project's own repository must not be dropped", central, effective.get(0));
        assertTrue("ours is appended after", BasquinInjector.REPO_ID.equals(effective.get(1).getId()));
    }
}
