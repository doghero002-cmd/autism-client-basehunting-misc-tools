package com.example.donutflipscanner.automation.model;

import java.util.Objects;

public record ListingResult(boolean submitted, String message) {
    public ListingResult {
        message = Objects.requireNonNullElse(message, "");
    }
}
