package com.example.donutflipscanner.market.confidence;

import java.math.BigDecimal;
import java.util.Objects;

public record ConfidenceConfig(
        ConfidenceWeights weights,
        int lowSampleThreshold,
        int lowSampleMaximumScore,
        int approximateItemMaximumScore,
        int unsupportedItemMaximumScore,
        BigDecimal extremeVolatilityCoefficient,
        int extremeVolatilityMaximumScore
) {
    public ConfidenceConfig {
        Objects.requireNonNull(weights, "weights");
        if (lowSampleThreshold < 1) {
            throw new IllegalArgumentException("lowSampleThreshold must be positive");
        }
        checkScore(lowSampleMaximumScore, "lowSampleMaximumScore");
        checkScore(approximateItemMaximumScore, "approximateItemMaximumScore");
        checkScore(unsupportedItemMaximumScore, "unsupportedItemMaximumScore");
        Objects.requireNonNull(extremeVolatilityCoefficient, "extremeVolatilityCoefficient");
        if (extremeVolatilityCoefficient.signum() <= 0) {
            throw new IllegalArgumentException("extremeVolatilityCoefficient must be positive");
        }
        checkScore(extremeVolatilityMaximumScore, "extremeVolatilityMaximumScore");
    }

    public static ConfidenceConfig defaults() {
        return new ConfidenceConfig(
                ConfidenceWeights.defaults(),
                3,
                35,
                60,
                20,
                new BigDecimal("0.75"),
                50
        );
    }

    private static void checkScore(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between zero and one hundred");
        }
    }
}
