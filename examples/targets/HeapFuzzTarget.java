package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

public class HeapFuzzTarget implements IterationTarget, InputReceiver {
    private byte[] last;
    private byte[] hold; // retained to increase heap delta across iteration

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() {
        int kb = deriveKb(last);
        int capKb = Integer.getInteger("examples.heap.maxKb", 2048); // default cap 2MB
        kb = Math.min(Math.max(kb, 0), capKb);
        if (kb > 0) {
            hold = new byte[kb * 1024];
            // touch a few pages
            for (int i = 0; i < hold.length; i += 4096) hold[i] = (byte) (i & 0xFF);
            try { Thread.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private static int deriveKb(byte[] data) {
        if (data == null || data.length == 0) return 0;
        // Parse first ASCII integer as kilobytes
        int val = 0; boolean found = false;
        for (byte b : data) {
            if (b >= '0' && b <= '9') { found = true; val = (val * 10) + (b - '0'); if (val > 1_000_000) { val = 1_000_000; break; } }
            else if (found) break;
        }
        if (!found) {
            int sum = 0;
            for (byte b : data) sum += (b & 0xFF);
            val = (sum % 2048); // up to 2MB
        }
        return val;
    }
}

