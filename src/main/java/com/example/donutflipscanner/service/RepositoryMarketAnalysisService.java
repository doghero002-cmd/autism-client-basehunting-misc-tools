package com.example.donutflipscanner.service;

import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.market.item.NormalizedItemPersistenceMapper;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.example.donutflipscanner.market.opportunity.OpportunityDetectionResult;
import com.example.donutflipscanner.market.opportunity.OpportunityDetector;
import com.example.donutflipscanner.market.opportunity.OpportunityEvaluation;
import com.example.donutflipscanner.market.opportunity.OpportunityEvaluationConfig;
import com.example.donutflipscanner.market.opportunity.OpportunityEvaluationRequest;
import com.example.donutflipscanner.market.opportunity.OpportunityPersistenceMapper;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.market.statistics.MarketStatisticsConfig;
import com.example.donutflipscanner.market.statistics.RepositoryMarketStatisticsService;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.value.FairValueMarketContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Recalculates completed-sale statistics and evaluates active asks after an ingestion batch.
 * Work remains on repository futures and never runs on Minecraft's render thread.
 */
public final class RepositoryMarketAnalysisService implements MarketStatisticsRefreshService {
    public static final int MAXIMUM_ACTIVE_LISTINGS_PER_ITEM = 100;
    public static final int MAXIMUM_ACTIVE_FINGERPRINTS = 2_000;

    private final FingerprintRepository fingerprints;
    private final ListingRepository listings;
    private final OpportunityRepository opportunities;
    private final SaleRepository sales;
    private final RepositoryMarketStatisticsService statistics;
    private final OpportunityDetector detector;
    private final NormalizedItemPersistenceMapper itemMapper = new NormalizedItemPersistenceMapper();
    private final OpportunityPersistenceMapper opportunityMapper = new OpportunityPersistenceMapper();
    private final AtomicReference<OpportunityEvaluationConfig> evaluationConfig;
    private final AtomicLong completedSalesVersion = new AtomicLong();
    private final AtomicLong filterVersion = new AtomicLong();

    public RepositoryMarketAnalysisService(
            FingerprintRepository fingerprints,
            ListingRepository listings,
            OpportunityRepository opportunities,
            SaleRepository sales,
            RepositoryMarketStatisticsService statistics,
            OpportunityDetector detector,
            OpportunityEvaluationConfig evaluationConfig
    ) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.listings = Objects.requireNonNull(listings, "listings");
        this.opportunities = Objects.requireNonNull(opportunities, "opportunities");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.evaluationConfig = new AtomicReference<>(Objects.requireNonNull(evaluationConfig, "evaluationConfig"));
    }

    @Override
    public CompletableFuture<Integer> refresh(Set<String> changedFingerprints, Instant calculatedAt) {
        List<String> snapshot = Set.copyOf(Objects.requireNonNull(changedFingerprints, "changedFingerprints"))
                .stream().sorted().toList();
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return sales.count().thenCompose(count -> {
            completedSalesVersion.accumulateAndGet(count, Math::max);
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (String fingerprint : snapshot) {
                chain = chain.thenCompose(total -> refreshFingerprint(fingerprint, calculatedAt)
                        .thenApply(changed -> total + (changed ? 1 : 0)));
            }
            return chain;
        });
    }

    /** Applies a UI filter change and reevaluates the current bounded active set asynchronously. */
    public CompletableFuture<Void> updateFilterPolicy(ItemFilterPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        OpportunityEvaluationConfig current = evaluationConfig.get();
        OpportunityEvaluationConfig updated = new OpportunityEvaluationConfig(
                current.evaluationVersion(), current.configurationVersion(),
                "live-filters-v" + filterVersion.incrementAndGet(), policy,
                current.supportedItemPolicy(), current.fairValueConfig(), current.profitConfig(),
                current.confidenceConfig(), current.riskConfig(), current.thresholds(),
                current.alertCooldown(), current.staleReevaluationInterval()
        );
        evaluationConfig.set(updated);
        return listings.findActiveFingerprints(MAXIMUM_ACTIVE_FINGERPRINTS)
                .thenCompose(values -> refresh(Set.copyOf(values), Instant.now()))
                .thenApply(ignored -> null);
    }

    private CompletableFuture<Boolean> refreshFingerprint(String fingerprint, Instant now) {
        CompletableFuture<Optional<ItemFingerprintEntity>> storedItem = fingerprints.find(fingerprint);
        CompletableFuture<List<ListingEntity>> activeListings =
                listings.findActiveByFingerprint(fingerprint, MAXIMUM_ACTIVE_LISTINGS_PER_ITEM);
        return storedItem.thenCombine(activeListings, AnalysisInput::new)
                .thenCompose(input -> {
                    if (input.item().isEmpty() || input.listings().isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    ItemFingerprintEntity entity = input.item().orElseThrow();
                    NormalizedItem statisticsItem;
                    try {
                        statisticsItem = itemMapper.fromEntity(entity, input.listings().getFirst().itemCount());
                    } catch (RuntimeException invalidStoredItem) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return marketContext(statisticsItem).thenCompose(context ->
                            evaluateListings(entity, input.listings(), context, now)
                                    .thenApply(ignored -> true));
                });
    }

    private CompletableFuture<FairValueMarketContext> marketContext(NormalizedItem item) {
        var valueConfig = evaluationConfig.get().fairValueConfig();
        MarketStatisticsConfig defaults = MarketStatisticsConfig.defaults()
                .withMinimumComparableSales(valueConfig.minimumCompletedSales());
        CompletableFuture<ItemMarketStatistics> primary = statistics.statisticsFor(
                item, defaults.withLookback(valueConfig.primaryLookback())
        );
        CompletableFuture<ItemMarketStatistics> recent = statistics.statisticsFor(
                item, defaults.withLookback(valueConfig.recentLookback())
        );
        CompletableFuture<ItemMarketStatistics> longTerm = statistics.statisticsFor(
                item, defaults.withLookback(valueConfig.longTermLookback())
        );
        return CompletableFuture.allOf(primary, recent, longTerm).thenApply(ignored ->
                new FairValueMarketContext(primary.join(), recent.join(), longTerm.join())
        );
    }

    private CompletableFuture<Void> evaluateListings(
            ItemFingerprintEntity entity,
            List<ListingEntity> values,
            FairValueMarketContext market,
            Instant now
    ) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ListingEntity listing : values) {
            chain = chain.thenCompose(ignored -> evaluateListing(entity, listing, market, now));
        }
        return chain;
    }

    private CompletableFuture<Void> evaluateListing(
            ItemFingerprintEntity entity,
            ListingEntity listing,
            FairValueMarketContext market,
            Instant now
    ) {
        NormalizedItem item;
        try {
            item = itemMapper.fromEntity(entity, listing.itemCount());
        } catch (RuntimeException invalidStoredItem) {
            return CompletableFuture.completedFuture(null);
        }
        Duration marketAge = nonNegative(Duration.between(market.primary().calculatedAt(), now));
        Duration variantAge = nonNegative(Duration.between(entity.createdAt(), now));
        OpportunityEvaluationRequest request = new OpportunityEvaluationRequest(
                listing, item, market, Optional.empty(), BigDecimal.ZERO, marketAge,
                Optional.of(variantAge), completedSalesVersion.get(), false
        );
        OpportunityDetectionResult detection = detector.evaluate(request, evaluationConfig.get(), now);
        if (detection.evaluation().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        OpportunityEvaluation evaluated = detection.evaluation().orElseThrow();
        return opportunities.find(evaluated.opportunityId()).thenCompose(previous -> {
            if (!evaluated.accepted() && previous.map(this::isTerminalManualState).orElse(false)) {
                return CompletableFuture.completedFuture(null);
            }
            OpportunityEvaluation preserved = preserveManualState(evaluated, previous);
            if (!preserved.accepted() && previous.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return opportunities.upsert(opportunityMapper.toEntity(preserved));
        });
    }

    private OpportunityEvaluation preserveManualState(
            OpportunityEvaluation evaluated,
            Optional<OpportunityEntity> previous
    ) {
        if (previous.isEmpty()) {
            return evaluated;
        }
        OpportunityState state;
        try {
            state = OpportunityState.valueOf(previous.orElseThrow().state());
        } catch (IllegalArgumentException unknownState) {
            return evaluated;
        }
        if (state == OpportunityState.DISMISSED || state == OpportunityState.PURCHASED_MANUALLY
                || (state == OpportunityState.REVIEWED && evaluated.accepted())) {
            detector.updateState(evaluated.opportunityId(), state);
            return evaluated.withState(state);
        }
        return evaluated;
    }

    private boolean isTerminalManualState(OpportunityEntity entity) {
        try {
            OpportunityState state = OpportunityState.valueOf(entity.state());
            return state == OpportunityState.DISMISSED || state == OpportunityState.PURCHASED_MANUALLY;
        } catch (IllegalArgumentException unknownState) {
            return false;
        }
    }

    private static Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    private record AnalysisInput(
            Optional<ItemFingerprintEntity> item,
            List<ListingEntity> listings
    ) {
    }
}
