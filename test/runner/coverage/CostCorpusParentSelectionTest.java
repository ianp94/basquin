package runner.coverage;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-035 review fix: a replay-only, TAB-joined multi-step sequence entry (emitted by Task 6's
 * whole-sequence corpus emission) must NEVER be handed back by {@code selectParent} as a
 * single-request mutation parent — firing it through {@code mutate}/{@code request} as one URL
 * manufactures a garbled request and a false-positive crash finding. It MUST still appear in
 * {@code snapshotByCost} so load-mode replay still gets the sequence.
 */
public class CostCorpusParentSelectionTest {

    private static final String SEQUENCE_ENTRY =
            "POST /actions/Account.action?signon= username=j2ee&password=j2ee"
                    + "\tPOST /actions/Cart.action?addItem= workingItemId=EST-1";

    @After public void clearProps() {
        System.clearProperty("basquin.pheromone");
    }

    @Test
    public void selectParentNeverReturnsATabSequenceEntryUniform() {
        CostCorpus corpus = new CostCorpus(Arrays.asList(), false);
        corpus.consider("/single-step", 1.0, 1, 0, 0, 0, true);
        corpus.consider(SEQUENCE_ENTRY, 0.0, 0, 0, 0, 0, true);

        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            CorpusEntry picked = corpus.selectParent(rnd);
            assertTrue("selectParent must never hand back a TAB-joined sequence as a mutation parent",
                    picked == null || picked.input.indexOf('\t') < 0);
            if (picked != null) assertEquals("/single-step", picked.input);
        }
    }

    @Test
    public void selectParentNeverReturnsATabSequenceEntryPheromoneWeighted() {
        CostCorpus corpus = new CostCorpus(Arrays.asList(), false, true);
        corpus.consider("/single-step", 1.0, 1, 0, 0, 0, true);
        corpus.consider(SEQUENCE_ENTRY, 0.0, 0, 0, 0, 0, true);

        Random rnd = new Random(11);
        for (int i = 0; i < 500; i++) {
            CorpusEntry picked = corpus.selectParent(rnd);
            assertTrue("pheromone-weighted selection must also exclude TAB sequence entries",
                    picked == null || picked.input.indexOf('\t') < 0);
            if (picked != null) assertEquals("/single-step", picked.input);
        }
    }

    @Test
    public void sequenceEntryStillPresentInReplaySnapshotDespiteBeingIneligibleAsAParent() {
        CostCorpus corpus = new CostCorpus(Arrays.asList(), false);
        corpus.consider("/single-step", 1.0, 1, 0, 0, 0, true);
        corpus.consider(SEQUENCE_ENTRY, 0.0, 0, 0, 0, 0, true);

        List<CorpusEntry> snap = corpus.snapshotByCost();
        assertTrue("the replay corpus must still carry the whole sequence entry",
                snap.stream().anyMatch(e -> e.input.equals(SEQUENCE_ENTRY)));
    }

    @Test
    public void selectParentReturnsNullWhenOnlySequenceEntriesArePresentUniform() {
        CostCorpus corpus = new CostCorpus(Arrays.asList(), false);
        corpus.consider(SEQUENCE_ENTRY, 0.0, 0, 0, 0, 0, true);
        corpus.consider("POST /x y=1\tPOST /z w=2", 0.0, 0, 0, 0, 0, true);

        Random rnd = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertNull("no eligible single-request parent exists; must be null, not a sequence",
                    corpus.selectParent(rnd));
        }
    }

    @Test
    public void selectParentReturnsNullWhenOnlySequenceEntriesArePresentPheromoneWeighted() {
        CostCorpus corpus = new CostCorpus(Arrays.asList(), false, true);
        corpus.consider(SEQUENCE_ENTRY, 0.0, 0, 0, 0, 0, true);

        Random rnd = new Random(5);
        for (int i = 0; i < 100; i++) {
            assertNull(corpus.selectParent(rnd));
        }
    }
}
