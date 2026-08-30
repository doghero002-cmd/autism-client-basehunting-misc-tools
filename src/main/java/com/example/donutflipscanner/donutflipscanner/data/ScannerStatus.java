package com.example.donutflipscanner.data;

import java.util.Objects;
import java.util.Optional;

public record ScannerStatus(
        boolean enabled,
        String lifecycleState,
        String detail,
        Optional<String> warning
) {
    public ScannerStatus {
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        detail = Objects.requireNonNull(detail, "detail");
        warning = Objects.requireNonNullElse(warning, Optional.empty());
    }

    public ScannerStatus(boolean enabled) {
        this(enabled, enabled ? "RUNNING" : "STOPPED", enabled ? "Scanner enabled" : "Scanner disabled",
                Optional.empty());
    }

    public String displayName() {
        return enabled ? "Enabled" : "Disabled";
    }
}
