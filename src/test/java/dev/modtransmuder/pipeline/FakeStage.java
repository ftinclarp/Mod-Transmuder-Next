package dev.modtransmuder.pipeline;

/**
 * Test-only fake stage: records whether {@link #run} was invoked and returns
 * a predetermined result. Used ONLY by {@link StageSequencerTest}. Kept in
 * src/test, never in src/main.
 */
final class FakeStage implements Stage {

    private final String id;
    private final Status status;
    private final Throwable throwable;
    private boolean ran;

    FakeStage(String id, Status status) {
        this(id, status, null);
    }

    FakeStage(String id, Status status, Throwable throwable) {
        this.id = id;
        this.status = status;
        this.throwable = throwable;
    }

    @Override
    public String id() {
        return id;
    }

    boolean ran() {
        return ran;
    }

    @Override
    public StageResult run(PipelineContext ctx) {
        ran = true;
        return new StageResult(id, status, "msg-" + id, throwable, 0L);
    }
}
