package com.basquin.quarkus.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, hand-rolled {@link InvocationHandler} for building single-purpose fakes of Vert.x's
 * wide interfaces ({@code RoutingContext} alone runs 40+ methods, most of them default methods with
 * real bodies we never want to execute in a unit test) without pulling in a mocking library — this
 * module's rule (DD-043 PR-2) is {@code junit:junit:4.13.2} only. A JDK {@link Proxy} needs a
 * canned return value only for the handful of methods a given test actually calls; every other call
 * falls back to a type-appropriate default, so building one of these fakes costs a few {@link #stub}
 * calls, not implementing an interface by hand.
 *
 * <p>Every invocation is recorded, in order, so a test can assert not just what a fake returned but
 * WHICH methods were actually called. That is the entire reason this class exists: DD-043 PR-2's
 * Fix 2 (normalized path, not raw request path) can be pinned at the RULE level by testing a pure
 * predicate directly, but that alone proves the rule, not the wiring — it does not prove the real
 * call site actually reads {@code ctx.normalizedPath()} rather than {@code ctx.request().path()}.
 * Only exercising the real {@code handle(RoutingContext)} entry point against a fake where the two
 * disagree, and checking which downstream calls fired, proves the wiring.
 */
final class FakeInvocationHandler implements InvocationHandler {

    private final Map<String, Object> stubbed = new HashMap<>();
    private final List<String> calls = new ArrayList<>();

    /**
     * Makes every call to a method named {@code methodName} — regardless of overload or arguments
     * — return {@code value}. Sufficient here because none of these fakes are ever driven with two
     * differently-typed overloads of the same method name within one test.
     */
    void stub(String methodName, Object value) {
        stubbed.put(methodName, value);
    }

    /** Every method name invoked on the proxy, in call order (duplicates included). */
    List<String> calls() {
        return calls;
    }

    boolean called(String methodName) {
        return calls.contains(methodName);
    }

    /** Builds a {@code Proxy} implementing {@code iface}, backed by a fresh handler. */
    static <T> T proxy(Class<T> iface, FakeInvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler));
    }

    @Override
    public Object invoke(Object proxyInstance, Method method, Object[] args) {
        calls.add(method.getName());
        if (stubbed.containsKey(method.getName())) {
            return stubbed.get(method.getName());
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        // Fluent builder methods (RoutingContext#put, HttpServerResponse#setStatusCode/putHeader,
        // etc.) return the interface itself; handing back the same proxy keeps a chained call
        // working without needing an explicit stub for every link in the chain.
        if (returnType.isInstance(proxyInstance)) return proxyInstance;
        return null;
    }
}
