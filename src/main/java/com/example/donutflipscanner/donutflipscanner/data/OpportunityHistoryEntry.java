package com.example.donutflipscanner.data;

import java.util.Objects;

public record OpportunityHistoryEntry(
        String opportunityId,
        String itemId,
        String itemName,
        String detectedAt,
        long listingPrice,
        long estimatedValue,
        long estimatedProfit,
        double roiPercent,
        double confidencePercent,
        String state
) {
    public OpportunityHistoryEntry {
        opportunityId = Objects.requireNonNull(opportunityId, "opportunityId");
        itemId = Objects.requireNonNull(itemId, "itemId");
        itemName = Objects.requireNonNull(itemName, "itemName");
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        state = Objects.requireNonNull(state, "state");
    }

    public OpportunityHistoryEntry(String opportunityId, String itemName, String state) {
        this(opportunityId, "unknown:unknown", itemName, "Unknown", 0L, 0L, 0L, 0.0D, 0.0D, state);
    }
}
