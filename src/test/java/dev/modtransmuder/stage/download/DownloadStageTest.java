package dev.modtransmuder.stage.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;
import dev.modtransmuder.util.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadStageTest {

    @TempDir
    Path tmp;

    private static final String URL = "https://example.com/template.zip";
    private static final byte[] CONTENT = "fake-zip-bytes".getBytes(StandardCharsets.UTF_8);

    private Path cacheDir;
    private FakeFetcher fetcher;

    @BeforeEach
    void setUp() {
        cacheDir = tmp.resolve("cache");
        fetcher = new FakeFetcher();
    }

    private PipelineContext context(String sha256) {
        Config config = new Config(
                URL, "out", "in",
                new ObjectMapper().createArrayNode(), true,
                cacheDir.toString(), null, sha256, null, null, null, null, null, null);
        return new PipelineContext(config, dev.modtransmuder.util.PathResolver.resolve(config),
                new Logger(), false);
    }

    /** The cache hit test needs the exact sha1-based filename DownloadStage derives. */
    private static String expectedCacheFile() throws Exception {
        String sha1 = shaHex("SHA-1", URL);
        return sha1 + ".zip";
    }

    private static String shaHex(String alg, String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance(alg);
        return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void cacheMissDownloadsAndWritesZip() throws Exception {
        DownloadStage stage = new DownloadStage(fetcher);

        StageResult result = stage.run(context(null));

        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, fetcher.fetchCount);
        Path zip = cacheDir.resolve(expectedCacheFile());
        assertTrue(Files.isRegularFile(zip), "zip must be cached in cache dir");
        assertEquals(new String(CONTENT, StandardCharsets.UTF_8), Files.readString(zip));
        assertNotNull(result.message());
    }

    @Test
    void cacheHitWithMatchingShaSkipsFetch() throws Exception {
        // Pre-seed the cache with a valid zip whose sha256 equals template_sha256.
        Path zip = cacheDir.resolve(expectedCacheFile());
        Files.createDirectories(cacheDir);
        Files.write(zip, CONTENT);
        String sha256 = shaHex("SHA-256", new String(CONTENT, StandardCharsets.UTF_8));

        DownloadStage stage = new DownloadStage(fetcher);
        StageResult result = stage.run(context(sha256));

        assertEquals(Status.SUCCESS, result.status());
        assertEquals(0, fetcher.fetchCount, "cache hit must not fetch");
    }

    /** Minimal fixed-response HttpFetcher for tests. */
    private static final class FakeFetcher implements HttpFetcher {
        int fetchCount;

        @Override
        public byte[] fetch(URI uri, Duration connectTimeout, Duration readTimeout) {
            fetchCount++;
            assertEquals(URL, uri.toString());
            return CONTENT;
        }
    }
}
