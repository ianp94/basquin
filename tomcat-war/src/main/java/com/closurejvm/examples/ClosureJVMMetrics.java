package com.closurejvm.examples;

import java.util.concurrent.atomic.AtomicLong;

public final class ClosureJVMMetrics {
    private static final AtomicLong totalRequests = new AtomicLong();
    private static final AtomicLong totalCrashes = new AtomicLong();
    private static final AtomicLong totalInvariantEvents = new AtomicLong();

    private ClosureJVMMetrics() {}

    public static void incRequests() { totalRequests.incrementAndGet(); }
    public static void incCrashes() { totalCrashes.incrementAndGet(); }
    public static void addInvariantCount(long n) { if (n > 0) totalInvariantEvents.addAndGet(n); }

    public static long requests() { return totalRequests.get(); }
    public static long crashes() { return totalCrashes.get(); }
    public static long invariants() { return totalInvariantEvents.get(); }
}

