package com.example.donutflipscanner.data;

import java.util.Objects;

public record OpportunityActionResult(boolean successful, String message) {
    public OpportunityActionResult {
        message = Objects.requireNonNull(message, "message");
    }
}
