package agent;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;

import org.junit.Test;

/**
 * DD-043 PR-1 guard. basquin-core's classes MUST load parent-first in GenericRunner's
 * reset ClassLoader. If they do not, each reset gets a fresh ResultStore and per-request
 * results are written to one instance and polled from another — DD-040's defect class,
 * silently.
 *
 * This is why basquin-core keeps `package agent` instead of `com.basquin.core`: the
 * predicate matches on the literal "agent." prefix, and targetPrefix defaults to empty,
 * which disables the only other clause that would have covered a renamed package.
 *
 * Access route: {@code runner.GenericRunner.ChildFirstURLClassLoader} is a
 * {@code private static final} nested class and {@code parentFirst(String)} is a
 * {@code private} instance method — neither is visible even from another class in the
 * {@code runner} package, let alone from {@code agent}. Widening either would touch
 * GenericRunner's structure, which Task 2 is not allowed to do beyond the documented
 * package-private fallback. Reflection reaches the predicate without changing any
 * modifier in GenericRunner.java at all, so it is used here instead.
 */
public class ResetLoaderParentFirstTest {

    @Test
    public void coreClassesLoadParentFirst() throws Exception {
        // Derived from the classes themselves, not typed as string literals: a rename of
        // either class's package changes what .class.getName() returns, so this assertion
        // tracks the actual class and fails on the rename it exists to catch. A hand-typed
        // literal like "agent.ResultStore" would keep matching "agent." forever, even after
        // the class itself moved to a different package — see the mutation evidence recorded
        // in .superpowers/sdd/task-3-report.md.
        assertTrue("agent.ResultStore must be parent-first or the reset loader forks the store",
                isParentFirst(ResultStore.class.getName()));
        assertTrue("agent.Invariants must be parent-first",
                isParentFirst(Invariants.class.getName()));
    }

    @Test
    public void aRenamedCorePackageWouldNotBeParentFirst() throws Exception {
        // Pins WHY the package is not renamed. This literal is deliberate, not a mistake like
        // the ones above: it documents a hypothetical renamed name that does not exist as a
        // class today, so there is nothing to derive it from. A failure here means "the rename
        // is now safe" — the predicate has been taught about the new prefix — not "the test is
        // broken". Do not "fix" it by reverting parentFirst; update this test's expectation
        // instead once that happens deliberately.
        assertFalse("com.basquin.core.* is not covered by parentFirst with an empty targetPrefix",
                isParentFirst("com.basquin.core.ResultStore"));
    }

    /** Invokes GenericRunner's reset-loader predicate for {@code name} with an empty targetPrefix. */
    private boolean isParentFirst(String name) throws Exception {
        Class<?> loaderClass = Class.forName("runner.GenericRunner$ChildFirstURLClassLoader");

        Constructor<?> ctor = loaderClass.getDeclaredConstructor(URL[].class, ClassLoader.class, String.class);
        ctor.setAccessible(true);
        Object loader = ctor.newInstance((Object) new URL[0], getClass().getClassLoader(), "");

        Method predicate = loaderClass.getDeclaredMethod("parentFirst", String.class);
        predicate.setAccessible(true);
        return (boolean) predicate.invoke(loader, name);
    }
}
