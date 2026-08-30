package com.example.donutflipscanner.market.confidence;

import java.util.Objects;

public record AppliedConfidenceAdjustment(
        String code,
        int reductionPoints,
        String explanation
) {
    public AppliedConfidenceAdjustment {
        code = Objects.requireNonNull(code, "code");
        if (reductionPoints < 1 || reductionPoints > 100) {
            throw new IllegalArgumentException("reductionPoints must be between one and one hundred");
        }
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
