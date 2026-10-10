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
 * build file gets the layer dependency (JitPack by default, mavenLocal when
 * {@code use_local_layer=true}), and fabric.mod.json is generated from the
 * parsed metadata with the {@code mtn:forge-mod-class} entrypoint.
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
        Path inMods = tmp.resolve("in-mods");
        Path out = tmp.resolve("out");
        Config config = new Config(
                "https://example.com/template.zip", out.toString(), inMods.toString(),
                new ObjectMapper().createArrayNode(), true, null, null, null, null,
                null, null, null);

        StageResult result = runTransform(config, inMods, out);
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
        assertTrue(fabric.contains("\"mtn:forge-mod-class\" : [ \"com.mtn.example.ExampleMod\" ]"),
                "fabric.mod.json must name the @Mod class under mtn:forge-mod-class: " + fabric);

        // default (use_local_layer absent/false): JitPack + default remote layer version
        String build = Files.readString(out.resolve("build.gradle"));
        assertTrue(build.contains("https://jitpack.io"), "JitPack maven repo missing");
        assertTrue(build.contains(
                "modImplementation \"com.github.ftinclarp:MTN-forge-layer:"
                        + TransformStage.DEFAULT_REMOTE_LAYER_VERSION + "\""),
                "layer must be a modImplementation dependency: " + build);
        assertFalse(build.contains("mavenLocal()"), "JitPack mode must not add mavenLocal(): " + build);
    }

    @Test
    void useLocalLayerEmitsMavenLocalAndLocalVersion() throws IOException {
        Path inMods = tmp.resolve("in-mods");
        Path out = tmp.resolve("out-local");
        Config config = new Config(
                "https://example.com/template.zip", out.toString(), inMods.toString(),
                new ObjectMapper().createArrayNode(), true, null, null, null, null,
                true, "1.0.2-SNAPSHOT", null);

        StageResult result = runTransform(config, inMods, out);
        assertEquals(Status.SUCCESS, result.status(), result.message());

        String build = Files.readString(out.resolve("build.gradle"));
        assertTrue(build.contains("mavenLocal()"),
                "local mode must emit mavenLocal(): " + build);
        assertTrue(build.contains("modImplementation \"com.github.ftinclarp:MTN-forge-layer:1.0.2-SNAPSHOT\""),
                "layer must use the configured local version: " + build);
        assertFalse(build.contains("jitpack"), "local mode must not emit JitPack: " + build);
    }

    /** Build a minimal input/out tree and run the transform stage on it. */
    private StageResult runTransform(Config config, Path inMods, Path out) throws IOException {
        // --- input: in-mods/some-mod/src/main/resources/mcmod.info (descend one level) ---
        Path modRoot = inMods.resolve("some-mod");
        Path mcmodInfo = modRoot.resolve("src/main/resources/mcmod.info");
        Files.createDirectories(mcmodInfo.getParent());
        Files.writeString(mcmodInfo, MC_MOD_INFO);

        Path exampleMod = modRoot.resolve("src/main/java/com/mtn/example/ExampleMod.java");
        Files.createDirectories(exampleMod.getParent());
        Files.writeString(exampleMod, """
                package com.mtn.example;

                import cpw.mods.fml.common.Mod;
                import cpw.mods.fml.common.event.FMLPreInitializationEvent;

                @Mod(modid = "mtnexample", name = "MTN Example", version = "1.0")
                public class ExampleMod {
                    @Mod.EventHandler
                    public void preInit(FMLPreInitializationEvent event) {
                    }
                }
                """);

        // --- output: minimal template tree ---
        Path buildFile = out.resolve("build.gradle");
        Files.createDirectories(out.resolve("src/main/java/com/example"));
        Files.createDirectories(out.resolve("src/main/resources"));
        Files.writeString(buildFile, "repositories {\n}\n\ndependencies {\n}\n");
        Files.writeString(out.resolve("src/main/resources/fabric.mod.json"), "{}\n");
        Files.writeString(out.resolve("src/main/java/com/example/ExampleMod.java"),
                "package com.example;\n\nimport net.fabricmc.api.ModInitializer;\npublic class ExampleMod {}\n");

        PipelineContext ctx = new PipelineContext(config, PathResolver.resolve(config), new Logger(), false);
        return new TransformStage().run(ctx);
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
