package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.nio.charset.StandardCharsets;

public class JsonTokenizerTarget implements IterationTarget, InputReceiver {
    private byte[] last;

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() {
        String s = last != null ? new String(last, StandardCharsets.UTF_8) : "{}";
        examples.fuzzapps.JsonTokenizer.validate(s);
    }
}

