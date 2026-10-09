package dev.modtransmuder.stage.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link HttpFetcher} backed by {@code java.net.http.HttpClient}.
 * Treats any non-2xx response as a hard failure (never a success), matches
 * the "non-2xx → DownloadException" rule at the {@link DownloadStage}
 * boundary via IOException.
 */
final class HttpClientFetcher implements HttpFetcher {

    @Override
    public byte[] fetch(URI uri, Duration connectTimeout, Duration readTimeout) throws IOException {
        // GitHub's /archive/... URLs 302-redirect to codeload.github.com, so
        // follow redirects. NORMAL = follow HTTPS->HTTPS and HTTPS->HTTP, but
        // never downgrade HTTPS->HTTP where the original was HTTPS (ALWAYS
        // would, so we do not use it).
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + uri);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + uri, e);
        } catch (IOException e) {
            throw e;
        }
    }
}
