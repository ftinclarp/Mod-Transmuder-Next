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
 * Stage 4 mod-dir resolution: {@code transmudation_input} may be a mod
 * directory itself or a container with exactly one mod subdirectory.
 */
class ModDirResolutionTest {

    @TempDir
    Path tmp;

    @Test
    void inputThatIsItselfAModIsUsedDirectly() throws IOException {
        Path mod = tmp.resolve("my-mod");
        Files.createDirectories(mod.resolve("src/main/java/com/example"));
        Files.createDirectories(mod.resolve("src/main/resources"));
        Files.writeString(mod.resolve("src/main/resources/mcmod.info"), "{}\n");

        Path resolved = TransformStage.resolveModRoot(mod);

        assertEquals(mod.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void containerWithExactlyOneModResolvesThatMod() throws IOException {
        Path container = tmp.resolve("in-mods");
        Path mod = container.resolve("some-mod");
        Files.createDirectories(mod.resolve("src/api/java/somemod"));
        Files.writeString(mod.resolve("src/api/java/somemod/Api.java"), "class Api {}\n");

        Path resolved = TransformStage.resolveModRoot(container);

        assertEquals(mod, resolved);
    }

    @Test
    void containerWithZeroModsFailsClearly() throws IOException {
        Path container = tmp.resolve("empty-in-mods");
        Files.createDirectories(container.resolve("not-a-mod"));
        Files.createDirectories(container.resolve("README.md"));

        TransformException e = assertThrows(TransformException.class,
                () -> TransformStage.resolveModRoot(container));

        assertTrue(e.getMessage().contains("no mod found under " + container.toAbsolutePath()),
                "expected clear no-mod message, got: " + e.getMessage());
    }

    @Test
    void containerWithTwoModsFailsAndListsThem() throws IOException {
        Path container = tmp.resolve("two-mods");
        Files.createDirectories(container.resolve("ironchest/src/main/resources"));
        Files.createDirectories(container.resolve("MTN-example-forge-mod/src/main/resources"));
        Files.writeString(container.resolve("ironchest/src/main/resources/mcmod.info"), "{}\n");
        Files.writeString(container.resolve("MTN-example-forge-mod/src/main/resources/mcmod.info"), "{}\n");

        TransformException e = assertThrows(TransformException.class,
                () -> TransformStage.resolveModRoot(container));

        assertTrue(e.getMessage().contains("multiple mods found under " + container.toAbsolutePath()),
                "expected multiple-mods message, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("MTN-example-forge-mod") && e.getMessage().contains("ironchest"),
                "message should list both candidate names: " + e.getMessage());
    }
}
