package runner.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final List<Sequence> sequences = new ArrayList<>();
    private final Random rnd;

    /**
     * An ordered multi-step transaction (e.g. signon → view item → add to cart → check out).
     * Steps run in order against one session, and — critically — placeholders bind ONCE per
     * execution and are reused across steps, so the item you add to the cart is the item you
     * viewed. Re-randomising per step would produce an incoherent transaction that never reaches
     * the code a real checkout does.
     */
    public static final class Sequence {
        public final String name;
        public final List<String> steps = new ArrayList<>();
        Sequence(String name) { this.name = name; }
    }

    private RequestGrammar(Random rnd) {
        this.rnd = rnd;
    }

    public static RequestGrammar load(Path file, Random rnd) throws IOException {
        RequestGrammar g = new RequestGrammar(rnd);
        Path base = file.toAbsolutePath().getParent();
        Sequence current = null;
        for (String raw : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            line = line.trim();
            if (line.isEmpty()) { current = null; continue; }   // blank line ends a sequence block
            if (line.startsWith("@sequence")) {
                String name = line.substring("@sequence".length()).trim();
                current = new Sequence(name.isEmpty() ? "seq" + g.sequences.size() : name);
                g.sequences.add(current);
                continue;
            }
            if (current != null && indented && line.startsWith("/")) {
                current.steps.add(line);
                continue;
            }
            current = null;
            if (line.startsWith("$")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(1, eq).trim();
                List<String> alts = new ArrayList<>();
                for (String alt : line.substring(eq + 1).split("\\|")) {
                    String a = alt.trim();
                    if (a.isEmpty()) continue;
                    if (a.startsWith("@")) {
                        // Values come from the corpus: one per line. This is the corpus/grammar
                        // split — real values live as data next to the app's other seeds, while
                        // the grammar file stays a statement about *structure*.
                        alts.addAll(readValues(base, a.substring(1).trim()));
                    } else {
                        alts.add(a);
                    }
                }
                if (!alts.isEmpty()) g.rules.put(name, alts);
            } else if (line.startsWith("/")) {
                g.routes.add(line);
            }
        }
        return g;
    }

    private static List<String> readValues(Path base, String ref) {
        List<String> out = new ArrayList<>();
        Path p = Paths.get(ref);
        if (!p.isAbsolute() && base != null) p = base.resolve(ref);
        if (readInto(p, out)) return out;

        // Fallback: resolve the ref's basename against a flat corpus dir. A grammar authored with
        // laptop-relative @refs (e.g. @../corpus/jpetstore/values/categoryId.txt, which resolves next
        // to the grammar file on disk) then works unchanged in a container where the operator mounts a
        // flat corpus ConfigMap and sets -Dclosurejvm.corpusDir — the tree can't survive a flat
        // ConfigMap, but the basename can.
        String corpusDir = System.getProperty("closurejvm.corpusDir", "");
        if (!corpusDir.isEmpty()) {
            Path alt = Paths.get(corpusDir).resolve(p.getFileName().toString());
            if (readInto(alt, out)) {
                System.out.println("[ClosureJVM] grammar: resolved values \"" + ref + "\" via corpusDir (" + alt + ")");
                return out;
            }
            System.err.println("[ClosureJVM] grammar: values file not found at " + p + " or " + alt);
        } else {
            System.err.println("[ClosureJVM] grammar: could not read values file " + p);
        }
        return out;
    }

    /** Read non-comment, non-blank lines of {@code p} into {@code out}. Returns false if unreadable. */
    private static boolean readInto(Path p, List<String> out) {
        if (!Files.isReadable(p)) return false;
        try {
            for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
                String v = line.trim();
                if (v.isEmpty() || v.startsWith("#")) continue;
                out.add(v);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isEmpty() { return routes.isEmpty(); }
    public int routeCount() { return routes.size(); }
    public int ruleCount() { return rules.size(); }

    public int sequenceCount() { return sequences.size(); }
    public boolean hasSequences() { return !sequences.isEmpty(); }

    /** All route templates, expanded once each — used for a deterministic first pass. */
    public List<String> expandAll() {
        List<String> out = new ArrayList<>(routes.size());
        for (String r : routes) out.add(expand(r, new LinkedHashMap<>()));
        return out;
    }

    /** A random route template, expanded (placeholders bound independently). */
    public String randomRequest() {
        if (routes.isEmpty()) return "/";
        return expand(routes.get(rnd.nextInt(routes.size())), new LinkedHashMap<>());
    }

    /**
     * A random sequence, expanded as a unit: every step shares one binding map, so a placeholder
     * used in several steps resolves to the same value throughout the transaction.
     */
    public List<String> randomSequence() {
        if (sequences.isEmpty()) return null;
        return expandSequence(sequences.get(rnd.nextInt(sequences.size())));
    }

    /** Each sequence expanded once — used for the deterministic first pass. */
    public List<List<String>> expandAllSequences() {
        List<List<String>> out = new ArrayList<>();
        for (Sequence s : sequences) out.add(expandSequence(s));
        return out;
    }

    private List<String> expandSequence(Sequence s) {
        Map<String, String> bindings = new LinkedHashMap<>();
        List<String> out = new ArrayList<>(s.steps.size());
        for (String step : s.steps) out.add(expand(step, bindings));
        return out;
    }

    /**
     * Re-expand the template that produced {@code concrete}, giving a new value for its
     * placeholders. Falls back to a fresh random request when no template matches (e.g. the input
     * came from a plain seed corpus rather than the grammar).
     */
    public String mutate(String concrete) {
        String template = templateFor(concrete);
        return template != null ? expand(template, new LinkedHashMap<>()) : randomRequest();
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

    /**
     * Expand a template. {@code bindings} carries values already chosen in this scope: within a
     * sequence it is shared across steps (so {@code ${itemId}} is the same item throughout);
     * for a standalone request a fresh map is passed, so each request binds independently.
     */
    private String expand(String template, Map<String, String> bindings) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("${", i);
            if (open < 0) { out.append(template, i, template.length()); break; }
            int close = template.indexOf('}', open);
            if (close < 0) { out.append(template, i, template.length()); break; }
            out.append(template, i, open);
            String name = template.substring(open + 2, close);
            out.append(bindings.computeIfAbsent(name, this::value));
            i = close + 1;
        }
        return out.toString();
    }

    private String value(String ruleName) {
        List<String> alts = rules.get(ruleName);
        if (alts == null || alts.isEmpty()) return "";
        return generator(alts.get(rnd.nextInt(alts.size())));
    }

    /**
     * Structural generator: {@code ~EST-[0-9]{1,4}} produces a value that MATCHES THE APP'S ID
     * SHAPE but need not exist. This is the point of separating corpus from grammar — the corpus
     * supplies real values (which reach the happy paths and deep code), the grammar supplies the
     * *structure* of invented ones. A structurally-valid-but-nonexistent id gets past the app's
     * parsing and format checks and into lookup/deref code, where the interesting failures live;
     * a purely random {@code <string>} usually gets rejected at the first validation and never
     * gets that far. Supports literals, {@code [A-Z] [a-z] [0-9] [abc]} classes, and
     * {@code {n}} / {@code {n,m}} repetition — enough for id/username shapes, deliberately not a
     * full regex engine.
     */
    private String structural(String pattern) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            String alphabet;
            if (c == '[') {
                int close = pattern.indexOf(']', i);
                if (close < 0) { out.append(c); i++; continue; }
                alphabet = expandClass(pattern.substring(i + 1, close));
                i = close + 1;
            } else {
                alphabet = String.valueOf(c);
                i++;
            }
            int min = 1, max = 1;
            if (i < pattern.length() && pattern.charAt(i) == '{') {
                int close = pattern.indexOf('}', i);
                if (close > 0) {
                    String spec = pattern.substring(i + 1, close);
                    try {
                        int comma = spec.indexOf(',');
                        if (comma < 0) { min = max = Integer.parseInt(spec.trim()); }
                        else {
                            min = Integer.parseInt(spec.substring(0, comma).trim());
                            max = Integer.parseInt(spec.substring(comma + 1).trim());
                        }
                    } catch (NumberFormatException ignored) { /* treat as literal repeat of 1 */ }
                    i = close + 1;
                }
            }
            int reps = max > min ? min + rnd.nextInt(max - min + 1) : min;
            for (int r = 0; r < reps; r++) {
                out.append(alphabet.isEmpty() ? "" : String.valueOf(alphabet.charAt(rnd.nextInt(alphabet.length()))));
            }
        }
        return out.toString();
    }

    private static String expandClass(String spec) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            if (i + 2 < spec.length() && spec.charAt(i + 1) == '-') {
                for (char c = spec.charAt(i); c <= spec.charAt(i + 2); c++) sb.append(c);
                i += 2;
            } else {
                sb.append(spec.charAt(i));
            }
        }
        return sb.toString();
    }

    private String generator(String token) {
        if (token.startsWith("~")) {
            return structural(token.substring(1));
        }
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
