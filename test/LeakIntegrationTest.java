package test;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Forked integration test that runs runner.Runner with a leaking demo target
 * and asserts the process fails (indicating leak detection worked).
 */
public class LeakIntegrationTest {

    @Test
    public void leakRunShouldFailProcess() throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("-Dclosurejvm.target=examples.targets.ThreadLeakTarget");
        cmd.add("-Dexamples.mode=leak");
        cmd.add("-Dexamples.sleepMs=50");
        cmd.add("-Dexamples.threads=2");
        cmd.add("-Dclosurejvm.forceExitOnLeak=true");
        cmd.add("runner.Runner");
        cmd.add("1");

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

        // Process should fail due to IllegalStateException thrown by Agent.endIteration()
        assertNotEquals("Leak run should exit with non-zero status", 0, exit);
        assertTrue("Output should mention leak detection",
                output.contains("Thread leak detected") || output.contains("Leak(s) detected"));
    }
}
