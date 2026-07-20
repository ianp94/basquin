package test;

import org.junit.Test;
import runner.util.FindingsClusterer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Clustering is what turns hundreds of raw findings into a reviewable list, so a regression here
 * silently makes results either unreadable (no grouping) or misleading (over-grouping). Each test
 * below corresponds to a bug that actually occurred while developing it.
 */
public class FindingsClustererTest {

    /** Build a findings record in exactly the shape DashboardClient pushes. */
    private static String record(String classification, long ts, String input, String text) {
        return "{\"file\":\"f.meta.txt\",\"classification\":\"" + classification + "\""
             + ",\"timestamp\":\"" + ts + "\",\"text\":\"" + esc(text) + "\""
             + ",\"input\":\"" + esc(input) + "\",\"inputSize\":" + input.length()
             + ",\"inputBinary\":false}";
    }

    private static String array(String... records) {
        return "[" + String.join(",", records) + "]";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Test
    public void groupsSameKindAndRouteShapeIgnoringParameterValues() {
        // The whole point: 3 requests differing only by id are ONE issue, not three.
        String json = array(
            record("Invariant-Remote", 1, "/actions/Catalog.action?viewProduct=&productId=FI-SW-01",
                   "route=/actions/Catalog.action?viewProduct=&productId=FI-SW-01\ndetail=heapDelta: 300KB > 256KB"),
            record("Invariant-Remote", 2, "/actions/Catalog.action?viewProduct=&productId=K9-BD-01",
                   "route=/actions/Catalog.action?viewProduct=&productId=K9-BD-01\ndetail=heapDelta: 500KB > 256KB"),
            record("Invariant-Remote", 3, "/actions/Catalog.action?viewProduct=&productId=AV-CB-01",
                   "route=/actions/Catalog.action?viewProduct=&productId=AV-CB-01\ndetail=heapDelta: 400KB > 256KB"));

        List<FindingsClusterer.Cluster> clusters = FindingsClusterer.cluster(json);

        assertEquals(1, clusters.size());
        FindingsClusterer.Cluster c = clusters.get(0);
        assertEquals(3, c.count);
        assertEquals("heapDelta", c.kind);
        assertEquals("magnitude range should span the observed values", 300, c.minMagnitude);
        assertEquals(500, c.maxMagnitude);
        assertEquals("three distinct concrete routes", 3, c.distinctRoutes.size());
    }

    @Test
    public void keepsDifferentRouteShapesApart() {
        String json = array(
            record("Invariant-Remote", 1, "/actions/Catalog.action?viewProduct=&productId=X",
                   "route=/actions/Catalog.action?viewProduct=&productId=X\ndetail=heapDelta: 300KB > 256KB"),
            record("Invariant-Remote", 2, "/actions/Cart.action?viewCart=",
                   "route=/actions/Cart.action?viewCart=\ndetail=heapDelta: 300KB > 256KB"));

        assertEquals(2, FindingsClusterer.cluster(json).size());
    }

    /**
     * REGRESSION: two saved formats exist. HTTP-driven finds write "detail=kind: ...", but
     * local/JQF finds put the bare "kind: ..." line in the body with no prefix. Keying only on
     * "detail=" left every local finding's kind unresolved as "?".
     */
    @Test
    public void readsInvariantKindFromBothSavedFormats() {
        String httpDriven = record("Invariant-Remote", 1, "/x",
                "route=/x\ndetail=heapDelta: 300KB > 256KB");
        String localJqf = record("Invariant", 2, "seed-input",
                "details=\nheapDelta: 2048KB > 64KB\nstack=\nThread 'main'");

        assertEquals("heapDelta", FindingsClusterer.cluster(array(httpDriven)).get(0).kind);
        assertEquals("heapDelta", FindingsClusterer.cluster(array(localJqf)).get(0).kind);
    }

    /**
     * REGRESSION: crash findings have no "route=" line, so every crash on every endpoint collapsed
     * into a single cluster keyed only by exception class, and the dashboard displayed
     * "route=(none)". The saved input is the route for HTTP targets, so it's the fallback.
     */
    @Test
    public void crashesUseTheSavedInputAsRouteWhenNoRouteLineExists() {
        String json = array(
            record("Crash", 1, "/actions/Order.action?listOrders=",
                   "exception=java.lang.RuntimeException\nmessage=HTTP 500\nstack=\n  at Foo.bar(Foo.java:1)"),
            record("Crash", 2, "/actions/Catalog.action?viewItem=&itemId=EST-9",
                   "exception=java.lang.RuntimeException\nmessage=HTTP 500\nstack=\n  at Foo.bar(Foo.java:1)"));

        List<FindingsClusterer.Cluster> clusters = FindingsClusterer.cluster(json);

        assertEquals("same exception on different endpoints must not merge", 2, clusters.size());
        for (FindingsClusterer.Cluster c : clusters) {
            assertFalse("route should be recovered from the input", c.routePattern.isEmpty());
        }
    }

    @Test
    public void separatesCrashesByExceptionClass() {
        String json = array(
            record("Crash", 1, "/x", "exception=java.lang.NullPointerException\nmessage=boom"),
            record("Crash", 2, "/x", "exception=java.lang.IllegalStateException\nmessage=boom"));
        assertEquals(2, FindingsClusterer.cluster(json).size());
    }

    @Test
    public void retainsBoundedInputSamplesForTheViewer() {
        String[] records = new String[25];
        for (int i = 0; i < records.length; i++) {
            records[i] = record("Invariant-Remote", i,
                    "/actions/Catalog.action?viewItem=&itemId=EST-" + i,
                    "route=/actions/Catalog.action?viewItem=&itemId=EST-" + i
                    + "\ndetail=heapDelta: 300KB > 256KB");
        }
        FindingsClusterer.Cluster c = FindingsClusterer.cluster(array(records)).get(0);

        assertEquals(25, c.count);
        assertTrue("samples must be bounded, not one per finding", c.samples.size() <= 10);
        assertFalse("a sample must carry the concrete input", c.samples.get(0).input.isEmpty());
    }

    /** The same defect found on several targets is one issue, with attribution to each target. */
    @Test
    public void mergesTheSameDefectAcrossCampaignsAndRecordsWhereItWasSeen() {
        String finding = array(record("Crash", 1, "/actions/Order.action?listOrders=",
                "exception=java.lang.NullPointerException\nmessage=boom"));
        Map<String, String> byCampaign = new LinkedHashMap<>();
        byCampaign.put("pod-a", finding);
        byCampaign.put("pod-b", finding);
        byCampaign.put("pod-c", finding);

        List<FindingsClusterer.Cluster> clusters = FindingsClusterer.clusterAcross(byCampaign);

        assertEquals("one defect, not three", 1, clusters.size());
        assertEquals(3, clusters.get(0).count);
        assertEquals(3, clusters.get(0).campaigns.size());
        assertTrue(clusters.get(0).campaigns.contains("pod-b"));
    }

    @Test
    public void ordersClustersByFrequency() {
        String common = record("Invariant-Remote", 1, "/a", "route=/a\ndetail=heapDelta: 300KB > 256KB");
        String rare = record("Invariant-Remote", 2, "/b", "route=/b\ndetail=latency: 90ms > 25ms");
        List<FindingsClusterer.Cluster> clusters =
                FindingsClusterer.cluster(array(common, common, common, rare));
        assertEquals("most frequent first", 3, clusters.get(0).count);
        assertEquals(1, clusters.get(1).count);
    }

    @Test
    public void handlesEmptyInput() {
        assertTrue(FindingsClusterer.cluster("[]").isEmpty());
        assertEquals("[]", FindingsClusterer.toJson(FindingsClusterer.cluster("[]")));
    }

    @Test
    public void emitsParseableJsonIncludingSamples() {
        String json = array(record("Crash", 7, "/actions/Order.action?listOrders=",
                "exception=java.lang.NullPointerException\nmessage=boom"));
        String out = FindingsClusterer.toJson(FindingsClusterer.cluster(json));
        assertTrue(out.contains("\"classification\":\"Crash\""));
        assertTrue(out.contains("\"samples\":["));
        assertTrue(out.contains("\"campaigns\":"));
    }
}
