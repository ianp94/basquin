package runner.coverage;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RequestLineV3Test {

    @Test public void parsesTrailingCaptureSuffixWithSubstitutionBody() {
        RequestLine r = RequestLine.parse(
            "POST /Edit.jsp page=Main&t=${{csrf}} <<csrf=input:X-XSRF-TOKEN");
        assertEquals("POST", r.method());
        assertEquals("/Edit.jsp", r.path());
        assertEquals("page=Main&t=${{csrf}}", r.body());
        assertEquals(1, r.captures().size());
        assertEquals("csrf", r.captures().get(0).name());
        assertEquals(Capture.Kind.INPUT, r.captures().get(0).kind());
        assertEquals("X-XSRF-TOKEN", r.captures().get(0).arg());
    }

    @Test public void roundTripsCaptureAndSubstitutionAcrossSteps() {
        String line = "POST /Login.jsp user=a <<sess=header:Set-Cookie"
            + "\t" + "POST /Edit.jsp page=Main&t=${{sess}}";
        List<RequestLine> seq = RequestLine.parseSequence(line);
        assertEquals(line, RequestLine.formatSequence(seq));

        assertEquals(1, seq.get(0).captures().size());
        assertEquals("sess", seq.get(0).captures().get(0).name());
        assertTrue(seq.get(1).captures().isEmpty());
        assertTrue(seq.get(1).needsSubstitution());
    }

    @Test public void v2LineHasNullCaptureAndByteIdenticalFormat() {
        String line = "POST /actions/Account.action?signon= username=j2ee&password=j2ee";
        RequestLine r = RequestLine.parse(line);
        assertTrue(r.captures().isEmpty());
        assertEquals(line, r.format());
    }

    @Test public void needsSubstitutionTrueIffBodyHasPlaceholder() {
        assertTrue(RequestLine.parse("POST /x a=${{tok}}").needsSubstitution());
        assertFalse(RequestLine.parse("POST /x a=b").needsSubstitution());
        assertFalse(RequestLine.parse("/x").needsSubstitution());
    }

    @Test public void backwardCompatBarePathAndMethodPathUnchanged() {
        RequestLine bare = RequestLine.parse("/x");
        assertEquals("GET", bare.method());
        assertEquals("/x", bare.path());
        assertNull(bare.body());
        assertTrue(bare.captures().isEmpty());

        RequestLine withQuery = RequestLine.parse("GET /x?a=b");
        assertEquals("GET", withQuery.method());
        assertEquals("/x?a=b", withQuery.path());
        assertTrue(withQuery.captures().isEmpty());
    }

    @Test public void malformedTrailingCaptureLeavesCaptureNullAndStaysInBody() {
        RequestLine r = RequestLine.parse("/x <<bad");
        assertTrue(r.captures().isEmpty());
        assertEquals("<<bad", r.body());
    }

    @Test public void parsesMultipleTrailingCaptures() {
        RequestLine r = RequestLine.parse("/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+");
        assertEquals("/Edit.jsp?page=Main", r.path());
        assertEquals(2, r.captures().size());
        assertEquals("csrf", r.captures().get(0).name());   // source order preserved
        assertEquals("spam", r.captures().get(1).name());
        assertEquals(Capture.Kind.INPUTPAIR, r.captures().get(1).kind());
        // round-trip
        assertEquals("/Edit.jsp?page=Main <<csrf=input:X-XSRF-TOKEN <<spam=inputpair:[a-z]{6}=-?[0-9]+", r.format());
    }

    @Test public void v2LineHasEmptyCapturesAndByteIdenticalFormat() {
        RequestLine r = RequestLine.parse("POST /actions/Order.action?newOrder= _sourcePage=/x");
        assertTrue(r.captures().isEmpty());
        assertEquals("POST /actions/Order.action?newOrder= _sourcePage=/x", r.format());
    }
}
