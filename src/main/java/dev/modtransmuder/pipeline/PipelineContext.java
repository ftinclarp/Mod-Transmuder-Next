package dev.modtransmuder.pipeline;

import dev.modtransmuder.config.Config;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver.ResolvedPaths;

import java.nio.file.Path;

/**
 * Immutable context handed to every stage (ARCHITECTURE §4, §5). Carries the
 * resolved config and paths plus the logger. The sequencer owns any
 * accumulation of stage results; this class deliberately holds none.
 */
public final class PipelineContext {

    private final Config config;
    private final ResolvedPaths paths;
    private final Logger logger;
    private Path lastZipPath;
    private final boolean keepStaging;

    public PipelineContext(Config config, ResolvedPaths paths, Logger logger, boolean keepStaging) {
        this.config = config;
        this.paths = paths;
        this.logger = logger;
        this.keepStaging = keepStaging;
    }

    public Config config() {
        return config;
    }

    public ResolvedPaths paths() {
        return paths;
    }

    public Logger logger() {
        return logger;
    }

    /**
     * Absolute path of the downloaded template zip, set by {@code
     * stage-download} and consumed by {@code stage-unpack}. {@code null}
     * until download has run.
     */
    public Path lastZipPath() {
        return lastZipPath;
    }

    public void setLastZipPath(Path lastZipPath) {
        this.lastZipPath = lastZipPath;
    }

    public boolean keepStaging() {
        return keepStaging;
    }
}
