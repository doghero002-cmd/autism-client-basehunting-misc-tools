package com.example.donutflipscanner.automation.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record AuctionListingCandidate(
        String listingKey,
        String itemFingerprint,
        String itemId,
        int itemCount,
        Optional<String> seller,
        BigDecimal listingPrice
) {
    public AuctionListingCandidate {
        listingKey = required(listingKey, "listingKey");
        itemFingerprint = required(itemFingerprint, "itemFingerprint");
        itemId = required(itemId, "itemId");
        seller = Objects.requireNonNullElse(seller, Optional.empty());
        Objects.requireNonNull(listingPrice, "listingPrice");
        if (itemCount < 1 || listingPrice.signum() <= 0) {
            throw new IllegalArgumentException("candidate count and price must be positive");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
