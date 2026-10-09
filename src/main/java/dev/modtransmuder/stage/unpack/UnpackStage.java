package dev.modtransmuder.stage.unpack;

import dev.modtransmuder.error.UnpackException;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.Stage;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;
import dev.modtransmuder.util.ZipUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code stage-unpack} (ARCHITECTURE §5). Extracts the template zip (whose
 * path is read from {@link PipelineContext#lastZipPath()}) into a staging dir
 * next to the output, flattens the single wrapper dir that GitHub archive
 * URLs add, then swaps it into place. The swap first cleans the existing
 * output spotlessly so reruns are idempotent.
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
            flattenSingleTopLevelDir(staging);
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
     * If {@code staging} contains exactly one entry and that entry is a
     * directory, moves the directory's contents up into {@code staging} and
     * deletes the now-empty directory. Otherwise leaves the tree untouched.
     */
    private static void flattenSingleTopLevelDir(Path staging) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(staging)) {
            entries = stream.collect(Collectors.toList());
        }
        if (entries.size() != 1 || !Files.isDirectory(entries.get(0))) {
            return; // not a single wrapper dir — leave as-is
        }
        Path wrapper = entries.get(0);
        List<Path> inner;
        try (var stream = Files.list(wrapper)) {
            inner = stream.collect(Collectors.toList());
        }
        for (Path child : inner) {
            Files.move(child, staging.resolve(child.getFileName()));
        }
        Files.delete(wrapper);
    }

    /**
     * Swap {@code staging} onto {@code output}, making the run idempotent.
     *
     * <p>Sequence: verify {@code output} is exactly the recorded output (the
     * guard, never weakened), then recursively clean the existing target's
     * contents (skipping {@code .git/} with a warning, failing loudly on any
     * locked file), then attempt the atomic move, falling back to
     * delete-then-rename within the same parent. Log which path was taken at
     * DEBUG.
     */
    private static void swapIntoPlace(Path staging, Path output, PipelineContext ctx) throws IOException {
        Path recorded = ctx.paths().outputDir().toAbsolutePath().normalize();
        if (!output.equals(recorded)) {
            throw new UnpackException("refusing to replace non-output path: " + output);
        }

        if (Files.exists(output)) {
            cleanContents(output, ctx);
        }

        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ctx.logger().debug("swapped staging onto output via atomic move: " + output);
        } catch (IOException e) {
            // Contents already clean; remove the (now mostly empty) dir and rename.
            ctx.logger().debug("atomic move failed (" + e.getMessage()
                    + "); falling back to delete-then-rename: " + output);
            deleteRecursively(output);
            Files.move(staging, output);
            ctx.logger().debug("swapped staging onto output via delete-then-rename fallback: " + output);
        }
    }

    /**
     * Deletes every entry inside {@code output} (children before parents) so a
     * stale build can no longer block the swap. Skips {@code output/.git/}
     * entirely (with a warning) so user VCS metadata is preserved. Throws
     * {@link UnpackException} naming the entry when a locked/permissioned file
     * cannot be deleted — never silently continues.
     */
    private static void cleanContents(Path output, PipelineContext ctx) throws IOException {
        Path gitDir = output.resolve(".git");
        boolean gitWarned = false;
        List<Path> paths;
        try (var walk = Files.walk(output)) {
            // Children before parents so directories empty out naturally.
            paths = walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
        }
        for (Path p : paths) {
            if (p.equals(output) || p.equals(gitDir) || p.startsWith(gitDir)) {
                if (!gitWarned && (p.equals(gitDir) || p.startsWith(gitDir))) {
                    ctx.logger().warn("output contains .git metadata; preserving it: " + gitDir);
                    gitWarned = true;
                }
                continue;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new UnpackException("cannot delete " + p + " (locked or permission?): " + e.getMessage(), e);
            }
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
