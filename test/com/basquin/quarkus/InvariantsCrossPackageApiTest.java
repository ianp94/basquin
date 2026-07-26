package com.basquin.quarkus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import agent.Invariants;
import org.junit.Test;

/**
 * DD-043 PR-2 entry requirement: {@code basquin-core}'s invariant API must be callable from OUTSIDE
 * {@code package agent}.
 *
 * <p><b>This test lives in {@code com.basquin.quarkus} on purpose</b> — the package the Quarkus
 * extension will occupy. That is the whole point: before this, {@code Invariants},
 * {@code evaluateAndMaybeFail} and {@code Result} were package-private, and {@code Violation}'s fields
 * were too even though the class itself was public. The extension's boundary filter could not have
 * called any of it, which would have defeated the extraction that put these classes in a separate
 * artifact in the first place.
 *
 * <p><b>Why this exists as a test rather than a comment.</b> Narrowing any of those back is a
 * one-word edit that still compiles everywhere inside {@code package agent} — every existing caller
 * is same-package, so nothing else in the build would notice. This test is the only thing that would.
 * If it fails to compile, the API has been narrowed and PR-2's consumer is broken; that is the signal,
 * not a reason to move the test into {@code package agent}.
 */
public class InvariantsCrossPackageApiTest {

    /** With no thresholds configured, evaluation yields no violations and no hard failure. */
    @Test
    public void apiIsReachableFromOutsidePackageAgent() {
        Invariants.Result result = Invariants.evaluateAndMaybeFail(1, 0L, 0L, 1, 0);

        assertNotNull("Result must be constructible and returned across a package boundary", result);
        assertNotNull("Result.violations must be readable from another package", result.violations);
        assertEquals("no thresholds configured, so no violations", 0, result.violations.size());
        assertNull("no thresholds configured, so no hard failure", result.hardFailureMessage);
    }

    /**
     * Reads {@code Violation}'s fields specifically. {@code Violation} was already a public class, so
     * a visibility check that only touched the enclosing type would pass while these stayed
     * unreadable — which is exactly the trap recorded in the spec's PR-2 entry requirement.
     */
    @Test
    public void violationFieldsAreReadableFromOutsidePackageAgent() {
        String prop = "basquin.invariant.latency.maxMs";
        String previous = System.getProperty(prop);
        System.setProperty(prop, "1");
        System.setProperty("basquin.invariant.latency.mode", "soft");
        try {
            Invariants.Result result = Invariants.evaluateAndMaybeFail(1, 5000L, 0L, 1, 0);

            assertEquals("a latency threshold of 1ms exceeded by 5000ms should violate",
                    1, result.violations.size());
            Invariants.Violation v = result.violations.get(0);
            assertEquals("latency", v.name);
            assertNotNull("Violation.detail must be readable from another package", v.detail);
        } finally {
            if (previous == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, previous);
            }
            System.clearProperty("basquin.invariant.latency.mode");
        }
    }
}
