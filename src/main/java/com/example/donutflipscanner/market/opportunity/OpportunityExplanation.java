package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.confidence.ConfidenceBreakdown;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.profit.ProfitEvaluation;
import com.example.donutflipscanner.market.risk.ManipulationRiskAssessment;
import com.example.donutflipscanner.market.value.FairValueEstimate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Structured, UI-ready evidence retained for both accepted and rejected evaluations. */
public record OpportunityExplanation(
        FilterDecision filterDecision,
        ItemMatchType itemMatchType,
        Optional<ManipulationRiskAssessment> riskAssessment,
        Optional<FairValueEstimate> fairValue,
        Optional<ProfitEvaluation> profit,
        Optional<ConfidenceBreakdown> confidence,
        int acceptedComparableSales,
        int rejectedComparableSales,
        int effectiveMinimumConfidence,
        int effectiveMinimumComparableSales,
        boolean itemOverridesApplied,
        List<String> passedChecks,
        List<OpportunityRejection> rejections
) {
    public OpportunityExplanation {
        Objects.requireNonNull(filterDecision, "filterDecision");
        Objects.requireNonNull(itemMatchType, "itemMatchType");
        riskAssessment = optional(riskAssessment);
        fairValue = optional(fairValue);
        profit = optional(profit);
        confidence = optional(confidence);
        if (acceptedComparableSales < 0 || rejectedComparableSales < 0
                || effectiveMinimumConfidence < 0 || effectiveMinimumConfidence > 100
                || effectiveMinimumComparableSales < 0) {
            throw new IllegalArgumentException("explanation counts and thresholds are invalid");
        }
        passedChecks = List.copyOf(Objects.requireNonNull(passedChecks, "passedChecks"));
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return Objects.requireNonNullElse(value, Optional.empty());
    }
}
