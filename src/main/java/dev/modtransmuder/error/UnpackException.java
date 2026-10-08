package dev.modtransmuder.error;

/**
 * Stage failure for {@code stage-unpack}; also thrown by {@link
 * dev.modtransmuder.util.ZipUtil} on zip-slip. Maps to {@link
 * ExitCode#UNPACK} (4).
 */
public final class UnpackException extends TransmuderException {

    public UnpackException(String message) {
        super(message);
    }

    public UnpackException(String message, Throwable cause) {
        super(message, cause);
    }
}
