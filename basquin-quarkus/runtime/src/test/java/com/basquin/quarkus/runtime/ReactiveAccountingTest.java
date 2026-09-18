package com.basquin.quarkus.runtime;

import agent.ResultStore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ReactiveAccountingTest {
    @After public void cleanup() {
        ResultStore.clearForTest();
        for (String key : new String[]{"latency.maxMs", "heapDelta.maxKb", "threadDelta.max", "mode"})
            System.clearProperty("basquin.invariant." + key);
    }

    @Test public void overlapSurvivesPeerCompletionAndFutureWindowsRecover() {
        ReactiveWindows windows = new ReactiveWindows();
        var first = windows.begin();
        var peer = windows.begin();
        assertTrue(windows.end(peer));
        assertTrue(windows.end(first));
        assertEquals(0, windows.active());
        assertFalse(windows.end(windows.begin()));
    }

    @Test public void allFourHeapExclusionsAndUnknownGcAreConservative() {
        assertTrue(BasquinBoundaryFilter.heapAvailable(1_048_576, false, 7, 7));
        assertFalse(BasquinBoundaryFilter.heapAvailable(2_000_000, true, 7, 7));
        assertFalse(BasquinBoundaryFilter.heapAvailable(1_048_575, false, 7, 7));
        assertFalse(BasquinBoundaryFilter.heapAvailable(-1, false, 7, 7));
        assertFalse(BasquinBoundaryFilter.heapAvailable(2_000_000, false, 7, 8));
        assertFalse(BasquinBoundaryFilter.heapAvailable(2_000_000, false, -1, -1));
    }

    @Test public void unavailableHeapAndThreadSignalsCannotFireButLatencyCan() {
        System.setProperty("basquin.invariant.mode", "soft");
        System.setProperty("basquin.invariant.latency.maxMs", "1");
        System.setProperty("basquin.invariant.heapDelta.maxKb", "-1");
        System.setProperty("basquin.invariant.threadDelta.max", "-1");
        BasquinBoundaryFilter.publish("masked", 20, 9_000_000, 100, 99, false);
        var entry = ResultStore.take("masked").get(0);
        assertEquals("UNMEASURED", entry.disposition());
        assertEquals("20,,0", entry.costCsv());
        assertEquals(1, entry.invariantCount());
        assertTrue(entry.detail().startsWith("latency:"));
    }

    @Test public void untaggedOverlapAndDisconnectRunTheRealEndHandlers() {
        Request driver = new Request("driver");
        Request background = new Request(null);
        new BasquinBoundaryFilter().handle(driver.context);
        new BasquinBoundaryFilter().handle(background.context);
        background.finish(false);
        // Force a large deterministic delta; overlap must exclude it regardless.
        driver.data.put("basquin.baselineHeapBytes", -100_000_000L);
        driver.finish(true);
        assertEquals("UNMEASURED", ResultStore.take("driver").get(0).disposition());

        Request disconnected = new Request("disconnect");
        new BasquinBoundaryFilter().handle(disconnected.context);
        disconnected.finish(false);
        var entry = ResultStore.take("disconnect").get(0);
        assertEquals("disconnected", entry.disposition());
        assertNull(entry.costCsv());
        assertEquals(0, entry.invariantCount());

        Request clean = new Request("after");
        new BasquinBoundaryFilter().handle(clean.context);
        clean.data.put("basquin.baselineHeapBytes", -100_000_000L);
        clean.data.put("basquin.gcCount", BasquinBoundaryFilter.collectionCount());
        clean.finish(true);
        assertEquals("measured", ResultStore.take("after").get(0).disposition());
    }

    private static final class Request {
        final Map<String,Object> data = new HashMap<>();
        final RoutingContext context;
        Handler<AsyncResult<Void>> end;
        @SuppressWarnings("unchecked")
        Request(String id) {
            HttpServerRequest request = (HttpServerRequest) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{HttpServerRequest.class},
                    (p,m,a) -> m.getName().equals("getHeader") ? id : null);
            context = (RoutingContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RoutingContext.class}, (p,m,a) -> {
                        switch (m.getName()) {
                            case "normalizedPath": return "/app";
                            case "request": return request;
                            case "put": data.put((String)a[0],a[1]); return p;
                            case "get": return data.get(a[0]);
                            case "addEndHandler": end=(Handler<AsyncResult<Void>>)a[0]; return 1;
                            default: return null;
                        }
                    });
        }
        void finish(boolean success) {
            end.handle(success ? Future.succeededFuture() : Future.failedFuture("disconnect"));
        }
    }
}
