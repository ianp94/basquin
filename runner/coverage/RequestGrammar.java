package runner.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A tiny grammar for generating request patterns, so an app's reachable surface AND its parameter
 * value space are both data instead of compiled-in constants (DD-016 removed hardcoded routes;
 * this removes the hardcoded value dictionaries too).
 *
 * Format — one directive per line, {@code #} comments:
 * <pre>
 *   $categoryId = FISH | DOGS | CATS          # a dictionary of literal values
 *   $keyword    = fish | dog | &lt;string&gt;       # may include generators
 *   $orderId    = &lt;int&gt; | 1
 *
 *   /actions/Catalog.action                                        # a route template
 *   /actions/Catalog.action?viewCategory=&categoryId=${categoryId}
 * </pre>
 *
 * Generators (the fuzzing part — these produce values a hand-written dictionary never would):
 * <ul>
 *   <li>{@code <int>}     — an integer, biased toward boundaries (0, 1, -1, MAX_VALUE, …)</li>
 *   <li>{@code <string>}  — a random short string, sometimes with metacharacters</li>
 *   <li>{@code <empty>}   — the empty string</li>
 *   <li>{@code <long>}    — a long string (length/overflow probing)</li>
 * </ul>
 *
 * A route is expanded by substituting each {@code ${name}} with a random choice from that rule;
 * "mutating" an input is just re-expanding its template, so mutation stays inside the grammar's
 * shape rather than producing garbage URLs.
 */
public final class RequestGrammar {

    private final Map<String, List<String>> rules = new LinkedHashMap<>();
    private final List<String> routes = new ArrayList<>();
    private final Random rnd;

    private RequestGrammar(Random rnd) {
        this.rnd = rnd;
    }

    public static RequestGrammar load(Path file, Random rnd) throws IOException {
        RequestGrammar g = new RequestGrammar(rnd);
        for (String raw : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("$")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(1, eq).trim();
                List<String> alts = new ArrayList<>();
                for (String alt : line.substring(eq + 1).split("\\|")) {
                    String a = alt.trim();
                    if (!a.isEmpty()) alts.add(a);
                }
                if (!alts.isEmpty()) g.rules.put(name, alts);
            } else if (line.startsWith("/")) {
                g.routes.add(line);
            }
        }
        return g;
    }

    public boolean isEmpty() { return routes.isEmpty(); }
    public int routeCount() { return routes.size(); }
    public int ruleCount() { return rules.size(); }

    /** All route templates, expanded once each — used for a deterministic first pass. */
    public List<String> expandAll() {
        List<String> out = new ArrayList<>(routes.size());
        for (String r : routes) out.add(expand(r));
        return out;
    }

    /** A random route template, expanded. */
    public String randomRequest() {
        if (routes.isEmpty()) return "/";
        return expand(routes.get(rnd.nextInt(routes.size())));
    }

    /**
     * Re-expand the template that produced {@code concrete}, giving a new value for its
     * placeholders. Falls back to a fresh random request when no template matches (e.g. the input
     * came from a plain seed corpus rather than the grammar).
     */
    public String mutate(String concrete) {
        String template = templateFor(concrete);
        return template != null ? expand(template) : randomRequest();
    }

    /** Find the template whose literal (non-placeholder) parts match this concrete request. */
    private String templateFor(String concrete) {
        for (String t : routes) {
            if (t.indexOf("${") < 0) {
                if (t.equals(concrete)) return t;
                continue;
            }
            String regex = java.util.regex.Pattern.quote(t)
                    .replaceAll("\\$\\{[a-zA-Z0-9_]+\\}", "\\\\E.*\\\\Q");
            try {
                if (concrete.matches(regex)) return t;
            } catch (Exception ignored) {
                // a template that doesn't compile just doesn't match
            }
        }
        return null;
    }

    private String expand(String template) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("${", i);
            if (open < 0) { out.append(template, i, template.length()); break; }
            int close = template.indexOf('}', open);
            if (close < 0) { out.append(template, i, template.length()); break; }
            out.append(template, i, open);
            out.append(value(template.substring(open + 2, close)));
            i = close + 1;
        }
        return out.toString();
    }

    private String value(String ruleName) {
        List<String> alts = rules.get(ruleName);
        if (alts == null || alts.isEmpty()) return "";
        return generator(alts.get(rnd.nextInt(alts.size())));
    }

    private String generator(String token) {
        switch (token) {
            case "<int>": {
                int[] boundaries = {0, 1, -1, 2, 127, 128, 255, 256, 65535, Integer.MAX_VALUE, Integer.MIN_VALUE};
                return rnd.nextInt(100) < 60
                        ? String.valueOf(boundaries[rnd.nextInt(boundaries.length)])
                        : String.valueOf(rnd.nextInt(10000));
            }
            case "<empty>":
                return "";
            case "<long>": {
                int n = 256 + rnd.nextInt(1024);
                StringBuilder b = new StringBuilder(n);
                for (int i = 0; i < n; i++) b.append('A');
                return b.toString();
            }
            case "<string>": {
                String[] spicy = {"'", "\"", "<", ">", "%", "&", "..", "/", "\\", "null", "0", " "};
                int n = 1 + rnd.nextInt(8);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    b.append(rnd.nextInt(100) < 25
                            ? spicy[rnd.nextInt(spicy.length)]
                            : String.valueOf((char) ('a' + rnd.nextInt(26))));
                }
                return b.toString();
            }
            default:
                return token; // a literal dictionary value
        }
    }
}
