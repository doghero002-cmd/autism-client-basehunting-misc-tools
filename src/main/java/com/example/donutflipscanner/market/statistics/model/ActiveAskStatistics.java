package com.example.donutflipscanner.market.statistics.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ActiveAskStatistics(
        Optional<BigDecimal> lowestAsk,
        Optional<BigDecimal> secondLowestAsk,
        Optional<BigDecimal> medianAsk,
        int listingCount,
        long totalSupplyQuantity,
        int uniqueSellerCount,
        Optional<BigDecimal> largestSellerListingSharePercent
) {
    public ActiveAskStatistics {
        lowestAsk = optional(lowestAsk);
        secondLowestAsk = optional(secondLowestAsk);
        medianAsk = optional(medianAsk);
        if (listingCount < 0 || totalSupplyQuantity < 0 || uniqueSellerCount < 0) {
            throw new IllegalArgumentException("ask counts must not be negative");
        }
        largestSellerListingSharePercent = optional(largestSellerListingSharePercent);
    }

    public static ActiveAskStatistics empty() {
        return new ActiveAskStatistics(
                Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, 0, Optional.empty()
        );
    }

    private static Optional<BigDecimal> optional(Optional<BigDecimal> value) {
        return Objects.requireNonNullElse(value, Optional.empty());
    }
}
