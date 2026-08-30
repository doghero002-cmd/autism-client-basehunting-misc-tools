package com.example.donutflipscanner.market.opportunity;

import java.util.Objects;

public record FilterDecision(boolean allowed, String explanation) {
    public FilterDecision {
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
