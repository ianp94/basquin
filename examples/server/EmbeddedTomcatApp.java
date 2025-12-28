package examples.server;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.io.PrintWriter;

public class EmbeddedTomcatApp {
    private Tomcat tomcat;
    private int port;

    public void start(int port) {
        try {
            this.port = port;
            tomcat = new Tomcat();
            tomcat.setPort(port);
            tomcat.setBaseDir(createTempDir());
            Context ctx = tomcat.addContext("", createTempDir());
            Tomcat.addServlet(ctx, "crash", new CrashServlet());
            ctx.addServletMappingDecoded("/crash", "crash");
            Tomcat.addServlet(ctx, "latency", new LatencyServlet());
            ctx.addServletMappingDecoded("/latency", "latency");
            Tomcat.addServlet(ctx, "heap", new HeapServlet());
            ctx.addServletMappingDecoded("/heap", "heap");
            tomcat.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded Tomcat", e);
        }
    }

    public void stop() {
        if (tomcat != null) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (Exception ignored) {}
        }
    }

    public int getPort() { return port; }

    private static String createTempDir() {
        try {
            java.nio.file.Path p = java.nio.file.Files.createTempDirectory("tomcat");
            p.toFile().deleteOnExit();
            return p.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class CrashServlet extends HttpServlet {
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

    static class LatencyServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            String msS = req.getParameter("ms");
            int ms = 0;
            try { ms = Math.max(0, Integer.parseInt(msS)); } catch (Exception ignored) {}
            try { Thread.sleep(Math.min(ms, 2000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            writeOk(resp, "slept=" + ms);
        }
    }

    static class HeapServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            String kbS = req.getParameter("kb");
            int kb = 0;
            try { kb = Math.max(0, Integer.parseInt(kbS)); } catch (Exception ignored) {}
            byte[] buf = new byte[Math.min(kb, 4096) * 1024];
            for (int i = 0; i < buf.length; i += 4096) buf[i] = (byte)(i & 0xFF);
            writeOk(resp, "allocKB=" + kb);
        }
    }

    private static void writeOk(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(200);
        resp.setContentType("text/plain");
        try (PrintWriter w = resp.getWriter()) { w.println(msg); }
    }
}

