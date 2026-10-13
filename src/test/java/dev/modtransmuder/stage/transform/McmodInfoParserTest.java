package dev.modtransmuder.stage.transform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4a — parses the real {@code mcmod.info} shipped with the example
 * input mod, plus a legacy Forge 1.7.10 single-element array fixture.
 */
class McmodInfoParserTest {

    @Test
    void parsesLegacyArrayMcmodInfo() throws java.io.IOException {
        Path legacy = java.nio.file.Paths.get("src/test/resources/mcmod-info-legacy-array.json");
        assertTrue(java.nio.file.Files.isRegularFile(legacy), "expected fixture at " + legacy);

        ForgeModInfo info = McmodInfoParser.parse(legacy);

        assertEquals("examplelegacy", info.modid());
        assertEquals("Example Legacy", info.name());
        assertEquals("1.0", info.version());
        assertTrue(info.description().contains("Synthetic legacy-array mcmod.info fixture"),
                "legacy 'description' should be read");
        assertEquals(java.util.List.of("Test Author"), info.authors(),
                "legacy 'authors' fallback should populate authors");
    }

    @Test
    void defaultsMissingFieldsToEmpty() throws java.io.IOException {
        Path weird = java.nio.file.Files.createTempFile("mcmod-info-", ".json");
        java.nio.file.Files.writeString(weird,
                "[ { \"modid\": \"Odd\", \"name\": \"Odd Mod\" } ]");

        ForgeModInfo info = McmodInfoParser.parse(weird);

        assertEquals("Odd", info.modid());
        assertEquals("Odd Mod", info.name());
        assertEquals("", info.version());
        assertEquals("", info.description());
        assertTrue(info.authors().isEmpty());
    }
}
