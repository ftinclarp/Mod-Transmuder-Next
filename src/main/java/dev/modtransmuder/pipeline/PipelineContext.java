package dev.modtransmuder.pipeline;

import dev.modtransmuder.config.Config;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver.ResolvedPaths;

/**
 * Immutable context handed to every stage (ARCHITECTURE §4, §5). Carries the
 * resolved config and paths plus the logger. The sequencer owns any
 * accumulation of stage results; this class deliberately holds none.
 */
public final class PipelineContext {

    private final Config config;
    private final ResolvedPaths paths;
    private final Logger logger;

    public PipelineContext(Config config, ResolvedPaths paths, Logger logger) {
        this.config = config;
        this.paths = paths;
        this.logger = logger;
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
}
