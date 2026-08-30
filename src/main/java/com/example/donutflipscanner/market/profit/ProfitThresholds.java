package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ProfitThresholds(
        BigDecimal minimumGrossProfit,
        BigDecimal minimumNetProfit,
        BigDecimal minimumRoiPercent,
        Optional<BigDecimal> maximumPurchasePrice
) {
    public ProfitThresholds {
        minimumGrossProfit = nonNegative(minimumGrossProfit, "minimumGrossProfit");
        minimumNetProfit = nonNegative(minimumNetProfit, "minimumNetProfit");
        minimumRoiPercent = nonNegative(minimumRoiPercent, "minimumRoiPercent");
        maximumPurchasePrice = optionalNonNegative(maximumPurchasePrice, "maximumPurchasePrice");
    }

    public static ProfitThresholds permissiveDefaults() {
        return new ProfitThresholds(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Optional.empty()
        );
    }

    static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static Optional<BigDecimal> optionalNonNegative(Optional<BigDecimal> value, String name) {
        Optional<BigDecimal> safe = Objects.requireNonNullElse(value, Optional.empty());
        safe.ifPresent(number -> nonNegative(number, name));
        return safe;
    }
}
