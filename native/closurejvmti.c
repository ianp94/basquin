/*
 * ClosureJVM native agent (JVMTI).
 *
 * Purpose: track thread lifecycle via ThreadStart/ThreadEnd events so the harness
 * can read a live-thread count with zero polling and zero safepoint stack walks.
 * This is the event-driven replacement for Thread.getAllStackTraces()-based
 * enumeration on the hot path.
 *
 * Load into the JVM with:  -agentpath:/abs/path/libclosurejvmti.so
 * The same shared object also exports the JNI methods for agent.NativeThreadTracker,
 * so the Java side does System.load() on the same file to resolve them. The counters
 * are shared C globals, so both entry points see the same numbers.
 *
 * Build: see native/Makefile or the Gradle 'buildNativeAgent' task.
 */

#include <jvmti.h>
#include <jni.h>
#include <string.h>

/* Live-thread counters, maintained incrementally from thread events.
 * Updated with GCC atomic builtins because thread events fire concurrently. */
static volatile long g_live = 0;
static volatile long g_non_daemon = 0;
static volatile int  g_tracking = 0;   /* 1 once seeded and events are enabled */

static jvmtiEnv *g_jvmti = NULL;

static int thread_is_daemon(jvmtiEnv *jvmti, jthread thread) {
    jvmtiThreadInfo info;
    memset(&info, 0, sizeof(info));
    if ((*jvmti)->GetThreadInfo(jvmti, thread, &info) != JVMTI_ERROR_NONE) {
        return 0; /* assume non-daemon on error so we never under-report leaks */
    }
    int daemon = info.is_daemon ? 1 : 0;
    if (info.name != NULL) {
        (*jvmti)->Deallocate(jvmti, (unsigned char *) info.name);
    }
    return daemon;
}

static void JNICALL
cb_thread_start(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    __atomic_add_fetch(&g_live, 1, __ATOMIC_RELAXED);
    if (!thread_is_daemon(jvmti, thread)) {
        __atomic_add_fetch(&g_non_daemon, 1, __ATOMIC_RELAXED);
    }
}

static void JNICALL
cb_thread_end(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    __atomic_sub_fetch(&g_live, 1, __ATOMIC_RELAXED);
    if (!thread_is_daemon(jvmti, thread)) {
        __atomic_sub_fetch(&g_non_daemon, 1, __ATOMIC_RELAXED);
    }
}

/* Seed absolute counts from the threads already running at VM init, THEN turn on
 * the incremental events. Seeding before enabling events avoids double-counting
 * threads that would otherwise fire ThreadStart during startup. */
static void JNICALL
cb_vm_init(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
    jint count = 0;
    jthread *threads = NULL;
    long live = 0, non_daemon = 0;

    if ((*jvmti)->GetAllThreads(jvmti, &count, &threads) == JVMTI_ERROR_NONE) {
        for (jint i = 0; i < count; i++) {
            live++;
            if (!thread_is_daemon(jvmti, threads[i])) {
                non_daemon++;
            }
        }
        if (threads != NULL) {
            (*jvmti)->Deallocate(jvmti, (unsigned char *) threads);
        }
    }

    __atomic_store_n(&g_live, live, __ATOMIC_RELAXED);
    __atomic_store_n(&g_non_daemon, non_daemon, __ATOMIC_RELAXED);

    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, NULL);
    __atomic_store_n(&g_tracking, 1, __ATOMIC_RELAXED);
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION_1_2) != JNI_OK || jvmti == NULL) {
        return JNI_ERR;
    }
    g_jvmti = jvmti;

    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.VMInit = &cb_vm_init;
    callbacks.ThreadStart = &cb_thread_start;
    callbacks.ThreadEnd = &cb_thread_end;
    if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }

    /* Only VMInit is enabled here; thread events are enabled once seeded (see cb_vm_init). */
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
    return JNI_OK;
}

JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
    (void) vm;
}

/* Allow System.load() of this same .so to succeed so the JNI methods below resolve. */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_agent_NativeThreadTracker_nativeAvailable(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return __atomic_load_n(&g_tracking, __ATOMIC_RELAXED) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_agent_NativeThreadTracker_nativeLiveThreadCount(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jint) __atomic_load_n(&g_live, __ATOMIC_RELAXED);
}

JNIEXPORT jint JNICALL
Java_agent_NativeThreadTracker_nativeNonDaemonThreadCount(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jint) __atomic_load_n(&g_non_daemon, __ATOMIC_RELAXED);
}
