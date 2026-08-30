package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record EffectiveProfitThresholds(
        BigDecimal minimumGrossProfit,
        BigDecimal minimumNetProfit,
        BigDecimal minimumRoiPercent,
        Optional<BigDecimal> globalMaximumPurchasePrice,
        Optional<BigDecimal> itemMaximumPurchasePrice,
        boolean itemOverridesApplied
) {
    public EffectiveProfitThresholds {
        minimumGrossProfit = ProfitThresholds.nonNegative(minimumGrossProfit, "minimumGrossProfit");
        minimumNetProfit = ProfitThresholds.nonNegative(minimumNetProfit, "minimumNetProfit");
        minimumRoiPercent = ProfitThresholds.nonNegative(minimumRoiPercent, "minimumRoiPercent");
        globalMaximumPurchasePrice = ProfitThresholds.optionalNonNegative(
                globalMaximumPurchasePrice, "globalMaximumPurchasePrice"
        );
        itemMaximumPurchasePrice = ProfitThresholds.optionalNonNegative(
                itemMaximumPurchasePrice, "itemMaximumPurchasePrice"
        );
    }

    public static EffectiveProfitThresholds resolve(
            ProfitThresholds global,
            Optional<ItemProfitThresholds> itemThresholds
    ) {
        Objects.requireNonNull(global, "global");
        Optional<ItemProfitThresholds> item = Objects.requireNonNullElse(itemThresholds, Optional.empty());
        return new EffectiveProfitThresholds(
                item.flatMap(ItemProfitThresholds::minimumGrossProfit).orElse(global.minimumGrossProfit()),
                item.flatMap(ItemProfitThresholds::minimumNetProfit).orElse(global.minimumNetProfit()),
                item.flatMap(ItemProfitThresholds::minimumRoiPercent).orElse(global.minimumRoiPercent()),
                global.maximumPurchasePrice(),
                item.flatMap(ItemProfitThresholds::maximumPurchasePrice),
                item.map(ItemProfitThresholds::hasOverrides).orElse(false)
        );
    }
}
