package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
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

    /**
     * PR #103's approver, finding 5: the previous test above asserted only {@code id} and
     * {@code url}, so a mutant flipping {@code releases.setEnabled(false)} at
     * {@code BasquinInjector.java:527-531} (the model {@link RepositoryPolicy} pair) — or drifting
     * either {@link ArtifactRepositoryPolicy} at {@code :540-545} (the effective pair, the half
     * spike S5 exists to protect) — passed every test in this file while leaving the injected
     * repository unable to serve the release artifacts it exists for. Pins every field
     * {@code addRepository} actually sets, at both levels, so such a change fails loudly.
     */
    @Test
    public void pinsTheInjectedRepositorysReleaseAndSnapshotPolicies() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        Repository modelRepo = p.getModel().getRepositories().get(0);
        assertEquals(BasquinInjector.REPO_ID, modelRepo.getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, modelRepo.getUrl());
        assertTrue("model releases must be enabled — this is a release repository",
                modelRepo.getReleases().isEnabled());
        assertFalse("model snapshots must be disabled — S5 only measured releases",
                modelRepo.getSnapshots().isEnabled());

        ArtifactRepository effective = p.getRemoteArtifactRepositories().get(0);
        assertEquals(BasquinInjector.REPO_ID, effective.getId());
        assertEquals(BasquinInjector.DEFAULT_REPO_URL, effective.getUrl());
        assertTrue("effective repository must use Maven's default layout",
                effective.getLayout() instanceof DefaultRepositoryLayout);

        ArtifactRepositoryPolicy effectiveReleases = effective.getReleases();
        assertTrue("effective releases must be enabled — the half that actually resolves",
                effectiveReleases.isEnabled());
        assertEquals(ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, effectiveReleases.getUpdatePolicy());
        assertEquals(ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN,
                effectiveReleases.getChecksumPolicy());

        ArtifactRepositoryPolicy effectiveSnapshots = effective.getSnapshots();
        assertFalse("effective snapshots must be disabled", effectiveSnapshots.isEnabled());
        assertEquals(ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, effectiveSnapshots.getUpdatePolicy());
        assertEquals(ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN,
                effectiveSnapshots.getChecksumPolicy());
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

    /**
     * DD-043 PR-4, Task 2. Mirrors {@link #injectsTheDependencyIntoEveryProjectInTheReactor}: the
     * jacoco offline-instrument execution is a second, independent mutation this injector makes to
     * every project in the reactor, not only to the one carrying basquin-quarkus.
     */
    @Test
    public void injectsTheJacocoInstrumentExecutionIntoEveryProjectInTheReactor() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (MavenProject p : reactor) {
            List<Plugin> plugins = p.getModel().getBuild().getPlugins();
            assertEquals("exactly one plugin injected into " + p.getArtifactId(), 1, plugins.size());
            Plugin plugin = plugins.get(0);
            assertEquals(BasquinInjector.JACOCO_GROUP_ID, plugin.getGroupId());
            assertEquals(BasquinInjector.JACOCO_ARTIFACT_ID, plugin.getArtifactId());
            assertEquals(JacocoVersion.value(), plugin.getVersion());
        }
    }

    /**
     * Pins every field {@link BasquinInjector#addJacocoInstrumentExecution} sets on the
     * {@link PluginExecution}, not just the plugin coordinate — the same PR #103 round-5-finding-5
     * discipline {@link #pinsTheInjectedRepositorysReleaseAndSnapshotPolicies} already applies to the
     * repository injection. The id and phase are what the §8.2 spike measured reaching Maven's
     * execution plan; the goal is what makes the execution actually run {@code instrument} rather than
     * some other jacoco mojo.
     */
    @Test
    public void pinsTheInjectedJacocoExecutionsIdPhaseAndGoal() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        Plugin plugin = p.getModel().getBuild().getPlugins().get(0);
        assertEquals(1, plugin.getExecutions().size());
        PluginExecution exec = plugin.getExecutions().get(0);
        assertEquals(BasquinInjector.JACOCO_EXECUTION_ID, exec.getId());
        assertEquals(BasquinInjector.JACOCO_PHASE, exec.getPhase());
        assertEquals(Arrays.asList(BasquinInjector.JACOCO_GOAL), exec.getGoals());
    }

    /**
     * The version injected is never a hand-typed literal — it is baked from the shared root
     * {@code gradle.properties jacocoVersion} property via {@link JacocoVersion#value()}, the same
     * lockstep {@code JacocoVersionLockstepTest} checks against the checked-in property text.
     */
    @Test
    public void injectedJacocoExecutionUsesTheSharedGradlePropertyVersion() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        assertEquals(JacocoVersion.value(), p.getModel().getBuild().getPlugins().get(0).getVersion());
    }

    /**
     * A1 (docs/superpowers/plans/2026-08-10-dd043-pr4-coverage.md): the jacoco runtime dependency
     * arrives transitively through basquin-quarkus's own {@code implementation
     * org.jacoco:org.jacoco.agent:runtime} dependency, never as a second thing this injector adds
     * directly. Only the basquin-quarkus dependency itself may appear in the model's dependency list.
     */
    @Test
    public void doesNotAddAJacocoRuntimeDependencyDirectly() throws Exception {
        MavenProject p = project("a");

        new BasquinInjector().inject(Arrays.asList(p), props());

        List<Dependency> deps = p.getModel().getDependencies();
        assertEquals("only the basquin-quarkus dependency, never a jacoco one", 1, deps.size());
        assertEquals(BasquinInjector.ARTIFACT_ID, deps.get(0).getArtifactId());
    }

    /**
     * §5's aliasing rule, extended to the jacoco mutation: Maven's model objects are mutable, so a
     * {@link Plugin}/{@link PluginExecution} instance hoisted above the per-project loop would alias
     * across the whole reactor, and a later in-place mutation on one module's copy would bleed into
     * every other module's. Mirrors {@link #allocatesFreshModelObjectsPerProject}, which pins the same
     * property for the dependency/repository mutations — a single-module reactor cannot catch either.
     */
    @Test
    public void allocatesFreshJacocoPluginObjectsPerProject() throws Exception {
        List<MavenProject> reactor = Arrays.asList(project("a"), project("b"), project("c"));

        new BasquinInjector().inject(reactor, props());

        for (int i = 0; i < reactor.size(); i++) {
            for (int j = i + 1; j < reactor.size(); j++) {
                Plugin pluginI = reactor.get(i).getModel().getBuild().getPlugins().get(0);
                Plugin pluginJ = reactor.get(j).getModel().getBuild().getPlugins().get(0);
                assertNotSame("Plugin aliased across projects", pluginI, pluginJ);
                assertNotSame("PluginExecution aliased across projects",
                        pluginI.getExecutions().get(0), pluginJ.getExecutions().get(0));
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
