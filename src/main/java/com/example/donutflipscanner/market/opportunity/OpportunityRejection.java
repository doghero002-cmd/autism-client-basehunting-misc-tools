package com.example.donutflipscanner.market.opportunity;

import java.util.Objects;

public record OpportunityRejection(OpportunityRejectionCode code, String explanation) {
    public OpportunityRejection {
        Objects.requireNonNull(code, "code");
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
