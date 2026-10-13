package dev.modtransmuder.stage.transform;

import dev.modtransmuder.error.TransformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4 mod-dir validation: {@code transmudation_input} points at the mod
 * directory directly — never a container of mods.
 */
class ModDirResolutionTest {

    @TempDir
    Path tmp;

    @Test
    void modDirectoryResolves() throws IOException {
        Path mod = tmp.resolve("my-mod");
        Files.createDirectories(mod.resolve("src/main/java/com/example"));
        Files.createDirectories(mod.resolve("src/main/resources"));
        Files.writeString(mod.resolve("src/main/resources/mcmod.info"), "{}\n");

        Path resolved = TransformStage.resolveModRoot(mod);

        assertEquals(mod.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void nonModDirectoryFailsWithClearError() throws IOException {
        Path nonMod = tmp.resolve("not-a-mod");
        Files.createDirectories(nonMod);
        Files.writeString(nonMod.resolve("README.md"), "just a readme\n");

        TransformException e = assertThrows(TransformException.class,
                () -> TransformStage.resolveModRoot(nonMod));

        assertTrue(e.getMessage().contains("does not look like a mod"),
                "expected clear 'does not look like a mod' message, got: " + e.getMessage());
        assertTrue(e.getMessage().contains(nonMod.toAbsolutePath().toString()),
                "message should name the offending path: " + e.getMessage());
    }
}
