package runner;

import agent.Agent;
import runner.api.IterationTarget;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic runner that loads a target class implementing runner.api.IterationTarget
 * and executes it across N iterations within begin/end iteration boundaries.
 *
 * Usage:
 *   java -cp <cp> runner.GenericRunner [iterations] <targetClass>
 * or set -Dclosurejvm.target=<targetClass> and pass only [iterations].
 *
 * v0.2: Optional classloader reset fallback (disabled by default).
 *   -Dclosurejvm.reset=classloader
 *   -Dclosurejvm.reset.onFailure=true
 */
public class GenericRunner {

    private static final int DEFAULT_ITERATIONS = 1000;

    public static void main(String[] args) {
        System.out.println("Starting ClosureJVM GenericRunner");
        // Start the triage consumer and status threads before any iteration baseline is
        // captured, so they never appear as a mid-iteration thread delta.
        runner.util.TriageSink.ensureStarted();
        runner.util.StatusReporter.ensureStarted();
        runner.util.StatusServer.startIfEnabled();

        int iterations = parseIterations(args);
        String targetClass = parseTargetClass(args);
        if (targetClass == null || targetClass.isEmpty()) {
            throw new IllegalArgumentException("Target class not specified. Pass as arg or -Dclosurejvm.target");
        }

        boolean resetViaClassloader = "classloader".equalsIgnoreCase(System.getProperty("closurejvm.reset"));
        boolean resetOnFailure = Boolean.getBoolean("closurejvm.reset.onFailure");
        int resets = 0;
        int maxResets = Integer.getInteger("closurejvm.reset.maxResets", 3);

        System.out.println("Running " + iterations + " iterations with target: " + targetClass +
                (resetViaClassloader ? " [reset=classloader, onFailure=" + resetOnFailure + "]" : ""));

        TargetHandle handle = createTargetHandle(targetClass, resetViaClassloader);

        try {
            handle.target.initialize();
            for (int i = 0; i < iterations; i++) {
                if (!runner.util.StatusReporter.isEnabled()) {
                    System.out.println("Iteration " + (i + 1));
                }
                boolean failed = false;
                boolean endAttempted = false;
                Agent.beginIteration();
                try {
                    handle.target.executeIteration();
                    endAttempted = true;
                    Agent.endIteration();
                } catch (Throwable t) {
                    failed = true;
                    // Ensure endIteration still runs metrics/leak checks if executeIteration failed,
                    // but never re-run it when the failure came from endIteration itself
                    if (!endAttempted) {
                        // A throw from executeIteration is a target crash — unless the target
                        // declares it an expected input rejection (a throw from endIteration is
                        // our own leak/invariant signal, already counted).
                        if (isExpectedRejection(handle.target, t)) {
                            runner.util.StatusReporter.recordRejected();
                        } else {
                            runner.util.StatusReporter.recordCrash();
                        }
                        try { Agent.endIteration(); } catch (Throwable t2) { /* prefer original failure info */ }
                    }
                    if (!(resetViaClassloader && resetOnFailure && resets < maxResets)) {
                        // Bubble up if not resetting
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        throw new RuntimeException("Failure during run", t);
                    }
                    System.err.println("[ClosureJVM] Iteration failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }

                // Reset on failure if configured
                if (failed && resetViaClassloader && resetOnFailure && resets < maxResets) {
                    resets++;
                    runner.util.StatusReporter.recordReset();
                    safeCloseTarget(handle);
                    safeCloseLoader(handle);
                    handle = createTargetHandle(targetClass, true);
                    try { handle.target.initialize(); } catch (Exception e) {
                        throw new RuntimeException("Failed to reinitialize target after reset", e);
                    }
                    System.out.println("[ClosureJVM] Performed classloader reset (#" + resets + ")");
                }
            }
            runner.util.StatusReporter.renderFinal();
            System.out.println("GenericRunner completed " + iterations + " iterations");
        } catch (Exception e) {
            throw new RuntimeException("Failure during run", e);
        } finally {
            safeCloseTarget(handle);
            safeCloseLoader(handle);
        }
    }

    static boolean isExpectedRejection(runner.api.IterationTarget target, Throwable t) {
        return target instanceof runner.api.CrashClassifier
                && ((runner.api.CrashClassifier) target).isExpected(t);
    }

    private static int parseIterations(String[] args) {
        if (args.length > 0) {
            try { return Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_ITERATIONS;
    }

    private static String parseTargetClass(String[] args) {
        if (args.length > 1) {
            return args[1];
        }
        return System.getProperty("closurejvm.target");
    }

    // --- Reset implementation helpers ---

    private static class TargetHandle {
        final IterationTarget target;
        final URLClassLoader loader; // may be null if not using classloader reset
        TargetHandle(IterationTarget t, URLClassLoader l) { this.target = t; this.loader = l; }
    }

    private static TargetHandle createTargetHandle(String className, boolean useChildLoader) {
        if (!useChildLoader) {
            return new TargetHandle(instantiateTargetWith(ClassLoader.getSystemClassLoader(), className), null);
        }
        String pkgPrefix = packagePrefix(className);
        URLClassLoader child = new ChildFirstURLClassLoader(runtimeClasspathUrls(), GenericRunner.class.getClassLoader(), pkgPrefix);
        return new TargetHandle(instantiateTargetWith(child, className), child);
    }

    private static IterationTarget instantiateTargetWith(ClassLoader loader, String className) {
        try {
            Class<?> cls = Class.forName(className, true, loader);
            Object o = cls.getDeclaredConstructor().newInstance();
            if (!(o instanceof IterationTarget)) {
                throw new IllegalArgumentException("Class does not implement runner.api.IterationTarget: " + className);
            }
            return (IterationTarget) o;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to instantiate target: " + className, e);
        }
    }

    private static String packagePrefix(String className) {
        int i = className.lastIndexOf('.');
        return i > 0 ? className.substring(0, i + 1) : ""; // include trailing dot for startsWith checks
    }

    private static URL[] runtimeClasspathUrls() {
        String cp = System.getProperty("java.class.path", "");
        String sep = File.pathSeparator;
        String[] parts = cp.split(java.util.regex.Pattern.quote(sep));
        List<URL> urls = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            try { urls.add(new File(p).toURI().toURL()); } catch (MalformedURLException ignored) {}
        }
        return urls.toArray(new URL[0]);
    }

    private static void safeCloseTarget(TargetHandle handle) {
        if (handle != null && handle.target != null) {
            try { handle.target.close(); } catch (Exception ignored) {}
        }
    }

    private static void safeCloseLoader(TargetHandle handle) {
        if (handle != null && handle.loader != null) {
            try { handle.loader.close(); } catch (Exception ignored) {}
        }
    }

    // Child-first loader for target package to enable class redefinition via new loader instances
    private static final class ChildFirstURLClassLoader extends URLClassLoader {
        private final String targetPrefix;
        ChildFirstURLClassLoader(URL[] urls, ClassLoader parent, String targetPrefix) {
            super(urls, parent);
            this.targetPrefix = targetPrefix == null ? "" : targetPrefix;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Avoid messing with core/platform or harness classes
            if (parentFirst(name)) {
                return super.loadClass(name, resolve);
            }
            // child-first
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    // fallback to parent
                    c = super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }

        private boolean parentFirst(String name) {
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("agent.") || name.startsWith("runner.") || (!targetPrefix.isEmpty() && !name.startsWith(targetPrefix));
        }
    }
}
