package com.example.donutflipscanner.service;

import java.time.Duration;
import java.util.Objects;

public record MarketRetentionPolicy(
        Duration inactiveListingRetention,
        Duration rawListingJsonRetention,
        int cleanupBatchLimit
) {
    public MarketRetentionPolicy {
        inactiveListingRetention = positive(inactiveListingRetention, "inactiveListingRetention");
        rawListingJsonRetention = positive(rawListingJsonRetention, "rawListingJsonRetention");
        if (cleanupBatchLimit < 1 || cleanupBatchLimit > 100_000) {
            throw new IllegalArgumentException("cleanupBatchLimit must be between 1 and 100000");
        }
    }

    public static MarketRetentionPolicy defaults() {
        return new MarketRetentionPolicy(Duration.ofDays(14), Duration.ofDays(3), 1_000);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
