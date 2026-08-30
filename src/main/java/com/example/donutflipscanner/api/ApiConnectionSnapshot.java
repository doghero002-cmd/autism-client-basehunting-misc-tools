package com.example.donutflipscanner.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record ApiConnectionSnapshot(
        ApiConnectionState state,
        Optional<Instant> lastSuccessfulRequest,
        Optional<Instant> lastFailedRequest,
        Optional<String> lastErrorSummary,
        Duration currentCooldown,
        int requestsInCurrentWindow,
        Duration averageLatency,
        long attemptedRequestCount,
        long successfulRequestCount,
        long failedRequestCount
) {
    public ApiConnectionSnapshot(
            ApiConnectionState state,
            Optional<Instant> lastSuccessfulRequest,
            Optional<Instant> lastFailedRequest,
            Optional<String> lastErrorSummary,
            Duration currentCooldown,
            int requestsInCurrentWindow,
            Duration averageLatency
    ) {
        this(state, lastSuccessfulRequest, lastFailedRequest, lastErrorSummary, currentCooldown,
                requestsInCurrentWindow, averageLatency, 0, 0, 0);
    }

    public ApiConnectionSnapshot {
        if (attemptedRequestCount < 0 || successfulRequestCount < 0 || failedRequestCount < 0
                || successfulRequestCount + failedRequestCount > attemptedRequestCount) {
            throw new IllegalArgumentException("API request totals are invalid");
        }
    }
}
