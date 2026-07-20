package examples.targets;

import runner.api.CrashClassifier;
import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.nio.charset.StandardCharsets;

public class CalculatorFuzzTarget implements IterationTarget, InputReceiver, CrashClassifier {
    private byte[] last;

    /**
     * The calculator deliberately throws IllegalArgumentException / IllegalStateException to
     * reject malformed input ("bad char", "mismatched parenthesis", "bad expr") — those are
     * expected, not crashes. Anything else (e.g. a NoSuchElementException from popping an empty
     * stack on a truncated expression) is an unhandled edge = a genuine crash worth surfacing.
     */
    @Override
    public boolean isExpected(Throwable t) {
        return t instanceof IllegalArgumentException || t instanceof IllegalStateException;
    }

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

