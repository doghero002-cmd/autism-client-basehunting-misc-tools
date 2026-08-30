package com.example.donutflipscanner.data;

import java.util.Objects;
import java.util.Optional;

public record ApiConnectionStatus(
        String displayName,
        String lastUpdateText,
        boolean connected,
        int requestsInCurrentWindow,
        long averageLatencyMillis,
        long cooldownSeconds,
        Optional<String> warning
) {
    public ApiConnectionStatus {
        displayName = Objects.requireNonNull(displayName, "displayName");
        lastUpdateText = Objects.requireNonNull(lastUpdateText, "lastUpdateText");
        warning = Objects.requireNonNullElse(warning, Optional.empty());
    }

    public ApiConnectionStatus(String displayName, String lastUpdateText, boolean connected) {
        this(displayName, lastUpdateText, connected, 0, 0L, 0L, Optional.empty());
    }
}
