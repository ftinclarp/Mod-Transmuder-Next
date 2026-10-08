package dev.modtransmuder.pipeline;

/**
 * A pipeline stage (ARCHITECTURE §5). Implementations provide an id for
 * selection and a {@code run} that produces a {@link StageResult}.
 */
public interface Stage {

    /** Stable id, one of {@code stage-download} / {@code stage-unpack} / {@code stage-transform} / {@code stage-validate}. */
    String id();

    /** Execute this stage against the shared context. Must not throw; failures are reported via {@link StageResult}. */
    StageResult run(PipelineContext ctx);
}
