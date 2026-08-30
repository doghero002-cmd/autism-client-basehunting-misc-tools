package com.example.donutflipscanner.market.confidence;

import java.util.Objects;

public record AppliedConfidenceCap(
        ConfidenceCapReason reason,
        int maximumScore,
        String explanation
) {
    public AppliedConfidenceCap {
        Objects.requireNonNull(reason, "reason");
        if (maximumScore < 0 || maximumScore > 100) {
            throw new IllegalArgumentException("maximumScore must be between zero and one hundred");
        }
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
