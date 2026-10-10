package dev.modtransmuder.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.util.PathResolver.ResolvedPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathResolverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Config config(String input, String output, String cache) {
        try {
            JsonNode rules = JSON.readTree("[]");
            return new Config(
                    "https://example.com/template.zip",
                    output,
                    input,
                    rules,
                    true,
                    cache,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Documents the deliberate choice (see {@link PathResolver}'s Javadoc):
     * relative config paths resolve against the JVM's working directory
     * ({@code user.dir}), not against the config file's location.
     */
    @Test
    void relativePathsResolveAgainstProcessCwd() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cwdOfJvm = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        // For the Gradle test worker these are the same; the rule is what matters.
        assertEquals(cwd, cwdOfJvm);

        ResolvedPaths p = PathResolver.resolve(config("in/mods", "out", "cache/external"));
        assertEquals(cwd.resolve("in/mods").normalize(), p.inputDir());
        assertEquals(cwd.resolve("out").normalize(), p.outputDir());
        assertEquals(cwd.resolve("cache/external").normalize(), p.cacheDir());
        assertEquals(p.outputDir(), p.workDir(), "workDir defaults to outputDir");
    }

    @Test
    void absolutePathsArePreserved(@TempDir Path tmp) {
        Path absInput = tmp.resolve("src");
        Path absOutput = tmp.resolve("out");
        ResolvedPaths p = PathResolver.resolve(config(absInput.toString(), absOutput.toString(), null));
        assertEquals(absInput.toAbsolutePath().normalize(), p.inputDir());
        assertEquals(absOutput.toAbsolutePath().normalize(), p.outputDir());
        assertEquals(p.outputDir(), p.workDir());
    }

    @Test
    void defaultCacheDirIsUnderSystemTemp() {
        ResolvedPaths p = PathResolver.resolve(config("in", "out", null));
        Path expected = Path.of(System.getProperty("java.io.tmpdir"), "mod-transmuder-next", "cache");
        assertEquals(expected.toAbsolutePath().normalize(), p.cacheDir());
    }
}
