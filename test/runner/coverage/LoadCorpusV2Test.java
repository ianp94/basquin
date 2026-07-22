package runner.coverage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * DD-035 task 2: {@code LoadRun.readCorpus} moves from bare route strings to v2 {@link RequestLine}
 * sequences. The filter that decides whether a corpus line is a route (vs. a grammar value line like
 * {@code values/keyword.txt}) must key off the FIRST STEP's path, not the raw line text — otherwise a
 * line whose first token is an HTTP method (e.g. {@code "POST /actions/Account.action?signon= ..."})
 * gets dropped even though it's a legitimate route, because the raw line doesn't start with '/'.
 */
public class LoadCorpusV2Test {

    @Test
    public void parsesV2SequencesAndKeepsValueFileExclusion() throws Exception {
        Path dir = Files.createTempDirectory("load-corpus-v2");
        dir.toFile().deleteOnExit();
        Path corpusFile = dir.resolve("corpus.txt");
        String contents = String.join("\n",
                "/actions/Catalog.action",
                "POST /a?x=\tPOST /b?y=",
                "POST /actions/Account.action?signon= u=j2ee",
                "cat",
                "dog",
                "");
        Files.write(corpusFile, contents.getBytes(StandardCharsets.UTF_8));
        corpusFile.toFile().deleteOnExit();

        List<LoadRun.Seq> sequences = LoadRun.readCorpus(dir.toString());

        assertEquals("value lines (cat/dog) must be excluded; exactly 3 route sequences remain",
                3, sequences.size());

        List<RequestLine> bareGet = sequences.get(0).steps();
        assertEquals(1, bareGet.size());
        assertEquals("GET", bareGet.get(0).method());
        assertEquals("/actions/Catalog.action", bareGet.get(0).path());

        List<RequestLine> twoStep = sequences.get(1).steps();
        assertEquals(2, twoStep.size());
        assertEquals("POST", twoStep.get(0).method());
        assertEquals("/a?x=", twoStep.get(0).path());
        assertEquals("POST", twoStep.get(1).method());
        assertEquals("/b?y=", twoStep.get(1).path());

        // Old filter (raw-line startsWith("/")) would drop this: the line starts with "POST", not "/".
        List<RequestLine> signon = sequences.get(2).steps();
        assertEquals(1, signon.size());
        assertEquals("POST", signon.get(0).method());
        assertEquals("/actions/Account.action?signon=", signon.get(0).path());
        assertEquals("u=j2ee", signon.get(0).body());
    }
}
