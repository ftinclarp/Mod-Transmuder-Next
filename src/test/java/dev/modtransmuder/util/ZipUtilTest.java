package dev.modtransmuder.util;

import dev.modtransmuder.error.UnpackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipUtilTest {

    @TempDir
    Path tmp;

    private Path writeZip(String... entries) throws IOException {
        Path zip = tmp.resolve("test.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes());
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsNormalZipPreservingContent() throws IOException {
        Path zip = writeZip("a.txt", "hello", "dir/b.txt", "world");

        Path target = tmp.resolve("out");
        ZipUtil.extractSafely(zip, target);

        assertEquals("hello", Files.readString(target.resolve("a.txt")));
        assertEquals("world", Files.readString(target.resolve("dir/b.txt")));
    }

    @Test
    void rejectsZipSlipEntry() throws IOException {
        // "../evil.txt" would escape the target directory if extracted naively.
        Path zip = writeZip("../evil.txt", "boom");

        Path target = tmp.resolve("out");
        UnpackException ex = assertThrows(UnpackException.class,
                () -> ZipUtil.extractSafely(zip, target));

        assertTrue(ex.getMessage().contains("evil.txt"));
        assertTrue(Files.notExists(target.resolve("evil.txt")),
                "escaping entry must not be written outside the target");
    }
}
