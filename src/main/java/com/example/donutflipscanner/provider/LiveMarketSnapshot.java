package com.example.donutflipscanner.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LiveMarketSnapshot(
        long activeListings,
        long storedTransactions,
        long databaseOpportunityCount,
        long activeOpportunityCount,
        BigDecimal combinedPotentialProfit,
        List<MarketOpportunitySnapshot> activeOpportunities,
        List<MarketOpportunitySnapshot> history,
        Optional<Instant> refreshedAt,
        boolean databaseAvailable,
        Optional<String> warning
) {
    public LiveMarketSnapshot {
        if (activeListings < 0 || storedTransactions < 0 || databaseOpportunityCount < 0
                || activeOpportunityCount < 0) {
            throw new IllegalArgumentException("snapshot counts must not be negative");
        }
        Objects.requireNonNull(combinedPotentialProfit, "combinedPotentialProfit");
        activeOpportunities = List.copyOf(Objects.requireNonNull(activeOpportunities, "activeOpportunities"));
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        refreshedAt = Objects.requireNonNullElse(refreshedAt, Optional.empty());
        warning = Objects.requireNonNullElse(warning, Optional.empty());
    }

    public static LiveMarketSnapshot empty() {
        return new LiveMarketSnapshot(
                0, 0, 0, 0, BigDecimal.ZERO, List.of(), List.of(), Optional.empty(), true, Optional.empty()
        );
    }
}
