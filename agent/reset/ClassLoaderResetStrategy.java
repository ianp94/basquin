package agent.reset;

/**
 * Stub for hard reset via ClassLoader swap. v0.2 will flesh this out.
 * For now, this is a placeholder that documents intent without affecting runtime.
 */
public class ClassLoaderResetStrategy implements ResetStrategy {
    @Override
    public void beforeIteration(int iteration) {
        // no-op stub
    }

    @Override
    public boolean afterIteration(int iteration) {
        // TODO: implement classloader swap and reinitialize target app in v0.2
        return false;
    }
}

