package test;

import org.junit.Test;
import runner.coverage.RequestGrammar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * The grammar decides what the fuzzer can ever reach, so a silent regression here shows up as
 * "coverage plateaued" rather than as a failure — which is exactly how the hardcoded-routes
 * problem (DD-016) hid for so long. These pin the behaviours that matter.
 */
public class RequestGrammarTest {

    private static Path write(String content) throws IOException {
        Path f = Files.createTempFile("grammar", ".txt");
        f.toFile().deleteOnExit();
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static RequestGrammar load(String content) throws IOException {
        return RequestGrammar.load(write(content), new Random(42));
    }

    @Test
    public void parsesRoutesAndRulesIgnoringCommentsAndBlanks() throws IOException {
        RequestGrammar g = load(
            "# a comment\n"
          + "$id = A | B | C\n"
          + "\n"
          + "/plain\n"
          + "/with?id=${id}   # trailing comment\n");

        assertEquals(2, g.routeCount());
        assertEquals(1, g.ruleCount());
        assertFalse(g.isEmpty());
    }

    @Test
    public void expandsPlaceholdersFromTheDictionary() throws IOException {
        RequestGrammar g = load("$id = ONLYVALUE\n/x?id=${id}\n");
        assertEquals("/x?id=ONLYVALUE", g.randomRequest());
    }

    @Test
    public void expandAllCoversEveryRouteTemplateOnce() throws IOException {
        // The deterministic first pass is what guarantees no endpoint is left to chance.
        RequestGrammar g = load("$id = A\n/one\n/two?id=${id}\n/three\n");
        List<String> all = g.expandAll();
        assertEquals(3, all.size());
        assertTrue(all.contains("/one"));
        assertTrue(all.contains("/two?id=A"));
        assertTrue(all.contains("/three"));
    }

    /**
     * Structural generation is the reason grammar finds bugs a value list cannot: it invents ids
     * that PARSE but do not EXIST, reaching lookup/dereference code instead of being rejected by
     * validation.
     */
    @Test
    public void structuralGeneratorProducesValuesMatchingTheDeclaredShape() throws IOException {
        RequestGrammar g = load("$itemId = ~EST-[0-9]{1,4}\n/x?itemId=${itemId}\n");
        for (int i = 0; i < 50; i++) {
            String out = g.randomRequest();
            assertTrue("unexpected shape: " + out, out.matches("/x\\?itemId=EST-[0-9]{1,4}"));
        }
    }

    @Test
    public void structuralGeneratorSupportsCharacterClassesAndFixedRepetition() throws IOException {
        RequestGrammar g = load("$p = ~[A-Z]{2}-[A-Z]{2}-[0-9]{2}\n/x?p=${p}\n");
        for (int i = 0; i < 25; i++) {
            assertTrue(g.randomRequest().matches("/x\\?p=[A-Z]{2}-[A-Z]{2}-[0-9]{2}"));
        }
    }

    @Test
    public void loadsDictionaryValuesFromACorpusFile() throws IOException {
        Path values = Files.createTempFile("values", ".txt");
        values.toFile().deleteOnExit();
        Files.write(values, "ALPHA\n# comment\n\nBETA\n".getBytes(StandardCharsets.UTF_8));

        RequestGrammar g = load("$v = @" + values.toAbsolutePath() + "\n/x?v=${v}\n");
        for (int i = 0; i < 20; i++) {
            String out = g.randomRequest();
            assertTrue("comments/blanks must not become values: " + out,
                    out.equals("/x?v=ALPHA") || out.equals("/x?v=BETA"));
        }
    }

    /**
     * A grammar authored with laptop-relative @refs (grammar and corpus as sibling dirs) must still
     * find its values in a container, where the operator flattens the corpus into one dir and sets
     * -Dbasquin.corpusDir. When the ref misses relative to the grammar file, resolution falls back
     * to corpusDir/<basename>. Without this, campaigns silently run on structure only (DD-018).
     */
    @Test
    public void resolvesValueFileByBasenameFromCorpusDirWhenRelativeRefMisses() throws IOException {
        Path corpusDir = Files.createTempDirectory("corpus");
        corpusDir.toFile().deleteOnExit();
        Path vf = corpusDir.resolve("categoryId.txt");
        Files.write(vf, "FISH\nDOGS\n".getBytes(StandardCharsets.UTF_8));
        vf.toFile().deleteOnExit();

        String prev = System.getProperty("basquin.corpusDir");
        System.setProperty("basquin.corpusDir", corpusDir.toString());
        try {
            // "nope/categoryId.txt" does not exist next to the temp grammar file → basename fallback.
            RequestGrammar g = load("$c = @nope/categoryId.txt\n/x?c=${c}\n");
            for (int i = 0; i < 20; i++) {
                String out = g.randomRequest();
                assertTrue("must resolve corpus values via corpusDir fallback: " + out,
                        out.equals("/x?c=FISH") || out.equals("/x?c=DOGS"));
            }
        } finally {
            if (prev == null) System.clearProperty("basquin.corpusDir");
            else System.setProperty("basquin.corpusDir", prev);
        }
    }

    /**
     * A present-but-empty value file at the laptop-relative path must not silently mask the corpusDir
     * fallback (review #22): an empty primary read is not "authoritative", so resolution still falls
     * through to corpusDir/<basename>.
     */
    @Test
    public void emptyPrimaryValueFileStillFallsBackToCorpusDir() throws IOException {
        Path grammarDir = Files.createTempDirectory("gram");
        grammarDir.toFile().deleteOnExit();
        Path emptyPrimary = grammarDir.resolve("categoryId.txt"); // exists but empty
        Files.write(emptyPrimary, new byte[0]);
        emptyPrimary.toFile().deleteOnExit();
        Path grammarFile = grammarDir.resolve("g.grammar");
        Files.write(grammarFile, "$c = @categoryId.txt\n/x?c=${c}\n".getBytes(StandardCharsets.UTF_8));
        grammarFile.toFile().deleteOnExit();

        Path corpusDir = Files.createTempDirectory("corpus");
        corpusDir.toFile().deleteOnExit();
        Path good = corpusDir.resolve("categoryId.txt");
        Files.write(good, "FISH\n".getBytes(StandardCharsets.UTF_8));
        good.toFile().deleteOnExit();

        String prev = System.getProperty("basquin.corpusDir");
        System.setProperty("basquin.corpusDir", corpusDir.toString());
        try {
            RequestGrammar g = RequestGrammar.load(grammarFile, new Random(7));
            assertEquals("empty primary must not defeat the corpusDir fallback", "/x?c=FISH", g.randomRequest());
        } finally {
            if (prev == null) System.clearProperty("basquin.corpusDir");
            else System.setProperty("basquin.corpusDir", prev);
        }
    }

    @Test
    public void emptyGrammarIsReportedRatherThanSilentlyExploringNothing() throws IOException {
        assertTrue(load("# nothing but a comment\n").isEmpty());
    }

    // --- sequences -----------------------------------------------------------------

    @Test
    public void parsesSequencesFromIndentedSteps() throws IOException {
        RequestGrammar g = load(
            "$id = A\n"
          + "@sequence flow\n"
          + "  /step1\n"
          + "  /step2?id=${id}\n"
          + "\n"
          + "/standalone\n");

        assertTrue(g.hasSequences());
        assertEquals(1, g.sequenceCount());
        assertEquals("a blank line ends the sequence, so /standalone is a route", 1, g.routeCount());
        assertEquals(2, g.randomSequence().size());
    }

    /**
     * THE critical invariant: within one sequence execution a placeholder resolves ONCE and is
     * reused. If it re-randomised per step, a transaction would add one item to the cart and
     * remove a different one — incoherent, and it would never reach the code a real checkout
     * exercises. Uses a dictionary wide enough that per-step binding would collide only by luck.
     */
    @Test
    public void placeholdersBindOncePerSequenceExecution() throws IOException {
        StringBuilder alts = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (i > 0) alts.append(" | ");
            alts.append("EST-").append(i);
        }
        RequestGrammar g = load(
            "$itemId = " + alts + "\n"
          + "@sequence cart\n"
          + "  /view?itemId=${itemId}\n"
          + "  /add?itemId=${itemId}\n"
          + "  /remove?itemId=${itemId}\n");

        for (int run = 0; run < 30; run++) {
            List<String> steps = g.randomSequence();
            String a = steps.get(0).substring(steps.get(0).indexOf('=') + 1);
            String b = steps.get(1).substring(steps.get(1).indexOf('=') + 1);
            String c = steps.get(2).substring(steps.get(2).indexOf('=') + 1);
            assertEquals("all steps of one transaction must share the value", a, b);
            assertEquals(a, c);
        }
    }

    /** Different executions must still vary, otherwise a sequence explores exactly one path. */
    @Test
    public void differentSequenceExecutionsPickDifferentValues() throws IOException {
        StringBuilder alts = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) alts.append(" | ");
            alts.append("V").append(i);
        }
        RequestGrammar g = load("$v = " + alts + "\n@sequence s\n  /x?v=${v}\n");

        boolean sawDifferent = false;
        String first = g.randomSequence().get(0);
        for (int i = 0; i < 50 && !sawDifferent; i++) {
            if (!g.randomSequence().get(0).equals(first)) sawDifferent = true;
        }
        assertTrue("sequences must not be pinned to a single expansion", sawDifferent);
    }

    @Test
    public void standaloneRequestsBindIndependentlyOfEachOther() throws IOException {
        // Only sequences share bindings; two separate requests should be free to differ.
        StringBuilder alts = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) alts.append(" | ");
            alts.append("V").append(i);
        }
        RequestGrammar g = load("$v = " + alts + "\n/x?v=${v}\n");

        boolean sawDifferent = false;
        String first = g.randomRequest();
        for (int i = 0; i < 50 && !sawDifferent; i++) {
            if (!g.randomRequest().equals(first)) sawDifferent = true;
        }
        assertTrue(sawDifferent);
    }

    @Test
    public void expandAllSequencesCoversEveryDeclaredSequence() throws IOException {
        RequestGrammar g = load(
            "@sequence one\n  /a\n\n"
          + "@sequence two\n  /b\n  /c\n");
        assertEquals(2, g.expandAllSequences().size());
    }

    @Test
    public void mutateKeepsTheRequestWellFormed() throws IOException {
        RequestGrammar g = load("$id = ~[0-9]{2}\n/x?id=${id}\n");
        String mutated = g.mutate("/x?id=42");
        assertTrue("mutation must stay inside the template's shape: " + mutated,
                mutated.matches("/x\\?id=[0-9]{2}"));
    }

    @Test
    public void mutateFallsBackToAFreshRequestForUnknownInput() throws IOException {
        RequestGrammar g = load("$id = A\n/x?id=${id}\n");
        assertEquals("/x?id=A", g.mutate("/completely-unrelated-seed"));
    }
}
