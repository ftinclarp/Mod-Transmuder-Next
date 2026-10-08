package dev.modtransmuder.util;

import dev.modtransmuder.error.UnpackException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal zip tooling used by {@code stage-unpack}. Safe extraction only
 * (ARCHITECTURE §5).
 */
public final class ZipUtil {

    private ZipUtil() {
    }

    /**
     * Extracts {@code zip} into {@code targetDir}, creating directories as
     * needed. Rejects any entry that resolves outside {@code targetDir}
     * (zip-slip) with {@link UnpackException}. Permissions and timestamps are
     * not preserved.
     *
     * @throws IOException     on I/O or corrupt-archive errors
     * @throws UnpackException on any entry escaping the target directory
     */
    public static void extractSafely(Path zip, Path targetDir) throws IOException {
        Path target = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                Path resolved = target.resolve(name).normalize();
                if (!resolved.startsWith(target)) {
                    throw new UnpackException(
                            "zip entry escapes target directory: " + entry.getName());
                }
                Files.createDirectories(resolved.getParent());
                try (OutputStream out = Files.newOutputStream(resolved)) {
                    in.transferTo(out);
                }
            }
        }
    }
}
