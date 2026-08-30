package com.example.donutflipscanner.balance;

import com.example.donutflipscanner.api.DonutApiClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Cached, read-only balance provider backed by the official player stats endpoint.
 * Network work remains asynchronous and a frame may only read the current snapshot.
 */
public final class ApiPlayerBalanceProvider implements BalanceProvider {
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final DonutApiClient apiClient;
    private final Supplier<String> username;
    private final Duration refreshInterval;
    private final Clock clock;
    private final Object refreshLock = new Object();
    private volatile BalanceSnapshot current = BalanceSnapshot.unavailable(
            "Waiting for the first automatic balance refresh"
    );
    private volatile Instant lastAttempt = Instant.EPOCH;
    private CompletableFuture<BalanceSnapshot> inFlight;

    public ApiPlayerBalanceProvider(DonutApiClient apiClient, Supplier<String> username) {
        this(apiClient, username, DEFAULT_REFRESH_INTERVAL, Clock.systemUTC());
    }

    ApiPlayerBalanceProvider(
            DonutApiClient apiClient,
            Supplier<String> username,
            Duration refreshInterval,
            Clock clock
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.username = Objects.requireNonNull(username, "username");
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("refreshInterval must be positive");
        }
    }

    @Override
    public BalanceSnapshot snapshot() {
        Instant now = clock.instant();
        if (lastAttempt.equals(Instant.EPOCH)
                || !now.isBefore(lastAttempt.plus(refreshInterval))) {
            refresh();
        }
        return current;
    }

    @Override
    public CompletableFuture<BalanceSnapshot> refresh() {
        synchronized (refreshLock) {
            if (inFlight != null) {
                return inFlight;
            }
            String playerName = Objects.requireNonNullElse(username.get(), "").strip();
            lastAttempt = clock.instant();
            if (playerName.isBlank()) {
                current = BalanceSnapshot.unavailable("Minecraft profile name is unavailable");
                return CompletableFuture.completedFuture(current);
            }
            current = BalanceSnapshot.refreshing(current.amount(), current.observedAt());
            CompletableFuture<BalanceSnapshot> request;
            try {
                request = apiClient.fetchPlayerStats(playerName)
                        .handle((stats, error) -> error == null
                                ? BalanceSnapshot.available(stats.money(), clock.instant())
                                : BalanceSnapshot.error(current.amount(), current.observedAt()));
            } catch (RuntimeException failure) {
                current = BalanceSnapshot.error(current.amount(), current.observedAt());
                return CompletableFuture.completedFuture(current);
            }
            inFlight = request;
            request.whenComplete((snapshot, error) -> {
                synchronized (refreshLock) {
                    current = error == null && snapshot != null
                            ? snapshot : BalanceSnapshot.error(current.amount(), current.observedAt());
                    if (inFlight == request) {
                        inFlight = null;
                    }
                }
            });
            return request;
        }
    }
}
