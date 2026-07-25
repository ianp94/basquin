package com.basquin.quarkus.deployment;

import com.basquin.quarkus.runtime.BasquinBoundaryFilter;
import com.basquin.quarkus.runtime.BasquinControlHandler;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.FilterBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;

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
     * on the event loop.
     */
    @BuildStep
    RouteBuildItem controlRoute() {
        return RouteBuildItem.builder()
                .route(BasquinControlHandler.PREFIX + "*")
                .handler(new BasquinControlHandler())
                .blockingRoute()
                .build();
    }
}
