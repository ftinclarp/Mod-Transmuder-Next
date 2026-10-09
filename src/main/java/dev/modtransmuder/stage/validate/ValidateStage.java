package dev.modtransmuder.stage.validate;

import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.Stage;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code stage-validate} (ARCHITECTURE §5). Runs a Gradle build in the output
 * tree to prove it compiles, and maps the {@link BuildOutcome} into a
 * {@link StageResult}. Never throws out of {@code run()} — everything is
 * wrapped in a stage result.
 */
public final class ValidateStage implements Stage {

    /** Default wall-clock timeout when config {@code timeoutSeconds} is absent. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    private final BuildRunner buildRunner;

    /** Production wiring: a real {@link ProcessBuildRunner}. */
    public ValidateStage() {
        this(new ProcessBuildRunner());
    }

    /** Test seam: inject a fake {@link BuildRunner}. */
    public ValidateStage(BuildRunner buildRunner) {
        this.buildRunner = Objects.requireNonNull(buildRunner, "buildRunner");
    }

    @Override
    public String id() {
        return "stage-validate";
    }

    @Override
    public StageResult run(PipelineContext ctx) {
        try {
            if (buildRunner instanceof ProcessBuildRunner prb) {
                // Stream subprocess output into the run's logger at DEBUG.
                prb.setLogger(ctx.logger());
            }
            Duration timeout = effectiveTimeout(ctx);
            BuildOutcome outcome = buildRunner.run(ctx.paths().outputDir(), timeout);

            if (outcome.timedOut()) {
                return new StageResult(id(), Status.FAILED,
                        "gradle build timed out after " + timeout.getSeconds() + "s", null, 0L);
            }
            if (outcome.exitCode() == 0) {
                return new StageResult(id(), Status.SUCCESS, "gradle build succeeded", null, 0L);
            }
            return failureResult(outcome);
        } catch (RuntimeException e) {
            return new StageResult(id(), Status.FAILED,
                    "validate failed: " + e.getMessage(), e, 0L);
        }
    }

    private StageResult failureResult(BuildOutcome outcome) {
        String tail = outcome.tailOutput() == null ? "" : outcome.tailOutput().strip();
        String message = "gradle build failed with exit code " + outcome.exitCode()
                + "\n--- last output ---\n" + tail;
        return new StageResult(id(), Status.FAILED, message, null, 0L);
    }

    private static Duration effectiveTimeout(PipelineContext ctx) {
        Integer configured = ctx.config().timeoutSeconds();
        return configured != null ? Duration.ofSeconds(configured) : DEFAULT_TIMEOUT;
    }
}
