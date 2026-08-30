package com.example.donutflipscanner.balance;

import java.util.concurrent.CompletableFuture;

/** Provider-neutral, non-blocking source for passive balance display data. */
public interface BalanceProvider {
    /** Returns immediately and may start a bounded background refresh when stale. */
    BalanceSnapshot snapshot();

    /** Explicitly requests a refresh without blocking Minecraft's render thread. */
    CompletableFuture<BalanceSnapshot> refresh();

    /** Display snapshots are not transactional proof of a completed purchase. */
    default boolean supportsAutomatedPurchaseVerification() {
        return false;
    }

    static BalanceProvider unavailable() {
        return new BalanceProvider() {
            private final BalanceSnapshot snapshot = BalanceSnapshot.unavailable(
                    "A live API session is required for automatic balance display"
            );

            @Override
            public BalanceSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public CompletableFuture<BalanceSnapshot> refresh() {
                return CompletableFuture.completedFuture(snapshot);
            }
        };
    }
}
