package runner.coverage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * DD-036 (response correlation): a grammar-authored {@code ${{name}}} is a correlation reference
 * meant to be filled in later (from a captured response value), NOT a {@code ${name}} rule
 * placeholder. Before this fix, {@code expand} treated the char after the first {@code {} as the
 * start of the placeholder name, so {@code ${{csrf}}} was read as name {@code "{csrf"} — no rule
 * ever matches that, so it silently expanded to {@code ""} (plus a stray trailing {@code }}),
 * DESTROYING the reference a correlated sequence depends on. {@code expand} is private, so these
 * exercise it through the public entry points, as {@code RequestGrammarTest}/{@code
 * GrammarMethodTest} do.
 */
public class GrammarCorrelationTest {

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
    public void expandAllPreservesDoubleBraceCorrelationRefVerbatimWhileSubstitutingRealPlaceholder() throws IOException {
        RequestGrammar g = load(
            "$page = home | account | profile\n"
          + "POST /Edit.jsp X-XSRF-TOKEN=${{csrf}}&page=${page}\n");

        List<String> all = g.expandAll();
        assertEquals(1, all.size());
        String out = all.get(0);

        assertTrue("the correlation ref must survive expansion verbatim: " + out,
                out.contains("${{csrf}}"));
        assertFalse("the ${page} placeholder must have been substituted, not left literal: " + out,
                out.contains("${page}"));
        assertTrue("the real placeholder must resolve to one of its rule values: " + out,
                out.equals("POST /Edit.jsp X-XSRF-TOKEN=${{csrf}}&page=home")
             || out.equals("POST /Edit.jsp X-XSRF-TOKEN=${{csrf}}&page=account")
             || out.equals("POST /Edit.jsp X-XSRF-TOKEN=${{csrf}}&page=profile"));
    }

    @Test
    public void randomSequencePreservesCorrelationRefAcrossASharedBindingStep() throws IOException {
        RequestGrammar g = load(
            "$page = home | account | profile\n"
          + "@sequence flow\n"
          + "  /Login.jsp\n"
          + "  POST /Edit.jsp X-XSRF-TOKEN=${{csrf}}&page=${page}\n");

        for (int run = 0; run < 20; run++) {
            List<String> steps = g.randomSequence();
            assertEquals(2, steps.size());
            String edit = steps.get(1);
            assertTrue("correlation ref must survive inside a sequence step: " + edit,
                    edit.contains("${{csrf}}"));
            assertFalse("real placeholder must not remain literal: " + edit,
                    edit.contains("${page}"));
        }
    }

    /** Control: with no double-brace ref present, a single-brace placeholder must still substitute. */
    @Test
    public void controlOnlySingleBracePlaceholderStillSubstitutesNormally() throws IOException {
        RequestGrammar g = load("$page = ONLYVALUE\n/x?page=${page}\n");
        assertEquals("/x?page=ONLYVALUE", g.randomRequest());
    }
}
