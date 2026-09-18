package com.basquin.quarkus.runtime;

/** Process-wide request overlap history. A window remains tainted even after its peer ends. */
final class ReactiveWindows {
    private int active;
    private long overlapEpoch;

    static final class Window {
        final long epoch;
        final boolean overlappedAtStart;
        boolean ended;
        Window(long epoch, boolean overlapped) {
            this.epoch = epoch;
            this.overlappedAtStart = overlapped;
        }
    }

    synchronized Window begin() {
        boolean overlap = active > 0;
        if (overlap) overlapEpoch++;
        active++;
        return new Window(overlapEpoch, overlap);
    }

    synchronized boolean end(Window window) {
        if (window.ended) throw new IllegalStateException("request window already ended");
        window.ended = true;
        active--;
        return window.overlappedAtStart || window.epoch != overlapEpoch;
    }

    synchronized int active() { return active; }
}
