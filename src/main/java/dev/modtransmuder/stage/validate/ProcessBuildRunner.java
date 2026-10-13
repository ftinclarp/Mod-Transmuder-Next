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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.io.UncheckedIOException;

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

        String jdk25 = detectJdk25();
        if (jdk25 == null) {
            throw new IllegalStateException(
                    "fabric-loom 1.18 requires JDK 25 for the Gradle daemon; none found on this system");
        }
        logger.info("stage-validate: using JDK 25 at " + jdk25);
        applyJdkEnvironment(pb, jdk25);

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
     * Configures the sub-process to run on a JDK 25 installation.
     *
     * <p>fabric-loom 1.18 requires the Gradle daemon itself to run on JDK 25;
     * the mod sources are compiled for Java 21 via the toolchain inside the
     * generated build. So we only need to point the daemon at a JDK 25.
     */
    private static void applyJdkEnvironment(ProcessBuilder pb, String jdk25) {
        Map<String, String> env = pb.environment();
        Path home = Path.of(jdk25);
        env.put("JAVA_HOME", home.toString());
        String existingPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        String bin = home.resolve("bin").toString();
        env.put("PATH", bin + java.io.File.pathSeparator + existingPath);
        // Unix-style layout has bin/java (no .exe); JAVA_HOME alone suffices on
        // both platforms because Gradle's launch scripts prefer JAVA_HOME.
    }

    /**
     * Locates a JDK 25 installation. Search order:
     * <ol>
     *   <li>{@code JAVA_HOME_25} env var</li>
     *   <li>{@code C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot}</li>
     *   <li>any {@code jdk-25*} dir directly under {@code C:\Program Files\Eclipse Adoptium}</li>
     *   <li>any dir containing {@code jdk-25} under {@code C:\Program Files\}</li>
     *   <li>{@code TRANSMUDER_JDK25} env fallback</li>
     * </ol>
     * Returns the absolute path of the first candidate that looks like a JDK
     * (i.e. has a {@code bin/java} or {@code bin/java.exe}); {@code null} when
     * none matches.
     */
    static String detectJdk25() {
        return detectJdk25(System.getenv());
    }

    /** Testable core: builds and checks candidate paths from {@code env} + well-known locations. */
    static String detectJdk25(Map<String, String> env) {
        return firstJdk25(jdk25Candidates(env));
    }

    /**
     * Returns the first candidate that looks like a JDK (has {@code bin/java}
     * or {@code bin/java.exe}), as an absolute path string, or {@code null}
     * when none do. Exposed package-private for hermetic testing with a mocked
     * candidate list.
     */
    static String firstJdk25(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (looksLikeJdk(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /** The ordered JDK 25 candidate directories, deduplicated. */
    static List<Path> jdk25Candidates(Map<String, String> env) {
        List<Path> candidates = new ArrayList<>();
        addCandidate(candidates, env.get("JAVA_HOME_25"));
        addCandidate(candidates, "C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot");
        addAdoptiumCandidates(candidates);
        addProgramFilesScan(candidates);
        addCandidate(candidates, env.get("TRANSMUDER_JDK25"));
        return candidates;
    }

    private static void addCandidate(List<Path> candidates, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Path p = Path.of(value);
        if (!candidates.contains(p)) {
            candidates.add(p);
        }
    }

    /** Any {@code jdk-25*} directory directly under {@code C:\Program Files\Eclipse Adoptium}. */
    private static void addAdoptiumCandidates(List<Path> candidates) {
        Path adoptium = Path.of("C:/Program Files/Eclipse Adoptium");
        if (!Files.isDirectory(adoptium)) {
            return;
        }
        try (var stream = Files.list(adoptium)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("jdk-25"))
                    .sorted()
                    .forEach(p -> addCandidate(candidates, p.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Any directory containing {@code jdk-25} in its name discovered anywhere
     * under {@code C:\Program Files\} at depth &le; 5, covering vendor layouts
     * such as {@code Microsoft\jdk-25...} and {@code Adoptium\jdk-25...}. Runs a
     * tolerant scan: unreadable subdirectories (common under Program Files) are
     * silently skipped rather than failing detection.
     */
    private static void addProgramFilesScan(List<Path> candidates) {
        Path programFiles = Path.of("C:/Program Files");
        if (!Files.isDirectory(programFiles)) {
            return;
        }
        scanProgramFiles(candidates, programFiles, 0);
    }

    private static void scanProgramFiles(List<Path> candidates, Path dir, int depth) {
        if (depth > 5) {
            return;
        }
        List<Path> children;
        try (var stream = Files.list(dir)) {
            children = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return; // unreadable dir — skip
        }
        for (Path child : children) {
            if (child.getFileName().toString().toLowerCase(Locale.ROOT).contains("jdk-25")) {
                addCandidate(candidates, child.toString());
            }
            scanProgramFiles(candidates, child, depth + 1);
        }
    }

    private static boolean looksLikeJdk(Path candidate) {
        return Files.isDirectory(candidate) && (Files.isRegularFile(candidate.resolve("bin/java.exe"))
                || Files.isRegularFile(candidate.resolve("bin/java")));
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
