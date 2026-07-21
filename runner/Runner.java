package runner;

/**
 * Deprecated thin wrapper that forwards to GenericRunner.
 * Keeps old demos working while removing hard-coded dispatch.
 */
public class Runner {

    private static final int DEFAULT_ITERATIONS = 1000;

    public static void main(String[] args) {
        System.out.println("Starting Basquin Runner (deprecated) -> forwarding to GenericRunner");
        int iterations = parseIterations(args);
        String target = resolveTargetFromProperties();
        if (target == null) {
            // Default to the simple ThreadLeakTarget in proper mode
            target = "examples.targets.ThreadLeakTarget";
        }
        System.out.println("Forwarding to GenericRunner with target: " + target);
        runner.GenericRunner.main(new String[]{String.valueOf(iterations), target});
    }

    private static int parseIterations(String[] args) {
        if (args.length > 0) {
            try { return Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_ITERATIONS;
    }

    private static String resolveTargetFromProperties() {
        // Only accept explicit target; no hard-coded cases
        String target = System.getProperty("basquin.target");
        return (target != null && !target.isBlank()) ? target : null;
    }
}
