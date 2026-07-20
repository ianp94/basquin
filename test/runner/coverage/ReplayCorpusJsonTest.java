package runner.coverage;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The end-of-run "replay corpus" is spliced into the summary the operator reads back (DD-026 PR 1),
 * which rides the pod termination message (~4 KiB cap). These pin the size-capping, dedup, and JSON
 * escaping so a change can't silently blow the cap or emit malformed JSON. Package {@code runner.coverage}
 * to reach the package-private method.
 */
public class ReplayCorpusJsonTest {

    @Test
    public void emptyOrNullIsAnEmptyArray() {
        assertEquals("[]", CoverageGuidedRun.replayCorpusJson(null, 3000));
        assertEquals("[]", CoverageGuidedRun.replayCorpusJson(Collections.<String>emptyList(), 3000));
    }

    @Test
    public void encodesAndDeduplicates() {
        List<String> corpus = Arrays.asList("/a", "/b", "/a");
        String json = CoverageGuidedRun.replayCorpusJson(corpus, 3000);
        assertEquals("[\"/a\",\"/b\"]", json);
    }

    @Test
    public void escapesJsonMetacharacters() {
        String json = CoverageGuidedRun.replayCorpusJson(Arrays.asList("/x?q=\"a\"\n"), 3000);
        assertTrue(json, json.contains("\\\"a\\\""));
        assertTrue(json, json.contains("\\n"));
    }

    @Test
    public void combinedSummaryStaysUnderTheTerminationCap() throws Exception {
        // writeSummary splices the corpus into the real metrics JSON; the whole thing rides the pod
        // termination message (kubelet caps it at 4096 bytes). A huge corpus must be budgeted down so
        // the merged write stays under the cap — otherwise kubelet truncation makes it un-parseable and
        // the operator loses BOTH the corpus and the coverage/findings summary.
        java.util.List<String> big = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            big.add(String.format("/actions/Catalog.action?categoryId=CAT%04d&productId=FI-SW-%04d", i, i));
        }
        CoverageGuidedRun.lastCorpus = big;
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("summary", ".json");
        tmp.toFile().deleteOnExit();
        CoverageGuidedRun.writeSummary(tmp.toString());

        byte[] written = java.nio.file.Files.readAllBytes(tmp);
        assertTrue("summary must stay under kubelet's 4096-byte cap, was " + written.length, written.length < 4096);
        String s = new String(written, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("single JSON object", s.startsWith("{") && s.endsWith("}"));
        assertTrue("carries the metrics", s.contains("\"exploration\":"));
        assertTrue("carries the replay corpus", s.contains("\"replayCorpus\":["));
    }

    @Test
    public void capsAtTheByteBudget() {
        // 200 distinct routes = ~4 KB of content; a 500-byte budget must truncate well under it.
        java.util.List<String> big = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            big.add(String.format("/actions/x?id=%06d", i));
        }
        String json = CoverageGuidedRun.replayCorpusJson(big, 500);
        assertTrue("must stay within budget: " + json.length(), json.length() <= 500);
        assertTrue("valid JSON array", json.startsWith("[") && json.endsWith("]"));
        assertFalse("must have truncated, not emitted all 200", json.contains("000199"));
    }
}
