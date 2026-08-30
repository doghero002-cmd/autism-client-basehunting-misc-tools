package com.example.donutflipscanner.market.statistics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record RecencyWeightPolicy(
        BigDecimal underOneHour,
        BigDecimal oneToSixHours,
        BigDecimal sixToTwentyFourHours,
        BigDecimal oneToThreeDays,
        BigDecimal threeToSevenDays,
        BigDecimal older
) {
    public RecencyWeightPolicy {
        underOneHour = positive(underOneHour, "underOneHour");
        oneToSixHours = positive(oneToSixHours, "oneToSixHours");
        sixToTwentyFourHours = positive(sixToTwentyFourHours, "sixToTwentyFourHours");
        oneToThreeDays = positive(oneToThreeDays, "oneToThreeDays");
        threeToSevenDays = positive(threeToSevenDays, "threeToSevenDays");
        older = positive(older, "older");
    }

    public static RecencyWeightPolicy defaults() {
        return new RecencyWeightPolicy(
                new BigDecimal("1.00"),
                new BigDecimal("0.90"),
                new BigDecimal("0.75"),
                new BigDecimal("0.55"),
                new BigDecimal("0.35"),
                new BigDecimal("0.20")
        );
    }

    public BigDecimal weight(Instant soldAt, Instant now) {
        Objects.requireNonNull(soldAt, "soldAt");
        Objects.requireNonNull(now, "now");
        Duration age = Duration.between(soldAt, now);
        if (age.isNegative() || age.compareTo(Duration.ofHours(1)) < 0) {
            return underOneHour;
        }
        if (age.compareTo(Duration.ofHours(6)) < 0) {
            return oneToSixHours;
        }
        if (age.compareTo(Duration.ofHours(24)) < 0) {
            return sixToTwentyFourHours;
        }
        if (age.compareTo(Duration.ofDays(3)) < 0) {
            return oneToThreeDays;
        }
        if (age.compareTo(Duration.ofDays(7)) < 0) {
            return threeToSevenDays;
        }
        return older;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
