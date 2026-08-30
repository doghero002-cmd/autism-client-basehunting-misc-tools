package com.example.donutflipscanner.automation.model;

import java.util.Objects;

public record PurchaseResult(boolean attempted, boolean acknowledged, String message) {
    public PurchaseResult {
        message = Objects.requireNonNullElse(message, "");
        if (acknowledged && !attempted) {
            throw new IllegalArgumentException("an unattempted purchase cannot be acknowledged");
        }
    }
}
