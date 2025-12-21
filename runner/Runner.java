package runner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal runner for ClosureJVM that executes an iteration loop
 * over a corpus directory
 */
public class Runner {
    
    private static final int DEFAULT_ITERATIONS = 1000;
    
    /**
     * Main method to run the iteration loop
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.out.println("Starting ClosureJVM Runner");
        
        // Parse arguments
        int iterations = parseIterations(args);
        String corpusPath = parseCorpusPath(args);
        
        // Execute iterations
        runIterations(iterations, corpusPath);
    }
    
    /**
     * Parse the number of iterations from command line arguments
     * @param args command line arguments
     * @return number of iterations to run
     */
    private static int parseIterations(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid iteration count, using default: " + DEFAULT_ITERATIONS);
            }
        }
        return DEFAULT_ITERATIONS;
    }
    
    /**
     * Parse the corpus path from command line arguments
     * @param args command line arguments
     * @return corpus path
     */
    private static String parseCorpusPath(String[] args) {
        if (args.length > 1) {
            return args[1];
        }
        return "examples/corpus";
    }
    
    /**
     * Run the specified number of iterations over the corpus
     * @param iterations number of iterations to run
     * @param corpusPath path to corpus directory
     */
    private static void runIterations(int iterations, String corpusPath) {
        System.out.println("Running " + iterations + " iterations over corpus: " + corpusPath);
        
        // Create corpus directory if it doesn't exist
        File corpusDir = new File(corpusPath);
        if (!corpusDir.exists()) {
            corpusDir.mkdirs();
            System.out.println("Created corpus directory: " + corpusPath);
        }
        
        // Execute iterations
        for (int i = 0; i < iterations; i++) {
            System.out.println("Iteration " + (i + 1));
            
            // Begin iteration
            agent.Agent.beginIteration();
            
            // Execute one iteration of the target application
            executeIteration();
            
            // End iteration
            agent.Agent.endIteration();
        }
        
        System.out.println("Runner completed " + iterations + " iterations");
    }
    
    /**
     * Execute one iteration of the target application
     * This is where the actual work would be done
     */
    private static void executeIteration() {
        // Optional demo target selection via system property: -DdemoTarget=leak|proper
        String demo = System.getProperty("demoTarget");

        if ("leak".equalsIgnoreCase(demo)) {
            System.out.println("  Executing demo target: examples.ThreadLeakExample.createLeakingThreads()");
            examples.ThreadLeakExample.createLeakingThreads();
            return;
        } else if ("proper".equalsIgnoreCase(demo)) {
            System.out.println("  Executing demo target: examples.ThreadLeakExample.createProperThreads()");
            examples.ThreadLeakExample.createProperThreads();
            return;
        }

        // Default: simulate some work
        System.out.println("  Executing iteration work...");
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
