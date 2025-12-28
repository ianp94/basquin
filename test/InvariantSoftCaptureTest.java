package test;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Ensures soft-mode invariants are detected and reported during corpus replay.
 */
public class InvariantSoftCaptureTest {

    @Test
    public void latencyViolationIsCapturedInSoftMode() throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("-Dclosurejvm.invariant.heapDelta.maxKb=64");
        cmd.add("-Dclosurejvm.invariant.mode=soft");
        cmd.add("runner.CorpusRunner");
        cmd.add("examples.targets.HeapFuzzTarget");
        cmd.add("examples/corpus/heap");

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

        // Soft mode should not crash process; should report violations
        assertEquals(0, exit);
        assertTrue("Should report violations in summary", output.contains("Violations="));
        assertTrue("Violations should be non-zero", !output.contains("Violations=0"));
    }
}
