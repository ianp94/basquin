package agent;

/**
 * Pure control-request logic for the valve's {@code /__basquin/*} interception (DD-029). Kept out of
 * the valve (which owns only the thin Catalina glue) so it's unit-testable without a Tomcat.
 *
 * <p>The driver reaches these over the app's own HTTP port — the valve already intercepts every
 * request — so no separate control listener, agent HTTP server, or operator wiring is needed. The
 * surface is unauthenticated on the app port (in-cluster trust, same model as the JaCoCo port,
 * DD-022); hardening is a follow-up.
 */
public final class LoadModeControl {

    /** Distinguished prefix; collision-unlikely with a real app route. */
    public static final String PREFIX = "/__basquin/";
    private static final long DEFAULT_TTL_MS = 60_000L;

    private LoadModeControl() {}

    /**
     * If {@code path} is a control request, apply its side effect and return the response body; else
     * return {@code null} (the valve then handles it as normal app traffic). Any {@code /__basquin/*}
     * path is handled (never returned as null), so a stray control-looking path can't reach the app.
     */
    public static String handle(String path, String query) {
        if (path == null || !path.startsWith(PREFIX)) {
            return null;
        }
        String sub = path.substring(PREFIX.length());
        switch (sub) {
            case "mode": {
                String to = param(query, "to");
                if ("load".equals(to)) { LoadMode.setLoad(ttl(query)); return "ok:load"; }
                if ("explore".equals(to)) { LoadMode.setExplore(); return "ok:explore"; }
                return "err:bad-to";
            }
            case "drift":
                return LoadMode.driftSnapshotCsv();
            case "result": {
                String id = param(query, "id");
                // Bounded wait on ITERATION_LOCK: Agent.end() sleeps 25ms BEFORE measuring, so on a
                // committed response the client reaches EOF while the entry is still ~25ms away.
                // Waiting here queues the poll behind the in-flight iteration instead of racing it.
                // A timeout (not an indefinite block) so a target wedged inside the app misses
                // rather than hanging the driver.
                RequestBoundary.awaitQuiescence(2000);
                return ResultStore.format(ResultStore.take(id));
            }
            case "violations":
                return Long.toString(ResultStore.totalViolations());
            default:
                return "err:unknown";
        }
    }

    private static long ttl(String query) {
        String t = param(query, "ttlMs");
        if (t == null) return DEFAULT_TTL_MS;
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return DEFAULT_TTL_MS; }
    }

    /** Minimal query-param read; no decoding needed (values are numeric/keyword). */
    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }
}
