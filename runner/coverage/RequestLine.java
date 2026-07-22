package runner.coverage;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record RequestLine(String method, String path, String body, List<Capture> captures) {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

    public RequestLine(String method, String path, String body) {
        this(method, path, body, List.of());
    }

    public boolean needsSubstitution() {
        return body != null && body.contains("${{");
    }

    public static RequestLine parse(String step) {
        if (step == null || step.isEmpty()) {
            return parseCore(step);
        }

        java.util.ArrayList<Capture> caps = new java.util.ArrayList<>();
        while (true) {
            int ls = step.lastIndexOf(' ');
            if (ls < 0 || !step.startsWith("<<", ls + 1)) break;
            Capture cap = Capture.parse(step.substring(ls + 1));
            if (cap == null) break;               // back-compat guard: a non-capture trailing token stays in the body
            caps.add(0, cap);                      // prepend → preserve left-to-right source order
            step = step.substring(0, ls);
        }

        RequestLine r = parseCore(step);
        return new RequestLine(r.method(), r.path(), r.body(), caps);
    }

    private static RequestLine parseCore(String step) {
        if (step == null || step.isEmpty()) {
            return new RequestLine("GET", "", null);
        }

        // Find the first space
        int firstSpace = step.indexOf(' ');

        if (firstSpace == -1) {
            // No space found
            if (isMethod(step)) {
                return new RequestLine(step, "", null);
            } else {
                return new RequestLine("GET", step, null);
            }
        }

        // There's a space
        String firstToken = step.substring(0, firstSpace);
        String remainder = step.substring(firstSpace + 1);

        if (isMethod(firstToken)) {
            // First token is a method
            String method = firstToken;

            // Find the next space in remainder
            int nextSpace = remainder.indexOf(' ');
            if (nextSpace == -1) {
                // No body
                return new RequestLine(method, remainder, null);
            } else {
                String path = remainder.substring(0, nextSpace);
                String body = remainder.substring(nextSpace + 1);
                return new RequestLine(method, path, body);
            }
        } else {
            // First token is not a method, so this is a bare path
            int nextSpace = step.indexOf(' ');
            if (nextSpace == -1) {
                return new RequestLine("GET", step, null);
            } else {
                String path = step.substring(0, nextSpace);
                String body = step.substring(nextSpace + 1);
                return new RequestLine("GET", path, body);
            }
        }
    }

    private static boolean isMethod(String token) {
        return METHODS.contains(token);
    }

    public String format() {
        StringBuilder sb = new StringBuilder(
            (method.equals("GET") ? "" : method + " ") + path + (body == null ? "" : " " + body));
        for (Capture c : captures) sb.append(' ').append(c.format());
        return sb.toString();
    }

    public static List<RequestLine> parseSequence(String line) {
        String[] fields = line.split("\t", -1);
        return Arrays.stream(fields)
            .map(RequestLine::parse)
            .toList();
    }

    public static String formatSequence(List<RequestLine> seq) {
        return seq.stream().map(RequestLine::format).collect(Collectors.joining("\t"));
    }

    public static String firstPath(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }

        int tabIndex = line.indexOf('\t');
        String firstField;
        if (tabIndex == -1) {
            firstField = line;
        } else {
            firstField = line.substring(0, tabIndex);
        }

        RequestLine req = parse(firstField);
        if (req.path().startsWith("/")) {
            return req.path();
        } else {
            return "";
        }
    }
}
