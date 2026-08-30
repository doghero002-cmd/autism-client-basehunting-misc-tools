package com.example.donutflipscanner.automation.model;

import java.util.Objects;

public record InventoryVerificationResult(boolean verified, boolean ambiguous, String message) {
    public InventoryVerificationResult {
        message = Objects.requireNonNullElse(message, "");
        if (verified && ambiguous) {
            throw new IllegalArgumentException("an ambiguous inventory result cannot be verified");
        }
    }
}
