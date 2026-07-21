package com.basquin.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import agent.Agent;
import java.io.IOException;
import java.io.PrintWriter;

public class StatusServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(200);
        resp.setContentType("application/json");
        long reqs = BasquinMetrics.requests();
        long crashes = BasquinMetrics.crashes();
        long invs = BasquinMetrics.invariants();
        java.util.List<String> recent = Agent.getLastInvariantViolations();
        String stack = Agent.getLastInvariantStack();
        String stackShort = shorten(stack, 12, 600);
        try (PrintWriter w = resp.getWriter()) {
            w.print('{');
            w.print("\"requests\":" + reqs + ",");
            w.print("\"crashes\":" + crashes + ",");
            w.print("\"invariants\":" + invs + ",");
            w.print("\"recent\":[");
            for (int i = 0; i < recent.size(); i++) {
                if (i > 0) w.print(',');
                w.print('"'); w.print(escape(recent.get(i))); w.print('"');
            }
            w.print("],");
            w.print("\"stack\":\"");
            w.print(escape(stackShort));
            w.print("\"}");
            w.println();
        }
    }

    private static String shorten(String s, int maxLines, int maxChars) {
        if (s == null) return "";
        String[] lines = s.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        int linesOut = 0;
        for (String line : lines) {
            if (linesOut >= maxLines) break;
            if (out.length() + line.length() + 1 > maxChars) break;
            if (linesOut > 0) out.append('\n');
            out.append(line);
            linesOut++;
        }
        return out.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
