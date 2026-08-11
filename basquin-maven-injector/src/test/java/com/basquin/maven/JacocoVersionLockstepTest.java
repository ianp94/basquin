package com.basquin.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * DD-043 PR-4 precursor (version lockstep). {@link JacocoVersion#value()} is generated at build
 * time from the shared root {@code gradle.properties} {@code jacocoVersion} property — the same
 * property the runner's {@code org.jacoco.core} dependency and the {@code jacocoAgent}
 * Tomcat-agent-jar configuration consume (root {@code build.gradle}).
 *
 * <p>This test reads that checked-in property file directly and compares it to the generated
 * constant, the same technique {@code verifyGradleInitScriptVersion} (this module's build.gradle)
 * uses on {@code basquin-init.gradle}'s checked-in text, rather than trusting the generation
 * wiring never drifts. A hardcoded literal slipped into {@code writeJacocoVersionResource} instead
 * of reading the property, or a stale generated resource surviving a property bump, both show up
 * here as a mismatch rather than as a silent skew. Drift is not cosmetic: offline-instrumented
 * classes reference the version-specific shaded package
 * {@code org.jacoco.agent.rt.internal_<hash>}, so a plugin/agent version mismatch silently breaks
 * the coverage read instead of failing loudly.
 */
public class JacocoVersionLockstepTest {

    private static final Pattern JACOCO_VERSION_LINE = Pattern.compile("(?m)^jacocoVersion=(\\S+)\\s*$");

    @Test
    public void injectorConstantMatchesTheSharedGradleProperty() throws IOException {
        File repoRoot = findRepoRoot(new File(System.getProperty("user.dir")));
        File propsFile = new File(repoRoot, "gradle.properties");
        assertTrue(propsFile.getPath() + " must exist as the single shared source of truth for the "
                + "JaCoCo version", propsFile.isFile());

        String text = new String(Files.readAllBytes(propsFile.toPath()), StandardCharsets.UTF_8);
        Matcher m = JACOCO_VERSION_LINE.matcher(text);
        if (!m.find()) {
            fail("could not find a 'jacocoVersion=<version>' line in " + propsFile + " — either the "
                    + "property was renamed/removed, or this regex no longer matches its shape. "
                    + "Update this test to match the new shape rather than deleting the check.");
        }
        String sharedVersion = m.group(1);

        assertEquals("JacocoVersion.value() (generated from the shared root gradle.properties "
                + "jacocoVersion property) has drifted from that property's checked-in value. "
                + "Offline-instrumented classes reference the version-specific shaded package "
                + "org.jacoco.agent.rt.internal_<hash> — a plugin/agent version mismatch silently "
                + "breaks the coverage read rather than failing loudly.",
                sharedVersion, JacocoVersion.value());
    }

    /**
     * Walk up from the test's working directory (the injector module's project directory, under
     * the default Gradle {@code Test} task working-directory convention) looking for the marker
     * that names repo root, rather than assuming a fixed number of parent hops.
     */
    private static File findRepoRoot(File start) {
        File dir = start;
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "settings.gradle").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("could not locate repo root (settings.gradle) walking up "
                + "from " + start + " — is the test working directory unexpected?");
    }
}
