package dev.modtransmuder.stage.validate;

import dev.modtransmuder.util.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link BuildRunner} backed by {@link ProcessBuilder}.
 *
 * <p>Invokes {@code gradlew.bat} on Windows, {@code ./gradlew} elsewhere,
 * with {@code build --no-daemon}. The process's combined stdout+stderr is
 * streamed line by line to the logger at DEBUG level and the last
 * {@link #TAIL_LINES} lines are retained for failure diagnostics.
 */
public final class ProcessBuildRunner implements BuildRunner {

    /** Number of trailing output lines kept for the failure message. */
    static final int TAIL_LINES = 50;
    static final int GRACE_MS = 2_000;

    private Logger logger = new Logger();

    /** The logger used for DEBUG streaming of subprocess output. */
    public void setLogger(Logger logger) {
        this.logger = logger != null ? logger : new Logger();
    }

    @Override
    public BuildOutcome run(Path projectDir, Duration timeout) {
        List<String> command = wrapperCommand(projectDir);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true); // combine stdout and stderr

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start gradle in " + projectDir + ": " + e.getMessage(), e);
        }

        TailCollector collector = new TailCollector(TAIL_LINES);
        Thread reader = new Thread(() -> drain(process, collector), "validate-stdout");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyForcibly(process);
            return new BuildOutcome(-1, collector.snapshot(), true);
        }

        if (!finished) {
            // Give it a short grace, then kill.
            if (!process.isAlive() || awaitExit(process, GRACE_MS)) {
                try {
                    reader.join(GRACE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new BuildOutcome(process.exitValue(), collector.snapshot(), false);
            }
            destroyForcibly(process);
            try {
                reader.join(GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new BuildOutcome(-1, collector.snapshot(), true);
        }

        try {
            reader.join(GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new BuildOutcome(process.exitValue(), collector.snapshot(), false);
    }

    private void drain(Process process, TailCollector collector) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                logger.debug("[validate] " + line);
                collector.add(line);
            }
        } catch (IOException e) {
            logger.debug("[validate] output stream closed: " + e.getMessage());
        }
    }

    private static boolean awaitExit(Process process, long ms) {
        try {
            return process.waitFor(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void destroyForcibly(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Platform-appropriate Gradle wrapper invocation: {@code gradlew.bat} on
     * Windows, {@code gradlew} on POSIX, always with {@code build --no-daemon}.
     */
    private static List<String> wrapperCommand(Path projectDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        Path wrapper = windows ? projectDir.resolve("gradlew.bat") : projectDir.resolve("gradlew");
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalStateException("gradle wrapper not found: " + wrapper);
        }
        List<String> cmd = new ArrayList<>();
        if (windows) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(wrapper.toString());
        } else {
            cmd.add("/bin/sh");
            cmd.add(wrapper.toString());
        }
        cmd.add("build");
        cmd.add("--no-daemon");
        return List.copyOf(cmd);
    }

    /** Bounded ring buffer keeping the last {@code max} lines. */
    static final class TailCollector {
        private final int max;
        private final Deque<String> lines = new ArrayDeque<>();

        TailCollector(int max) {
            this.max = max;
        }

        synchronized void add(String line) {
            if (lines.size() == max) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized String snapshot() {
            return String.join(System.lineSeparator(), lines);
        }
    }
}
