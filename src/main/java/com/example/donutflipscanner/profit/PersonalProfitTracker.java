package com.example.donutflipscanner.profit;

import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.database.PersonalProfitRepository;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates persistent matching while exposing only immutable, non-blocking GUI snapshots. */
public final class PersonalProfitTracker implements CompletedSalesObserver {
    private final PersonalProfitRepository repository;
    private final PlayerIdentity playerIdentity;
    private final Clock clock;
    private final AtomicReference<PersonalProfitSnapshot> snapshot =
            new AtomicReference<>(PersonalProfitSnapshot.empty());

    public PersonalProfitTracker(
            PersonalProfitRepository repository,
            PlayerIdentity playerIdentity,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.playerIdentity = Objects.requireNonNull(playerIdentity, "playerIdentity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PersonalProfitSnapshot snapshot() {
        return snapshot.get();
    }

    public CompletableFuture<PersonalProfitSnapshot> initialize() {
        CompletableFuture<Integer> reconcile = playerIdentity.isUsable()
                ? repository.reconcileStoredSales(playerIdentity)
                : CompletableFuture.completedFuture(0);
        return reconcile.thenCompose(ignored -> refresh());
    }

    public CompletableFuture<Boolean> confirmPurchase(String opportunityId) {
        Instant now = clock.instant();
        return repository.confirmPurchase(
                opportunityId, now,
                now.minus(LiveMarketSnapshotService.MAXIMUM_ACTIONABLE_VERIFICATION_AGE)
        ).thenCompose(confirmed -> refresh().thenApply(ignored -> confirmed));
    }

    @Override
    public CompletableFuture<Integer> observe(List<SaleEntity> sales) {
        if (!playerIdentity.isUsable()) {
            return CompletableFuture.completedFuture(0);
        }
        return repository.reconcileSales(playerIdentity, List.copyOf(sales))
                .thenCompose(realized -> refresh().thenApply(ignored -> realized));
    }

    public CompletableFuture<PersonalProfitSnapshot> refresh() {
        return repository.snapshot(clock.instant()).thenApply(updated -> {
            snapshot.set(updated);
            return updated;
        });
    }
}
