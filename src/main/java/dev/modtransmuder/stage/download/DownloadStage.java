package dev.modtransmuder.stage.download;

import dev.modtransmuder.config.Config;
import dev.modtransmuder.error.DownloadException;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.Stage;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * {@code stage-download} (ARCHITECTURE §5). Fetches the template zip, caches
 * it in {@link dev.modtransmuder.util.PathResolver.ResolvedPaths#cacheDir()},
 * and exposes the local zip path to later stages.
 */
public final class DownloadStage implements Stage {

    private final HttpFetcher fetcher;

    public DownloadStage() {
        this(new HttpClientFetcher());
    }

    DownloadStage(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return "stage-download";
    }

    @Override
    public StageResult run(PipelineContext ctx) {
        Config config = ctx.config();
        Path cacheDir = ctx.paths().cacheDir();
        // Cache filename derived from the URL: sha1(url) + ".zip", so the same
        // template URL always maps to the same cache file.
        String fileName = sha1Hex(config.templateZipUrl()) + ".zip";
        Path cachePath = cacheDir.resolve(fileName).toAbsolutePath().normalize();

        try {
            Files.createDirectories(cachePath.getParent());

            String expectedSha = config.templateSha256();
            if (Files.isRegularFile(cachePath)
                    && (expectedSha == null || expectedSha.equalsIgnoreCase(sha256HexFile(cachePath)))) {
                ctx.setLastZipPath(cachePath);
                return new StageResult(id(), Status.SUCCESS, "cache hit: " + cachePath, null, 0L);
            }

            byte[] downloaded = fetcher.fetch(
                    java.net.URI.create(config.templateZipUrl()),
                    connectTimeout(config.timeoutSeconds()),
                    readTimeout(config.timeoutSeconds()));

            if (expectedSha != null && !expectedSha.equalsIgnoreCase(sha256HexBytes(downloaded))) {
                throw new DownloadException("sha256 mismatch for template at " + config.templateZipUrl());
            }

            writeAtomically(cacheDir, cachePath, downloaded);
            ctx.setLastZipPath(cachePath);
            return new StageResult(id(), Status.SUCCESS, "downloaded: " + cachePath, null, 0L);
        } catch (IOException e) {
            throw new DownloadException("download failed for " + config.templateZipUrl() + ": " + e.getMessage(), e);
        }
    }

    /** Writes bytes to a temp file in {@code dir}, then atomically moves it to {@code target}. */
    private static void writeAtomically(Path dir, Path target, byte[] bytes) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, ".download-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /// hashing helpers

    private static String sha(String alg, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(alg + " missing from JRE", e);
        }
    }

    private static String sha256HexFile(Path path) throws IOException {
        return sha("SHA-256", Files.readAllBytes(path));
    }

    private static String sha256HexBytes(byte[] bytes) {
        return sha("SHA-256", bytes);
    }

    private static String sha1Hex(String input) {
        return sha("SHA-1", input.getBytes(StandardCharsets.UTF_8));
    }

    private static Duration connectTimeout(Integer configured) {
        return configured != null ? Duration.ofSeconds(configured) : Duration.ofSeconds(30);
    }

    private static Duration readTimeout(Integer configured) {
        return configured != null ? Duration.ofSeconds(configured) : Duration.ofSeconds(60);
    }
}
