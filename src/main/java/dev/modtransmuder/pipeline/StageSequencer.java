package dev.modtransmuder.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a list of {@link Stage}s in order (ARCHITECTURE §5). Each run's
 * wall-clock duration is measured here; any {@link Throwable} escaping a
 * stage is captured as a {@link Status#FAILED} result rather than propagated.
 *
 * <p>When {@code stopIfFail} is true, the run stops after the first FAILED
 * result; otherwise every stage runs. This method never throws.
 */
public final class StageSequencer {

    public List<StageResult> run(List<Stage> stages, PipelineContext ctx, boolean stopIfFail) {
        List<StageResult> results = new ArrayList<>();
        for (Stage stage : stages) {
            String id = stage.id();
            long startNanos = System.nanoTime();
            StageResult result;
            try {
                StageResult produced = stage.run(ctx);
                result = withDuration(produced, startNanos);
            } catch (Throwable t) {
                result = new StageResult(id, Status.FAILED, t.getMessage(),
                        t, elapsed(startNanos));
            }
            results.add(result);
            if (stopIfFail && result.status() == Status.FAILED) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static StageResult withDuration(StageResult produced, long startNanos) {
        return new StageResult(produced.stageId(), produced.status(),
                produced.message(), produced.throwable(), elapsed(startNanos));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
