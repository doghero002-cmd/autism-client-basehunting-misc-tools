package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.confidence.ConfidenceConfig;
import com.example.donutflipscanner.market.profit.ProfitEvaluationConfig;
import com.example.donutflipscanner.market.risk.ManipulationRiskConfig;
import com.example.donutflipscanner.market.value.FairValueConfig;

import java.time.Duration;
import java.util.Objects;

public record OpportunityEvaluationConfig(
        String evaluationVersion,
        String configurationVersion,
        String filterVersion,
        ItemFilterPolicy filterPolicy,
        SupportedItemPolicy supportedItemPolicy,
        FairValueConfig fairValueConfig,
        ProfitEvaluationConfig profitConfig,
        ConfidenceConfig confidenceConfig,
        ManipulationRiskConfig riskConfig,
        OpportunityThresholds thresholds,
        Duration alertCooldown,
        Duration staleReevaluationInterval
) {
    public OpportunityEvaluationConfig {
        evaluationVersion = required(evaluationVersion, "evaluationVersion");
        configurationVersion = required(configurationVersion, "configurationVersion");
        filterVersion = required(filterVersion, "filterVersion");
        Objects.requireNonNull(filterPolicy, "filterPolicy");
        Objects.requireNonNull(supportedItemPolicy, "supportedItemPolicy");
        Objects.requireNonNull(fairValueConfig, "fairValueConfig");
        Objects.requireNonNull(profitConfig, "profitConfig");
        Objects.requireNonNull(confidenceConfig, "confidenceConfig");
        Objects.requireNonNull(riskConfig, "riskConfig");
        Objects.requireNonNull(thresholds, "thresholds");
        alertCooldown = nonNegative(alertCooldown, "alertCooldown");
        staleReevaluationInterval = positive(staleReevaluationInterval, "staleReevaluationInterval");
    }

    public static OpportunityEvaluationConfig defaults() {
        return new OpportunityEvaluationConfig(
                "opportunity-v1", "default-config-v2-confidence-10", "default-filters-v1",
                ItemFilterPolicy.allowAll(), SupportedItemPolicy.safeDefaults(),
                FairValueConfig.defaults(), ProfitEvaluationConfig.defaults(), ConfidenceConfig.defaults(),
                ManipulationRiskConfig.defaults(), OpportunityThresholds.defaults(),
                Duration.ofSeconds(30), Duration.ofSeconds(30)
        );
    }

    public static OpportunityEvaluationConfig relaxedLiveDefaults() {
        return new OpportunityEvaluationConfig(
                "opportunity-v1", "live-config-v3-variety-relaxed-50", "default-filters-v1",
                ItemFilterPolicy.allowAll(), SupportedItemPolicy.liveDefaults(),
                FairValueConfig.relaxedLiveDefaults(), ProfitEvaluationConfig.defaults(),
                ConfidenceConfig.defaults(), ManipulationRiskConfig.defaults(),
                OpportunityThresholds.relaxedLiveDefaults(),
                Duration.ofSeconds(30), Duration.ofSeconds(30)
        );
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        nonNegative(value, name);
        if (value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
