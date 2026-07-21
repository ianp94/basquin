package test;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies that a very low latency threshold triggers an invariant failure.
 */
public class InvariantLatencyTest {

    @Test
    public void lowLatencyThresholdShouldFail() throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("-Dbasquin.target=examples.targets.ThreadLeakTarget");
        cmd.add("-Dexamples.mode=proper");
        cmd.add("-Dbasquin.invariant.latency.maxMs=1"); // practically guaranteed to exceed
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

        assertNotEquals("Run should exit non-zero due to latency invariant", 0, exit);
        assertTrue("Output should mention invariant violation", output.contains("[Invariant]") || output.contains("Latency invariant"));
    }
}

