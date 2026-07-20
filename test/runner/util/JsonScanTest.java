package runner.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link JsonScan} exists because of a real bug, so these tests are mostly regression guards
 * rather than API coverage. Lives in {@code runner.util} to reach the package-private scanner.
 */
public class JsonScanTest {

    @Test
    public void extractsSimpleValue() {
        String json = "{\"classification\":\"Crash\",\"timestamp\":\"123\"}";
        assertEquals("Crash", JsonScan.extract(json, "classification", 0));
        assertEquals("123", JsonScan.extract(json, "timestamp", 0));
    }

    @Test
    public void returnsNullForMissingKey() {
        assertNull(JsonScan.extract("{\"a\":\"b\"}", "nope", 0));
    }

    @Test
    public void respectsEscapedQuotesAndBackslashesInsideValues() {
        // A findings record routinely contains quoted text and Windows-ish paths; stopping at the
        // first '"' or mis-handling '\\' would truncate the value or run past its end.
        String json = "{\"text\":\"he said \\\"hi\\\" then a backslash \\\\ done\",\"next\":\"x\"}";
        assertEquals("he said \"hi\" then a backslash \\ done", JsonScan.extract(json, "text", 0));
        assertEquals("x", JsonScan.extract(json, "next", 0));
    }

    @Test
    public void decodesEscapeSequences() {
        String json = "{\"text\":\"line1\\nline2\\ttabbed\"}";
        assertEquals("line1\nline2\ttabbed", JsonScan.extract(json, "text", 0));
    }

    /**
     * REGRESSION: the original implementation matched escaped strings with a
     * {@code (?:[^"\\]|\\.)*} regex. Java's Pattern recurses one stack frame per character, so a
     * value of a few thousand characters threw StackOverflowError — not an exotic input at all:
     * a stack trace in a finding, or a paragraph of Claude analysis, hits it. Must stay iterative.
     */
    @Test
    public void handlesValuesFarLargerThanTheJavaRegexStack() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            big.append("at org.example.Frame").append(i % 97).append("(File.java:").append(i).append(")\\n");
        }
        String json = "{\"text\":\"" + big + "\",\"after\":\"sentinel\"}";

        String text = JsonScan.extract(json, "text", 0);   // must not StackOverflowError
        assertNotNull(text);
        assertTrue("value should be fully extracted", text.length() > 100000);
        // and the scan must still land correctly on the following field
        assertEquals("sentinel", JsonScan.extract(json, "after", 0));
    }

    @Test
    public void scansSequentiallyFromAnOffset() {
        // Clustering walks an array of records by advancing a cursor; each object's fields must
        // resolve relative to that position rather than always finding the first match.
        String json = "[{\"classification\":\"Crash\",\"text\":\"first\"},"
                    + "{\"classification\":\"Invariant\",\"text\":\"second\"}]";
        int firstEnd = JsonScan.stringEnd(json, JsonScan.valueStart(json, "classification", 0));
        assertEquals("first", JsonScan.extract(json, "text", firstEnd));

        int secondStart = JsonScan.valueStart(json, "classification", firstEnd);
        int secondEnd = JsonScan.stringEnd(json, secondStart);
        assertEquals("second", JsonScan.extract(json, "text", secondEnd));
    }

    @Test
    public void readsRawNumbersAndBooleans() {
        String json = "{\"inputSize\":42,\"inputBinary\":true,\"pct\":8.6}";
        assertEquals("42", JsonScan.rawNumber(json, "inputSize", 0));
        assertEquals("true", JsonScan.rawNumber(json, "inputBinary", 0));
        assertEquals("8.6", JsonScan.rawNumber(json, "pct", 0));
        assertNull(JsonScan.rawNumber(json, "absent", 0));
    }
}
