package com.basquin.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class HeapServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String kbS = req.getParameter("kb");
        int kb = 0;
        try { kb = Math.max(0, Integer.parseInt(kbS)); } catch (Exception ignored) {}
        byte[] buf = new byte[Math.min(kb, 4096) * 1024];
        for (int i = 0; i < buf.length; i += 4096) buf[i] = (byte)(i & 0xFF);
        resp.setStatus(200);
        resp.setContentType("text/plain");
        try (PrintWriter w = resp.getWriter()) { w.println("allocKB=" + kb); }
    }
}

