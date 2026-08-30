package com.example.donutflipscanner.market.confidence;

import java.util.Objects;

/** Optional hook for later risk systems; Chunk 7 does not detect manipulation. */
public record ExternalConfidenceCap(
        String code,
        int maximumScore,
        int reductionPoints,
        String explanation
) {
    public ExternalConfidenceCap {
        code = Objects.requireNonNull(code, "code");
        if (maximumScore < 0 || maximumScore > 100) {
            throw new IllegalArgumentException("maximumScore must be between zero and one hundred");
        }
        if (reductionPoints < 0 || reductionPoints > 100) {
            throw new IllegalArgumentException("reductionPoints must be between zero and one hundred");
        }
        explanation = Objects.requireNonNull(explanation, "explanation");
    }

    public ExternalConfidenceCap(String code, int maximumScore, String explanation) {
        this(code, maximumScore, 0, explanation);
    }
}
