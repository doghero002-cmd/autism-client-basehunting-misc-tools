package com.example.donutflipscanner.market.value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FairValueExplanation(
        int acceptedCompletedSales,
        int rejectedCompletedSales,
        Optional<BigDecimal> weightedMedian,
        Optional<BigDecimal> percentile40,
        Optional<BigDecimal> primaryMedian,
        Optional<BigDecimal> recentMedian,
        Optional<BigDecimal> longTermMedian,
        Optional<BigDecimal> consideredSecondLowestAsk,
        ActiveAskEvidenceStatus activeAskStatus,
        Optional<BigDecimal> recentToLongTermChangePercent,
        List<FairValueAdjustment> adjustments,
        List<String> notes
) {
    public FairValueExplanation {
        if (acceptedCompletedSales < 0 || rejectedCompletedSales < 0) {
            throw new IllegalArgumentException("sale counts must not be negative");
        }
        weightedMedian = optional(weightedMedian);
        percentile40 = optional(percentile40);
        primaryMedian = optional(primaryMedian);
        recentMedian = optional(recentMedian);
        longTermMedian = optional(longTermMedian);
        consideredSecondLowestAsk = optional(consideredSecondLowestAsk);
        Objects.requireNonNull(activeAskStatus, "activeAskStatus");
        recentToLongTermChangePercent = optional(recentToLongTermChangePercent);
        adjustments = List.copyOf(Objects.requireNonNull(adjustments, "adjustments"));
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    }

    private static Optional<BigDecimal> optional(Optional<BigDecimal> value) {
        return Objects.requireNonNullElse(value, Optional.empty());
    }
}
