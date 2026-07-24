package com.basquin.spike;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

public class BoundaryProbe {
    public void register(@Observes Filters filters) {
        filters.register(rc -> {
            long start = System.nanoTime();
            String id = "probe-" + System.nanoTime();
            rc.put("basquin.id", id);
            rc.addHeadersEndHandler(v ->
                rc.response().putHeader("X-Basquin-Req", id));
            rc.addEndHandler(ar -> {
                long ms = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("[PROBE] id=%s status=%d succeeded=%b cause=%s ms=%d%n",
                    id, rc.response().getStatusCode(), ar.succeeded(),
                    ar.succeeded() ? "-" : String.valueOf(ar.cause()), ms);
            });
            rc.next();
        }, 100);
    }
}
