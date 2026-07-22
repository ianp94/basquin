package runner.coverage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * A grammar route (or an indented @sequence step) may carry an optional leading
 * {@code METHOD } token (e.g. {@code POST /actions/Cart.action?...}). The method must be
 * retained end-to-end: recognised by the loader's gates, kept in the stored template, present
 * in generated concrete requests, and still resolvable back to its template by {@code
 * templateFor} (exercised here via the package-private accessor, since this test lives in
 * {@code runner.coverage} deliberately to reach it).
 */
public class GrammarMethodTest {

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
    public void generatedRequestForAMethodPrefixedRouteCarriesTheMethodAndAFilledParam() throws IOException {
        RequestGrammar g = load(
            "$item = EST-1 | EST-3\n"
          + "POST /actions/Cart.action?addItemToCart=&workingItemId=${item}\n");

        assertEquals("the POST route must be recognised", 1, g.routeCount());

        String concrete = g.randomRequest();
        assertTrue("generated request must start with the method: " + concrete, concrete.startsWith("POST "));
        assertTrue("generated request must have workingItemId filled with a real value: " + concrete,
                concrete.equals("POST /actions/Cart.action?addItemToCart=&workingItemId=EST-1")
             || concrete.equals("POST /actions/Cart.action?addItemToCart=&workingItemId=EST-3"));
    }

    @Test
    public void templateForRoundTripsAMethodPrefixedConcreteRequest() throws IOException {
        RequestGrammar g = load(
            "$item = EST-1 | EST-3\n"
          + "POST /actions/Cart.action?addItemToCart=&workingItemId=${item}\n");

        String concrete = g.randomRequest();
        String template = g.templateFor(concrete);
        assertEquals("POST /actions/Cart.action?addItemToCart=&workingItemId=${item}", template);
    }

    @Test
    public void sequenceStepAcceptsAnOptionalMethodPrefix() throws IOException {
        RequestGrammar g = load(
            "$item = EST-1 | EST-3\n"
          + "@sequence cart\n"
          + "  /actions/Catalog.action?viewItem=&itemId=${item}\n"
          + "  POST /actions/Cart.action?addItemToCart=&workingItemId=${item}\n");

        assertTrue(g.hasSequences());
        var steps = g.randomSequence();
        assertEquals(2, steps.size());
        assertFalse("bare step keeps no method prefix", steps.get(0).startsWith("POST "));
        assertTrue("indented step retains its method prefix: " + steps.get(1), steps.get(1).startsWith("POST "));
    }
}
