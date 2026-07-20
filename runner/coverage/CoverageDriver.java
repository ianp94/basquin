package runner.coverage;

import runner.util.StatusReporter;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs a coverage-guided-over-HTTP campaign: starts a background poller that pulls the
 * app-under-test's coverage from its JaCoCo agent and feeds it into the live status panel
 * ({@link StatusReporter#recordCoverage}), then delegates to {@link runner.GenericRunner} to
 * drive the app (typically {@code examples.targets.HttpRouteDriveTarget}).
 *
 * Config:
 * - {@code -Dclosurejvm.coverage.jacoco=host:port}  JaCoCo tcpserver (default localhost:6300)
 * - {@code -Dclosurejvm.coverage.classes=<dir>}     directory of the app's .class files
 * - {@code -Dclosurejvm.coverage.intervalMs=<n>}    poll interval (default 1000)
 *
 * Coverage feedback as a *guidance* signal (mutating inputs toward new coverage) is the next
 * step; this slice establishes the signal and shows a real "% of code explored" in the panel.
 */
public final class CoverageDriver {

    public static void main(String[] args) throws Exception {
        String jacoco = System.getProperty("closurejvm.coverage.jacoco", "localhost:6300");
        String classes = System.getProperty("closurejvm.coverage.classes");
        long intervalMs = Long.getLong("closurejvm.coverage.intervalMs", 1000L);

        if (classes != null && !classes.isEmpty()) {
            String host = jacoco.contains(":") ? jacoco.substring(0, jacoco.indexOf(':')) : jacoco;
            int port = jacoco.contains(":") ? Integer.parseInt(jacoco.substring(jacoco.indexOf(':') + 1)) : 6300;
            Path classesDir = Paths.get(classes);
            JacocoCoverageProvider provider = new JacocoCoverageProvider(host, port, classesDir);
            Thread poller = new Thread(() -> pollLoop(provider, intervalMs), "ClosureJVM-Coverage");
            poller.setDaemon(true);
            poller.start();
            System.out.println("[ClosureJVM][Coverage] polling JaCoCo at " + jacoco + " against " + classes);
        } else {
            System.err.println("[ClosureJVM][Coverage] -Dclosurejvm.coverage.classes not set; running without coverage");
        }

        runner.GenericRunner.main(args);
    }

    private static void pollLoop(JacocoCoverageProvider provider, long intervalMs) {
        while (true) {
            try {
                JacocoCoverageProvider.Coverage c = provider.sample();
                StatusReporter.recordCoverage(c.covered, c.total);
            } catch (Throwable t) {
                // Agent may not be up yet, or the socket blipped; keep trying quietly.
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
