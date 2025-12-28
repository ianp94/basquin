package com.closurejvm.examples;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CrashServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String type = req.getParameter("type");
        if (type == null) type = "NPE";
        switch (type.toUpperCase()) {
            case "NPE": throw new NullPointerException("boom");
            case "IAE": throw new IllegalArgumentException("bad arg");
            case "ISE": throw new IllegalStateException("bad state");
            default: throw new RuntimeException("unknown crash type: " + type);
        }
    }
}

