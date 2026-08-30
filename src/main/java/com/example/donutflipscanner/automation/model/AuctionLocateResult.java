package com.example.donutflipscanner.automation.model;

import java.util.Objects;
import java.util.Optional;

public record AuctionLocateResult(boolean located, Optional<AuctionListingCandidate> candidate, String message) {
    public AuctionLocateResult {
        candidate = Objects.requireNonNullElse(candidate, Optional.empty());
        message = Objects.requireNonNullElse(message, "");
        if (located != candidate.isPresent()) {
            throw new IllegalArgumentException("located result and candidate presence must agree");
        }
    }

    public static AuctionLocateResult found(AuctionListingCandidate candidate) {
        return new AuctionLocateResult(true, Optional.of(candidate), "Listing located.");
    }

    public static AuctionLocateResult missing(String message) {
        return new AuctionLocateResult(false, Optional.empty(), message);
    }
}
