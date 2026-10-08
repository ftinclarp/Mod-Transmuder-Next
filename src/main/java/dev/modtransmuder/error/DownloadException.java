package dev.modtransmuder.error;

/**
 * Stage failure for {@code stage-download}. Maps to {@link ExitCode#DOWNLOAD} (3).
 */
public final class DownloadException extends TransmuderException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
