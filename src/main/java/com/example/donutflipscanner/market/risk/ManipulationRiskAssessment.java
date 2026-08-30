package com.example.donutflipscanner.market.risk;

import com.example.donutflipscanner.market.confidence.ExternalConfidenceCap;
import com.example.donutflipscanner.market.value.FairValueEstimate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ManipulationRiskAssessment(
        String itemFingerprint,
        MarketRiskLevel riskLevel,
        int riskScore,
        boolean rejected,
        boolean suppressSoundAlerts,
        int confidenceReductionPoints,
        int confidenceMaximumScore,
        BigDecimal safetyBufferMultiplier,
        List<MarketAnomalyIndicator> indicators,
        Optional<String> rejectionExplanation
) {
    public ManipulationRiskAssessment {
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (riskScore < 0 || riskScore > 100
                || confidenceReductionPoints < 0 || confidenceReductionPoints > 100
                || confidenceMaximumScore < 0 || confidenceMaximumScore > 100) {
            throw new IllegalArgumentException("risk and confidence values must be between zero and one hundred");
        }
        safetyBufferMultiplier = Objects.requireNonNull(safetyBufferMultiplier, "safetyBufferMultiplier");
        if (safetyBufferMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("safetyBufferMultiplier must be at least one");
        }
        indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators"));
        rejectionExplanation = Objects.requireNonNullElse(rejectionExplanation, Optional.empty());
        if (rejected != rejectionExplanation.isPresent()) {
            throw new IllegalArgumentException("rejected assessments require exactly one rejection explanation");
        }
    }

    public ExternalConfidenceCap confidenceConstraint() {
        return new ExternalConfidenceCap(
                "MARKET_RISK_" + riskLevel,
                confidenceMaximumScore,
                confidenceReductionPoints,
                summary()
        );
    }

    /** Applies only risk buffer guidance; valuation numbers remain unchanged. */
    public FairValueEstimate applySafetyBufferGuidance(FairValueEstimate estimate) {
        Objects.requireNonNull(estimate, "estimate");
        if (!itemFingerprint.equals(estimate.itemFingerprint())) {
            throw new IllegalArgumentException("risk and fair-value fingerprints must match");
        }
        return new FairValueEstimate(
                estimate.itemFingerprint(),
                estimate.targetItemCount(),
                estimate.unitPriceBased(),
                estimate.conservativeValue(),
                estimate.centralValue(),
                estimate.optimisticValue(),
                estimate.observedLow(),
                estimate.observedHigh(),
                estimate.trend(),
                estimate.sufficientData(),
                estimate.recommendedSafetyBufferMultiplier().max(safetyBufferMultiplier),
                estimate.warnings(),
                estimate.explanation()
        );
    }

    private String summary() {
        if (indicators.isEmpty()) {
            return "No material market anomaly indicators were found.";
        }
        return riskLevel + " market anomaly risk: " + indicators.stream()
                .map(MarketAnomalyIndicator::label)
                .limit(3)
                .reduce((left, right) -> left + "; " + right)
                .orElse("market evidence requires review");
    }
}
