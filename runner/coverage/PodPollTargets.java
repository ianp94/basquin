package runner.coverage;

import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DD-040 §A.6: where a {@code /__basquin/result} poll must be sent so that it reaches the pod that
 * actually served the request.
 *
 * <p><b>Why a Service-VIP poll is wrong, not merely suboptimal.</b> The result store is
 * <em>per-JVM</em>: the boundary writes {@code id -> measurements} into the heap of the one pod that
 * handled the request. The driver's {@code baseURL} is a Kubernetes Service DNS name, which resolves
 * to a single virtual IP that load-balances each new connection across all endpoints. A poll through
 * the VIP therefore lands on a uniformly random replica and reaches the pod holding the entry with
 * probability 1/N. Those polls do not return stale or partial data — they return {@code miss}, and a
 * miss is (correctly, per §A.9) recorded as <em>unmeasured</em>. So a target that scales from 1 to 3
 * replicas silently stops reporting ~2/3 of its measurements while every component still calls
 * itself healthy. The channel would pass every existing (single-replica) test and be systematically
 * blind in production. That is a correctness defect, not a performance one.
 *
 * <p><b>What this class does.</b> It turns one base URL into the ordered list of base URLs a poll
 * should try. When the driver can see that the target is more than one instance it addresses the
 * pods directly — bypassing the VIP entirely — and the caller tries each until one returns the
 * entry.
 *
 * <p><b>The fan-out's safety, and how the redirect chain changes it.</b> For a request the driver
 * sends and the target answers <em>without a redirect</em> ({@code hops <= 1}), the run-salted id
 * plus remove-on-read means at most one pod can hold that id, so break-at-the-first-answer is exact:
 * a fan-out can be wrong only by finding nothing — a recorded miss, which is honest. That single-hop
 * path is unchanged from DD-040 and is pinned by
 * {@code MultiReplicaPollTest.theFanOutFindsTheEntryBehindAPodThatMisses}, where pod A misses and
 * pod B holds the entry.
 *
 * <p>A followed redirect breaks the "at most one pod" premise. DD-040 predicted this as a residual
 * and mis-stated its own fix: DD-039 does <b>not</b> stamp each hop with its own id (a chain of N
 * hops under N ids would need N polls, each an extra round trip inflating {@code latMs} — a
 * cost-model input — and {@code ResultStore.put} would still replace by key). What ships is <b>one
 * id</b> per request, re-sent on every hop, and a store that <b>accumulates</b> that id's hops
 * (spec §4b). The Service VIP may route hop 2 to a different replica, so hop 0's entry can sit on
 * pod A and hop 1's on pod B under the same id. The driver therefore knows its own hop count, and
 * for {@code hops > 1} the caller asks <b>every</b> base and <b>merges</b> — {@link CoverageGuidedRun}'s
 * {@code pollResultOrNull} sums the counts and heap/thread deltas across all pods' entries rather
 * than returning whichever pod answers first. That closes the residual DD-040 recorded here (a
 * same-method hop's clean measurement returned for work that violated on another hop): no hop's
 * measurement is dropped, because all of them are collected. Break-at-first is retained only for
 * {@code hops <= 1}, where the original one-pod assumption still holds.
 *
 * <p><b>Why fan-out rather than following {@code X-Basquin-Pod}.</b> The boundary does stamp its pod
 * identity on every explore exit, but that header rides the same response headers as
 * {@code X-Basquin-Cost} — so it is present exactly when the driver already has its measurements and
 * does <em>not</em> poll, and absent exactly when the response committed and the poll is needed. The
 * header is a verification and attribution signal (§A.6: "it cannot be the only mechanism"), never an
 * address the poll path can rely on. Resolution has to come from somewhere the driver can read
 * without the target's cooperation: DNS.
 *
 * <p><b>Where the pod addresses come from.</b> {@code InetAddress.getAllByName} on a headless Service
 * name returns every pod IP — the same mechanism {@link JacocoCoverageProvider} already uses to
 * union-merge coverage across replicas (DD-023), reused rather than reinvented. The source is, in
 * order of preference:
 *
 * <ol>
 *   <li>{@code -Dbasquin.report.podHost} — an explicit comma-separated list of {@code host[:port]}
 *       entries (each host is still DNS-expanded, so one headless Service name is the normal value).
 *       {@code off} disables pod addressing entirely.</li>
 *   <li>the host of {@code -Dbasquin.coverage.jacoco}, when set — in an operator campaign this is the
 *       target's headless coverage Service, which by construction selects exactly the target's
 *       pods.</li>
 *   <li>the base URL's own host — covers a campaign pointed straight at a headless Service.</li>
 * </ol>
 *
 * <p><b>The single-instance case is left completely alone.</b> When the source was merely derived
 * (2 or 3 above) and resolves to fewer than two addresses, there is one pod that can hold the entry
 * and the VIP necessarily routes to it, so the base URL is returned untouched — byte-identical to the
 * pre-§A.6 behaviour, with none of the port-mapping risk a host rewrite would carry. Rewriting buys
 * nothing when N == 1, so it is not done. An <em>explicitly</em> configured source is always honoured,
 * N == 1 included: that is an operator instruction, not an inference.
 */
public final class PodPollTargets {

    private PodPollTargets() { }

    /** Resolution cache TTL. Pods come and go; a per-request DNS lookup would be silly, and a
     *  permanently cached answer would keep polling a deleted pod's IP for the rest of the run. */
    private static final long TTL_MS = Long.getLong("basquin.report.podTtlMs", 10_000L);

    private static volatile String cacheKey;
    private static volatile List<String> cacheValue;
    private static volatile long cacheAt;

    /** Drop the memoized resolution. Tests change the properties this class reads between cases. */
    static void resetForTest() {
        cacheKey = null;
        cacheValue = null;
        cacheAt = 0L;
    }

    /**
     * The base URLs a result poll should try, in order.
     *
     * <ul>
     *   <li><b>One element ({@code base} itself)</b> — the target is a single instance, or the driver
     *       has no pod source to resolve. Today's behaviour exactly.</li>
     *   <li><b>N elements</b> — the pod source resolved to N distinct pod addresses; each is the base
     *       URL with its authority replaced by one pod address. The caller polls them until one
     *       hits.</li>
     *   <li><b>Empty</b> — pod addressing was explicitly requested via {@code basquin.report.podHost}
     *       and could not be resolved. The caller must record a miss (§A.6): "cannot tell which pod"
     *       is reported as unmeasured, never guessed at through the VIP.</li>
     * </ul>
     */
    public static List<String> pollBases(String base) {
        String configured = System.getProperty("basquin.report.podHost", "").trim();
        if ("off".equalsIgnoreCase(configured)) return Collections.singletonList(base);
        boolean explicit = !configured.isEmpty();
        String source = explicit ? configured : derivedSource(base);
        if (source == null || source.isEmpty()) return Collections.singletonList(base);

        String key = source + "|" + base + "|" + System.getProperty("basquin.report.podPort", "");
        List<String> cached = cacheValue;
        if (cached != null && key.equals(cacheKey) && System.currentTimeMillis() - cacheAt < TTL_MS) {
            return cached;
        }
        List<String> result = compute(base, source, explicit);
        cacheKey = key;
        cacheAt = System.currentTimeMillis();
        cacheValue = result;
        return result;
    }

    private static List<String> compute(String base, String source, boolean explicit) {
        List<String> candidates = new ArrayList<>();
        for (String entry : source.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String host = e;
            int port = -1;
            int colon = e.lastIndexOf(':');
            if (colon > 0 && e.indexOf(':') == colon) {          // host:port, never an IPv6 literal
                try {
                    port = Integer.parseInt(e.substring(colon + 1).trim());
                    host = e.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    port = -1;                                    // not a port; treat the whole as a host
                }
            }
            for (String addr : resolve(host)) {
                String u = withAddress(base, addr, port);
                if (u != null && !candidates.contains(u)) candidates.add(u);
            }
        }
        // Explicitly asked for pod addressing and DNS cannot say where the pods are: the honest
        // answer is "unmeasured". Falling back to the VIP here would reintroduce exactly the 1-in-N
        // lottery this class exists to remove, and hide it behind a poll that looks like it worked.
        if (candidates.isEmpty()) {
            return explicit ? Collections.<String>emptyList() : Collections.singletonList(base);
        }
        if (!explicit && candidates.size() < 2) return Collections.singletonList(base);
        return Collections.unmodifiableList(candidates);
    }

    /** The pod source when none was configured: the coverage endpoint's host, else the base's host. */
    private static String derivedSource(String base) {
        String jacoco = System.getProperty("basquin.coverage.jacoco");
        if (jacoco != null && !jacoco.trim().isEmpty()) {
            try {
                // The HOST only: 6300 is the JaCoCo tcpserver port, not the app's HTTP port.
                return JacocoCoverageProvider.parseEndpoints(jacoco).get(0).host;
            } catch (RuntimeException ignored) {
                // malformed coverage spec: fall through to the base URL's host
            }
        }
        try {
            return new URL(base).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * All distinct addresses behind {@code host}, with the loopback set collapsed to one entry for
     * the same reason {@link JacocoCoverageProvider} collapses it: "localhost" resolves to BOTH
     * 127.0.0.1 and ::1 on a dual-stack host, and treating that as two pods would make every
     * single-target run fan out to a phantom replica.
     */
    private static List<String> resolve(String host) {
        InetAddress[] found;
        try {
            found = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(found.length);
        boolean loopbackKept = false;
        for (InetAddress a : found) {
            if (a.isLoopbackAddress()) {
                if (loopbackKept) continue;
                loopbackKept = true;
            }
            String ip = a.getHostAddress();
            if (!out.contains(ip)) out.add(ip);
        }
        return out;
    }

    /**
     * {@code base} with its authority replaced by {@code addr}, keeping scheme and context path. The
     * port is the entry's own if it carried one, else {@code -Dbasquin.report.podPort}, else the base
     * URL's (for the rare Service whose published port differs from the container's).
     *
     * <p>Rewriting the host changes the {@code Host} header, which is safe here and only here:
     * {@code /__basquin/*} is answered by the boundary before the app ever sees the request, so no
     * host-based routing or link generation is involved.
     */
    static String withAddress(String base, String addr, int entryPort) {
        try {
            URL u = new URL(base);
            int port = entryPort > 0 ? entryPort
                    : Integer.getInteger("basquin.report.podPort",
                            u.getPort() != -1 ? u.getPort() : u.getDefaultPort());
            String host = addr.indexOf(':') >= 0 ? "[" + addr + "]" : addr;   // IPv6 literal
            String path = u.getPath() == null ? "" : u.getPath();
            return u.getProtocol() + "://" + host + (port > 0 ? ":" + port : "") + path;
        } catch (Exception e) {
            return null;
        }
    }
}
