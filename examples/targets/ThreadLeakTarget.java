package examples.targets;

import runner.api.IterationTarget;

public class ThreadLeakTarget implements IterationTarget {

    // mode: leak | proper (default: proper)
    private String mode;

    @Override
    public void initialize() {
        this.mode = System.getProperty("examples.mode", "proper");
        System.out.println("ThreadLeakTarget mode=" + mode);
    }

    @Override
    public void executeIteration() {
        if ("leak".equalsIgnoreCase(mode)) {
            examples.ThreadLeakExample.createLeakingThreads();
        } else {
            examples.ThreadLeakExample.createProperThreads();
        }
    }
}

