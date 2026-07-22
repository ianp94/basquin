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

    public enum Kind { HEADER, INPUT }

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
     * @return the extracted value, or null on any miss.
     */
    public String extract(Function<String, String> headerLookup, String body) {
        if (kind == Kind.HEADER) {
            return headerLookup.apply(arg);
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
            return CoverageGuidedRun.unescapeHtml(valueMatcher.group(2));
        }
        return null;
    }

    private static final Pattern INPUT_TAG_PATTERN = Pattern.compile("<input\\b[^>]*>");
    private static final Pattern VALUE_PATTERN = Pattern.compile("value\\s*=\\s*([\"'])(.*?)\\1");
}
