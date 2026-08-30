package com.example.donutflipscanner.market.scanner;

import java.time.Duration;
import java.util.Objects;

/** Contains capability flags only; the API key value is never copied into scanner state. */
public record MarketScannerConfig(
        boolean scannerEnabled,
        boolean apiKeyConfigured,
        boolean clientRunning,
        boolean mockDataMode,
        boolean serverAllowed,
        Duration recentListingsInterval,
        Duration completedTransactionsInterval,
        Duration activeListingsRefreshInterval,
        Duration retentionInterval,
        Duration shutdownGracePeriod,
        int maximumConsecutiveTemporaryFailures
) {
    public MarketScannerConfig {
        recentListingsInterval = positive(recentListingsInterval, "recentListingsInterval");
        completedTransactionsInterval = positive(
                completedTransactionsInterval, "completedTransactionsInterval"
        );
        activeListingsRefreshInterval = positive(
                activeListingsRefreshInterval, "activeListingsRefreshInterval"
        );
        retentionInterval = positive(retentionInterval, "retentionInterval");
        shutdownGracePeriod = positive(shutdownGracePeriod, "shutdownGracePeriod");
        if (maximumConsecutiveTemporaryFailures < 1) {
            throw new IllegalArgumentException("maximumConsecutiveTemporaryFailures must be positive");
        }
    }

    public static MarketScannerConfig defaults(boolean apiKeyConfigured) {
        return new MarketScannerConfig(
                true, apiKeyConfigured, true, false, true,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2),
                Duration.ofHours(6), Duration.ofSeconds(5), 5
        );
    }

    public Duration interval(ScannerActivity activity) {
        return switch (activity) {
            case RECENT_LISTINGS -> recentListingsInterval;
            case COMPLETED_TRANSACTIONS -> completedTransactionsInterval;
            case ACTIVE_LISTING_REFRESH -> activeListingsRefreshInterval;
            case RETENTION_CLEANUP -> retentionInterval;
            case STATISTICS_RECALCULATION -> throw new IllegalArgumentException(
                    "statistics recalculation is change-driven, not interval-driven"
            );
        };
    }

    public MarketScannerConfig withScannerEnabled(boolean enabled) {
        return new MarketScannerConfig(
                enabled, apiKeyConfigured, clientRunning, mockDataMode, serverAllowed,
                recentListingsInterval, completedTransactionsInterval, activeListingsRefreshInterval,
                retentionInterval, shutdownGracePeriod, maximumConsecutiveTemporaryFailures
        );
    }

    public MarketScannerConfig withMockDataMode(boolean enabled) {
        return new MarketScannerConfig(
                scannerEnabled, apiKeyConfigured, clientRunning, enabled, serverAllowed,
                recentListingsInterval, completedTransactionsInterval, activeListingsRefreshInterval,
                retentionInterval, shutdownGracePeriod, maximumConsecutiveTemporaryFailures
        );
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
