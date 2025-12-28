package com.closurejvm.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * /db route: uses a tiny pool and executes a simulated slow query via MySQL SLEEP().
 * Params:
 *  - sleepMs: milliseconds to sleep in the DB (rounded to nearest 1000th second)
 */
public class DbServlet extends HttpServlet {
    private volatile DbPool pool;

    @Override
    public void init() throws ServletException {
        super.init();
        // Default URL matches docker-compose mysql service and db
        String url = System.getProperty("closurejvm.demo.db.url",
                System.getenv().getOrDefault("CLOSUREJVM_DEMO_DB_URL", "jdbc:mysql://mysql:3306/demo?user=root&password=closurejvm"));
        int size = getIntProp("closurejvm.demo.db.poolSize", System.getenv("CLOSUREJVM_DEMO_DB_POOL"), 2);
        DbPool p = new DbPool(url, size);
        try {
            p.start();
        } catch (SQLException e) {
            throw new ServletException("Failed to start DB pool", e);
        }
        pool = p;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        int sleepMs = 0;
        try { sleepMs = Math.max(0, Integer.parseInt(req.getParameter("sleepMs"))); } catch (Exception ignored) {}
        double secs = Math.max(0.0, sleepMs / 1000.0);
        String sql = "SELECT SLEEP(?)"; // MySQL: sleeps for given seconds

        Connection c = null;
        try {
            c = pool.acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setDouble(1, secs);
                try (ResultSet rs = ps.executeQuery()) {
                    // drain
                    while (rs.next()) { /* ignore result */ }
                }
            }
            ok(resp, "dbSleepMs=" + sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            resp.sendError(500, "Interrupted");
        } catch (SQLException se) {
            resp.sendError(500, "DB error: " + se.getMessage());
        } finally {
            pool.release(c);
        }
    }

    private static void ok(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(200);
        resp.setContentType("text/plain");
        try (PrintWriter w = resp.getWriter()) { w.println(msg); }
    }

    private static int getIntProp(String sysProp, String env, int def) {
        if (sysProp != null) {
            try { return Integer.getInteger(sysProp, def); } catch (Exception ignored) {}
        }
        if (env != null) {
            try { return Integer.parseInt(env); } catch (Exception ignored) {}
        }
        return def;
    }
}

