package examples.targets;

import runner.api.InputReceiver;
import runner.api.IterationTarget;

import java.nio.charset.StandardCharsets;

public class HttpRequestLineTarget implements IterationTarget, InputReceiver {
    private byte[] last;

    @Override
    public void accept(byte[] data) { this.last = data; }

    @Override
    public void executeIteration() {
        String line = last != null ? new String(last, StandardCharsets.US_ASCII) : "GET / HTTP/1.1\r\n";
        examples.fuzzapps.HttpRequestLineParser.parse(line);
    }
}

