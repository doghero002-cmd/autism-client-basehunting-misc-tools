package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.profit.ItemProfitThresholds;

import java.util.Objects;
import java.util.OptionalInt;

public record ItemEvaluationProfile(
        boolean enabled,
        ItemProfitThresholds profitThresholds,
        OptionalInt minimumConfidence,
        OptionalInt minimumComparableSales
) {
    public ItemEvaluationProfile {
        Objects.requireNonNull(profitThresholds, "profitThresholds");
        minimumConfidence = minimumConfidence == null ? OptionalInt.empty() : minimumConfidence;
        minimumComparableSales = minimumComparableSales == null ? OptionalInt.empty() : minimumComparableSales;
        minimumConfidence.ifPresent(value -> checkRange(value, 0, 100, "minimumConfidence"));
        minimumComparableSales.ifPresent(value -> checkRange(value, 0, Integer.MAX_VALUE, "minimumComparableSales"));
    }

    public static ItemEvaluationProfile enabledDefaults() {
        return new ItemEvaluationProfile(true, ItemProfitThresholds.none(), OptionalInt.empty(), OptionalInt.empty());
    }

    public boolean hasOverrides() {
        return profitThresholds.hasOverrides() || minimumConfidence.isPresent() || minimumComparableSales.isPresent();
    }

    private static void checkRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside its supported range");
        }
    }
}
