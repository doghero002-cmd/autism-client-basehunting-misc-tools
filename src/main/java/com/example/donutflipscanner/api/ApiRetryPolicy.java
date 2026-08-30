package com.example.donutflipscanner.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public record ApiRetryPolicy(
        int maximumAttempts,
        List<Duration> backoff,
        Duration maximumDelay,
        double jitterFraction
) {
    public ApiRetryPolicy {
        backoff = List.copyOf(Objects.requireNonNull(backoff, "backoff"));
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be at least one");
        }
        if (backoff.isEmpty() && maximumAttempts > 1) {
            throw new IllegalArgumentException("backoff must not be empty when retries are enabled");
        }
        if (backoff.stream().anyMatch(delay -> delay == null || delay.isNegative())) {
            throw new IllegalArgumentException("backoff delays must be non-negative");
        }
        if (maximumDelay.isNegative()) {
            throw new IllegalArgumentException("maximumDelay must be non-negative");
        }
        if (jitterFraction < 0.0 || jitterFraction > 1.0) {
            throw new IllegalArgumentException("jitterFraction must be between zero and one");
        }
    }

    public Duration delayBeforeAttempt(int nextAttempt) {
        if (nextAttempt <= 1) {
            return Duration.ZERO;
        }
        int index = Math.min(nextAttempt - 2, backoff.size() - 1);
        Duration base = backoff.get(index).compareTo(maximumDelay) > 0 ? maximumDelay : backoff.get(index);
        if (jitterFraction == 0.0 || base.isZero()) {
            return base;
        }
        double multiplier = ThreadLocalRandom.current().nextDouble(1.0 - jitterFraction, 1.0 + jitterFraction);
        long jitteredMillis = Math.max(0L, Math.round(base.toMillis() * multiplier));
        return Duration.ofMillis(Math.min(jitteredMillis, maximumDelay.toMillis()));
    }

    public static ApiRetryPolicy productionDefaults() {
        return new ApiRetryPolicy(
                6,
                List.of(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(40)
                ),
                Duration.ofSeconds(40),
                0.2
        );
    }
}
