package com.example.donutflipscanner.market.profit;

import com.example.donutflipscanner.market.value.FairValueEstimate;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ProfitEvaluationRequest(
        String itemFingerprint,
        BigDecimal purchasePrice,
        FairValueEstimate fairValue,
        ProfitEvaluationConfig config,
        Optional<ItemProfitThresholds> itemThresholds,
        BigDecimal currentOpenExposure
) {
    public ProfitEvaluationRequest {
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        purchasePrice = ProfitThresholds.nonNegative(purchasePrice, "purchasePrice");
        Objects.requireNonNull(fairValue, "fairValue");
        Objects.requireNonNull(config, "config");
        itemThresholds = Objects.requireNonNullElse(itemThresholds, Optional.empty());
        currentOpenExposure = ProfitThresholds.nonNegative(currentOpenExposure, "currentOpenExposure");
        if (!itemFingerprint.equals(fairValue.itemFingerprint())) {
            throw new IllegalArgumentException("request and fair-value fingerprints must match");
        }
    }
}
