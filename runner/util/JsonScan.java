package runner.util;

/**
 * Tiny, iterative (non-regex) scanner for pulling a {@code "key":"value"} string out of JSON this
 * project produced itself. Exists specifically to avoid a real bug found while testing
 * {@link FindingsClusterer} against production-sized data: a regex of the classic shape
 * {@code (?:[^"\\]|\\.)*} to match an escaped JSON string value recurses one Java regex stack
 * frame per character (Pattern$Loop.match is implemented recursively), so matching a string of a
 * few thousand characters throws {@link StackOverflowError} — no exponential blowup needed, just
 * length. A findings' {@code text} field or a Claude analysis response both routinely exceed that.
 * This scanner is O(n) with O(1) recursion depth: a plain loop tracking an escape flag.
 *
 * Not a general JSON parser — sufficient only for flat {@code "key":"escaped string"} pairs in
 * payloads this project controls on both ends (same trust boundary as DD-013's numField scrape).
 */
final class JsonScan {

    private JsonScan() {}

    /** Index of the character right after the opening quote of {@code "key":"...}, or -1. */
    static int valueStart(String json, String key, int from) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle, from);
        if (k < 0) return -1;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return -1;
        int quote = json.indexOf('"', colon + 1);
        return quote < 0 ? -1 : quote + 1;
    }

    /** Index of the unescaped closing quote starting the scan at {@code from} (right after the opening quote). */
    static int stringEnd(String json, int from) {
        boolean escaped = false;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** Convenience: extract and unescape {@code "key":"value"} starting the search at {@code from}; null if absent. */
    static String extract(String json, String key, int from) {
        int vs = valueStart(json, key, from);
        if (vs < 0) return null;
        int ve = stringEnd(json, vs);
        if (ve < 0) return null;
        return unescape(json.substring(vs, ve));
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
