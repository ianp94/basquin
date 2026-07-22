package runner.coverage;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A named capture directive: extracts a dynamic token (CSRF, {@code _sourcePage}, session id, ...)
 * from a response so a later replay step can substitute it into a subsequent request.
 *
 * <p>Wire format: {@code "<<name=kind:arg"}, e.g. {@code "<<csrf=input:X-XSRF-TOKEN"} or
 * {@code "<<sess=header:Set-Cookie"}.
 */
public record Capture(String name, Kind kind, String arg) {

    public enum Kind { HEADER, INPUT, INPUTPAIR }

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    /** Parses {@code "<<name=kind:arg"}. Returns null on any malformed input (never throws). */
    public static Capture parse(String token) {
        if (token == null || !token.startsWith("<<")) return null;
        String rest = token.substring(2);
        int eq = rest.indexOf('=');
        if (eq < 0) return null;
        String name = rest.substring(0, eq);
        if (!NAME_PATTERN.matcher(name).matches()) return null;
        String kindAndArg = rest.substring(eq + 1);
        int colon = kindAndArg.indexOf(':');
        if (colon < 0) return null;
        String kindStr = kindAndArg.substring(0, colon);
        String arg = kindAndArg.substring(colon + 1);
        if (arg.isEmpty()) return null;
        Kind kind;
        if ("header".equals(kindStr)) {
            kind = Kind.HEADER;
        } else if ("input".equals(kindStr)) {
            kind = Kind.INPUT;
        } else if ("inputpair".equals(kindStr)) {
            int a = arg.indexOf('=');
            if (a <= 0 || a == arg.length() - 1) return null;   // need non-empty name AND value regex
            try {
                Pattern.compile(arg.substring(0, a));
                Pattern.compile(arg.substring(a + 1));
            } catch (java.util.regex.PatternSyntaxException e) {
                return null;
            }
            kind = Kind.INPUTPAIR;
        } else {
            return null;
        }
        return new Capture(name, kind, arg);
    }

    /** Inverse of {@link #parse(String)}. */
    public String format() {
        return "<<" + name + "=" + kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + arg;
    }

    /**
     * Extracts this capture's value from a response.
     *
     * <p>HEADER: looks up {@code arg} via {@code headerLookup}.
     * INPUT: finds the {@code <input ...>} tag whose {@code name} attribute equals {@code arg}
     * and returns its (HTML-entity-unescaped) {@code value} attribute.
     *
     * <p>The returned value is URL-encoded (DD-037 model A) so it is ready to splice verbatim into a
     * form body via {@link runner.coverage.LoadRun#substitute}.
     *
     * @return the URL-encoded extracted value, or null on any miss.
     */
    public String extract(Function<String, String> headerLookup, String body) {
        if (kind == Kind.HEADER) {
            return enc(headerLookup.apply(arg));
        }
        if (kind == Kind.INPUTPAIR) {
            if (body == null) return null;
            int a = arg.indexOf('=');
            Pattern nameRe = Pattern.compile(arg.substring(0, a));
            Pattern valRe  = Pattern.compile(arg.substring(a + 1));
            Matcher tags = INPUT_TAG_PATTERN.matcher(body);
            while (tags.find()) {
                String tag = tags.group();
                Matcher nm = NAME_ATTR_PATTERN.matcher(tag);
                if (!nm.find()) continue;
                Matcher vm = VALUE_PATTERN.matcher(tag);
                if (!vm.find()) continue;
                String nameVal = CoverageGuidedRun.unescapeHtml(nm.group(2));
                String valVal  = CoverageGuidedRun.unescapeHtml(vm.group(2));
                if (nameRe.matcher(nameVal).matches() && valRe.matcher(valVal).matches()) {
                    return enc(nameVal) + "=" + enc(valVal);
                }
            }
            return null;
        }
        // INPUT
        if (body == null) return null;
        Pattern namePattern = Pattern.compile("name\\s*=\\s*([\"'])" + Pattern.quote(arg) + "\\1");
        Matcher tagMatcher = INPUT_TAG_PATTERN.matcher(body);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            if (!namePattern.matcher(tag).find()) continue;
            Matcher valueMatcher = VALUE_PATTERN.matcher(tag);
            if (!valueMatcher.find()) continue;
            return enc(CoverageGuidedRun.unescapeHtml(valueMatcher.group(2)));
        }
        return null;
    }

    /** URL-encode for splicing into a form body; null-safe so a header/input miss stays null. */
    static String enc(String s) {
        return s == null ? null : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final Pattern INPUT_TAG_PATTERN = Pattern.compile("<input\\b[^>]*>");
    private static final Pattern VALUE_PATTERN = Pattern.compile("value\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern NAME_ATTR_PATTERN = Pattern.compile("(?<![\\w-])name\\s*=\\s*([\"'])(.*?)\\1");
}
