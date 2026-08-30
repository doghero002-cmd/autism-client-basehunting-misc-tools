package com.example.donutflipscanner.api;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ApiHttpTransport extends AutoCloseable {
    CompletableFuture<ApiHttpResponse> sendAsync(HttpRequest request);

    @Override
    default void close() {
    }
}
