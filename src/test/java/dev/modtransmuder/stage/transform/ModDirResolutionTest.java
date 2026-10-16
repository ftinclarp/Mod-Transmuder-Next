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
 * Stage 4 mod-dir validation: {@code transmudation_input} points at a full
 * mod project directory directly — never a container of mods. Looks like a
 * mod = has a {@code src/} subdirectory with a {@code src/<name>/java} root.
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

        // junk at the top level of the full mod project is ignored
        Files.createDirectories(mod.resolve(".git"));
        Files.createDirectories(mod.resolve("build"));
        Files.writeString(mod.resolve("build.gradle"), "plugins {}\n");
        Files.writeString(mod.resolve("README.md"), "readme\n");

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

        assertTrue(e.getMessage().contains("missing src/"),
                "expected 'missing src/' message, got: " + e.getMessage());
        assertTrue(e.getMessage().contains(nonMod.toAbsolutePath().toString()),
                "message should name the offending path: " + e.getMessage());
    }

    @Test
    void emptyDirectoryFails() throws IOException {
        Path empty = tmp.resolve("empty-project");
        Files.createDirectories(empty);

        TransformException e = assertThrows(TransformException.class,
                () -> TransformStage.resolveModRoot(empty));

        assertTrue(e.getMessage().contains("input directory is empty"),
                "expected 'input directory is empty' message, got: " + e.getMessage());
    }

    @Test
    void missingDirectoryFails() {
        Path missing = tmp.resolve("does-not-exist");

        TransformException e = assertThrows(TransformException.class,
                () -> TransformStage.resolveModRoot(missing));

        assertTrue(e.getMessage().contains("input directory does not exist"),
                "expected 'input directory does not exist' message, got: " + e.getMessage());
    }
}
