package com.example.donutflipscanner.balance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable balance display data. It is deliberately separate from trade execution. */
public record BalanceSnapshot(
        BalanceStatus status,
        Optional<BigDecimal> amount,
        String source,
        Instant observedAt,
        String message
) {
    public BalanceSnapshot {
        Objects.requireNonNull(status, "status");
        amount = Objects.requireNonNullElse(amount, Optional.empty());
        source = Objects.requireNonNullElse(source, "Unavailable");
        observedAt = Objects.requireNonNullElse(observedAt, Instant.EPOCH);
        message = Objects.requireNonNullElse(message, "");
        amount.ifPresent(value -> {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("balance must not be negative");
            }
        });
        if (status == BalanceStatus.AVAILABLE && amount.isEmpty()) {
            throw new IllegalArgumentException("an available balance requires an amount");
        }
    }

    public static BalanceSnapshot unavailable(String message) {
        return new BalanceSnapshot(
                BalanceStatus.UNAVAILABLE, Optional.empty(), "Unavailable", Instant.EPOCH, message
        );
    }

    public static BalanceSnapshot refreshing(Optional<BigDecimal> previousAmount, Instant previousObservation) {
        return new BalanceSnapshot(
                BalanceStatus.REFRESHING, previousAmount, "Official player stats API",
                previousObservation, "Refreshing automatically"
        );
    }

    public static BalanceSnapshot available(BigDecimal amount, Instant observedAt) {
        return new BalanceSnapshot(
                BalanceStatus.AVAILABLE, Optional.of(amount), "Official player stats API",
                observedAt, "Read-only balance; refreshed automatically"
        );
    }

    public static BalanceSnapshot error(Optional<BigDecimal> previousAmount, Instant previousObservation) {
        return new BalanceSnapshot(
                BalanceStatus.ERROR, previousAmount, "Official player stats API",
                previousObservation, "Balance refresh failed; retrying automatically"
        );
    }
}
