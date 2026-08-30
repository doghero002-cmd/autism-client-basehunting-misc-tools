package com.example.donutflipscanner.provider;

import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.database.entity.OpportunityListingView;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import com.example.donutflipscanner.diagnostics.PerformanceOperation;
import com.example.donutflipscanner.market.opportunity.OpportunityDetector;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.profit.PersonalProfitTracker;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.google.gson.JsonParser;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Builds immutable GUI snapshots asynchronously; provider getters only read the atomic snapshot. */
public final class LiveMarketSnapshotService {
    private static final int MAXIMUM_GUI_ROWS = 500;
    public static final Duration MAXIMUM_ACTIONABLE_VERIFICATION_AGE = Duration.ofSeconds(15);

    private final ListingRepository listings;
    private final SaleRepository sales;
    private final OpportunityRepository opportunities;
    private final Optional<OpportunityDetector> detector;
    private final PerformanceMetrics performanceMetrics;
    private final Clock clock;
    private final Duration maximumVerificationAge;
    private final Optional<PersonalProfitTracker> personalProfitTracker;
    private final AtomicReference<LiveMarketSnapshot> snapshot = new AtomicReference<>(LiveMarketSnapshot.empty());
    private final AtomicReference<CompletableFuture<LiveMarketSnapshot>> refreshInFlight = new AtomicReference<>();
    private final CopyOnWriteArrayList<Consumer<LiveMarketSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public LiveMarketSnapshotService(
            ListingRepository listings,
            SaleRepository sales,
            OpportunityRepository opportunities,
            Optional<OpportunityDetector> detector
    ) {
        this(listings, sales, opportunities, detector, Optional.empty(), new PerformanceMetrics(),
                Clock.systemUTC(), MAXIMUM_ACTIONABLE_VERIFICATION_AGE);
    }

    public LiveMarketSnapshotService(
            ListingRepository listings,
            SaleRepository sales,
            OpportunityRepository opportunities,
            Optional<OpportunityDetector> detector,
            PersonalProfitTracker personalProfitTracker
    ) {
        this(listings, sales, opportunities, detector, Optional.of(personalProfitTracker),
                new PerformanceMetrics(), Clock.systemUTC(), MAXIMUM_ACTIONABLE_VERIFICATION_AGE);
    }

    public LiveMarketSnapshotService(
            ListingRepository listings,
            SaleRepository sales,
            OpportunityRepository opportunities,
            Optional<OpportunityDetector> detector,
            PerformanceMetrics performanceMetrics
    ) {
        this(listings, sales, opportunities, detector, Optional.empty(), performanceMetrics,
                Clock.systemUTC(), MAXIMUM_ACTIONABLE_VERIFICATION_AGE);
    }

    public LiveMarketSnapshotService(
            ListingRepository listings,
            SaleRepository sales,
            OpportunityRepository opportunities,
            Optional<OpportunityDetector> detector,
            PerformanceMetrics performanceMetrics,
            Clock clock,
            Duration maximumVerificationAge
    ) {
        this(listings, sales, opportunities, detector, Optional.empty(), performanceMetrics,
                clock, maximumVerificationAge);
    }

    private LiveMarketSnapshotService(
            ListingRepository listings,
            SaleRepository sales,
            OpportunityRepository opportunities,
            Optional<OpportunityDetector> detector,
            Optional<PersonalProfitTracker> personalProfitTracker,
            PerformanceMetrics performanceMetrics,
            Clock clock,
            Duration maximumVerificationAge
    ) {
        this.listings = Objects.requireNonNull(listings, "listings");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.opportunities = Objects.requireNonNull(opportunities, "opportunities");
        this.detector = Objects.requireNonNullElse(detector, Optional.empty());
        this.personalProfitTracker = Objects.requireNonNullElse(personalProfitTracker, Optional.empty());
        this.performanceMetrics = Objects.requireNonNull(performanceMetrics, "performanceMetrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumVerificationAge = Objects.requireNonNull(
                maximumVerificationAge, "maximumVerificationAge"
        );
        if (maximumVerificationAge.isNegative() || maximumVerificationAge.isZero()) {
            throw new IllegalArgumentException("maximumVerificationAge must be positive");
        }
    }

    public LiveMarketSnapshot snapshot() {
        return snapshot.get();
    }

    /**
     * Registers a non-blocking publication listener. The returned action removes the listener and is
     * safe to call more than once. Listener failures are isolated from snapshot refreshes.
     */
    public Runnable subscribe(Consumer<LiveMarketSnapshot> listener) {
        Consumer<LiveMarketSnapshot> safeListener = Objects.requireNonNull(listener, "listener");
        listeners.add(safeListener);
        return () -> listeners.remove(safeListener);
    }

    public synchronized CompletableFuture<LiveMarketSnapshot> refresh() {
        CompletableFuture<LiveMarketSnapshot> existing = refreshInFlight.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        Instant refreshedAt = clock.instant();
        Instant verifiedAfter = refreshedAt.minus(maximumVerificationAge);
        CompletableFuture<LiveMarketSnapshot> created = performanceMetrics.measureAsync(
                PerformanceOperation.GUI_SNAPSHOT_GENERATION,
                () -> listings.markUnverifiedBefore(verifiedAfter)
                        .thenCompose(ignored -> opportunities.expireUnverifiedActive(
                                verifiedAfter, refreshedAt
                        ))
                        .thenApply(expired -> {
                            detector.ifPresent(value -> expired.forEach(id ->
                                    value.updateState(id, OpportunityState.NO_LONGER_AVAILABLE)
                            ));
                            return expired;
                        })
                        .thenCompose(ignored -> buildSnapshot(verifiedAfter, refreshedAt))
        ).handle((updated, error) -> {
            if (error == null) {
                snapshot.set(updated);
                publish(updated);
                return updated;
            }
            LiveMarketSnapshot previous = snapshot.get();
            LiveMarketSnapshot unavailable = new LiveMarketSnapshot(
                    previous.activeListings(), previous.storedTransactions(), previous.databaseOpportunityCount(),
                    previous.activeOpportunityCount(), previous.combinedPotentialProfit(),
                    previous.activeOpportunities(), previous.history(), previous.refreshedAt(), false,
                    Optional.of("Market database is temporarily unavailable; showing the last snapshot.")
            );
            snapshot.set(unavailable);
            publish(unavailable);
            return unavailable;
        });
        refreshInFlight.set(created);
        created.whenComplete((ignored, error) -> refreshInFlight.compareAndSet(created, null));
        return created;
    }

    private void publish(LiveMarketSnapshot value) {
        for (Consumer<LiveMarketSnapshot> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException ignored) {
                // Snapshot generation and the remaining subscribers must not be disrupted.
            }
        }
    }

    private CompletableFuture<LiveMarketSnapshot> buildSnapshot(
            Instant verifiedAfter,
            Instant refreshedAt
    ) {
        CompletableFuture<Long> listingCount = listings.countActive();
        CompletableFuture<Long> saleCount = sales.count();
        CompletableFuture<Long> opportunityCount = opportunities.count();
        CompletableFuture<Long> activeCount = opportunities.countActive();
        CompletableFuture<List<OpportunityListingView>> rows =
                opportunities.findRecentWithListings(MAXIMUM_GUI_ROWS, verifiedAfter);
        return CompletableFuture.allOf(listingCount, saleCount, opportunityCount, activeCount, rows)
                .thenApply(ignored -> build(
                        listingCount.join(), saleCount.join(), opportunityCount.join(), activeCount.join(), rows.join(),
                        refreshedAt
                ));
    }

    public CompletableFuture<Boolean> updateState(
            String opportunityId,
            OpportunityState state,
            String reason
    ) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(state, "state");
        Optional<String> safeReason = reason == null || reason.isBlank()
                ? Optional.empty() : Optional.of(reason);
        return opportunities.updateState(opportunityId, state.name(), clock.instant(), safeReason)
                .thenCompose(updated -> {
                    if (updated) {
                        detector.ifPresent(value -> value.updateState(opportunityId, state));
                    }
                    return refreshAfterCurrent().thenApply(ignored -> updated);
                });
    }

    public CompletableFuture<Boolean> confirmManualPurchase(String opportunityId) {
        Objects.requireNonNull(opportunityId, "opportunityId");
        if (personalProfitTracker.isEmpty()) {
            return updateState(
                    opportunityId, OpportunityState.PURCHASED_MANUALLY,
                    "Marked manually purchased by user"
            );
        }
        return personalProfitTracker.orElseThrow().confirmPurchase(opportunityId)
                .thenCompose(confirmed -> {
                    if (confirmed) {
                        detector.ifPresent(value -> value.updateState(
                                opportunityId, OpportunityState.PURCHASED_MANUALLY
                        ));
                    }
                    return refreshAfterCurrent().thenApply(ignored -> confirmed);
                });
    }

    public CompletableFuture<Integer> clearHistory() {
        return opportunities.deleteHistory().thenCompose(deleted ->
                refreshAfterCurrent().thenApply(ignored -> deleted)
        );
    }

    private CompletableFuture<LiveMarketSnapshot> refreshAfterCurrent() {
        CompletableFuture<LiveMarketSnapshot> current = refreshInFlight.get();
        if (current == null) {
            return refresh();
        }
        return current.handle((ignored, error) -> null).thenCompose(ignored -> refresh());
    }

    private static LiveMarketSnapshot build(
            long listingCount,
            long saleCount,
            long opportunityCount,
            long activeCount,
            List<OpportunityListingView> rows,
            Instant refreshedAt
    ) {
        List<MarketOpportunitySnapshot> mapped = rows.stream().map(LiveMarketSnapshotService::map).toList();
        List<MarketOpportunitySnapshot> active = mapped.stream()
                .filter(value -> value.state().equals(OpportunityState.NEW.name())
                        || value.state().equals(OpportunityState.REVIEWED.name()))
                .toList();
        List<MarketOpportunitySnapshot> history = mapped.stream()
                .filter(value -> !value.state().equals(OpportunityState.NEW.name())
                        && !value.state().equals(OpportunityState.REVIEWED.name()))
                .toList();
        BigDecimal combinedProfit = active.stream()
                .map(MarketOpportunitySnapshot::estimatedProfit)
                .filter(value -> value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LiveMarketSnapshot(
                listingCount, saleCount, opportunityCount, activeCount, combinedProfit,
                active, history, Optional.of(refreshedAt), true, Optional.empty()
        );
    }

    private static MarketOpportunitySnapshot map(OpportunityListingView row) {
        OpportunityEntity value = row.opportunity();
        return new MarketOpportunitySnapshot(
                value.opportunityId(), value.listingKey(), value.itemFingerprint(),
                row.itemId(), row.itemCount(), value.purchasePrice(),
                value.fairValue(), value.estimatedProfit(), value.roiPercent(), value.confidencePercent(),
                comparableSales(value.evaluationJson()), riskLevel(value.evaluationJson()),
                value.state(), value.detectedAt(), row.listedAt(), row.sellerName(),
                row.lastVerifiedAt(), value.rejectionReason(), value.evaluationJson(),
                row.normalizedItemMetadata()
        );
    }

    private static int comparableSales(Optional<String> json) {
        try {
            return json.map(JsonParser::parseString)
                    .filter(value -> value.isJsonObject())
                    .map(value -> value.getAsJsonObject().get("acceptedComparableSales"))
                    .filter(java.util.Objects::nonNull)
                    .map(value -> Math.max(0, value.getAsInt()))
                    .orElse(0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static MarketRiskLevel riskLevel(Optional<String> json) {
        try {
            return json.map(JsonParser::parseString)
                    .filter(value -> value.isJsonObject())
                    .map(value -> value.getAsJsonObject().get("riskLevel"))
                    .filter(java.util.Objects::nonNull)
                    .map(value -> MarketRiskLevel.valueOf(value.getAsString()))
                    .orElse(MarketRiskLevel.UNKNOWN);
        } catch (RuntimeException ignored) {
            return MarketRiskLevel.UNKNOWN;
        }
    }
}
