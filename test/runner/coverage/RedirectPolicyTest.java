package runner.coverage;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DD-039 §2/§3: the redirect decision, as pure functions. Kept off the network deliberately — the
 * riskiest behaviours here (an unparseable Location, a default-port comparison, HEAD surviving a
 * 302) are decisions, not I/O, and a decision that can only be exercised through a server is a
 * decision nobody tests at its edges.
 */
public class RedirectPolicyTest {

    @Test public void isRedirectCoversThe3xxRangeAndNothingElse() {
        assertTrue(CoverageGuidedRun.isRedirect(301));
        assertTrue(CoverageGuidedRun.isRedirect(302));
        assertTrue(CoverageGuidedRun.isRedirect(308));
        assertFalse(CoverageGuidedRun.isRedirect(200));
        assertFalse(CoverageGuidedRun.isRedirect(400));
        // getResponseCode() returns -1 on an unparseable status line; that must not read as a redirect.
        assertFalse("an unparseable status line is not a redirect", CoverageGuidedRun.isRedirect(-1));
    }

    @Test public void relativeLocationResolvesAgainstTheRequestUrl() {
        assertEquals(URI.create("http://svc:8080/login"),
                CoverageGuidedRun.resolveLocation("http://svc:8080/a/b?x=1", "/login"));
        assertEquals(URI.create("http://svc:8080/a/next"),
                CoverageGuidedRun.resolveLocation("http://svc:8080/a/b", "next"));
    }

    // THE test that keeps a fuzzed input from filing a false crash finding against the app. URI
    // throws on unencoded space, {}, | and ^ — exactly the bytes a fuzzer reflects into a query
    // string — and request(...) is `throws Exception` whose caller catches Throwable into
    // StatusReporter.recordCrash().
    @Test public void unparseableLocationReturnsNullRatherThanThrowing() {
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", "/x y|z^{}"));
        assertNull("a base that will not parse must not throw either",
                CoverageGuidedRun.resolveLocation(":::not a url:::", "/login"));
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", null));
        assertNull(CoverageGuidedRun.resolveLocation("http://svc/a", ""));
    }

    // Hop 0's URL is base + the SUBSTITUTED path, so it is a MORE hostile string than a Location.
    @Test public void safeUriNeverThrowsOnASubstitutedPath() {
        assertNull(CoverageGuidedRun.safeUri("http://svc/edit?q=a b|c^{}"));
        assertEquals(URI.create("http://svc/edit"), CoverageGuidedRun.safeUri("http://svc/edit"));
        assertNull(CoverageGuidedRun.safeUri(null));
    }

    @Test public void sameOriginNormalizesTheDefaultPortAndIgnoresHostCase() {
        assertTrue("URI.getPort() is -1 for http://svc/foo; it must equal a base of http://svc:80",
                CoverageGuidedRun.sameOrigin(URI.create("http://svc:80/a"), URI.create("http://svc/b")));
        assertTrue(CoverageGuidedRun.sameOrigin(URI.create("https://SVC/a"), URI.create("https://svc:443/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"), URI.create("http://evil.example.invalid/b")));
        assertFalse("a scheme change is a different origin",
                CoverageGuidedRun.sameOrigin(URI.create("http://svc/a"), URI.create("https://svc/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(URI.create("http://svc:8080/a"), URI.create("http://svc:9090/b")));
        assertFalse(CoverageGuidedRun.sameOrigin(null, URI.create("http://svc/b")));
    }

    @Test public void defaultPortIsSchemeDerivedOnlyWhenAbsent() {
        assertEquals(8080, CoverageGuidedRun.defaultPort(URI.create("http://svc:8080/a")));
        assertEquals(80, CoverageGuidedRun.defaultPort(URI.create("http://svc/a")));
        assertEquals(443, CoverageGuidedRun.defaultPort(URI.create("https://svc/a")));
    }

    // Preserves today's JDK behaviour rather than choosing new behaviour (spec §3, Evidence A and B).
    @Test public void followMethodMatchesTheJdkTableItReplaces() {
        assertEquals("GET", CoverageGuidedRun.followMethod(302, "POST"));
        assertEquals("GET", CoverageGuidedRun.followMethod(303, "POST"));
        assertEquals("GET", CoverageGuidedRun.followMethod(301, "PUT"));
        assertEquals("GET", CoverageGuidedRun.followMethod(302, "PATCH"));
        assertEquals("307 exists to preserve the method", "POST", CoverageGuidedRun.followMethod(307, "POST"));
        assertEquals("POST", CoverageGuidedRun.followMethod(308, "POST"));
        assertEquals("GET stays GET", "GET", CoverageGuidedRun.followMethod(302, "GET"));
        assertEquals("HEAD must NOT silently become GET — that pulls a body the caller never asked "
                + "for, inflating the input's measured latency and heap and corrupting its cost rank",
                "HEAD", CoverageGuidedRun.followMethod(302, "HEAD"));
        assertEquals("HEAD", CoverageGuidedRun.followMethod(303, "HEAD"));
    }

    // DD-036: a recorded hop URL must never carry a substituted token.
    @Test public void strippedUrlRemovesQueryAndFragment() {
        assertEquals("http://svc:8080/edit",
                CoverageGuidedRun.strippedUrl("http://svc:8080/edit?csrf=LIVE-TOKEN-123&page=Main"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit#frag"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit#f?notaquery"));
        assertEquals("http://svc/edit", CoverageGuidedRun.strippedUrl("http://svc/edit"));
        assertEquals("", CoverageGuidedRun.strippedUrl(null));
    }
}
