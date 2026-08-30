package com.example.donutflipscanner.database.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One bounded database row for GUI/provider snapshot creation. */
public record OpportunityListingView(
        OpportunityEntity opportunity,
        String itemId,
        int itemCount,
        Optional<String> sellerName,
        Optional<Instant> listedAt,
        Instant lastVerifiedAt,
        Optional<String> normalizedItemMetadata
) {
    public OpportunityListingView {
        Objects.requireNonNull(opportunity, "opportunity");
        itemId = EntityChecks.text(itemId, "itemId");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        sellerName = EntityChecks.optional(sellerName);
        listedAt = EntityChecks.optional(listedAt);
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        normalizedItemMetadata = EntityChecks.optional(normalizedItemMetadata);
    }

    public OpportunityListingView(
            OpportunityEntity opportunity,
            String itemId,
            int itemCount,
            Optional<String> sellerName,
            Optional<Instant> listedAt,
            Instant lastVerifiedAt
    ) {
        this(opportunity, itemId, itemCount, sellerName, listedAt, lastVerifiedAt, Optional.empty());
    }
}
