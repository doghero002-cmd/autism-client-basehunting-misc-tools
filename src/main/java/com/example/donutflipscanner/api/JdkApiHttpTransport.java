package com.example.donutflipscanner.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class JdkApiHttpTransport implements ApiHttpTransport {
    private static final int DEFAULT_MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final HttpClient client;
    private final int maximumResponseBytes;

    public JdkApiHttpTransport(Duration connectTimeout) {
        this(connectTimeout, DEFAULT_MAXIMUM_RESPONSE_BYTES);
    }

    public JdkApiHttpTransport(Duration connectTimeout, int maximumResponseBytes) {
        if (maximumResponseBytes < 1_024) {
            throw new IllegalArgumentException("maximumResponseBytes must be at least 1024");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public CompletableFuture<ApiHttpResponse> sendAsync(HttpRequest request) {
        Instant startedAt = Instant.now();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> boundedResponse(response, startedAt));
    }

    private ApiHttpResponse boundedResponse(HttpResponse<InputStream> response, Instant startedAt) {
        response.headers().firstValueAsLong("Content-Length").ifPresent(length -> {
            if (length > maximumResponseBytes) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // The bounded failure remains the useful error.
                }
                throw new ApiResponseException("DonutSMP API response exceeded the transport byte limit");
            }
        });
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maximumResponseBytes + 1);
            if (bytes.length > maximumResponseBytes) {
                throw new ApiResponseException("DonutSMP API response exceeded the transport byte limit");
            }
            return new ApiHttpResponse(
                    response.statusCode(), response.headers().map(),
                    new String(bytes, StandardCharsets.UTF_8), Duration.between(startedAt, Instant.now())
            );
        } catch (IOException error) {
            throw new ApiResponseException("DonutSMP API response could not be read safely", error);
        }
    }
}
