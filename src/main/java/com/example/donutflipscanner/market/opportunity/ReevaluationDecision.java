package com.example.donutflipscanner.market.opportunity;

import java.util.List;
import java.util.Objects;

public record ReevaluationDecision(boolean shouldEvaluate, List<ReevaluationReason> reasons) {
    public ReevaluationDecision {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("reevaluation decision requires a reason");
        }
    }
}
