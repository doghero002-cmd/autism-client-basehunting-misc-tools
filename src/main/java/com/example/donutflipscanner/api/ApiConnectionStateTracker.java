package com.example.donutflipscanner.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

final class ApiConnectionStateTracker {
    private ApiConnectionState state = ApiConnectionState.DISCONNECTED;
    private Instant lastSuccessfulRequest;
    private Instant lastFailedRequest;
    private String lastErrorSummary;
    private long completedRequestCount;
    private long totalLatencyNanos;
    private long attemptedRequestCount;
    private long failedRequestCount;

    synchronized void connecting() {
        attemptedRequestCount++;
        if (state != ApiConnectionState.CONNECTED && state != ApiConnectionState.RATE_LIMITED) {
            state = ApiConnectionState.CONNECTING;
        }
    }

    synchronized void connected(Duration latency) {
        state = ApiConnectionState.CONNECTED;
        lastSuccessfulRequest = Instant.now();
        lastErrorSummary = null;
        completedRequestCount++;
        totalLatencyNanos = saturatingAdd(totalLatencyNanos, Math.max(0L, latency.toNanos()));
    }

    synchronized void rateLimited() {
        failedRequestCount++;
        state = ApiConnectionState.RATE_LIMITED;
        lastFailedRequest = Instant.now();
        lastErrorSummary = "API request limit reached";
    }

    synchronized void authenticationFailed() {
        failedRequestCount++;
        state = ApiConnectionState.AUTHENTICATION_FAILED;
        lastFailedRequest = Instant.now();
        lastErrorSummary = "API authentication failed";
    }

    synchronized void temporaryError() {
        failedRequestCount++;
        state = ApiConnectionState.TEMPORARY_ERROR;
        lastFailedRequest = Instant.now();
        lastErrorSummary = "API temporarily unavailable";
    }

    synchronized void disabled() {
        state = ApiConnectionState.DISABLED;
        lastErrorSummary = "API key is not configured";
    }

    synchronized ApiConnectionSnapshot snapshot(Duration cooldown, int requestsInCurrentWindow) {
        Duration averageLatency = completedRequestCount == 0
                ? Duration.ZERO
                : Duration.ofNanos(totalLatencyNanos / completedRequestCount);
        ApiConnectionState visibleState = !cooldown.isZero() ? ApiConnectionState.RATE_LIMITED : state;
        return new ApiConnectionSnapshot(
                visibleState,
                Optional.ofNullable(lastSuccessfulRequest),
                Optional.ofNullable(lastFailedRequest),
                Optional.ofNullable(lastErrorSummary),
                cooldown,
                requestsInCurrentWindow,
                averageLatency,
                attemptedRequestCount,
                completedRequestCount,
                failedRequestCount
        );
    }

    private long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
