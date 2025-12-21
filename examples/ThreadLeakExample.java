package examples;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Example target that demonstrates a thread leak
 * This is used to test the thread leak detection functionality
 */
public class ThreadLeakExample {

    private static int threadCount() {
        String v = System.getProperty("examples.threads");
        if (v != null) {
            try { return Math.max(1, Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
        }
        return 5;
    }

    private static long sleepMs() {
        String v = System.getProperty("examples.sleepMs");
        if (v != null) {
            try { return Math.max(0, Long.parseLong(v)); } catch (NumberFormatException ignored) {}
        }
        return 1000L;
    }
    
    /**
     * Method that creates threads but doesn't properly clean them up
     * This simulates a thread leak that should be detected
     */
    public static void createLeakingThreads() {
        final int count = threadCount();
        final long ms = sleepMs();
        ExecutorService executor = agent.Agent.trackExecutor(Executors.newFixedThreadPool(count));
        
        // Submit tasks that will run for a while
        for (int i = 0; i < count; i++) {
            final int taskId = i;
            executor.submit(() -> {
                System.out.println("Task " + taskId + " started");
                try {
                    // Simulate work that takes some time
                    Thread.sleep(ms);
                    System.out.println("Task " + taskId + " completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        // Note: We're not calling executor.shutdown() or executor.awaitTermination()
        // This causes thread leaks that should be detected by our harness
        System.out.println("Created " + count + " threads but didn't shut down executor");
    }
    
    /**
     * Method that properly manages threads
     * This should not cause leaks
     */
    public static void createProperThreads() {
        final int count = threadCount();
        final long ms = sleepMs();
        ExecutorService executor = agent.Agent.trackExecutor(Executors.newFixedThreadPool(count));
        
        // Submit tasks
        for (int i = 0; i < count; i++) {
            final int taskId = i;
            executor.submit(() -> {
                System.out.println("Task " + taskId + " started");
                try {
                    Thread.sleep(ms);
                    System.out.println("Task " + taskId + " completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        // Properly shut down the executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("Properly managed " + count + " threads");
    }
    
    /**
     * Main method to demonstrate the examples
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.out.println("Thread Leak Example");
        System.out.println("===================");
        
        if (args.length > 0 && "leak".equals(args[0])) {
            System.out.println("Creating threads that will leak...");
            createLeakingThreads();
        } else {
            System.out.println("Creating properly managed threads...");
            createProperThreads();
        }
        
        System.out.println("Example completed");
    }
}
