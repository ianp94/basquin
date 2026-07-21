package com.basquin.examples;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Tiny fixed-size JDBC connection pool for demo purposes only.
 * Not production-ready; just enough to exercise DB latency/pool behavior.
 */
final class DbPool {
    private final BlockingQueue<Connection> queue;
    private final String url;
    private final int size;

    DbPool(String url, int size) {
        this.url = url;
        this.size = Math.max(1, size);
        this.queue = new ArrayBlockingQueue<>(this.size);
    }

    synchronized void start() throws SQLException {
        if (!queue.isEmpty()) return;
        for (int i = 0; i < size; i++) {
            queue.add(DriverManager.getConnection(url));
        }
    }

    Connection acquire() throws InterruptedException { return queue.take(); }

    void release(Connection c) {
        if (c == null) return;
        // Best-effort: return to pool unless closed
        try {
            if (!c.isClosed()) queue.offer(c);
        } catch (SQLException ignored) {}
    }

    synchronized void stop() {
        while (!queue.isEmpty()) {
            Connection c = queue.poll();
            if (c != null) try { c.close(); } catch (SQLException ignored) {}
        }
    }
}

