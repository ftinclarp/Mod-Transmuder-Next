package dev.modtransmuder.stage.validate;

/**
 * Immutable outcome of a build run (ARCHITECTURE §5 {@code BuildOutcome}).
 *
 * @param exitCode   the process exit code; meaningful only when
 *                   {@code !timedOut}
 * @param tailOutput up to {@link ProcessBuildRunner#TAIL_LINES} trailing lines
 *                   of the build's combined output; may be empty
 * @param timedOut   true when the build was killed after the requested timeout
 */
public record BuildOutcome(int exitCode, String tailOutput, boolean timedOut) {
}
