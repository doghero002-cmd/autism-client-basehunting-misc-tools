package com.example.donutflipscanner.market.profit;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ProfitBreakdown(
        BigDecimal conservativeFairValue,
        BigDecimal purchasePrice,
        BigDecimal grossProfit,
        BigDecimal purchaseFee,
        BigDecimal saleFee,
        BigDecimal flatCosts,
        BigDecimal configuredSafetyBufferPercent,
        BigDecimal effectiveSafetyBufferPercent,
        BigDecimal safetyBuffer,
        BigDecimal totalAcquisitionCost,
        BigDecimal estimatedNetProfit,
        Optional<BigDecimal> roiPercent,
        BigDecimal projectedOpenExposure
) {
    public ProfitBreakdown {
        conservativeFairValue = nonNegative(conservativeFairValue, "conservativeFairValue");
        purchasePrice = nonNegative(purchasePrice, "purchasePrice");
        purchaseFee = nonNegative(purchaseFee, "purchaseFee");
        saleFee = nonNegative(saleFee, "saleFee");
        flatCosts = nonNegative(flatCosts, "flatCosts");
        configuredSafetyBufferPercent = nonNegative(
                configuredSafetyBufferPercent, "configuredSafetyBufferPercent"
        );
        effectiveSafetyBufferPercent = nonNegative(
                effectiveSafetyBufferPercent, "effectiveSafetyBufferPercent"
        );
        safetyBuffer = nonNegative(safetyBuffer, "safetyBuffer");
        totalAcquisitionCost = nonNegative(totalAcquisitionCost, "totalAcquisitionCost");
        Objects.requireNonNull(grossProfit, "grossProfit");
        Objects.requireNonNull(estimatedNetProfit, "estimatedNetProfit");
        roiPercent = Objects.requireNonNullElse(roiPercent, Optional.empty());
        projectedOpenExposure = nonNegative(projectedOpenExposure, "projectedOpenExposure");
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        return ProfitThresholds.nonNegative(value, name);
    }
}
