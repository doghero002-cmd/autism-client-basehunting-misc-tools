package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record CapitalLimits(
        Optional<BigDecimal> assumedBankroll,
        Optional<BigDecimal> maximumBankrollPercentPerPurchase,
        Optional<BigDecimal> maximumTotalOpenExposure
) {
    public CapitalLimits {
        assumedBankroll = optionalPositive(assumedBankroll, "assumedBankroll");
        maximumBankrollPercentPerPurchase = optionalPositive(
                maximumBankrollPercentPerPurchase, "maximumBankrollPercentPerPurchase"
        );
        if (maximumBankrollPercentPerPurchase.isPresent()) {
            if (maximumBankrollPercentPerPurchase.orElseThrow().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("maximumBankrollPercentPerPurchase must not exceed 100");
            }
            if (assumedBankroll.isEmpty()) {
                throw new IllegalArgumentException("a manually assumed bankroll is required for a percentage limit");
            }
        }
        maximumTotalOpenExposure = ProfitThresholds.optionalNonNegative(
                maximumTotalOpenExposure, "maximumTotalOpenExposure"
        );
    }

    public static CapitalLimits unlimited() {
        return new CapitalLimits(Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<BigDecimal> optionalPositive(Optional<BigDecimal> value, String name) {
        Optional<BigDecimal> safe = Objects.requireNonNullElse(value, Optional.empty());
        safe.ifPresent(number -> {
            if (number.signum() <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        });
        return safe;
    }
}
