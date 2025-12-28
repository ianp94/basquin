package runner.api;

/**
 * Optional interface for targets that can consume fuzz inputs from the harness.
 */
public interface InputReceiver {
    void accept(byte[] data) throws Exception;
}

