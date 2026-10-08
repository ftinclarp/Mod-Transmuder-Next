package dev.modtransmuder.util;

import java.io.PrintStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Tiny hand-rolled stderr logger (ARCHITECTURE §7). There is deliberately no
 * logging framework. All human-readable output goes to {@code stderr} so that
 * {@code stdout} stays clean for the machine-readable JSON summary.
 *
 * <p>Methods are {@code synchronized} on the instance so messages do not
 * interleave mid-line; no other thread-safety guarantees are offered.
 */
public final class Logger {

    /** Severity levels, lowest to highest. */
    public enum Level {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3);

        private final int rank;

        Level(int rank) {
            this.rank = rank;
        }
    }

    private final Object lock = new Object();
    private final PrintStream out;
    private Level threshold;

    /** Default level is INFO (ARCHITECTURE §7). */
    public Logger() {
        this(Level.INFO);
    }

    public Logger(Level threshold) {
        this(threshold, System.err);
    }

    /** Test seam: writes to an arbitrary stream instead of stderr. */
    public Logger(Level threshold, PrintStream out) {
        this.threshold = threshold;
        this.out = out;
    }

    /** Quiet → {@code WARN+}; verbose → {@code DEBUG+}. */
    public void setThreshold(Level threshold) {
        this.threshold = threshold;
    }

    public Level getThreshold() {
        return threshold;
    }

    public void debug(String msg) {
        log(Level.DEBUG, msg);
    }

    public void info(String msg) {
        log(Level.INFO, msg);
    }

    public void warn(String msg) {
        log(Level.WARN, msg);
    }

    public void error(String msg) {
        log(Level.ERROR, msg);
    }

    public void error(String msg, Throwable t) {
        log(Level.ERROR, msg);
        if (t != null) {
            t.printStackTrace(out);
        }
    }

    private void log(Level level, String msg) {
        if (level.rank < threshold.rank) {
            return;
        }
        synchronized (lock) {
            String line = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                    + " " + level.name() + " " + msg;
            out.println(line);
        }
    }
}
