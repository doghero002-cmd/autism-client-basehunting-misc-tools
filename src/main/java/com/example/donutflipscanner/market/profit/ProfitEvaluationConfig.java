package com.example.donutflipscanner.market.profit;

import java.util.Objects;

public record ProfitEvaluationConfig(
        TradingCostConfig tradingCosts,
        ProfitThresholds globalThresholds,
        CapitalLimits capitalLimits
) {
    public ProfitEvaluationConfig {
        Objects.requireNonNull(tradingCosts, "tradingCosts");
        Objects.requireNonNull(globalThresholds, "globalThresholds");
        Objects.requireNonNull(capitalLimits, "capitalLimits");
    }

    public static ProfitEvaluationConfig defaults() {
        return new ProfitEvaluationConfig(
                TradingCostConfig.defaults(),
                ProfitThresholds.permissiveDefaults(),
                CapitalLimits.unlimited()
        );
    }
}
