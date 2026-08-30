package com.example.donutflipscanner.market.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public enum MarketLookbackPeriod {
    ONE_HOUR(Duration.ofHours(1)),
    SIX_HOURS(Duration.ofHours(6)),
    TWENTY_FOUR_HOURS(Duration.ofHours(24)),
    THREE_DAYS(Duration.ofDays(3)),
    SEVEN_DAYS(Duration.ofDays(7)),
    THIRTY_DAYS(Duration.ofDays(30)),
    ALL_HISTORY(null);

    private final Duration duration;

    MarketLookbackPeriod(Duration duration) {
        this.duration = duration;
    }

    public Optional<Duration> duration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Instant> cutoff(Instant now) {
        return duration().map(now::minus);
    }
}
