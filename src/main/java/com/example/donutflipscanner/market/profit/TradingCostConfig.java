package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;

/** User-entered assumptions; no DonutSMP fee is implied by these defaults. */
public record TradingCostConfig(
        BigDecimal purchaseFeePercent,
        BigDecimal saleFeePercent,
        BigDecimal flatCost,
        BigDecimal safetyBufferPercent
) {
    public TradingCostConfig {
        purchaseFeePercent = percentage(purchaseFeePercent, "purchaseFeePercent");
        saleFeePercent = percentage(saleFeePercent, "saleFeePercent");
        flatCost = nonNegative(flatCost, "flatCost");
        safetyBufferPercent = percentage(safetyBufferPercent, "safetyBufferPercent");
    }

    public static TradingCostConfig defaults() {
        return new TradingCostConfig(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("5")
        );
    }

    private static BigDecimal percentage(BigDecimal value, String name) {
        nonNegative(value, name);
        if (value.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException(name + " must be below one hundred");
        }
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
