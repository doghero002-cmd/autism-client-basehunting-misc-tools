package com.example.donutflipscanner.market.value;

import java.math.BigDecimal;
import java.util.Objects;

public record FairValueAdjustment(
        String code,
        BigDecimal reductionPercent,
        String explanation
) {
    public FairValueAdjustment {
        code = Objects.requireNonNull(code, "code");
        reductionPercent = Objects.requireNonNull(reductionPercent, "reductionPercent");
        if (reductionPercent.signum() < 0 || reductionPercent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("reductionPercent must be between zero and one hundred");
        }
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
