package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end stage 4 over minimal temp in-mods/ and out/: checks the mod is
 * discovered under the input dir, the template example package is removed, the
 * build file gets the JitPack repo + layer dependency, and fabric.mod.json is
 * generated from the parsed metadata.
 */
class TransformStageIntegrationTest {

    @TempDir
    Path tmp;

    private static final String MC_MOD_INFO = """
            {
              "modListVersion": 2,
              "modList": [{
                "modid": "mtnexample",
                "name": "MTN Example",
                "version": "1.0",
                "description": "test mod",
                "authorList": ["MTN"]
              }]
            }
            """;

    @Test
    void transformsMinimalInputIntoMinimalOutput() throws IOException {
        // --- input: in-mods/some-mod/src/main/resources/mcmod.info (descend one level) ---
        Path inMods = tmp.resolve("in-mods");
        Path modRoot = inMods.resolve("some-mod");
        Path mcmodInfo = modRoot.resolve("src/main/resources/mcmod.info");
        Files.createDirectories(mcmodInfo.getParent());
        Files.writeString(mcmodInfo, MC_MOD_INFO);

        // --- output: minimal template tree ---
        Path out = tmp.resolve("out");
        Path buildFile = out.resolve("build.gradle");
        Files.createDirectories(out.resolve("src/main/java/com/example"));
        Files.createDirectories(out.resolve("src/main/resources"));
        Files.writeString(buildFile, "repositories {\n}\n\ndependencies {\n}\n");
        Files.writeString(out.resolve("src/main/resources/fabric.mod.json"), "{}\n");
        Files.writeString(out.resolve("src/main/java/com/example/ExampleMod.java"),
                "package com.example;\n\nimport net.fabricmc.api.ModInitializer;\npublic class ExampleMod {}\n");

        Config config = new Config(
                "https://example.com/template.zip", out.toString(), inMods.toString(),
                new ObjectMapper().createArrayNode(), true, null, null, null, null);
        PipelineContext ctx = new PipelineContext(config, PathResolver.resolve(config), new Logger(), false);

        StageResult result = new TransformStage().run(ctx);

        assertEquals(Status.SUCCESS, result.status(), result.message());
        assertFalse(Files.exists(out.resolve("src/main/java/com/example")),
                "template example package must be deleted");

        // fabric.mod.json generated with correct identity + entrypoint
        String fabric = Files.readString(out.resolve("src/main/resources/fabric.mod.json"));
        JsonAssert.assertField(fabric, "id", "mtnexample");
        JsonAssert.assertField(fabric, "name", "MTN Example");
        JsonAssert.assertField(fabric, "version", "1.0");
        assertTrue(fabric.contains("mtn.forge_layer.FabricEntry"),
                "entrypoint must point at the layer's FabricEntry");

        // build wired with JitPack + layer dependency
        String build = Files.readString(buildFile);
        assertTrue(build.contains("https://jitpack.io"), "JitPack maven repo missing");
        assertTrue(build.contains("com.github.ftinclarp:MTN-forge-layer:v1.0.1"), "layer dependency missing");
    }

    /** Structurally assert a top-level string field exists with an expected value in a JSON file. */
    static final class JsonAssert {
        private JsonAssert() {
        }

        static void assertField(String json, String key, String expected) throws IOException {
            var node = new ObjectMapper().readTree(json);
            assertTrue(node.has(key), "json missing field '" + key + "': " + json);
            assertEquals(expected, node.get(key).asText());
        }
    }
}
