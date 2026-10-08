package dev.modtransmuder.pipeline;

/**
 * Terminal outcome of one stage run (ARCHITECTURE §4, §5).
 *
 * @param stageId   the {@link Stage#id()} that produced this result
 * @param status    SUCCESS / FAILED / SKIPPED
 * @param message   free-text message; may be {@code null}
 * @param throwable the cause when {@link Status#FAILED}; otherwise {@code null}
 * @param durationMs measured wall-clock duration of the stage run
 */
public record StageResult(
        String stageId,
        Status status,
        String message,
        Throwable throwable,
        long durationMs) {

    public StageResult {
        if (stageId == null || status == null) {
            throw new NullPointerException("stageId and status must not be null");
        }
    }
}
