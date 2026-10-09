package dev.modtransmuder.stage.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ValidateStage} with a fake {@link BuildRunner} — no
 * real Gradle process is started here.
 */
class ValidateStageTest {

    @Test
    void exitCodeZeroMapsToSuccess() {
        FakeRunner runner = new FakeRunner(new BuildOutcome(0, "", false));

        StageResult result = new ValidateStage(runner).run(context(tmp("out")));

        assertEquals(Status.SUCCESS, result.status(), result.message());
    }

    @Test
    void nonZeroExitCodeMapsToFailureWithTail() {
        FakeRunner runner = new FakeRunner(new BuildOutcome(1, "line1\nline2\nBUILD FAILED", false));

        StageResult result = new ValidateStage(runner).run(context(tmp("out")));

        assertEquals(Status.FAILED, result.status());
        assertTrue(result.message().contains("exit code 1"), result.message());
        assertTrue(result.message().contains("line2"), "message must include the output tail");
        assertTrue(result.message().contains("BUILD FAILED"), "message must include the output tail");
    }

    @Test
    void timeoutMapsToFailureWithTimedOutMessage() {
        FakeRunner runner = new FakeRunner(new BuildOutcome(-1, "", true));

        StageResult result = new ValidateStage(runner).run(context(tmp("out")));

        assertEquals(Status.FAILED, result.status());
        assertTrue(result.message().contains("timed out"), result.message());
    }

    // ---------------- helpers ----------------

    private static Path tmp(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), "validate-test-" + name);
    }

    private static PipelineContext context(Path out) {
        Config config = new Config(
                "https://example.com/template.zip", out.toString(), "in",
                new ObjectMapper().createArrayNode(), true,
                null, null, null, null);
        return new PipelineContext(config, PathResolver.resolve(config), new Logger(), false);
    }

    /** Fake {@link BuildRunner} returning a canned outcome and recording args. */
    static final class FakeRunner implements BuildRunner {
        private final BuildOutcome outcome;
        private Path seenProjectDir;
        private Duration seenTimeout;

        FakeRunner(BuildOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public BuildOutcome run(Path projectDir, Duration timeout) {
            this.seenProjectDir = projectDir;
            this.seenTimeout = timeout;
            return outcome;
        }

        Path seenProjectDir() {
            return seenProjectDir;
        }

        Duration seenTimeout() {
            return seenTimeout;
        }
    }
}
