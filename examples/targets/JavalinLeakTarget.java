package examples.targets;

import runner.api.IterationTarget;

public class JavalinLeakTarget implements IterationTarget {
    @Override
    public void initialize() {
        examples.JavalinLeakExample.ensureServerStarted();
    }

    @Override
    public void executeIteration() {
        examples.JavalinLeakExample.callLeakEndpoint();
    }
}

