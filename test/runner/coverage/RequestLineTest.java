package runner.coverage;
import org.junit.Test; import java.util.List; import static org.junit.Assert.*;
public class RequestLineTest {
  @Test public void barePathIsGetWithNoBody() {
    RequestLine r = RequestLine.parse("/actions/Catalog.action");
    assertEquals("GET", r.method()); assertEquals("/actions/Catalog.action", r.path()); assertNull(r.body());
  }
  @Test public void methodPrefixAndBodyAreSplit() {
    RequestLine r = RequestLine.parse("POST /actions/Account.action?signon= username=j2ee&password=j2ee");
    assertEquals("POST", r.method()); assertEquals("/actions/Account.action?signon=", r.path());
    assertEquals("username=j2ee&password=j2ee", r.body());
  }
  @Test public void parseFormatRoundTrips() {
    for (String s : new String[]{"/x","GET /x?a=b","POST /y z=1&w=2",
        "POST /actions/Account.action?signon= username=j2ee&password=j2ee"})
      assertEquals(RequestLine.parse(s), RequestLine.parse(RequestLine.parse(s).format()));
    assertEquals("/x?a=b", RequestLine.parse("GET /x?a=b").format()); // GET canonicalizes bare
  }
  @Test public void sequenceSplitsOnTabAndJoinsBack() {
    List<RequestLine> seq = RequestLine.parseSequence("POST /a b=1\t/c\tGET /d");
    assertEquals(3, seq.size()); assertEquals("b=1", seq.get(0).body()); assertEquals("GET", seq.get(1).method());
    assertEquals("POST /a b=1\t/c\t/d", RequestLine.formatSequence(seq));
  }
  @Test public void firstPathDrivesTheCorpusLineFilter() {
    assertEquals("/a", RequestLine.firstPath("POST /a b=1\tGET /c"));
    assertEquals("/x", RequestLine.firstPath("/x"));
    assertEquals("", RequestLine.firstPath("cat"));
  }
  @Test public void sequenceRoundTripsWithTrailingEmptyStep() {
    List<RequestLine> seq = RequestLine.parseSequence("/x\t");
    assertEquals(2, seq.size());
    assertEquals("", seq.get(1).path());
    assertEquals("/x\t", RequestLine.formatSequence(seq));
  }
}
