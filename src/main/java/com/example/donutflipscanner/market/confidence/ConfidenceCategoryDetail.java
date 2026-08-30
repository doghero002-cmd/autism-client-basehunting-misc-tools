package com.example.donutflipscanner.market.confidence;

import java.util.List;
import java.util.Objects;

public record ConfidenceCategoryDetail(
        ConfidenceCategory category,
        int score,
        int maximumScore,
        List<String> evidence
) {
    public ConfidenceCategoryDetail {
        Objects.requireNonNull(category, "category");
        if (score < 0 || maximumScore < 0 || score > maximumScore) {
            throw new IllegalArgumentException("category score must be inside its configured range");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
