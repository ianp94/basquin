import org.junit.Test;
import runner.util.DashboardServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure pieces of the DD-028 read-auth machinery (#55 review): the security
 * behavior end to end is exercised by the in-cluster e2e's 401/200 pair; these pin the parsing
 * and comparison helpers that e2e failures would only implicate indirectly.
 */
public class DashboardAuthTest {

    // --- tokensEqual (constant-time comparison core) ---

    @Test
    public void tokensEqualMatchesExactly() {
        assertTrue(DashboardServer.tokensEqual("abc123", "abc123"));
        assertFalse(DashboardServer.tokensEqual("abc123", "abc124"));
        assertFalse(DashboardServer.tokensEqual("abc123", "abc12"));
        assertFalse(DashboardServer.tokensEqual("abc123", ""));
    }

    @Test
    public void tokensEqualRejectsNullCandidate() {
        assertFalse(DashboardServer.tokensEqual("abc123", null));
    }

    @Test
    public void emptyExpectedOnlyMatchesEmpty() {
        // The server never consults tokensEqual when no token is configured, but the helper itself
        // must not treat "" as a wildcard.
        assertTrue(DashboardServer.tokensEqual("", ""));
        assertFalse(DashboardServer.tokensEqual("", "anything"));
    }

    // --- cookieName (per-campaign, derived from the token) ---

    @Test
    public void cookieNameIsDeterministicAndPerToken() {
        String a1 = DashboardServer.cookieName("token-a");
        String a2 = DashboardServer.cookieName("token-a");
        String b = DashboardServer.cookieName("token-b");
        assertEquals(a1, a2);
        assertNotEquals(a1, b); // two campaigns → two cookie names (cookies ignore ports)
        assertTrue(a1, a1.startsWith("basquin_dash_"));
        // RFC 6265 cookie-name safety: the suffix is hex only.
        assertTrue(a1, a1.matches("basquin_dash_[0-9a-f]{8}"));
    }

    // --- cookieValue (Cookie header parsing) ---

    @Test
    public void cookieValueFindsAmongMultipleCookies() {
        List<String> headers = Collections.singletonList("foo=1; basquin_dash_ab12cd34=tok; bar=2");
        assertEquals("tok", DashboardServer.cookieValue(headers, "basquin_dash_ab12cd34"));
    }

    @Test
    public void cookieValueHandlesMultipleHeadersAndSpacing() {
        List<String> headers = Arrays.asList("foo=1", "  basquin_dash_ab12cd34 = tok  ; x=y");
        assertEquals("tok", DashboardServer.cookieValue(headers, "basquin_dash_ab12cd34"));
    }

    @Test
    public void cookieValueKeepsEqualsSignsInsideTheValue() {
        // Hex tokens never contain '=', but the parser must not truncate if a value does.
        List<String> headers = Collections.singletonList("name=va=lue");
        assertEquals("va=lue", DashboardServer.cookieValue(headers, "name"));
    }

    @Test
    public void cookieValueMissesCleanly() {
        assertNull(DashboardServer.cookieValue(null, "name"));
        assertNull(DashboardServer.cookieValue(Collections.emptyList(), "name"));
        assertNull(DashboardServer.cookieValue(Collections.singletonList("other=1"), "name"));
        // Malformed fragments (no '=', bare ';', null entry) must not throw or match.
        assertNull(DashboardServer.cookieValue(Arrays.asList("garbage; ;;", null, "=leadingeq"), "name"));
        // A cookie whose NAME merely contains the target as a prefix/suffix must not match.
        assertNull(DashboardServer.cookieValue(Collections.singletonList("namex=1; xname=2"), "name"));
    }
}
