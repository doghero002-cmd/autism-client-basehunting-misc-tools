package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.risk.MarketRiskLevel;

import java.util.Objects;

public record OpportunityThresholds(
        int minimumConfidence,
        int minimumComparableSales,
        MarketRiskLevel maximumAlertRisk
) {
    public OpportunityThresholds {
        if (minimumConfidence < 0 || minimumConfidence > 100) {
            throw new IllegalArgumentException("minimumConfidence must be between zero and one hundred");
        }
        if (minimumComparableSales < 0) {
            throw new IllegalArgumentException("minimumComparableSales must not be negative");
        }
        Objects.requireNonNull(maximumAlertRisk, "maximumAlertRisk");
    }

    public static OpportunityThresholds defaults() {
        return new OpportunityThresholds(10, 8, MarketRiskLevel.MODERATE);
    }

    /** User-requested live profile: half the confidence and evidence gates. */
    public static OpportunityThresholds relaxedLiveDefaults() {
        return new OpportunityThresholds(5, 4, MarketRiskLevel.MODERATE);
    }
}
