package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Optional;

/** Optional per-fingerprint overrides for minimums plus a separate item capital ceiling. */
public record ItemProfitThresholds(
        Optional<BigDecimal> minimumGrossProfit,
        Optional<BigDecimal> minimumNetProfit,
        Optional<BigDecimal> minimumRoiPercent,
        Optional<BigDecimal> maximumPurchasePrice
) {
    public ItemProfitThresholds {
        minimumGrossProfit = ProfitThresholds.optionalNonNegative(minimumGrossProfit, "minimumGrossProfit");
        minimumNetProfit = ProfitThresholds.optionalNonNegative(minimumNetProfit, "minimumNetProfit");
        minimumRoiPercent = ProfitThresholds.optionalNonNegative(minimumRoiPercent, "minimumRoiPercent");
        maximumPurchasePrice = ProfitThresholds.optionalNonNegative(maximumPurchasePrice, "maximumPurchasePrice");
    }

    public static ItemProfitThresholds none() {
        return new ItemProfitThresholds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
    }

    public boolean hasOverrides() {
        return minimumGrossProfit.isPresent()
                || minimumNetProfit.isPresent()
                || minimumRoiPercent.isPresent()
                || maximumPurchasePrice.isPresent();
    }
}
