package runner.coverage;

import org.junit.Test;
import java.util.function.Function;
import static org.junit.Assert.*;

public class CaptureTest {

    @Test public void parseFormatRoundTripsHeader() {
        Capture c = Capture.parse("<<sess=header:Set-Cookie");
        assertNotNull(c);
        assertEquals("sess", c.name());
        assertEquals(Capture.Kind.HEADER, c.kind());
        assertEquals("Set-Cookie", c.arg());
        assertEquals("<<sess=header:Set-Cookie", c.format());
    }

    @Test public void parseFormatRoundTripsInput() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        assertNotNull(c);
        assertEquals("csrf", c.name());
        assertEquals(Capture.Kind.INPUT, c.kind());
        assertEquals("X-XSRF-TOKEN", c.arg());
        assertEquals("<<csrf=input:X-XSRF-TOKEN", c.format());
    }

    @Test public void parseReturnsNullOnMalformed() {
        assertNull(Capture.parse("/x"));
        assertNull(Capture.parse("<<bad"));
        assertNull(Capture.parse("<<n=nope:x"));
        assertNull(Capture.parse("<<=input:X"));
        assertNull(Capture.parse(null));
        assertNull(Capture.parse(""));
        assertNull(Capture.parse("<<bad name=input:X"));
        assertNull(Capture.parse("<<name=input:"));
    }

    @Test public void inputExtractBasic() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input type=\"hidden\" name=\"X-XSRF-TOKEN\" value=\"abc+/=\">";
        // inputExtractBasic: value in the form is "abc+/="; extract now returns it URL-encoded
        assertEquals("abc%2B%2F%3D", c.extract(h -> null, body));
    }

    @Test public void inputExtractValueBeforeName() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input value=\"abc+/=\" type=\"hidden\" name=\"X-XSRF-TOKEN\">";
        assertEquals("abc%2B%2F%3D", c.extract(h -> null, body));
    }

    @Test public void inputExtractSingleQuotes() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input type='hidden' name='X-XSRF-TOKEN' value='abc+/='>";
        assertEquals("abc%2B%2F%3D", c.extract(h -> null, body));
    }

    @Test public void inputExtractUnescapesEntities() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input name=\"X-XSRF-TOKEN\" value=\"a&amp;b\">";
        // unescape "a&amp;b" -> "a&b", then extract URL-encodes it -> "a%26b"
        assertEquals("a%26b", c.extract(h -> null, body));
    }

    @Test public void inputExtractMissReturnsNull() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input type=\"hidden\" name=\"other\" value=\"abc\">";
        assertNull(c.extract(h -> null, body));
    }

    @Test public void inputExtractMissWhenNoInputTags() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        assertNull(c.extract(h -> null, "<div>no inputs here</div>"));
    }

    @Test public void inputExtractIgnoresOtherAttributesInterleaved() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input id=\"foo\" type=\"hidden\" name=\"X-XSRF-TOKEN\" class=\"bar\" value=\"abc\" disabled>";
        assertEquals("abc", c.extract(h -> null, body));
    }

    @Test public void headerExtract() {
        Capture c = Capture.parse("<<sess=header:Set-Cookie");
        Function<String, String> lookup = h -> "Set-Cookie".equals(h) ? "JSESSIONID=z" : null;
        assertEquals("JSESSIONID%3Dz", c.extract(lookup, "irrelevant body"));
    }

    @Test public void headerExtractMissReturnsNull() {
        Capture c = Capture.parse("<<sess=header:X-Missing");
        Function<String, String> lookup = h -> null;
        assertNull(c.extract(lookup, "irrelevant body"));
    }

    @Test public void multiInputPicksMatchingTagsValue() {
        Capture c = Capture.parse("<<t=input:X-XSRF-TOKEN");
        String body = "<input name=\"other\" value=\"WRONG\"><input name=\"X-XSRF-TOKEN\" value=\"RIGHT\">";
        assertEquals("RIGHT", c.extract(h -> null, body));
    }

    @Test public void nameSubstringDoesNotFalseMatch() {
        Capture c = Capture.parse("<<t=input:X-XSRF-TOKEN");
        String body = "<input name=\"X-XSRF-TOKEN-2\" value=\"NO\">";
        assertNull(c.extract(h -> null, body));

        // Reverse: searching for TOKEN shouldn't match X-XSRF-TOKEN
        Capture c2 = Capture.parse("<<t=input:TOKEN");
        String body2 = "<input name=\"X-XSRF-TOKEN\" value=\"NO\">";
        assertNull(c2.extract(h -> null, body2));
    }

    @Test public void parseFormatRoundTripsInputPair() {
        Capture c = Capture.parse("<<spam=inputpair:[a-z]{6}=-?[0-9]+");
        assertNotNull(c);
        assertEquals("spam", c.name());
        assertEquals(Capture.Kind.INPUTPAIR, c.kind());
        assertEquals("[a-z]{6}=-?[0-9]+", c.arg());
        assertEquals("<<spam=inputpair:[a-z]{6}=-?[0-9]+", c.format());
    }

    @Test public void inputPairParseNullOnMissingEquals() {
        assertNull(Capture.parse("<<spam=inputpair:[a-z]{6}"));      // no name=value split
        assertNull(Capture.parse("<<spam=inputpair:[a-z]{6}="));     // empty value regex
        assertNull(Capture.parse("<<spam=inputpair:=[0-9]+"));       // empty name regex
        assertNull(Capture.parse("<<spam=inputpair:[a-z(=[0-9]+"));  // name regex doesn't compile
    }

    @Test public void inputPairExtractsPairEncodedAmongDecoys() {
        Capture c = Capture.parse("<<spam=inputpair:[a-z]{6}=-?[0-9]+");
        String body =
            "<input type='hidden' name='action' value='save' />" +          // name matches [a-z]{6}, value not numeric
            "<input type='hidden' name='_editedtext' value='12' />" +       // value numeric, name not 6-lower
            "<input type='hidden' name='ztbams' value='1719016235' />";     // the one
        assertEquals("ztbams=1719016235", c.extract(h -> null, body));
    }

    @Test public void inputPairAnchoredAndEncodes() {
        Capture c = Capture.parse("<<p=inputpair:[a-z]{3}=[a-z+]+");
        // value contains '+', name is exactly 3 lowercase; both halves URL-encoded, joined by literal '='
        String body = "<input name=\"abc\" value=\"x+y\" />";
        assertEquals("abc=x%2By", c.extract(h -> null, body));
    }

    @Test public void inputPairDataNameNotMistakenForName() {
        Capture c = Capture.parse("<<p=inputpair:[a-z]{6}=[0-9]+");
        // only a data-name attribute matches; there is no real name=, so no match
        String body = "<input data-name=\"ztbams\" value=\"123\" />";
        assertNull(c.extract(h -> null, body));
    }
}
