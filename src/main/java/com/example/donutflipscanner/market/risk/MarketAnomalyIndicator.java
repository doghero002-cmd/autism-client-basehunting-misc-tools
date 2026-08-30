package com.example.donutflipscanner.market.risk;

import java.util.Objects;

public record MarketAnomalyIndicator(
        MarketAnomalyType type,
        MarketAnomalySeverity severity,
        int riskPoints,
        String label,
        String explanation
) {
    public MarketAnomalyIndicator {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        if (riskPoints < 1 || riskPoints > 100) {
            throw new IllegalArgumentException("riskPoints must be between one and one hundred");
        }
        label = Objects.requireNonNull(label, "label");
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
