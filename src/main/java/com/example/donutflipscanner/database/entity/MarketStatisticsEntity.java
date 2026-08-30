package com.example.donutflipscanner.database.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MarketStatisticsEntity(
        String statisticsKey,
        String itemFingerprint,
        Instant computedAt,
        Instant windowStart,
        Instant windowEnd,
        int sampleCount,
        Optional<BigDecimal> minimumPrice,
        Optional<BigDecimal> maximumPrice,
        Optional<BigDecimal> medianPrice,
        Optional<String> statisticsJson
) {
    public MarketStatisticsEntity {
        statisticsKey = EntityChecks.text(statisticsKey, "statisticsKey");
        itemFingerprint = EntityChecks.text(itemFingerprint, "itemFingerprint");
        Objects.requireNonNull(computedAt, "computedAt");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (windowEnd.isBefore(windowStart) || sampleCount < 0) {
            throw new IllegalArgumentException("Invalid statistics window or sample count");
        }
        minimumPrice = EntityChecks.optional(minimumPrice);
        maximumPrice = EntityChecks.optional(maximumPrice);
        medianPrice = EntityChecks.optional(medianPrice);
        minimumPrice.ifPresent(value -> EntityChecks.nonNegative(value, "minimumPrice"));
        maximumPrice.ifPresent(value -> EntityChecks.nonNegative(value, "maximumPrice"));
        medianPrice.ifPresent(value -> EntityChecks.nonNegative(value, "medianPrice"));
        statisticsJson = EntityChecks.optional(statisticsJson);
    }
}
