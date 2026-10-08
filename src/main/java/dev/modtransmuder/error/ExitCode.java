package dev.modtransmuder.error;

/**
 * Process exit codes, mapping one-to-one to the list in ARCHITECTURE §7.
 */
public enum ExitCode {
    OK(0),
    GENERIC(1),
    CONFIG(2),
    DOWNLOAD(3),
    UNPACK(4),
    TRANSFORM(5),
    VALIDATION(6);

    private final int value;

    ExitCode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
