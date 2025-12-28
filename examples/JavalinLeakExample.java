package examples;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Javalin-based demo target that exposes a "/leak" endpoint.
 * The handler intentionally leaks a small fixed thread pool per request.
 *
 * Note: This example uses reflection to avoid a hard compile-time dependency
 * on Javalin. To run it, ensure Javalin is on the runtime classpath.
 */
public class JavalinLeakExample {

    private static volatile boolean started = false;
    private static volatile Object javalin; // io.javalin.Javalin instance
    private static volatile int port;

    public static void ensureServerStarted() {
        if (started) return;
        synchronized (JavalinLeakExample.class) {
            if (started) return;
            port = Integer.getInteger("examples.javalin.port", 7071);
            try {
                Class<?> javalinClass = Class.forName("io.javalin.Javalin");
                // Javalin.create(Consumer<JavalinConfig>)
                Method create = javalinClass.getMethod("create", java.util.function.Consumer.class);
                Object consumer = (java.util.function.Consumer<?>) (cfg) -> { /* default config */ };
                javalin = create.invoke(null, consumer);

                // Register GET "/leak" with a Handler proxy
                Class<?> handlerClass = Class.forName("io.javalin.http.Handler");
                Object handler = Proxy.newProxyInstance(
                        handlerClass.getClassLoader(),
                        new Class[]{handlerClass},
                        new LeakHandlerInvocation());
                Method get = javalinClass.getMethod("get", String.class, handlerClass);
                get.invoke(javalin, "/leak", handler);

                // Start server
                Method start = javalinClass.getMethod("start", int.class);
                start.invoke(javalin, port);

                started = true;
                System.out.println("JavalinLeakExample started on port " + port);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Javalin classes not found. Add Javalin to the runtime classpath.", e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to start Javalin demo server via reflection", e);
            }
        }
    }

    public static void callLeakEndpoint() {
        Objects.requireNonNull(javalin, "Javalin server not started");
        String url = "http://localhost:" + port + "/leak";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* drain */ }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Javalin /leak endpoint at " + url, e);
        }
    }

    private static class LeakHandlerInvocation implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Expecting Handler.handle(Context ctx)
            if ("handle".equals(method.getName()) && args != null && args.length == 1 && args[0] != null) {
                // Intentionally leak a small executor per request
                ExecutorService ex = agent.Agent.trackExecutor(Executors.newFixedThreadPool(2));
                ex.submit(() -> sleepQuiet(200));
                ex.submit(() -> sleepQuiet(200));

                // ctx.result("leaked") via reflection
                try {
                    Method result = args[0].getClass().getMethod("result", String.class);
                    result.invoke(args[0], "leaked");
                } catch (NoSuchMethodException ignored) {
                    // Best-effort; older/newer Javalin versions
                }
                return null;
            }
            return null;
        }

        private void sleepQuiet(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
}

