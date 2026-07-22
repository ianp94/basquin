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
        assertNotNull(r.capture());
        assertEquals("csrf", r.capture().name());
        assertEquals(Capture.Kind.INPUT, r.capture().kind());
        assertEquals("X-XSRF-TOKEN", r.capture().arg());
    }

    @Test public void roundTripsCaptureAndSubstitutionAcrossSteps() {
        String line = "POST /Login.jsp user=a <<sess=header:Set-Cookie"
            + "\t" + "POST /Edit.jsp page=Main&t=${{sess}}";
        List<RequestLine> seq = RequestLine.parseSequence(line);
        assertEquals(line, RequestLine.formatSequence(seq));

        assertNotNull(seq.get(0).capture());
        assertEquals("sess", seq.get(0).capture().name());
        assertNull(seq.get(1).capture());
        assertTrue(seq.get(1).needsSubstitution());
    }

    @Test public void v2LineHasNullCaptureAndByteIdenticalFormat() {
        String line = "POST /actions/Account.action?signon= username=j2ee&password=j2ee";
        RequestLine r = RequestLine.parse(line);
        assertNull(r.capture());
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
        assertNull(bare.capture());

        RequestLine withQuery = RequestLine.parse("GET /x?a=b");
        assertEquals("GET", withQuery.method());
        assertEquals("/x?a=b", withQuery.path());
        assertNull(withQuery.capture());
    }

    @Test public void malformedTrailingCaptureLeavesCaptureNullAndStaysInBody() {
        RequestLine r = RequestLine.parse("/x <<bad");
        assertNull(r.capture());
        assertEquals("<<bad", r.body());
    }
}
