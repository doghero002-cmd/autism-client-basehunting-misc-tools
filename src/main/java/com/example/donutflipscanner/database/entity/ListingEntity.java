package com.example.donutflipscanner.database.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ListingEntity(
        String listingKey,
        Optional<String> remoteListingId,
        Optional<String> sellerUuid,
        Optional<String> sellerName,
        String itemFingerprint,
        String rawItemId,
        int itemCount,
        BigDecimal listingPrice,
        Optional<BigDecimal> unitPrice,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Optional<Instant> listedAt,
        Optional<Instant> expiresAt,
        ListingState state,
        int missingObservations,
        Optional<String> rawJson
) {
    public ListingEntity {
        listingKey = EntityChecks.text(listingKey, "listingKey");
        remoteListingId = EntityChecks.optional(remoteListingId);
        sellerUuid = EntityChecks.optional(sellerUuid);
        sellerName = EntityChecks.optional(sellerName);
        itemFingerprint = EntityChecks.text(itemFingerprint, "itemFingerprint");
        rawItemId = EntityChecks.text(rawItemId, "rawItemId");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        listingPrice = EntityChecks.nonNegative(listingPrice, "listingPrice");
        unitPrice = EntityChecks.optional(unitPrice);
        unitPrice.ifPresent(value -> EntityChecks.nonNegative(value, "unitPrice"));
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastSeenAt must not precede firstSeenAt");
        }
        listedAt = EntityChecks.optional(listedAt);
        expiresAt = EntityChecks.optional(expiresAt);
        Objects.requireNonNull(state, "state");
        if (missingObservations < 0) {
            throw new IllegalArgumentException("missingObservations must not be negative");
        }
        rawJson = EntityChecks.optional(rawJson);
    }
}
