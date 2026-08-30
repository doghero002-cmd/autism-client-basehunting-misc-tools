package com.example.donutflipscanner.market.opportunity;

import java.util.Objects;
import java.util.Optional;

public record OpportunityDetectionResult(
        ReevaluationDecision reevaluation,
        Optional<OpportunityEvaluation> evaluation,
        boolean duplicateUnchangedListing
) {
    public OpportunityDetectionResult {
        Objects.requireNonNull(reevaluation, "reevaluation");
        evaluation = Objects.requireNonNullElse(evaluation, Optional.empty());
        if (duplicateUnchangedListing == reevaluation.shouldEvaluate()) {
            throw new IllegalArgumentException("duplicate flag must be the inverse of shouldEvaluate");
        }
        if (reevaluation.shouldEvaluate() != evaluation.isPresent()) {
            throw new IllegalArgumentException("evaluated decisions must include exactly one evaluation");
        }
    }
}
