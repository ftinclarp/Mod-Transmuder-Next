package dev.modtransmuder.stage.unpack;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpackStageTest {

    @TempDir
    Path tmp;

    private static PipelineContext context(Path outputDir, Path zip) {
        Config config = new Config(
                "https://example.com/template.zip", outputDir.toString(), "in",
                new ObjectMapper().createArrayNode(), true,
                null, null, null, null);
        PipelineContext ctx = new PipelineContext(config, PathResolver.resolve(config), new Logger(), false);
        ctx.setLastZipPath(zip);
        return ctx;
    }

    private Path zipWithWrapperDir() throws IOException {
        Path zip = tmp.resolve("template.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeEntry(out, "MTN-template-2/build.gradle", "plugins {}");
            writeEntry(out, "MTN-template-2/settings.gradle", "rootProject.name = 'modid'");
            writeEntry(out, "MTN-template-2/gradle.properties", "version=0.1.0");
            writeEntry(out, "MTN-template-2/src/main/java/Example.java", "class Example {}");
        }
        return zip;
    }

    private static void writeEntry(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes());
        out.closeEntry();
    }

    @Test
    void flattensSingleTopLevelDir() throws IOException {
        Path output = tmp.resolve("out");
        PipelineContext ctx = context(output, zipWithWrapperDir());

        StageResult result = new UnpackStage().run(ctx);

        assertEquals(Status.SUCCESS, result.status());
        assertTrue(Files.isRegularFile(output.resolve("build.gradle")),
                "build.gradle must sit directly in output");
        assertTrue(Files.isRegularFile(output.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(output.resolve("gradle.properties")));
        assertTrue(Files.isRegularFile(output.resolve("src/main/java/Example.java")));
        assertFalse(Files.exists(output.resolve("MTN-template-2")),
                "wrapper dir must be gone after flatten");
    }
}
