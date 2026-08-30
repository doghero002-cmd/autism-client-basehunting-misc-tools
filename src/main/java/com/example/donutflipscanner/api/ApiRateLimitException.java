package com.example.donutflipscanner.api;

import java.time.Duration;

public final class ApiRateLimitException extends ApiException {
    private final Duration retryAfter;

    public ApiRateLimitException(Duration retryAfter) {
        super("DonutSMP API rate limit reached", 429, true);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
