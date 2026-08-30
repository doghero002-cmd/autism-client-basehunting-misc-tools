package com.example.donutflipscanner.automation.model;

import java.util.Objects;

public record AuctionVerificationResult(boolean verified, String message) {
    public AuctionVerificationResult {
        message = Objects.requireNonNullElse(message, "");
    }

    public static AuctionVerificationResult accepted() {
        return new AuctionVerificationResult(true, "Listing matches the immutable request.");
    }

    public static AuctionVerificationResult rejected(String message) {
        return new AuctionVerificationResult(false, message);
    }
}
