package com.example.donutflipscanner.market.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record ManipulationRiskConfig(
        int minimumAssessmentSales,
        BigDecimal suddenMovementPercent,
        BigDecimal sharpMovementPercent,
        int lowVolumeMaximumSales,
        BigDecimal concentrationWarningPercent,
        BigDecimal concentrationHighPercent,
        BigDecimal unusualTradeDeviationPercent,
        int repeatedIdenticalTradeCount,
        BigDecimal lowActiveAskRatio,
        BigDecimal highActiveAskRatio,
        Duration circularTradeWindow,
        Duration rapidAlternationWindow,
        int rapidAlternationTransitions,
        Duration newVariantMaximumAge,
        int newVariantMaximumSales,
        int moderateRiskScore,
        int highRiskScore,
        int severeRiskScore
) {
    public ManipulationRiskConfig {
        if (minimumAssessmentSales < 1 || lowVolumeMaximumSales < 1
                || repeatedIdenticalTradeCount < 2 || rapidAlternationTransitions < 2
                || newVariantMaximumSales < 1) {
            throw new IllegalArgumentException("risk count thresholds are invalid");
        }
        suddenMovementPercent = positive(suddenMovementPercent, "suddenMovementPercent");
        sharpMovementPercent = positive(sharpMovementPercent, "sharpMovementPercent");
        if (sharpMovementPercent.compareTo(suddenMovementPercent) <= 0) {
            throw new IllegalArgumentException("sharp movement threshold must exceed sudden movement threshold");
        }
        concentrationWarningPercent = positive(concentrationWarningPercent, "concentrationWarningPercent");
        concentrationHighPercent = positive(concentrationHighPercent, "concentrationHighPercent");
        if (concentrationHighPercent.compareTo(concentrationWarningPercent) <= 0
                || concentrationHighPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("concentration thresholds are invalid");
        }
        unusualTradeDeviationPercent = positive(
                unusualTradeDeviationPercent, "unusualTradeDeviationPercent"
        );
        lowActiveAskRatio = positive(lowActiveAskRatio, "lowActiveAskRatio");
        highActiveAskRatio = positive(highActiveAskRatio, "highActiveAskRatio");
        if (lowActiveAskRatio.compareTo(BigDecimal.ONE) >= 0
                || highActiveAskRatio.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("active ask ratios must straddle one");
        }
        circularTradeWindow = positive(circularTradeWindow, "circularTradeWindow");
        rapidAlternationWindow = positive(rapidAlternationWindow, "rapidAlternationWindow");
        newVariantMaximumAge = positive(newVariantMaximumAge, "newVariantMaximumAge");
        if (moderateRiskScore < 1 || highRiskScore <= moderateRiskScore
                || severeRiskScore <= highRiskScore || severeRiskScore > 100) {
            throw new IllegalArgumentException("risk score boundaries are invalid");
        }
    }

    public static ManipulationRiskConfig defaults() {
        return new ManipulationRiskConfig(
                3,
                new BigDecimal("20"),
                new BigDecimal("40"),
                5,
                new BigDecimal("60"),
                new BigDecimal("80"),
                new BigDecimal("25"),
                3,
                new BigDecimal("0.70"),
                new BigDecimal("1.50"),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                2,
                Duration.ofHours(24),
                7,
                20,
                40,
                70
        );
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
