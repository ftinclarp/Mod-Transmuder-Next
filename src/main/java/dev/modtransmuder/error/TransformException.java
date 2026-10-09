package dev.modtransmuder.error;

/**
 * Stage failure for {@code stage-transform}. Maps to {@link ExitCode#TRANSFORM} (5).
 */
public final class TransformException extends TransmuderException {

    public TransformException(String message) {
        super(message);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
