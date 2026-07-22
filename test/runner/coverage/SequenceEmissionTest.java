package runner.coverage;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DD-035 (Task 6): today the explore engine emits only the LAST step of a coverage-finding
 * sequence to the replay corpus, which orphans it — replaying that single tail step (e.g. a
 * checkout POST with no cart, no session) 500s. These pin the three coupled fixes: (A) whole-
 * sequence, method-aware emission; (B) multi-step seed lines routed to {@code pendingSequences}
 * instead of being fired as one garbled URL; (C) {@code mutate} treats a TAB-joined sequence as
 * replay-only and returns it unchanged. Lives in {@code runner.coverage} to reach the
 * package-private seams ({@code formatSequenceForCorpus}, {@code splitSeeds}, {@code mutate},
 * {@code replayCorpusJson}).
 */
public class SequenceEmissionTest {

    // --- (A) whole-sequence, method-aware emission ------------------------------------------

    @Test
    public void keptThreeStepSequenceEmitsAsAThreeFieldTabLine() {
        List<String> sequence = Arrays.asList(
                "/actions/Account.action?signonForm=",
                "POST /actions/Account.action?signon= username=j2ee&password=j2ee",
                "POST /actions/Cart.action?addItem= workingItemId=EST-1");

        String emitted = CoverageGuidedRun.formatSequenceForCorpus(sequence);

        String[] fields = emitted.split("\t", -1);
        assertEquals("must be a 3-field TAB line, not the bare tail", 3, fields.length);
        assertEquals("POST /actions/Cart.action?addItem= workingItemId=EST-1",
                fields[2]); // last step preserved
        assertTrue("method-aware: the POST steps must carry their method",
                fields[1].startsWith("POST "));
        assertFalse("must not degrade to just the last step",
                emitted.equals(sequence.get(sequence.size() - 1)));

        // End-to-end through the actual retention path: a sequence coverage-find is retained
        // and replayed as the whole line, not the orphaned tail.
        CostCorpus corpus = new CostCorpus(Arrays.asList("/seed"), false);
        corpus.consider(emitted, 0.0, 0, 0, 0, 0, true);
        boolean found = false;
        for (CorpusEntry e : corpus.snapshotByCost()) {
            if (e.input.equals(emitted)) { found = true; break; }
        }
        assertTrue("whole sequence must be retained verbatim in the corpus", found);
    }

    // --- (B) multi-step seeds route to pendingSequences, not `request` -----------------------

    @Test
    public void singleStepSeedGoesToTheFiredSeedsList() {
        CoverageGuidedRun.SeedSplit split = CoverageGuidedRun.splitSeeds(
                Arrays.asList("/actions/Catalog.action", "POST /actions/Cart.action x=1"));
        assertEquals(2, split.singleStep.size());
        assertEquals(0, split.sequences.size());
    }

    @Test
    public void multiStepSeedLineGoesToPendingSequencesNotTheFiredSeedsList() {
        String line = "POST /actions/Account.action?signon= username=j2ee&password=j2ee"
                + "\tPOST /actions/Cart.action?addItem= workingItemId=EST-1";

        CoverageGuidedRun.SeedSplit split = CoverageGuidedRun.splitSeeds(Arrays.asList(line));

        assertEquals("must NOT be fired as a single URL via the seeds list", 0, split.singleStep.size());
        assertEquals(1, split.sequences.size());
        List<String> seq = split.sequences.get(0);
        assertEquals(2, seq.size());
        assertTrue(seq.get(0).startsWith("POST "));
        assertTrue(seq.get(1).startsWith("POST "));
    }

    @Test
    public void roundTripEmittedSequenceLandsBackInPendingSequences() {
        // (c) round-trip: an emitted multi-step corpus line, re-parsed via the seed-splitting
        // seam, lands as a multi-step sequence — not fired as one URL.
        List<String> original = Arrays.asList(
                "/actions/Account.action?signonForm=",
                "POST /actions/Account.action?signon= username=j2ee&password=j2ee",
                "POST /actions/Cart.action?addItem= workingItemId=EST-1");
        String emitted = CoverageGuidedRun.formatSequenceForCorpus(original);

        CoverageGuidedRun.SeedSplit split = CoverageGuidedRun.splitSeeds(Arrays.asList(emitted));

        assertEquals(0, split.singleStep.size());
        assertEquals(1, split.sequences.size());
        assertEquals(3, split.sequences.get(0).size());
        assertEquals("POST /actions/Cart.action?addItem= workingItemId=EST-1",
                split.sequences.get(0).get(2));
    }

    // --- (C) mutation guard: replay-only sequences --------------------------------------------

    @Test
    public void mutateReturnsAMultiStepSequenceUnchanged() {
        Random rnd = new Random(42);
        String seq = "POST /a x=1\tPOST /b y=2";
        assertEquals(seq, CoverageGuidedRun.mutate(seq, rnd));
    }

    @Test
    public void mutateStillMutatesASingleStepInputWithADictionaryParam() {
        Random rnd = new Random(1);
        String single = "/actions/Catalog.action?categoryId=FISH";
        String mutated = CoverageGuidedRun.mutate(single, rnd);
        assertTrue("single-step inputs are still mutated as before",
                mutated.startsWith("/actions/Catalog.action?categoryId="));
    }

    // --- (d) replayCorpusJson: a whole multi-step entry that overflows the budget is dropped whole

    @Test
    public void oversizedMultiStepEntryIsDroppedWholeAndArrayStaysValid() {
        String small = "/a";
        String big = "POST /actions/Account.action?signon= username=j2ee&password=j2ee"
                + "\tPOST /actions/Cart.action?addItem= workingItemId=EST-1"
                + "\tPOST /actions/Order.action?newOrderForm=";

        // Budget fits "small" plus the array brackets/comma but not the long multi-step "big" entry.
        int budget = ("[\"" + small + "\"]").length() + 4;
        String json = CoverageGuidedRun.replayCorpusJson(Arrays.asList(small, big), budget);

        assertTrue("valid JSON array", json.startsWith("[") && json.endsWith("]"));
        assertTrue("the small entry that fits must be present", json.contains("\"" + small + "\""));
        assertFalse("the oversized multi-step entry must be dropped WHOLE, not truncated mid-step",
                json.contains("Account.action"));
        assertFalse("must not contain a partial/corrupted fragment of the big entry",
                json.contains("\\t"));
    }
}
