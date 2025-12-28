package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.nio.charset.StandardCharsets;

public class CalculatorFuzzTarget implements IterationTarget, InputReceiver {
    private byte[] last;

    @Override
    public void accept(byte[] data) {
        this.last = data;
    }

    @Override
    public void executeIteration() {
        String s = last != null ? new String(last, StandardCharsets.UTF_8) : "1+1";
        // Exercise the simple calculator; exceptions are considered interesting by the fuzz harness
        int out = examples.fuzzapps.SimpleCalculator.eval(s);
        // Optionally touch output to avoid dead-code elimination (no-op)
        if (out == 0x7fffffff) {
            System.out.print("");
        }
    }
}

