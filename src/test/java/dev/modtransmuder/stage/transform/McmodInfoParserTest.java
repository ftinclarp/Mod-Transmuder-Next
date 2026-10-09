package dev.modtransmuder.stage.transform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4a — parses the real {@code mcmod.info} shipped with the example
 * input mod.
 */
class McmodInfoParserTest {

    @Test
    void parsesRealSimplifiedMcmodInfo() {
        Path real = Path.of("in-mods/MTN-example-forge-mod/src/main/resources/mcmod.info");
        assertTrue(java.nio.file.Files.isRegularFile(real), "expected example mcmod.info at " + real);

        ForgeModInfo info = McmodInfoParser.parse(real);

        assertEquals("mtnexample", info.modid());
        assertEquals("MTN Example", info.name());
        assertEquals("1.0", info.version());
        assertTrue(info.description().contains("Minimal reference Forge 1.7.10 input mod"),
                "description should come from mcmod.info");
    }
}
