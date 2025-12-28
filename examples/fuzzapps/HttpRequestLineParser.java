package examples.fuzzapps;

/**
 * Tiny HTTP request-line parser: "METHOD SP request-target SP HTTP/x.y" up to line ending.
 * Throws IllegalArgumentException on malformed input.
 */
public final class HttpRequestLineParser {
    private HttpRequestLineParser() {}

    public static Parsed parse(String line) {
        if (line == null) throw new IllegalArgumentException("null");
        // Trim trailing CR/LF
        int end = line.indexOf('\r');
        if (end < 0) end = line.indexOf('\n');
        if (end >= 0) line = line.substring(0, end);
        int n = line.length();
        int i = 0;

        // METHOD
        int mStart = i;
        while (i < n && isToken(line.charAt(i))) i++;
        if (i == mStart) throw new IllegalArgumentException("empty method");
        String method = line.substring(mStart, i);
        if (!isUpper(method)) throw new IllegalArgumentException("method not uppercase");

        // SP
        if (i >= n || line.charAt(i) != ' ') throw new IllegalArgumentException("no space after method");
        i++;

        // request-target
        int tStart = i;
        while (i < n && line.charAt(i) != ' ') i++;
        if (i == tStart) throw new IllegalArgumentException("empty target");
        String target = line.substring(tStart, i);

        // SP
        if (i >= n || line.charAt(i) != ' ') throw new IllegalArgumentException("no space after target");
        i++;

        // HTTP/
        final String prefix = "HTTP/";
        if (i + prefix.length() + 2 > n) throw new IllegalArgumentException("short version");
        if (!line.regionMatches(i, prefix, 0, prefix.length())) throw new IllegalArgumentException("missing HTTP/");
        i += prefix.length();
        int majStart = i;
        while (i < n && Character.isDigit(line.charAt(i))) i++;
        if (i == majStart) throw new IllegalArgumentException("no major");
        if (i >= n || line.charAt(i) != '.') throw new IllegalArgumentException("missing dot");
        i++;
        int minStart = i;
        while (i < n && Character.isDigit(line.charAt(i))) i++;
        if (i == minStart) throw new IllegalArgumentException("no minor");

        int major = Integer.parseInt(line.substring(majStart, line.indexOf('.', majStart)));
        int minor = Integer.parseInt(line.substring(minStart, i));

        return new Parsed(method, target, major, minor);
    }

    private static boolean isUpper(String s) {
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static boolean isToken(char c) {
        // RFC 7230 tchar without punctuation complications; accept ALPHA DIGIT and few safe chars
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    public static final class Parsed {
        public final String method;
        public final String target;
        public final int major;
        public final int minor;
        public Parsed(String m, String t, int maj, int min) {
            this.method = m; this.target = t; this.major = maj; this.minor = min;
        }
    }
}

