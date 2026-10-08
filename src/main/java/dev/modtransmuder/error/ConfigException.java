package dev.modtransmuder.error;

/**
 * Raised for any problem with the JSON config: unreadable file, malformed
 * JSON, unknown keys, missing or mistyped fields. Maps to {@link
 * ExitCode#CONFIG} (exit code 2) in {@code Main}.
 */
public final class ConfigException extends TransmuderException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
