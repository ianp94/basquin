package runner.util;

/**
 * A one-shot smoke check for the dashboard's optional Claude-analysis path (DD-015). It exercises
 * the <em>real</em> {@link ClaudeAnalyzer#analyze} — the same auth header, model id, {@code
 * thinking:disabled} body, and {@link JsonScan} response parsing the dashboard uses — so a green
 * run proves the live round-trip works end to end, not just that the code compiles.
 *
 * <p>Run it with the operator's key in the environment; the key is read by {@link ClaudeAnalyzer}
 * exactly as it is in production and is never printed:
 *
 * <pre>ANTHROPIC_API_KEY=sk-... ./gradlew verifyClaude -q</pre>
 *
 * Exit code is 0 on a successful, non-truncated reply and 1 otherwise, so it is usable as a gate.
 */
public final class ClaudeSmokeCheck {

    private ClaudeSmokeCheck() {}

    public static void main(String[] args) {
        if (!ClaudeAnalyzer.isConfigured()) {
            System.err.println("[claude-check] no key: set ANTHROPIC_API_KEY (or "
                    + "-Dclosurejvm.claude.apiKey). Nothing sent.");
            System.exit(1);
        }
        String model = System.getProperty("closurejvm.claude.model", "claude-sonnet-5");
        System.out.println("[claude-check] model=" + model + " -> sending a minimal prompt…");
        try {
            // Deliberately tiny and deterministic: proves auth + model + body + parse without
            // spending meaningful tokens. Not asking for exact-match text — models legitimately add
            // punctuation — just that a non-empty, non-error reply comes back and parses.
            String reply = ClaudeAnalyzer.analyze(
                    "Reply with a single word confirming you received this. No preamble.");
            if (reply == null || reply.isBlank()) {
                System.err.println("[claude-check] FAIL: empty reply parsed from the response.");
                System.exit(1);
            }
            if (reply.contains("[truncated: hit max_tokens")) {
                // Shouldn't happen for a one-word ask; if it does, the ceiling wiring is wrong.
                System.err.println("[claude-check] FAIL: reply was truncated at max_tokens on a "
                        + "one-word prompt — check thinking:disabled / maxTokens wiring.");
                System.err.println("[claude-check] reply: " + reply);
                System.exit(1);
            }
            System.out.println("[claude-check] OK — live round-trip succeeded.");
            System.out.println("[claude-check] reply: " + reply.strip());
        } catch (Exception e) {
            // ClaudeAnalyzer already truncates the API error body into the message.
            System.err.println("[claude-check] FAIL: " + e.getMessage());
            System.exit(1);
        }
    }
}
