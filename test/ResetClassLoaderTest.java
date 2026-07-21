package test;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ensures classloader reset fallback can recover from a deliberate failure.
 */
public class ResetClassLoaderTest {

    @Test
    public void resetShouldRecoverAndContinue() throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("-Dbasquin.reset=classloader");
        cmd.add("-Dbasquin.reset.onFailure=true");
        cmd.add("runner.GenericRunner");
        cmd.add("2");
        cmd.add("examples.targets.ResetSmokeTarget");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }

        int exit = p.waitFor();
        String output = out.toString();

        assertEquals("Run should succeed after reset", 0, exit);
        assertTrue("Should report classloader reset", output.contains("Performed classloader reset"));
        assertTrue("Should complete two iterations", output.contains("Iteration 2"));
    }
}

