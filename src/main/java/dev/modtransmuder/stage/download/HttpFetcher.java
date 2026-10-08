package dev.modtransmuder.stage.download;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Package-private seam that {@link DownloadStage} fetches through, so tests
 * can inject a fake. The default {@link HttpClientFetcher} uses {@code
 * java.net.http.HttpClient}.
 */
@FunctionalInterface
interface HttpFetcher {
    byte[] fetch(URI uri, Duration connectTimeout, Duration readTimeout) throws IOException;
}
