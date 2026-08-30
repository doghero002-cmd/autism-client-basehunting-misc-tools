package com.example.donutflipscanner.market.statistics;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record MarketStatisticsConfig(
        MarketLookbackPeriod lookback,
        int minimumComparableSales,
        int outlierMinimumSample,
        BigDecimal madModifiedZThreshold,
        BigDecimal iqrMultiplier,
        int maxComparableSales,
        int maxActiveListings,
        Duration staleAfter,
        RecencyWeightPolicy recencyWeights
) {
    public MarketStatisticsConfig {
        Objects.requireNonNull(lookback, "lookback");
        if (minimumComparableSales < 1) {
            throw new IllegalArgumentException("minimumComparableSales must be positive");
        }
        if (outlierMinimumSample < 3) {
            throw new IllegalArgumentException("outlierMinimumSample must be at least three");
        }
        madModifiedZThreshold = positive(madModifiedZThreshold, "madModifiedZThreshold");
        iqrMultiplier = positive(iqrMultiplier, "iqrMultiplier");
        if (maxComparableSales < 1 || maxComparableSales > 10_000) {
            throw new IllegalArgumentException("maxComparableSales must be between 1 and 10000");
        }
        if (maxActiveListings < 1 || maxActiveListings > 10_000) {
            throw new IllegalArgumentException("maxActiveListings must be between 1 and 10000");
        }
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        Objects.requireNonNull(recencyWeights, "recencyWeights");
    }

    public static MarketStatisticsConfig defaults() {
        return new MarketStatisticsConfig(
                MarketLookbackPeriod.SEVEN_DAYS,
                8,
                8,
                new BigDecimal("3.5"),
                new BigDecimal("1.5"),
                10_000,
                10_000,
                Duration.ofHours(6),
                RecencyWeightPolicy.defaults()
        );
    }

    public MarketStatisticsConfig withLookback(MarketLookbackPeriod newLookback) {
        return new MarketStatisticsConfig(
                newLookback,
                minimumComparableSales,
                outlierMinimumSample,
                madModifiedZThreshold,
                iqrMultiplier,
                maxComparableSales,
                maxActiveListings,
                staleAfter,
                recencyWeights
        );
    }

    public MarketStatisticsConfig withMinimumComparableSales(int newMinimumComparableSales) {
        return new MarketStatisticsConfig(
                lookback, newMinimumComparableSales, outlierMinimumSample,
                madModifiedZThreshold, iqrMultiplier, maxComparableSales,
                maxActiveListings, staleAfter, recencyWeights
        );
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
