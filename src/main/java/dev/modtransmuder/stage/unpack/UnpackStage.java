package dev.modtransmuder.stage.unpack;

import dev.modtransmuder.error.UnpackException;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.Stage;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;
import dev.modtransmuder.util.ZipUtil;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * {@code stage-unpack} (ARCHITECTURE §5). Extracts the template zip (whose
 * path is read from {@link PipelineContext#lastZipPath()}) into a staging dir
 * next to the output, then swaps it into place.
 */
public final class UnpackStage implements Stage {

    @Override
    public String id() {
        return "stage-unpack";
    }

    @Override
    public StageResult run(PipelineContext ctx) {
        Path outputDir = ctx.paths().outputDir().toAbsolutePath().normalize();
        Path zipPath = ctx.lastZipPath();
        if (zipPath == null) {
            throw new UnpackException("no template zip in context: stage-download must run first");
        }

        // Staging dir: sibling of output, "<output>.staging.<runId>".
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Path staging = outputDir.resolveSibling(outputDir.getFileName() + ".staging." + runId);

        try {
            Files.createDirectories(staging);
            ZipUtil.extractSafely(zipPath, staging);

            deleteQuietly(staging.resolve(".git"));
            deleteQuietly(staging.resolve("LICENSE"));
            deleteQuietly(staging.resolve("README.md"));

            swapIntoPlace(staging, outputDir, ctx);
            return new StageResult(id(), Status.SUCCESS, "unpacked: " + outputDir, null, 0L);
        } catch (IOException e) {
            if (!ctx.keepStaging()) {
                deleteRecursively(staging);
            }
            throw new UnpackException("unpack failed: " + e.getMessage(), e);
        }
    }

    /**
     * Atomic swap of {@code staging} onto {@code output}. If the filesystem
     * does not support atomic move, falls back to delete-then-rename within
     * the same parent — and only after verifying the resolved output is
     * exactly the recorded output dir.
     */
    private static void swapIntoPlace(Path staging, Path output, PipelineContext ctx) throws IOException {
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Path recorded = ctx.paths().outputDir().toAbsolutePath().normalize();
            if (!output.equals(recorded)) {
                throw new UnpackException("refusing to replace non-output path: " + output);
            }
            if (Files.exists(output)) {
                deleteRecursively(output);
            }
            Files.move(staging, output);
        }
    }

    private static void deleteQuietly(Path target) {
        if (target != null && Files.exists(target)) {
            deleteRecursively(target);
        }
    }

    /** Best-effort recursive delete that never throws. */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            // Delete children before parents.
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort
                }
            });
        } catch (IOException ignore) {
            // best effort
        }
    }
}
