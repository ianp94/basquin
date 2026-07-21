package runner.coverage;

import org.junit.Test;
import runner.coverage.JacocoCoverageProvider.Endpoint;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Endpoint parsing is the seam where a mistyped {@code -Dbasquin.coverage.jacoco} flag becomes
 * either a clear error or a silently-wrong campaign (DD-023). Lives in {@code runner.coverage} to
 * read {@link Endpoint}'s package-private {@code host}/{@code port}. The actual union merge needs a
 * live JaCoCo tcpserver, so it is exercised in a real multi-replica run, not here.
 */
public class JacocoEndpointParseTest {

    @Test
    public void parsesASingleHostPort() {
        List<Endpoint> eps = JacocoCoverageProvider.parseEndpoints("localhost:6300");
        assertEquals(1, eps.size());
        assertEquals("localhost", eps.get(0).host);
        assertEquals(6300, eps.get(0).port);
    }

    @Test
    public void parsesACommaSeparatedFleetTrimmingWhitespace() {
        List<Endpoint> eps = JacocoCoverageProvider.parseEndpoints("10.0.1.4:6300, 10.0.1.5:6300 ,10.0.1.6:6301");
        assertEquals(3, eps.size());
        assertEquals("10.0.1.6", eps.get(2).host);
        assertEquals(6301, eps.get(2).port);
    }

    @Test
    public void ignoresEmptyEntriesFromTrailingOrDoubleCommas() {
        List<Endpoint> eps = JacocoCoverageProvider.parseEndpoints("a:1,,b:2,");
        assertEquals(2, eps.size());
        assertEquals("a", eps.get(0).host);
        assertEquals("b", eps.get(1).host);
    }

    /**
     * A headless Service name carries dots and its own colon-less form here; the host is kept
     * verbatim so {@code getAllByName} can expand it to pod IPs at sample time.
     */
    @Test
    public void keepsAFullyQualifiedServiceHostIntact() {
        List<Endpoint> eps = JacocoCoverageProvider.parseEndpoints("jpetstore-jacoco.default.svc.cluster.local:6300");
        assertEquals(1, eps.size());
        assertEquals("jpetstore-jacoco.default.svc.cluster.local", eps.get(0).host);
        assertEquals(6300, eps.get(0).port);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEntryWithNoPort() {
        JacocoCoverageProvider.parseEndpoints("localhost");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANonNumericPort() {
        JacocoCoverageProvider.parseEndpoints("localhost:notaport");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptySpec() {
        JacocoCoverageProvider.parseEndpoints("   ");
    }
}
