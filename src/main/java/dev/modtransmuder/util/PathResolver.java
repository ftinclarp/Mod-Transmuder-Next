package dev.modtransmuder.util;

import dev.modtransmuder.config.Config;
import java.nio.file.Path;

/**
 * Resolves a {@link Config} into absolute {@link Path}s (ARCHITECTURE §5).
 * Only a pure path computation: it never touches the filesystem.
 *
 * <p>Resolution rule: every relative path in the config resolves against the
 * <em>process working directory</em> ({@code user.dir}), not against the
 * config file's directory. This is a deliberate, documented choice so that a
 * run launched from any directory behaves the same way for the same config —
 * the CLI contract is "run the tool from a directory, point it at a config".
 */
public final class PathResolver {

    private PathResolver() {
    }

    /**
     * Resolved absolute path set (immutable). The derived {@code workDir}
     * defaults to the output dir; {@code cacheDir} defaults to a
     * project-owned directory under the system temp dir.
     */
    public record ResolvedPaths(
            Path inputDir,
            Path outputDir,
            Path cacheDir,
            Path workDir) {

        public ResolvedPaths {
            inputDir = inputDir.toAbsolutePath().normalize();
            outputDir = outputDir.toAbsolutePath().normalize();
            cacheDir = cacheDir.toAbsolutePath().normalize();
            workDir = workDir.toAbsolutePath().normalize();
        }
    }

    public static ResolvedPaths resolve(Config config) {
        Path outputDir = toAbsolute(config.transmudationOutput());
        Path cacheDir = config.cacheDir() == null
                ? defaultCacheDir()
                : toAbsolute(config.cacheDir());
        return new ResolvedPaths(
                toAbsolute(config.transmudationInput()),
                outputDir,
                cacheDir,
                outputDir);
    }

    private static Path toAbsolute(String p) {
        return Path.of(p).toAbsolutePath().normalize();
    }

    private static Path defaultCacheDir() {
        String tmpRoot = System.getProperty("java.io.tmpdir", "tmp");
        return Path.of(tmpRoot, "mod-transmuder-next", "cache");
    }
}
