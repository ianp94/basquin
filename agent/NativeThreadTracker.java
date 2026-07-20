package agent;

/**
 * Bridge to the optional ClosureJVM JVMTI native agent (native/closurejvmti.c).
 *
 * When the JVM is started with {@code -agentpath:/abs/libclosurejvmti.so}, the native
 * agent maintains live/non-daemon thread counts incrementally from ThreadStart/ThreadEnd
 * events — no polling, no safepoint stack walks. This class exposes those counts to the
 * harness, and degrades gracefully to a no-op (isActive() == false) when the agent is not
 * loaded, so callers can fall back to {@code ThreadMXBean}.
 *
 * The library path is provided via {@code -Dclosurejvm.native.lib=/abs/libclosurejvmti.so}
 * (the same file passed to -agentpath). We System.load() it here so the JNI methods resolve;
 * the counters are shared C globals, so both the agent and JNI views agree.
 */
public final class NativeThreadTracker {

    private static final boolean LOADED = tryLoad();

    private NativeThreadTracker() {}

    private static boolean tryLoad() {
        String path = System.getProperty("closurejvm.native.lib");
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            System.load(path);
            // Loaded successfully; nativeAvailable() reports whether Agent_OnLoad also ran
            // (i.e. the same file was passed to -agentpath and thread events are active).
            return nativeAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True only when the native agent is loaded AND actively tracking thread events. */
    public static boolean isActive() {
        return LOADED && nativeAvailable();
    }

    /** Live thread count (daemon + non-daemon) from native event tracking. */
    public static int liveThreadCount() {
        return nativeLiveThreadCount();
    }

    /** Live non-daemon thread count from native event tracking. */
    public static int nonDaemonThreadCount() {
        return nativeNonDaemonThreadCount();
    }

    /**
     * Live non-daemon Thread objects tracked from ThreadStart/ThreadEnd events —
     * the leak-set source, with no thread enumeration. Never null when isActive().
     */
    public static Thread[] nonDaemonThreads() {
        Thread[] threads = nativeNonDaemonThreads();
        return threads != null ? threads : new Thread[0];
    }

    private static native boolean nativeAvailable();
    private static native int nativeLiveThreadCount();
    private static native int nativeNonDaemonThreadCount();
    private static native Thread[] nativeNonDaemonThreads();
}
