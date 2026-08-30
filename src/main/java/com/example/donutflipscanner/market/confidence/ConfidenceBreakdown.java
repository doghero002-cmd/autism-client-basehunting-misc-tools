package com.example.donutflipscanner.market.confidence;

import java.util.List;
import java.util.Objects;

public record ConfidenceBreakdown(
        int comparableSalesScore,
        int sellerDiversityScore,
        int priceStabilityScore,
        int itemMatchScore,
        int liquidityScore,
        int freshnessScore,
        int rawScore,
        int scoreAfterAdjustments,
        int totalScore,
        List<ConfidenceCategoryDetail> categories,
        List<AppliedConfidenceAdjustment> adjustments,
        List<AppliedConfidenceCap> caps,
        List<ConfidenceWarning> warnings
) {
    public ConfidenceBreakdown {
        if (comparableSalesScore < 0 || sellerDiversityScore < 0 || priceStabilityScore < 0
                || itemMatchScore < 0 || liquidityScore < 0 || freshnessScore < 0
                || rawScore < 0 || rawScore > 100
                || scoreAfterAdjustments < 0 || scoreAfterAdjustments > 100
                || totalScore < 0 || totalScore > 100) {
            throw new IllegalArgumentException("confidence scores must be between zero and one hundred");
        }
        int categoryTotal = comparableSalesScore + sellerDiversityScore + priceStabilityScore
                + itemMatchScore + liquidityScore + freshnessScore;
        if (categoryTotal != rawScore) {
            throw new IllegalArgumentException("rawScore must equal the category score sum");
        }
        if (scoreAfterAdjustments > rawScore || totalScore > scoreAfterAdjustments) {
            throw new IllegalArgumentException("adjustments and caps must never increase confidence");
        }
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        adjustments = List.copyOf(Objects.requireNonNull(adjustments, "adjustments"));
        caps = List.copyOf(Objects.requireNonNull(caps, "caps"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
