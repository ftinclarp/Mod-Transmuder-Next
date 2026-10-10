package dev.modtransmuder.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageSequencerTest {

    private final StageSequencer sequencer = new StageSequencer();

    private static PipelineContext context() {
        Config config = new Config(
                "https://example.com/t.zip", "out", "in",
                new ObjectMapper().createArrayNode(), true,
                null, null, null, null, null, null, null, null, null);
        return new PipelineContext(config, PathResolver.resolve(config), new Logger(), false);
    }

    @Test
    void stopIfFailTrueStopsAfterSecondStageFailure() {
        FakeStage first = new FakeStage("first", Status.SUCCESS);
        FakeStage second = new FakeStage("second", Status.FAILED);
        FakeStage third = new FakeStage("third", Status.SUCCESS);

        List<StageResult> results = sequencer.run(List.of(first, second, third), context(), true);

        assertEquals(2, results.size());
        assertEquals(Status.SUCCESS, results.get(0).status());
        assertEquals(Status.FAILED, results.get(1).status());
        assertFalse(third.ran(), "third stage must not run when stopIfFail=true");
    }

    @Test
    void stopIfFailFalseRunsAllStages() {
        FakeStage first = new FakeStage("first", Status.SUCCESS);
        FakeStage second = new FakeStage("second", Status.FAILED);
        FakeStage third = new FakeStage("third", Status.SUCCESS);

        List<StageResult> results = sequencer.run(List.of(first, second, third), context(), false);

        assertEquals(3, results.size());
        assertEquals(Status.FAILED, results.get(1).status());
        assertTrue(third.ran(), "third stage must run when stopIfFail=false");
    }
}
