package dev.modtransmuder.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerTest {

    /** Wraps a logger writing into an in-memory buffer for assertions. */
    private record Captured(Logger logger, ByteArrayOutputStream out) {
        private Captured {
            // nothing extra
        }
    }

    private static Captured newLogger(Logger.Level threshold) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Logger logger = new Logger(threshold, new PrintStream(bos, true, StandardCharsets.UTF_8));
        return new Captured(logger, bos);
    }

    @Test
    void defaultInfoSuppressesDebug() {
        Captured c = newLogger(Logger.Level.INFO);
        c.logger().debug("dbg");
        assertFalse(c.out().toString().contains("dbg"));
    }

    @Test
    void quietWarnThresholdSuppressesInfo() {
        Captured c = newLogger(Logger.Level.WARN);
        c.logger().info("inf");
        c.logger().warn("wrn");
        assertFalse(c.out().toString().contains("inf"));
        assertTrue(c.out().toString().contains("wrn"));
    }

    @Test
    void verboseDebugEmitsEverything() {
        Captured c = newLogger(Logger.Level.DEBUG);
        c.logger().error("err");
        c.logger().info("inf");
        c.logger().debug("dbg");
        String all = c.out().toString();
        assertTrue(all.contains("err"));
        assertTrue(all.contains("inf"));
        assertTrue(all.contains("dbg"));
    }

    @Test
    void changeThresholdAtRuntime() {
        Captured c = newLogger(Logger.Level.INFO);
        c.logger().debug("first-debug-marker");
        c.logger().setThreshold(Logger.Level.DEBUG);
        c.logger().debug("second-debug-marker");
        String all = c.out().toString();
        assertFalse(all.contains("first-debug-marker"));
        assertTrue(all.contains("second-debug-marker"));
    }
}
