package com.closurejvm.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class LatencyServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String msS = req.getParameter("ms");
        int ms = 0;
        try { ms = Math.max(0, Integer.parseInt(msS)); } catch (Exception ignored) {}
        try { Thread.sleep(Math.min(ms, 2000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        resp.setStatus(200);
        resp.setContentType("text/plain");
        try (PrintWriter w = resp.getWriter()) { w.println("slept=" + ms); }
    }
}

