package examples.fuzzapps;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal JSON tokenizer and validator for a single JSON value.
 * Supports strings (basic escapes), numbers, true/false/null, arrays, and objects.
 * Throws IllegalArgumentException on invalid input.
 */
public final class JsonTokenizer {
    private JsonTokenizer() {}

    public static void validate(String s) {
        if (s == null) throw new IllegalArgumentException("null");
        int[] i = {0};
        Deque<Character> stack = new ArrayDeque<>();
        skipWs(s, i);
        parseValue(s, i, stack);
        skipWs(s, i);
        if (i[0] != s.length()) throw new IllegalArgumentException("trailing garbage");
        if (!stack.isEmpty()) throw new IllegalArgumentException("unclosed structure");
    }

    private static void parseValue(String s, int[] i, Deque<Character> stack) {
        skipWs(s, i);
        if (i[0] >= s.length()) throw new IllegalArgumentException("eof");
        char c = s.charAt(i[0]);
        switch (c) {
            case '"': parseString(s, i); return;
            case '{': parseObject(s, i, stack); return;
            case '[': parseArray(s, i, stack); return;
            case 't': expect(s, i, "true"); return;
            case 'f': expect(s, i, "false"); return;
            case 'n': expect(s, i, "null"); return;
            default: parseNumber(s, i); return;
        }
    }

    private static void parseObject(String s, int[] i, Deque<Character> stack) {
        stack.push('{');
        i[0]++;
        skipWs(s, i);
        if (i[0] < s.length() && s.charAt(i[0]) == '}') { i[0]++; stack.pop(); return; }
        while (true) {
            skipWs(s, i);
            if (i[0] >= s.length() || s.charAt(i[0]) != '"') throw new IllegalArgumentException("obj key");
            parseString(s, i);
            skipWs(s, i);
            if (i[0] >= s.length() || s.charAt(i[0]) != ':') throw new IllegalArgumentException(":");
            i[0]++;
            parseValue(s, i, stack);
            skipWs(s, i);
            if (i[0] < s.length() && s.charAt(i[0]) == '}') { i[0]++; stack.pop(); return; }
            if (i[0] < s.length() && s.charAt(i[0]) == ',') { i[0]++; continue; }
            throw new IllegalArgumentException("obj comma/}");
        }
    }

    private static void parseArray(String s, int[] i, Deque<Character> stack) {
        stack.push('[');
        i[0]++;
        skipWs(s, i);
        if (i[0] < s.length() && s.charAt(i[0]) == ']') { i[0]++; stack.pop(); return; }
        while (true) {
            parseValue(s, i, stack);
            skipWs(s, i);
            if (i[0] < s.length() && s.charAt(i[0]) == ']') { i[0]++; stack.pop(); return; }
            if (i[0] < s.length() && s.charAt(i[0]) == ',') { i[0]++; continue; }
            throw new IllegalArgumentException("arr comma/]");
        }
    }

    private static void parseString(String s, int[] i) {
        if (s.charAt(i[0]) != '"') throw new IllegalArgumentException("string");
        i[0]++;
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]++);
            if (c == '"') return;
            if (c == '\\') {
                if (i[0] >= s.length()) throw new IllegalArgumentException("esc eof");
                char e = s.charAt(i[0]++);
                if (e == 'u') {
                    for (int k = 0; k < 4; k++) {
                        if (i[0] >= s.length()) throw new IllegalArgumentException("u-escape eof");
                        char h = s.charAt(i[0]++);
                        if (!isHex(h)) throw new IllegalArgumentException("hex");
                    }
                } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                    throw new IllegalArgumentException("esc");
                }
            }
        }
        throw new IllegalArgumentException("string eof");
    }

    private static void parseNumber(String s, int[] i) {
        int start = i[0];
        char c = s.charAt(i[0]);
        if (c == '-') { i[0]++; if (i[0] >= s.length()) throw new IllegalArgumentException("num"); }
        if (i[0] < s.length() && s.charAt(i[0]) == '0') {
            i[0]++;
        } else {
            if (i[0] >= s.length() || !Character.isDigit(s.charAt(i[0]))) throw new IllegalArgumentException("num");
            while (i[0] < s.length() && Character.isDigit(s.charAt(i[0]))) i[0]++;
        }
        if (i[0] < s.length() && s.charAt(i[0]) == '.') {
            i[0]++;
            if (i[0] >= s.length() || !Character.isDigit(s.charAt(i[0]))) throw new IllegalArgumentException("frac");
            while (i[0] < s.length() && Character.isDigit(s.charAt(i[0]))) i[0]++;
        }
        if (i[0] < s.length() && (s.charAt(i[0]) == 'e' || s.charAt(i[0]) == 'E')) {
            i[0]++;
            if (i[0] < s.length() && (s.charAt(i[0]) == '+' || s.charAt(i[0]) == '-')) i[0]++;
            if (i[0] >= s.length() || !Character.isDigit(s.charAt(i[0]))) throw new IllegalArgumentException("exp");
            while (i[0] < s.length() && Character.isDigit(s.charAt(i[0]))) i[0]++;
        }
        if (i[0] == start) throw new IllegalArgumentException("num empty");
    }

    private static void expect(String s, int[] i, String lit) {
        if (i[0] + lit.length() > s.length()) throw new IllegalArgumentException(lit);
        if (!s.regionMatches(i[0], lit, 0, lit.length())) throw new IllegalArgumentException(lit);
        i[0] += lit.length();
    }

    private static void skipWs(String s, int[] i) {
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { i[0]++; }
            else break;
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
