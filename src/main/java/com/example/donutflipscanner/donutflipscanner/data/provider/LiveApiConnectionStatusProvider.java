package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.api.ApiConnectionSnapshot;
import com.example.donutflipscanner.api.ApiConnectionState;
import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.data.ApiConnectionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Adapts the live API snapshot to the existing status-bar provider contract. */
public final class LiveApiConnectionStatusProvider implements ApiConnectionStatusProvider {
    private final Supplier<ApiConnectionSnapshot> snapshots;
    private final Clock clock;

    public LiveApiConnectionStatusProvider(DonutApiClient apiClient) {
        this(Objects.requireNonNull(apiClient, "apiClient")::connectionSnapshot, Clock.systemUTC());
    }

    public LiveApiConnectionStatusProvider(Supplier<ApiConnectionSnapshot> snapshots, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ApiConnectionStatus getApiConnectionStatus() {
        ApiConnectionSnapshot snapshot = snapshots.get();
        return new ApiConnectionStatus(
                displayName(snapshot),
                lastUpdateText(snapshot),
                snapshot.state() == ApiConnectionState.CONNECTED,
                snapshot.requestsInCurrentWindow(),
                snapshot.averageLatency().toMillis(),
                snapshot.currentCooldown().toSeconds(),
                snapshot.lastErrorSummary()
        );
    }

    private String displayName(ApiConnectionSnapshot snapshot) {
        return switch (snapshot.state()) {
            case DISCONNECTED -> "Disconnected";
            case CONNECTING -> "Connecting";
            case CONNECTED -> "Connected";
            case RATE_LIMITED -> "Rate limited";
            case AUTHENTICATION_FAILED -> "Authentication failed";
            case TEMPORARY_ERROR -> "Temporarily unavailable";
            case DISABLED -> "Disabled";
        };
    }

    private String lastUpdateText(ApiConnectionSnapshot snapshot) {
        return snapshot.lastSuccessfulRequest()
                .map(this::formatAge)
                .orElse("Never");
    }

    private String formatAge(Instant instant) {
        long seconds = Math.max(0L, Duration.between(instant, clock.instant()).toSeconds());
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        return (minutes / 60) + "h ago";
    }
}
