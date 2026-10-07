package com.autism.seedcracker.util;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient for all addon HTTP (webhooks, APIs, translate). Each HttpClient owns a
 * selector thread + pooled connections; eight separate instances was pure waste.
 */
public final class Http {
    private Http() {}

    public static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
}
