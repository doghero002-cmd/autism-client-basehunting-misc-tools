package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;

import java.math.BigDecimal;
import java.util.Objects;

public record FairValueConfig(
        MarketLookbackPeriod primaryLookback,
        MarketLookbackPeriod recentLookback,
        MarketLookbackPeriod longTermLookback,
        int minimumCompletedSales,
        int minimumTrendSales,
        BigDecimal stableTrendBoundaryPercent,
        BigDecimal rapidRiseThresholdPercent,
        BigDecimal fallingMarketReductionPercent,
        BigDecimal highVolatilityCoefficientThreshold,
        BigDecimal highVolatilityReductionPercent,
        BigDecimal activeAskLowerCredibilityRatio,
        BigDecimal activeAskUpperCredibilityRatio,
        BigDecimal highSupplyListingToSaleRatio,
        BigDecimal highSupplyReductionPercent,
        BigDecimal cautionSafetyBufferMultiplier,
        BigDecimal elevatedRiskSafetyBufferMultiplier,
        boolean rejectStalePrimaryData
) {
    public FairValueConfig {
        Objects.requireNonNull(primaryLookback, "primaryLookback");
        Objects.requireNonNull(recentLookback, "recentLookback");
        Objects.requireNonNull(longTermLookback, "longTermLookback");
        if (minimumCompletedSales < 1 || minimumTrendSales < 1) {
            throw new IllegalArgumentException("minimum sale counts must be positive");
        }
        stableTrendBoundaryPercent = nonNegative(stableTrendBoundaryPercent, "stableTrendBoundaryPercent");
        rapidRiseThresholdPercent = positive(rapidRiseThresholdPercent, "rapidRiseThresholdPercent");
        if (rapidRiseThresholdPercent.compareTo(stableTrendBoundaryPercent) <= 0) {
            throw new IllegalArgumentException("rapidRiseThresholdPercent must exceed stableTrendBoundaryPercent");
        }
        fallingMarketReductionPercent = percentage(fallingMarketReductionPercent, "fallingMarketReductionPercent");
        highVolatilityCoefficientThreshold = positive(
                highVolatilityCoefficientThreshold, "highVolatilityCoefficientThreshold"
        );
        highVolatilityReductionPercent = percentage(
                highVolatilityReductionPercent, "highVolatilityReductionPercent"
        );
        activeAskLowerCredibilityRatio = positive(
                activeAskLowerCredibilityRatio, "activeAskLowerCredibilityRatio"
        );
        activeAskUpperCredibilityRatio = positive(
                activeAskUpperCredibilityRatio, "activeAskUpperCredibilityRatio"
        );
        if (activeAskLowerCredibilityRatio.compareTo(activeAskUpperCredibilityRatio) >= 0) {
            throw new IllegalArgumentException("active ask lower ratio must be below upper ratio");
        }
        highSupplyListingToSaleRatio = positive(
                highSupplyListingToSaleRatio, "highSupplyListingToSaleRatio"
        );
        highSupplyReductionPercent = percentage(highSupplyReductionPercent, "highSupplyReductionPercent");
        cautionSafetyBufferMultiplier = atLeastOne(
                cautionSafetyBufferMultiplier, "cautionSafetyBufferMultiplier"
        );
        elevatedRiskSafetyBufferMultiplier = atLeastOne(
                elevatedRiskSafetyBufferMultiplier, "elevatedRiskSafetyBufferMultiplier"
        );
        if (elevatedRiskSafetyBufferMultiplier.compareTo(cautionSafetyBufferMultiplier) < 0) {
            throw new IllegalArgumentException("elevated risk multiplier must not be below caution multiplier");
        }
    }

    public static FairValueConfig defaults() {
        return new FairValueConfig(
                MarketLookbackPeriod.THREE_DAYS,
                MarketLookbackPeriod.SIX_HOURS,
                MarketLookbackPeriod.THIRTY_DAYS,
                8,
                3,
                new BigDecimal("5"),
                new BigDecimal("20"),
                new BigDecimal("5"),
                new BigDecimal("0.30"),
                new BigDecimal("10"),
                new BigDecimal("0.70"),
                new BigDecimal("1.30"),
                BigDecimal.ONE,
                new BigDecimal("5"),
                new BigDecimal("1.25"),
                new BigDecimal("1.50"),
                true
        );
    }

    /** Live opportunity profile with evidence counts reduced by 50%; safety buffers remain intact. */
    public static FairValueConfig relaxedLiveDefaults() {
        FairValueConfig defaults = defaults();
        return new FairValueConfig(
                defaults.primaryLookback(), defaults.recentLookback(), defaults.longTermLookback(),
                4, 2, defaults.stableTrendBoundaryPercent(), defaults.rapidRiseThresholdPercent(),
                defaults.fallingMarketReductionPercent(), defaults.highVolatilityCoefficientThreshold(),
                defaults.highVolatilityReductionPercent(), defaults.activeAskLowerCredibilityRatio(),
                defaults.activeAskUpperCredibilityRatio(), defaults.highSupplyListingToSaleRatio(),
                defaults.highSupplyReductionPercent(), defaults.cautionSafetyBufferMultiplier(),
                defaults.elevatedRiskSafetyBufferMultiplier(), defaults.rejectStalePrimaryData()
        );
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static BigDecimal percentage(BigDecimal value, String name) {
        nonNegative(value, name);
        if (value.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException(name + " must be below one hundred");
        }
        return value;
    }

    private static BigDecimal atLeastOne(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException(name + " must be at least one");
        }
        return value;
    }
}
