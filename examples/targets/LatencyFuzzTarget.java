package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

public class LatencyFuzzTarget implements IterationTarget, InputReceiver {
    private byte[] last;

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() {
        int ms = deriveDelayMs(last);
        if (last == null) {
            String prop = System.getProperty("examples.latency.ms");
            if (prop != null) {
                try { ms = Math.max(ms, Integer.parseInt(prop)); } catch (NumberFormatException ignored) {}
            }
        }
        if (ms > 0) {
            try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private static int deriveDelayMs(byte[] data) {
        if (data == null || data.length == 0) return 0;
        // Try to parse first ASCII integer (e.g., "250") as milliseconds
        int val = 0; boolean found = false;
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b >= '0' && b <= '9') {
                found = true;
                val = (val * 10) + (b - '0');
                if (val > 10_000) { val = 10_000; break; }
            } else if (found) {
                break;
            }
        }
        if (!found) {
            // Fallback: map bytes to a bounded delay
            int sum = 0;
            for (byte b : data) sum += (b & 0xFF);
            val = sum % 1000; // <= 999ms
        }
        return Math.max(0, Math.min(val, 2000));
    }
}
