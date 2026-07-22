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
        assertEquals("abc+/=", c.extract(h -> null, body));
    }

    @Test public void inputExtractValueBeforeName() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input value=\"abc+/=\" type=\"hidden\" name=\"X-XSRF-TOKEN\">";
        assertEquals("abc+/=", c.extract(h -> null, body));
    }

    @Test public void inputExtractSingleQuotes() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input type='hidden' name='X-XSRF-TOKEN' value='abc+/='>";
        assertEquals("abc+/=", c.extract(h -> null, body));
    }

    @Test public void inputExtractUnescapesEntities() {
        Capture c = Capture.parse("<<csrf=input:X-XSRF-TOKEN");
        String body = "<input name=\"X-XSRF-TOKEN\" value=\"a&amp;b\">";
        assertEquals("a&b", c.extract(h -> null, body));
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
        assertEquals("JSESSIONID=z", c.extract(lookup, "irrelevant body"));
    }

    @Test public void headerExtractMissReturnsNull() {
        Capture c = Capture.parse("<<sess=header:X-Missing");
        Function<String, String> lookup = h -> null;
        assertNull(c.extract(lookup, "irrelevant body"));
    }
}
