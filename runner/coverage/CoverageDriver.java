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
 * - {@code -Dbasquin.coverage.jacoco=host:port}  JaCoCo tcpserver (default localhost:6300), or a
 *   URL (e.g. {@code http://host:8080/__basquin/coverage}) for a build-time-injected target's HTTP
 *   coverage transport (DD-043 PR-4) -- see {@link JacocoCoverageProvider#parseEndpoints}
 * - {@code -Dbasquin.coverage.classes=<dir>}     directory of the app's .class files
 * - {@code -Dbasquin.coverage.intervalMs=<n>}    poll interval (default 1000)
 *
 * Coverage feedback as a *guidance* signal (mutating inputs toward new coverage) is the next
 * step; this slice establishes the signal and shows a real "% of code explored" in the panel.
 */
public final class CoverageDriver {

    public static void main(String[] args) throws Exception {
        String jacoco = System.getProperty("basquin.coverage.jacoco", "localhost:6300");
        String classes = System.getProperty("basquin.coverage.classes");
        long intervalMs = Long.getLong("basquin.coverage.intervalMs", 1000L);

        if (classes != null && !classes.isEmpty()) {
            Path classesDir = Paths.get(classes);
            JacocoCoverageProvider provider =
                    new JacocoCoverageProvider(JacocoCoverageProvider.parseEndpoints(jacoco), classesDir);
            Thread poller = new Thread(() -> pollLoop(provider, intervalMs), "Basquin-Coverage");
            poller.setDaemon(true);
            poller.start();
            System.out.println("[Basquin][Coverage] polling JaCoCo at " + jacoco + " against " + classes);
        } else {
            System.err.println("[Basquin][Coverage] -Dbasquin.coverage.classes not set; running without coverage");
        }

        runner.GenericRunner.main(args);
    }

    /** Package-private for testing: the all-skip termination path needs to be asserted directly. */
    static void pollLoop(JacocoCoverageProvider provider, long intervalMs) {
        while (true) {
            try {
                JacocoCoverageProvider.Coverage c = provider.sample();
                StatusReporter.recordCoverage(c.covered, c.total, c.sourcesResponded, c.sourcesTotal);
            } catch (JacocoCoverageProvider.CoverageUnmeasurableException e) {
                // F1 (approver finding, DD-043 PR-4): the provider's D1 all-skip guard -- EVERY
                // supplied class file failed to analyze. This is deterministic (the provider's class
                // bytes never change across a run), so silently retrying forever would just repeat
                // the identical failure on every future poll. Unlike an ordinary blip (below), this
                // must be surfaced, not swallowed: log it once, then stop polling -- the driven
                // campaign (runner.GenericRunner, on the main thread) continues without a coverage
                // signal rather than either dying for a display-only defect or spinning silently.
                System.err.println("[Basquin][Coverage] FATAL: coverage is unmeasurable -- "
                        + e.getMessage() + " -- stopping the coverage poller; the driven campaign"
                        + " continues without a coverage signal.");
                return;
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
