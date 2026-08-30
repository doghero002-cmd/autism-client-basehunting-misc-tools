package com.example.donutflipscanner.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** A shared rolling-window limiter for every request made by one API client. */
public final class ApiRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_SERVER_COOLDOWN = Duration.ofHours(24);

    private final int requestLimit;
    private final Clock clock;
    private final ApiRequestScheduler scheduler;
    private final Deque<Instant> requestTimes = new ArrayDeque<>();
    private Instant cooldownUntil = Instant.EPOCH;

    public ApiRateLimiter(int requestLimit, ApiRequestScheduler scheduler) {
        this(requestLimit, scheduler, Clock.systemUTC());
    }

    ApiRateLimiter(int requestLimit, ApiRequestScheduler scheduler, Clock clock) {
        if (requestLimit < 1 || requestLimit > ApiClientConfig.DOCUMENTED_REQUESTS_PER_MINUTE) {
            throw new IllegalArgumentException("requestLimit must be between 1 and 250");
        }
        this.requestLimit = requestLimit;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<Void> acquire() {
        Duration wait;
        synchronized (this) {
            Instant now = clock.instant();
            removeExpired(now);
            Instant permitAt = now;
            if (cooldownUntil.isAfter(permitAt)) {
                permitAt = cooldownUntil;
            }
            if (requestTimes.size() >= requestLimit) {
                Instant windowPermit = requestTimes.peekFirst().plus(WINDOW);
                if (windowPermit.isAfter(permitAt)) {
                    permitAt = windowPermit;
                }
            }
            wait = Duration.between(now, permitAt);
            if (wait.isNegative() || wait.isZero()) {
                requestTimes.addLast(now);
                return CompletableFuture.completedFuture(null);
            }
        }
        return scheduler.delay(wait).thenCompose(ignored -> acquire());
    }

    public synchronized void enforceCooldown(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return;
        }
        Duration bounded = duration.compareTo(MAXIMUM_SERVER_COOLDOWN) > 0
                ? MAXIMUM_SERVER_COOLDOWN
                : duration;
        Instant proposed = clock.instant().plus(bounded);
        if (proposed.isAfter(cooldownUntil)) {
            cooldownUntil = proposed;
        }
    }

    public synchronized Duration currentCooldown() {
        Instant now = clock.instant();
        if (!cooldownUntil.isAfter(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, cooldownUntil);
    }

    public synchronized int requestsInCurrentWindow() {
        removeExpired(clock.instant());
        return requestTimes.size();
    }

    private void removeExpired(Instant now) {
        Instant threshold = now.minus(WINDOW);
        while (!requestTimes.isEmpty() && !requestTimes.peekFirst().isAfter(threshold)) {
            requestTimes.removeFirst();
        }
    }
}
