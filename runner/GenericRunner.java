package runner;

import agent.Agent;
import runner.api.IterationTarget;

/**
 * Generic runner that loads a target class implementing runner.api.IterationTarget
 * and executes it across N iterations within begin/end iteration boundaries.
 *
 * Usage:
 *   java -cp <cp> runner.GenericRunner [iterations] <targetClass>
 * or set -Dclosurejvm.target=<targetClass> and pass only [iterations].
 */
public class GenericRunner {

    private static final int DEFAULT_ITERATIONS = 1000;

    public static void main(String[] args) {
        System.out.println("Starting ClosureJVM GenericRunner");

        int iterations = parseIterations(args);
        String targetClass = parseTargetClass(args);
        if (targetClass == null || targetClass.isEmpty()) {
            throw new IllegalArgumentException("Target class not specified. Pass as arg or -Dclosurejvm.target");
        }

        System.out.println("Running " + iterations + " iterations with target: " + targetClass);

        IterationTarget target = instantiateTarget(targetClass);

        try {
            target.initialize();
            for (int i = 0; i < iterations; i++) {
                System.out.println("Iteration " + (i + 1));
                Agent.beginIteration();
                try {
                    target.executeIteration();
                } finally {
                    Agent.endIteration();
                }
            }
            System.out.println("GenericRunner completed " + iterations + " iterations");
        } catch (Exception e) {
            throw new RuntimeException("Failure during run", e);
        } finally {
            try { target.close(); } catch (Exception ignored) {}
        }
    }

    private static int parseIterations(String[] args) {
        if (args.length > 0) {
            try { return Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_ITERATIONS;
    }

    private static String parseTargetClass(String[] args) {
        if (args.length > 1) {
            return args[1];
        }
        return System.getProperty("closurejvm.target");
    }

    private static IterationTarget instantiateTarget(String className) {
        try {
            Class<?> cls = Class.forName(className);
            Object o = cls.getDeclaredConstructor().newInstance();
            if (!(o instanceof IterationTarget)) {
                throw new IllegalArgumentException("Class does not implement runner.api.IterationTarget: " + className);
            }
            return (IterationTarget) o;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to instantiate target: " + className, e);
        }
    }
}

