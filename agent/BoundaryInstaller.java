package agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;

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
    public static void install(Instrumentation instrumentation) {
        // Locate the advice class bytes explicitly. The agent jar is injected on BOTH the boot classpath
        // (-Xbootclasspath/a, so the advice class is visible to StandardHostValve's loader) AND the system
        // classpath (-javaagent). ByteBuddy's default locator derives from the advice class's loader, which
        // is the boot loader (null) for a -Xbootclasspath/a class — and that cannot enumerate boot-appended
        // resources, so the transform silently no-ops. Reading the bytes via the system loader (where the
        // -javaagent jar lives) is reliable; fall back to the advice class's own loader just in case.
        ClassFileLocator locator = new ClassFileLocator.Compound(
                ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ClassFileLocator.ForClassLoader.of(TomcatBoundaryAdvice.class.getClassLoader()));

        new AgentBuilder.Default()
                .disableClassFormatChanges() // advice-only; no class-schema changes
                // Surface transform errors instead of swallowing them (this is how a silent no-op hid before).
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(ElementMatchers.named("org.apache.catalina.core.StandardHostValve"))
                .transform((builder, type, cl, module, pd) -> builder.visit(
                        Advice.to(TomcatBoundaryAdvice.class, locator).on(ElementMatchers.named("invoke"))))
                .installOn(instrumentation);
        System.out.println("[Basquin] agent boundary installed on StandardHostValve");
    }
}
