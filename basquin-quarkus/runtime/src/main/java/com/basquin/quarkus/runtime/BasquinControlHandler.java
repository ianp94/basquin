package com.basquin.quarkus.runtime;

import agent.ResultStore;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The control surface at {@code /__basquin/*} — the prefix the driver actually calls (DD-043
 * §4.4a). Installed via a {@code RouteBuildItem} as a blocking route (see {@code
 * BasquinProcessor}): {@link #pollResult} can block its worker thread for up to
 * {@value #DEFAULT_RESULT_POLL_TIMEOUT_MS}ms, which must never happen on the event loop.
 *
 * <h2>Share the wire format, not the handler</h2>
 * This is deliberately NOT {@code agent.LoadModeControl.handle(path, query)}. That method
 * references {@code agent.LoadMode} (three times) and {@code agent.RequestBoundary.awaitQuiescence}
 * (once), both of which stay in {@code agent/} — {@code awaitQuiescence} is
 * {@code ITERATION_LOCK.tryLock(...)}, lock-based machinery with no meaning on this lock-free
 * reactive path, and depending on {@code agent/} from this extension would drag the Tomcat/JVMTI
 * surface into a native-targeted artifact. What genuinely IS shared — and is the part that
 * matters, since it is where the two paths could disagree — is the wire format:
 * {@link ResultStore#format} / {@link ResultStore#take}, reused verbatim.
 *
 * <p>{@code PREFIX} and the query-parameter parser mirror {@code agent.LoadModeControl}'s
 * (byte-for-byte, deliberately) but are NOT extracted into {@code basquin-core} by this increment:
 * this PR may only touch {@code basquin-core} to add tests, so the extension carries its own copy
 * of these two trivial, pure functions instead of refactoring a shipped control path.
 *
 * <p>{@code mode} and {@code drift} are out of scope here: both are {@code LoadMode}, the DD-029
 * valve strategy flag, and load mode against native/reactive targets is a DD-043 §2 non-goal
 * (DD-042's business). Any path under {@code PREFIX} other than {@code result}/{@code violations}/
 * {@code control/defect/*}/{@code coverage} — including {@code mode}/{@code drift} — answers
 * {@code err:unknown} and never reaches the app.
 *
 * <h2>Coverage (DD-043 PR-4, spec §6.4)</h2>
 * {@code coverage} answers offline-JaCoCo execution data for a target instrumented at build time
 * by {@code basquin-maven-injector} (DD-043 PR-3) — a binary body, so it is dispatched in {@link
 * #handle(RoutingContext)} ahead of the {@code text/plain} {@link #handle(String, String)} switch,
 * never through it (see {@link #dispatchCoverage()}). Reads via a direct, compile-time-typed
 * {@code org.jacoco.agent.rt.RT.getAgent()} call — NEVER reflection, which the DD-043 spike S1
 * measured failing deterministically under native-image's closed-world analysis — so no {@code
 * @RegisterForReflection}/reflect-config entry is needed; {@code RT}/{@code IAgent} are ordinary
 * public compiled types (spec decision A1: the extension's own {@code implementation} dependency
 * on {@code org.jacoco.agent:...:runtime}, propagating transitively to any target this extension
 * is injected into). When no agent is reachable, this answers a DISTINCT non-2xx, never an
 * empty-but-200 body — an empty 200 would be indistinguishable from "measured and genuinely zero
 * coverage" (DD-040's binding rule). Proven composed, end-to-end, native included, by the seam
 * spike ({@code .superpowers/sdd/dd043-pr4-seam-spike.md}).
 *
 * <h2>Negative-control defect routes (spec §7.3)</h2>
 * DD-040's binding rule is that a reported zero means "checked and clean", never "never measured".
 * §7.3 requires every invariant to ship with a negative control — a deliberately planted defect
 * proving the invariant CAN fire. The controls cannot live in the target application (§1.1 forbids
 * app-source edits, and the Phase-0 fixture is a different app on a different stack cell than the
 * published rows), so they ship here, in the extension's own runtime, under
 * {@code control/defect/{slow,alloc,error5xx,block-loop}}:
 *
 * <ul>
 *   <li>{@code slow} — sleeps well past a latency threshold (the latency invariant's control).</li>
 *   <li>{@code alloc?bytes=N} — allocates and RETAINS ~N bytes (the heap invariant's control).
 *       {@code N} is honoured faithfully, not cosmetically: §6.1 measured {@code
 *       Runtime.freeMemory()} quantizing at 524,288 B under SubstrateVM, so a request has to clear
 *       ~2 quanta (~1 MiB) to read as a number at all. Driving {@code bytes} far above that floor
 *       must produce a real delta; driving it far below must NOT — a plausible-looking number
 *       there would be manufactured, not measured.</li>
 *   <li>{@code error5xx} — returns HTTP 500 (the 5xx/crash control).</li>
 *   <li>{@code block-loop} — blocks the Vert.x event loop itself, not a worker thread (the
 *       event-loop-blocking control). Mounted as its own literal, non-blocking route by {@code
 *       BasquinProcessor} for exactly this reason — see that class.</li>
 * </ul>
 *
 * <p>All four are disabled unless {@value #DEFECT_ROUTES_ENABLED_PROPERTY} is set: a target
 * running with live defect routes reachable is a target lying about its own health, so a request
 * to any {@code control/defect/*} path gets a plain refusal (HTTP 403, not a 404 — a 404 is
 * indistinguishable from a typo) whenever the property is unset. The property is read fresh on
 * every call, deliberately not cached at class-load: it is meant to be flipped for a controlled
 * Phase-2 control run, not baked into a JVM-startup snapshot.
 */
public final class BasquinControlHandler implements Handler<RoutingContext> {

    /** Mirrors {@code agent.LoadModeControl.PREFIX}. Collision-unlikely with a real app route. */
    public static final String PREFIX = "/__basquin/";

    /**
     * The negative-control defect routes (spec §7.3) live under this sub-tree of {@link #PREFIX}.
     * Public so {@code BasquinProcessor} can mount {@code block-loop} as its own literal route
     * (see that class's javadoc for why) without duplicating this string, and so {@code
     * BasquinBoundaryFilter} can distinguish "a defect route — deliberately measured" from "a
     * meta-query about the measurement system itself (`result`/`violations`) — never measured."
     */
    public static final String DEFECT_PREFIX = PREFIX + "control/defect/";

    /**
     * Gates ALL FOUR negative-control defect routes at once (spec §7.3): unset or {@code false}
     * means every {@code control/defect/*} request refuses, regardless of which defect it names.
     * Enable only for a controlled Phase-2 control run, never on a target whose rows get
     * published — these routes plant REAL defects in the same process serving real traffic.
     */
    public static final String DEFECT_ROUTES_ENABLED_PROPERTY = "basquin.quarkus.control.defectsEnabled";

    private static final long DEFAULT_SLOW_MS = 3_000L;
    private static final long MAX_SLOW_MS = 60_000L;

    private static final long DEFAULT_BLOCK_LOOP_MS = 2_000L;
    private static final long MAX_BLOCK_LOOP_MS = 60_000L;

    /** Safety ceiling, not a spec requirement: keeps a malformed/adversarial `bytes` value from
     *  single-handedly OOMing the target. 256 MiB is far above anything §7.3's table asks for
     *  ("well above the quantum" is ~1 MiB; "well below" is a few hundred bytes). */
    private static final long MAX_ALLOC_BYTES = 256L * 1024 * 1024;

    /**
     * Retention for {@code alloc}'s allocations (spec §7.3: "the allocation must be retained
     * until after the boundary measures, or the delta vanishes before it is read"). A bounded
     * deque, not an unbounded one: this control route is meant to be hit a handful of times per
     * Phase-2 run, not on every request, so a small cap is enough to guarantee survival past the
     * boundary's {@code addEndHandler} (which fires within milliseconds of the response
     * completing) while still bounding worst-case retained memory across a long-running target.
     */
    private static final int MAX_RETAINED_ALLOCATIONS = 128;
    private static final Deque<byte[]> RETAINED_ALLOCATIONS = new ConcurrentLinkedDeque<>();

    /**
     * Bounded wait for an in-flight explore iteration's {@code addEndHandler} to publish (spec
     * §4.4/§4.4a). There is no {@code awaitQuiescence} on this path (no lock to wait on), so the
     * poll waits on the store itself instead, in short increments, up to this bound. A timeout
     * returns whatever {@link ResultStore#format} gives for an empty list — {@code "miss"} —
     * never a zero.
     *
     * <p>Overridable via the {@code basquin.quarkus.report.pollTimeoutMs} system property, set
     * before this class loads — mirroring {@code agent.LoadModeControl.QUIESCENCE_WAIT_MS}, which
     * is {@code static final} for the same reason: a value read once at class-load, not re-read
     * per call, so it is retuned at JVM startup, not mid-run.
     */
    static final long DEFAULT_RESULT_POLL_TIMEOUT_MS = 2_000L;
    private static final long RESULT_POLL_TIMEOUT_MS =
            Long.getLong("basquin.quarkus.report.pollTimeoutMs", DEFAULT_RESULT_POLL_TIMEOUT_MS);
    private static final long RESULT_POLL_INTERVAL_MS = 20L;

    @Override
    public void handle(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        if (path != null && path.startsWith(DEFECT_PREFIX)) {
            String defectName = path.substring(DEFECT_PREFIX.length());
            DefectOutcome outcome = dispatchDefect(defectName, ctx.request().query());
            ctx.response()
                    .setStatusCode(outcome.statusCode())
                    .putHeader("Content-Type", "text/plain")
                    .end(outcome.body());
            return;
        }
        if ((PREFIX + "coverage").equals(path)) {
            // Ahead of the text switch, deliberately: the body is binary (offline JaCoCo
            // execution data), never text/plain, so it must never fall into
            // handle(String, String)'s String-returning dispatch below. See dispatchCoverage()'s
            // javadoc for the read contract.
            CoverageOutcome outcome = dispatchCoverage();
            ctx.response()
                    .setStatusCode(outcome.statusCode())
                    .putHeader("Content-Type", outcome.contentType())
                    .end(Buffer.buffer(outcome.body()));
            return;
        }
        String query = ctx.request().query();
        String body = handle(path, query);
        ctx.response().putHeader("Content-Type", "text/plain").end(body);
    }

    /** Package-visible for tests: the pure dispatch logic, isolated from Vert.x wiring. */
    static String handle(String path, String query) {
        if (path == null || !path.startsWith(PREFIX)) {
            return "err:unknown";
        }
        String sub = path.substring(PREFIX.length());
        switch (sub) {
            case "result":
                return pollResult(param(query, "id"));
            case "violations":
                return Long.toString(ResultStore.totalViolations());
            default:
                // Includes mode/drift (out of scope here, see class javadoc), control/defect/*
                // and coverage (both dispatched separately, above — see #handle(RoutingContext))
                // and anything else: never reaches the app.
                return "err:unknown";
        }
    }

    /** The HTTP status + plaintext body a defect route resolves to. A record rather than writing
     *  directly to {@code ctx.response()} so {@link #dispatchDefect} — including the disabled
     *  guard and every per-defect body — is testable without a live {@code RoutingContext}. */
    record DefectOutcome(int statusCode, String body) {}

    /** Package-visible for tests: the pure dispatch logic for {@code control/defect/*}, isolated
     *  from Vert.x wiring, mirroring {@link #handle(String, String)}'s own split. */
    static DefectOutcome dispatchDefect(String defectName, String query) {
        if (!defectRoutesEnabled()) {
            // A plain refusal, not a 404 (spec: "disabled by default ... a plain refusal (not a
            // 404, which is indistinguishable from a typo)"). 403: the route exists and is
            // recognised, it is simply not permitted right now.
            return new DefectOutcome(403, "err:defect-routes-disabled");
        }
        switch (defectName) {
            case "slow":
                return new DefectOutcome(200, slowDefect(query));
            case "alloc":
                return new DefectOutcome(200, allocDefect(query));
            case "error5xx":
                return new DefectOutcome(500, "defect:error5xx");
            case "block-loop":
                return new DefectOutcome(200, blockLoopDefect(query));
            default:
                return new DefectOutcome(200, "err:unknown");
        }
    }

    /** The HTTP status + content-type + body bytes the coverage route resolves to (DD-043 PR-4,
     *  spec §6.4). A record, mirroring {@link DefectOutcome}'s split — writing to a plain data
     *  holder rather than directly to {@code ctx.response()} so {@link #dispatchCoverage()} is
     *  testable without a live {@code RoutingContext} — generalized to a {@code byte[]} body and
     *  an explicit content type, since offline JaCoCo execution data is binary, never text: unlike
     *  {@code DefectOutcome} this cannot hard-code {@code text/plain} for every outcome. */
    record CoverageOutcome(int statusCode, String contentType, byte[] body) {}

    /**
     * Package-visible for tests: the pure dispatch logic for {@code coverage}, isolated from
     * Vert.x wiring, mirroring {@link #dispatchDefect}'s own split.
     *
     * <p>The read is a direct, compile-time-typed call — {@code RT.getAgent()} then {@code
     * agent.getExecutionData(false)} — NEVER reflection, which the DD-043 spike S1 measured
     * failing deterministically under native-image's closed-world analysis. {@code RT} and {@code
     * IAgent} are ordinary public compiled types (spec decision A1: the extension's own {@code
     * implementation} dependency on {@code org.jacoco.agent:...:runtime} — see this module's
     * {@code build.gradle}), so this is plain virtual dispatch, visible to closed-world analysis
     * with no {@code @RegisterForReflection}/reflect-config entry needed — confirmed composed,
     * end-to-end, native included, by the seam spike ({@code
     * .superpowers/sdd/dd043-pr4-seam-spike.md}: the extension's typed call is what keeps the
     * agent AOT-reachable in the image at all). {@code getExecutionData(false)}, never {@code
     * true}: {@code true} resets the session, but the driver's cross-poll union-merge expects
     * cumulative data (spec §6.4).
     *
     * <p>When no agent is reachable — nothing was ever offline-instrumented into this process, so
     * {@code RT.getAgent()} throws — this answers a DISTINCT non-2xx (503), never an
     * empty-but-200 body: an empty 200 would be indistinguishable from "measured and genuinely
     * zero coverage" (DD-040's binding rule), which is exactly the shape an empty-but-200 response
     * would manufacture. Mirrors the seam spike's own control-cell finding verbatim (extension
     * present, nothing instrumented, agent never boots): {@code err:no-jacoco-agent
     * java.lang.IllegalStateException: JaCoCo agent not started.}
     */
    static CoverageOutcome dispatchCoverage() {
        byte[] data;
        try {
            IAgent agent = RT.getAgent();
            data = agent.getExecutionData(false);
        } catch (Throwable t) {
            String body = "err:no-jacoco-agent " + t.getClass().getName() + ": " + t.getMessage();
            return new CoverageOutcome(503, "text/plain", body.getBytes(StandardCharsets.UTF_8));
        }
        return new CoverageOutcome(200, "application/octet-stream", data);
    }

    /**
     * Whether the negative-control defect routes are permitted to actually plant their defects.
     * Read fresh every call (unlike {@link #RESULT_POLL_TIMEOUT_MS}, which is a startup-time
     * tuning knob) — this is a safety gate meant to be flipped on only for the duration of a
     * Phase-2 control run, and a test must be able to flip it without reloading the class.
     */
    static boolean defectRoutesEnabled() {
        return Boolean.getBoolean(DEFECT_ROUTES_ENABLED_PROPERTY);
    }

    /**
     * Plants the latency invariant's negative control: sleeps well past any sane latency
     * threshold before responding. Runs on {@code BasquinControlHandler}'s worker thread (this
     * whole surface is a blocking route — see {@code BasquinProcessor}), so the sleep costs
     * nothing but this one request's own latency, which is the entire point.
     *
     * <p>Optional {@code ms} query param (default {@value #DEFAULT_SLOW_MS}, capped at
     * {@value #MAX_SLOW_MS}) so a control run can size the defect to comfortably clear whatever
     * {@code basquin.invariant.latency.maxMs} threshold that run configured.
     */
    static String slowDefect(String query) {
        long ms = clamp(longParam(query, "ms", DEFAULT_SLOW_MS), 0, MAX_SLOW_MS);
        sleepQuietly(ms);
        return "defect:slow ms=" + ms;
    }

    /**
     * Plants the heap invariant's negative control: allocates and RETAINS ~{@code bytes} bytes.
     * {@code bytes} is REQUIRED and honoured faithfully — this route exists specifically to be
     * driven both far above §6.1's 524,288 B quantum (so the heap invariant fires) and
     * deliberately below it (so the sample must come back unmeasurable, never a manufactured
     * number). Missing or malformed {@code bytes} is refused rather than defaulted, since a
     * silent default would make the size no longer mean what the caller asked for.
     *
     * <p>The allocation is handed to {@link #retain} so it survives past this method's return —
     * {@code addEndHandler} (where {@code BasquinBoundaryFilter} takes its post-response heap
     * reading) fires only after the response is fully written, and an allocation with no
     * remaining reference by then is eligible for collection before that read happens, which
     * would make the delta vanish.
     */
    static String allocDefect(String query) {
        Long bytes = parseLong(param(query, "bytes"));
        if (bytes == null || bytes < 0 || bytes > MAX_ALLOC_BYTES) {
            return "err:bytes must be an integer in [0," + MAX_ALLOC_BYTES + "]";
        }
        byte[] block = new byte[bytes.intValue()];
        if (block.length > 0) {
            // Touch first and last byte: a pure zero-fill array is exactly what `new byte[n]`
            // already guarantees, but touching it defends against any future JIT/escape-analysis
            // change treating an untouched, never-read array as eligible for elimination.
            block[0] = 1;
            block[block.length - 1] = 1;
        }
        retain(block);
        return "defect:alloc bytes=" + bytes;
    }

    /** Appends to the retained-allocations deque, evicting the oldest once over the cap (see
     *  {@link #MAX_RETAINED_ALLOCATIONS}'s javadoc for why a bound, not an unbounded list, is
     *  the correct amount of retention here). */
    private static void retain(byte[] block) {
        RETAINED_ALLOCATIONS.addLast(block);
        while (RETAINED_ALLOCATIONS.size() > MAX_RETAINED_ALLOCATIONS) {
            RETAINED_ALLOCATIONS.pollFirst();
        }
    }

    /** Test-only: how many allocations {@code alloc} is currently retaining. */
    static int retainedAllocationCountForTest() {
        return RETAINED_ALLOCATIONS.size();
    }

    /** Test-only: drop all retained allocations between tests, mirroring {@code
     *  ResultStore#clearForTest}. */
    static void clearRetainedForTest() {
        RETAINED_ALLOCATIONS.clear();
    }

    /**
     * Plants the event-loop-blocking negative control: blocks whatever thread actually runs this
     * method. That thread is guaranteed to be a genuine Vert.x event-loop thread, NOT a worker
     * thread, because {@code BasquinProcessor} mounts {@code control/defect/block-loop} as its
     * own literal, non-blocking ({@code HandlerType.NORMAL}) route — see that class's javadoc.
     * This method itself has no Vert.x dependency and does not need one: "which thread this runs
     * on" is a routing decision made at build time, not something this method arranges.
     *
     * <p>The thread name is echoed in both the response body and an app-log line so a control run
     * can confirm, without guessing, that the defect actually landed on an event-loop thread
     * (Vert.x names those distinctly from worker-pool threads) rather than merely taking a long
     * time on some other thread.
     *
     * <p>No detector exists yet for this invariant (§6.3's watchdog is a later PR) — this route
     * plants the defect; nothing here claims it is caught.
     */
    static String blockLoopDefect(String query) {
        long ms = clamp(longParam(query, "ms", DEFAULT_BLOCK_LOOP_MS), 0, MAX_BLOCK_LOOP_MS);
        String threadName = Thread.currentThread().getName();
        System.out.println("[Basquin][defect] block-loop blocking thread=" + threadName + " for ms=" + ms);
        sleepQuietly(ms);
        System.out.println("[Basquin][defect] block-loop released thread=" + threadName);
        return "defect:block-loop ms=" + ms + " thread=" + threadName;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long longParam(String query, String key, long defaultValue) {
        Long v = parseLong(param(query, key));
        return v == null ? defaultValue : v;
    }

    private static Long parseLong(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pollResult(String id) {
        if (id == null) {
            return ResultStore.format(Collections.emptyList());
        }
        long deadlineNanos = System.nanoTime() + RESULT_POLL_TIMEOUT_MS * 1_000_000L;
        List<ResultStore.Entry> hops = ResultStore.take(id);
        while (hops.isEmpty() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(RESULT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            hops = ResultStore.take(id);
        }
        // A timeout falls straight through to format(empty) = "miss" — never a zero.
        return ResultStore.format(hops);
    }

    /** Minimal query-param read; mirrors {@code agent.LoadModeControl.param} (see class javadoc). */
    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }
}
