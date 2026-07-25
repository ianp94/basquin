package com.basquin.quarkus.deployment;

import com.basquin.quarkus.runtime.BasquinBoundaryFilter;
import com.basquin.quarkus.runtime.BasquinControlHandler;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.FilterBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.HandlerType;

/**
 * DD-043 PR-2: the build steps that install {@code basquin-quarkus}'s request boundary and
 * control surface into the augmented application. Deployment-only — never on a running app's
 * classpath; Quarkus's bootstrap resolver pulls this module in only during augmentation, via the
 * {@code deployment-artifact} coordinate {@code ../runtime}'s
 * {@code META-INF/quarkus-extension.properties} records.
 */
public class BasquinProcessor {

    /**
     * Arbitrary but must be {@code >= 0} ({@code FilterBuildItem}'s own validation rejects a
     * negative priority). This filter has no ordering dependency on any other filter in the
     * fixtures this PR validates against.
     */
    private static final int FILTER_PRIORITY = 100;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("basquin");
    }

    /**
     * Installs {@link BasquinBoundaryFilter} as a router-wide filter (spec §4.3): it sees every
     * request the router dispatches, not just JAX-RS-mapped ones, and gets access to
     * {@code RoutingContext}'s end hooks. {@code FilterBuildItem}, not {@code RouteBuildItem} —
     * the latter registers a route at a specific path, the former wraps the whole router.
     */
    @BuildStep
    FilterBuildItem boundaryFilter() {
        return new FilterBuildItem(new BasquinBoundaryFilter(), FILTER_PRIORITY);
    }

    /**
     * Mounts {@link BasquinControlHandler} at {@code /__basquin/*} — the control surface the
     * driver actually calls (spec §4.4a). A blocking route: {@code BasquinControlHandler}'s
     * result poll can block its worker thread for up to 2s (spec §4.4), which must never happen
     * on the event loop. {@code control/defect/slow} and {@code control/defect/alloc} (spec
     * §7.3) rely on this too — their whole point is to cost latency/memory on THIS request only,
     * never to stall the event loop other requests share.
     *
     * <p>{@code control/defect/block-loop} is deliberately NOT served by this route — see {@link
     * #blockLoopRoute()}.
     */
    @BuildStep
    RouteBuildItem controlRoute() {
        return RouteBuildItem.builder()
                .route(BasquinControlHandler.PREFIX + "*")
                .handler(new BasquinControlHandler())
                .blockingRoute()
                .build();
    }

    /**
     * Order for {@link #blockLoopRoute()}, chosen to win the match against {@link
     * #controlRoute()}'s {@code /__basquin/*} wildcard, while still running AFTER {@link
     * #boundaryFilter()} — both facts verified against the fixture, not assumed (see below).
     *
     * <p>Vert.x-Web matches candidate routes by explicit {@code Route.order()} (lower value =
     * tried first), never by path specificity, and a plain {@code .route(path)} without an
     * explicit order gets Vert.x's own small non-negative auto-increment default. A first attempt
     * at this value ({@code -10_000}) was WRONG in a way end-to-end evidence caught: {@code
     * FilterBuildItem}s are themselves installed as router-wide routes at {@code order = -priority}
     * (confirmed by decompiling {@code VertxHttpRecorder.finalizeRouter}), so {@link
     * #boundaryFilter()}'s priority 100 lands at order {@code -100}. {@code -10_000} is MORE
     * negative than {@code -100}, so that first attempt matched and terminated the request BEFORE
     * the boundary filter ever ran — confirmed by the fixture's own app log showing block-loop's
     * own diagnostic line but neither the boundary filter's nor the fixture's unrelated spike-era
     * probe filter's, for that one route, while every other control route showed both. A run with
     * the property enabled then polled {@code /__basquin/result} for that id and got {@code
     * "miss"}: no {@code addEndHandler} was ever registered, so nothing was ever published — the
     * exact silent-zero failure mode DD-040 exists to prevent, self-inflicted by an order value
     * one order of magnitude too aggressive.
     *
     * <p>{@code -1} is comfortably inside the open interval {@code (-100, 0)}: greater than any
     * {@code FilterBuildItem} at this or a lower priority (so filters still see the request first),
     * and less than the wildcard's non-negative default (so this literal route still wins that
     * match).
     */
    private static final int BLOCK_LOOP_ROUTE_ORDER = -1;

    /**
     * Mounts {@code control/defect/block-loop} (spec §7.3's event-loop-blocking negative control)
     * as its OWN literal, non-blocking route — deliberately NOT folded into {@link
     * #controlRoute()}'s blocking wildcard above.
     *
     * <p>The reason is the defect itself. {@code controlRoute()} is {@code .blockingRoute()} —
     * Vert.x-Web dispatches a blocking route's handler on a WORKER-pool thread precisely so a
     * slow control (like {@code control/defect/slow}) costs only that request, never the event
     * loop. That is exactly backwards for {@code block-loop}: the invariant under test is
     * "blocks the Vert.x event loop", so the handler MUST run on a genuine event-loop thread, or
     * the route would silently plant a different, much weaker defect (a blocked worker thread)
     * while claiming to demonstrate the real one. {@code HandlerType.NORMAL} is Vert.x-Web's
     * ordinary, non-blocking dispatch — the request is handled directly on the event-loop thread
     * that received it, with no hop to the worker pool.
     *
     * <p>Same {@link BasquinControlHandler} instance/class as {@code controlRoute()} — this is a
     * second ROUTE registration (a build-time wiring necessity, since Vert.x-Web has no
     * "blocking for these sub-paths, non-blocking for that one" mode on a single route), not a
     * second control-handler implementation.
     */
    @BuildStep
    RouteBuildItem blockLoopRoute() {
        return RouteBuildItem.builder()
                .orderedRoute(BasquinControlHandler.DEFECT_PREFIX + "block-loop", BLOCK_LOOP_ROUTE_ORDER)
                .handler(new BasquinControlHandler())
                .handlerType(HandlerType.NORMAL)
                .build();
    }
}
