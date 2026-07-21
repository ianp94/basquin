package agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;

/**
 * Installs the agent-side request boundary (DD-030) by inlining {@link TomcatBoundaryAdvice} into
 * {@code org.apache.catalina.core.StandardHostValve.invoke} via ByteBuddy.
 *
 * <p><b>Why this is a separate class from {@link Agent}, invoked reflectively.</b> This class — and
 * therefore the {@code net.bytebuddy.*} types it names — is loaded ONLY when {@link Agent#premain}
 * actually installs the boundary (i.e. in a target JVM with {@code -Dbasquin.boundary=agent} set,
 * where {@code basquin-agent.jar} bundles ByteBuddy). The coverage-driver ("runner") image also
 * carries the agent classes (for client-side {@code Agent.beginIteration}) but does NOT bundle
 * ByteBuddy; if {@code Agent} itself named ByteBuddy types, merely loading {@code Agent} in the runner
 * would fail verification with {@code NoClassDefFoundError: net/bytebuddy/...}. Keeping the ByteBuddy
 * references here, reached only reflectively from {@code premain}, lets the runner load {@code Agent}
 * cleanly while the target still gets the boundary.
 */
public final class BoundaryInstaller {

    private BoundaryInstaller() {}

    /**
     * Instrument {@code StandardHostValve.invoke}. Called reflectively by {@link Agent#premain} (so the
     * method name/signature must stay {@code public static void install(Instrumentation)}). Best-effort:
     * a transform failure is surfaced via the listener but never propagated — the app runs uninstrumented.
     */
    /** Advice class resolved by name (not by .class) — see {@link #install} for why. */
    private static final String ADVICE = "agent.TomcatBoundaryAdvice";

    public static void install(Instrumentation instrumentation) {
        // Tolerate class-file versions newer than this ByteBuddy officially supports: some Tomcat images
        // ship a very recent JDK (24/25), and without this the transform of StandardHostValve throws
        // "Java NN is not supported" and no-ops. Our advice is a trivial method enter/exit, well within
        // what experimental mode handles; the errors-only listener below still surfaces any real failure.
        System.setProperty("net.bytebuddy.experimental", "true");

        new AgentBuilder.Default()
                .disableClassFormatChanges() // advice-only; no class-schema changes
                // Surface transform errors instead of swallowing them (this is how a silent no-op hid before).
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(ElementMatchers.named("org.apache.catalina.core.StandardHostValve"))
                .transform((builder, type, classLoader, module, pd) -> {
                    // Resolve the advice — and, crucially, the org.apache.catalina.connector.Request/Response
                    // types its signatures name — through the TARGET class's loader (Catalina's), NOT the
                    // loaded advice Class. The advice is defined by the boot loader (agent jar is on
                    // -Xbootclasspath/a), so Advice.to(Class) would reflect on it and resolve those Catalina
                    // types via the boot loader, which can't see them → NoClassDefFoundError, swallowed as a
                    // silent no-op. The target loader sees Catalina (its own) AND the advice (via its boot
                    // parent), so a non-loaded TypePool over it resolves everything.
                    ClassFileLocator locator = classLoader == null
                            ? ClassFileLocator.ForClassLoader.ofSystemLoader()
                            : ClassFileLocator.ForClassLoader.of(classLoader);
                    TypeDescription advice = TypePool.Default.of(locator).describe(ADVICE).resolve();
                    return builder.visit(Advice.to(advice, locator).on(ElementMatchers.named("invoke")));
                })
                .installOn(instrumentation);
        System.out.println("[Basquin] agent boundary installed on StandardHostValve");
    }
}
