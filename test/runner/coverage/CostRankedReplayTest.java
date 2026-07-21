package runner.coverage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

/**
 * Integration pin for DD-031's wiring into {@code CoverageGuidedRun}: {@code lastCorpus} is now a
 * {@code CostCorpus}, and {@code writeSummary} must emit its replay corpus in {@code
 * snapshotByCost()} order end to end — cost-descending when cost tracking is enabled, and exactly
 * today's insertion order when {@code basquin.cost.enabled=false} (the kill-switch must fully
 * restore prior behavior, not just "mostly"). Lives in {@code runner.coverage} to reach the
 * package-private {@code lastCorpus} field and {@code writeSummary} method.
 */
public class CostRankedReplayTest {

    @Test
    public void enabledPutsTheHighCostInputFirstInTheWrittenSummary() throws Exception {
        CostCorpus corpus = new CostCorpus(Arrays.asList("/cheap"), true);
        // A coverage-independent, high-cost observation: retained purely for cost once trained past
        // the cold-start guard (defaults: minSamples=20). Feed enough cheap samples to train the EMA,
        // then one expensive one that must land ahead of everything else once ranked by cost.
        for (int i = 0; i < 25; i++) {
            corpus.consider("/warm" + i, 5.0, 1, 0, 0, 0, false);
        }
        corpus.consider("/expensive", 5000.0, 1, 0, 0, 0, false);

        CoverageGuidedRun.lastCorpus = corpus;
        Path tmp = Files.createTempFile("summary-cost-enabled", ".json");
        tmp.toFile().deleteOnExit();
        CoverageGuidedRun.writeSummary(tmp.toString());

        String written = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8);
        int corpusStart = written.indexOf("\"replayCorpus\":[");
        assertTrue("summary must carry a replayCorpus array", corpusStart >= 0);
        int expensiveIdx = written.indexOf("\"/expensive\"");
        int cheapIdx = written.indexOf("\"/cheap\"");
        assertTrue("both inputs must appear", expensiveIdx > 0 && cheapIdx > 0);
        assertTrue("the high-cost input must be ranked ahead of a zero-cost seed",
                expensiveIdx < cheapIdx);
    }

    @Test
    public void disabledPreservesInsertionOrderInTheWrittenSummary() throws Exception {
        // The kill-switch: with cost tracking off, CostCorpus.snapshotByCost() falls back to
        // insertion order — identical to the pre-DD-031 List<String> corpus behavior. All three are
        // coverage finds (the only kind retained when disabled); "/second" carries a huge cost value
        // purely to prove it does NOT get promoted to the front when cost ranking is off.
        CostCorpus corpus = new CostCorpus(Arrays.asList("/first"), false);
        corpus.consider("/second", 9999.0, 1, 9999, 5, 5, true);
        corpus.consider("/third", 0.0, 0, 0, 0, 0, true);

        CoverageGuidedRun.lastCorpus = corpus;
        Path tmp = Files.createTempFile("summary-cost-disabled", ".json");
        tmp.toFile().deleteOnExit();
        CoverageGuidedRun.writeSummary(tmp.toString());

        String written = new String(Files.readAllBytes(tmp), StandardCharsets.UTF_8);
        int firstIdx = written.indexOf("\"/first\"");
        int secondIdx = written.indexOf("\"/second\"");
        int thirdIdx = written.indexOf("\"/third\"");
        assertTrue("all three inputs must appear", firstIdx > 0 && secondIdx > 0 && thirdIdx > 0);
        assertTrue("insertion order preserved when disabled",
                firstIdx < secondIdx && secondIdx < thirdIdx);
    }
}
