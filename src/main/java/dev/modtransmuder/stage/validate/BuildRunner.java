package dev.modtransmuder.stage.validate;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs a build in a project directory and reports its outcome. The seam that
 * lets {@link ValidateStage} be unit-tested without running a real Gradle
 * process.
 */
public interface BuildRunner {

    /**
     * Run a build inside {@code projectDir}, streaming output to the caller's
     * logger (see {@link ProcessBuildRunner#setLogger}).
     *
     * @param projectDir the project to build (its Gradle wrapper is invoked)
     * @param timeout    maximum wall-clock time; on expiry the process is
     *                   killed and {@link BuildOutcome#timedOut()} is true
     * @return the build outcome; never {@code null}
     */
    BuildOutcome run(Path projectDir, Duration timeout);
}
