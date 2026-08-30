package com.example.donutflipscanner.configuration;

import java.math.BigDecimal;
import java.util.Objects;

public record ItemThresholdConfig(
        boolean enabled,
        BigDecimal minimumProfit,
        BigDecimal minimumRoi,
        int minimumConfidence,
        BigDecimal maximumPurchasePrice,
        int minimumComparableSales,
        int minimumStackSize,
        int maximumStackSize,
        boolean includeEnchanted,
        boolean includeRenamed,
        boolean includeDamaged,
        boolean includeContainers
) {
    public ItemThresholdConfig {
        nonNegative(minimumProfit, "minimumProfit");
        nonNegative(minimumRoi, "minimumRoi");
        nonNegative(maximumPurchasePrice, "maximumPurchasePrice");
        if (minimumConfidence < 0 || minimumConfidence > 100) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 100");
        }
        if (minimumComparableSales < 0 || minimumStackSize < 1
                || maximumStackSize < minimumStackSize || maximumStackSize > 99_999) {
            throw new IllegalArgumentException("item threshold counts are invalid");
        }
    }

    public static ItemThresholdConfig defaults() {
        return new ItemThresholdConfig(
                true, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                0, 1, 64, true, true, true, false
        );
    }

    private static void nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
