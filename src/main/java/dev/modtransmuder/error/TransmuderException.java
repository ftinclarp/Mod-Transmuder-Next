package dev.modtransmuder.error;

/**
 * Root of the tool's typed exception hierarchy (ARCHITECTURE §7). All
 * failures that map to a non-zero exit code derive from this class; nothing
 * outside it should be used to signal a deliberate run failure.
 */
public class TransmuderException extends RuntimeException {

    public TransmuderException(String message) {
        super(message);
    }

    public TransmuderException(String message, Throwable cause) {
        super(message, cause);
    }
}
