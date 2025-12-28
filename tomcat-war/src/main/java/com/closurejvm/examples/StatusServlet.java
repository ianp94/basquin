package com.closurejvm.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class StatusServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(200);
        resp.setContentType("application/json");
        long reqs = ClosureJVMMetrics.requests();
        long crashes = ClosureJVMMetrics.crashes();
        long invs = ClosureJVMMetrics.invariants();
        try (PrintWriter w = resp.getWriter()) {
            w.println("{" +
                    "\"requests\":" + reqs + "," +
                    "\"crashes\":" + crashes + "," +
                    "\"invariants\":" + invs +
                    "}");
        }
    }
}

