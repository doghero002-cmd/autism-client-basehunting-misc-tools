package com.example.donutflipscanner.service;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiSeller;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.database.BatchWriteResult;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.StableMarketKeyFactory;
import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import com.example.donutflipscanner.diagnostics.PerformanceOperation;
import com.example.donutflipscanner.market.item.ApiItemDescriptorMapper;
import com.example.donutflipscanner.market.item.ItemFingerprintPersistenceMapper;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.SafeItemCategoryRegistry;
import com.example.donutflipscanner.market.item.StackPriceNormalizer;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.scanner.ScanBatchResult;
import com.example.donutflipscanner.profit.CompletedSalesObserver;
import com.example.donutflipscanner.util.HashingUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates, normalizes, fingerprints, hashes, and persists whole API pages asynchronously.
 * The undocumented listing time-left unit is intentionally not converted into an expiration.
 */
public final class RepositoryMarketDataIngestionService implements MarketDataIngestionService {
    private static final int MAXIMUM_PAGE_HASH_ENTRIES = 512;
    private final DatabaseManager database;
    private final ListingRepository listings;
    private final SaleRepository sales;
    private final FingerprintRepository fingerprints;
    private final MarketStatisticsRefreshService statisticsRefresh;
    private final ConfigurationSaveService configurationSave;
    private final MarketRetentionPolicy retention;
    private final ApiItemDescriptorMapper descriptorMapper = new ApiItemDescriptorMapper();
    private final ItemNormalizer itemNormalizer = new ItemNormalizer(
            SafeItemCategoryRegistry.liveDefaults(), ItemNormalizer.DEFAULT_MAXIMUM_CONTAINER_DEPTH
    );
    private final ItemFingerprintPersistenceMapper fingerprintMapper = new ItemFingerprintPersistenceMapper();
    private final StackPriceNormalizer priceNormalizer = new StackPriceNormalizer();
    private final Map<String, String> pageHashes = new LinkedHashMap<>(32, 0.75F, true);
    private final PerformanceMetrics performanceMetrics;
    private final CompletedSalesObserver completedSalesObserver;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RepositoryMarketDataIngestionService(
            DatabaseManager database,
            MarketStatisticsRefreshService statisticsRefresh,
            ConfigurationSaveService configurationSave,
            MarketRetentionPolicy retention
    ) {
        this(database, statisticsRefresh, configurationSave, retention,
                new PerformanceMetrics(), CompletedSalesObserver.noOp());
    }

    public RepositoryMarketDataIngestionService(
            DatabaseManager database,
            MarketStatisticsRefreshService statisticsRefresh,
            ConfigurationSaveService configurationSave,
            MarketRetentionPolicy retention,
            PerformanceMetrics performanceMetrics
    ) {
        this(database, statisticsRefresh, configurationSave, retention,
                performanceMetrics, CompletedSalesObserver.noOp());
    }

    public RepositoryMarketDataIngestionService(
            DatabaseManager database,
            MarketStatisticsRefreshService statisticsRefresh,
            ConfigurationSaveService configurationSave,
            MarketRetentionPolicy retention,
            PerformanceMetrics performanceMetrics,
            CompletedSalesObserver completedSalesObserver
    ) {
        this.database = Objects.requireNonNull(database, "database");
        listings = new ListingRepository(database);
        sales = new SaleRepository(database);
        fingerprints = new FingerprintRepository(database);
        this.statisticsRefresh = Objects.requireNonNull(statisticsRefresh, "statisticsRefresh");
        this.configurationSave = Objects.requireNonNull(configurationSave, "configurationSave");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.performanceMetrics = Objects.requireNonNull(performanceMetrics, "performanceMetrics");
        this.completedSalesObserver = Objects.requireNonNull(completedSalesObserver, "completedSalesObserver");
    }

    @Override
    public CompletableFuture<ScanBatchResult> ingestListings(
            String sourceKey,
            ApiAuctionPage page,
            Instant receivedAt
    ) {
        requireOpen();
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(receivedAt, "receivedAt");
        List<NormalizedListing> valid = page.listings().stream()
                .map(value -> normalizeListing(value, receivedAt))
                .flatMap(Optional::stream)
                .toList();
        int invalid = page.listings().size() - valid.size();
        String hash = pageHash(valid.stream().map(value -> value.entity().listingKey()).toList());
        Set<String> activeKeys = valid.stream().map(value -> value.entity().listingKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Optional<String> lastKey = valid.isEmpty()
                ? Optional.empty() : Optional.of(valid.getLast().entity().listingKey());
        String previousHash = previousPageHash(sourceKey);
        if (hash.equals(previousHash)) {
            return listings.markObserved(activeKeys, receivedAt).thenApply(ignored ->
                    new ScanBatchResult(
                            page.listings().size(), 0, valid.size(), invalid, Set.of(), activeKeys,
                            Optional.of(hash), lastKey, receivedAt
                    )
            );
        }
        List<ItemFingerprintEntity> fingerprintEntities = uniqueFingerprints(valid.stream()
                .map(NormalizedListing::item).toList(), receivedAt);
        List<ListingEntity> entities = valid.stream().map(NormalizedListing::entity).toList();
        Set<String> changedFingerprints = valid.stream().map(value -> value.item().fingerprint().sha256())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return performanceMetrics.measureAsync(PerformanceOperation.DATABASE_INSERTION, () ->
                fingerprints.insertBatch(fingerprintEntities)
                .thenCompose(ignored -> listings.upsertBatch(entities))
                .thenApply(ignored -> {
                    rememberPageHash(sourceKey, hash);
                    return new ScanBatchResult(
                            page.listings().size(), valid.size(), 0, invalid, changedFingerprints, activeKeys,
                            Optional.of(hash), lastKey, receivedAt
                    );
                }));
    }

    @Override
    public CompletableFuture<ScanBatchResult> ingestTransactions(
            String sourceKey,
            ApiTransactionPage page,
            Instant receivedAt
    ) {
        requireOpen();
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(receivedAt, "receivedAt");
        List<NormalizedSale> valid = page.transactions().stream()
                .map(value -> normalizeSale(value, receivedAt))
                .flatMap(Optional::stream)
                .toList();
        int invalid = page.transactions().size() - valid.size();
        List<SaleEntity> entities = valid.stream().map(NormalizedSale::entity).toList();
        String hash = pageHash(valid.stream().map(value -> value.entity().saleKey()).toList());
        Optional<String> lastKey = valid.isEmpty()
                ? Optional.empty() : Optional.of(valid.getLast().entity().saleKey());
        String previousHash = previousPageHash(sourceKey);
        if (hash.equals(previousHash)) {
            return completedSalesObserver.observe(entities).thenApply(ignored ->
                    new ScanBatchResult(
                            page.transactions().size(), 0, valid.size(), invalid, Set.of(), Set.of(),
                            Optional.of(hash), lastKey, receivedAt
                    )
            );
        }
        List<ItemFingerprintEntity> fingerprintEntities = uniqueFingerprints(valid.stream()
                .map(NormalizedSale::item).toList(), receivedAt);
        Set<String> candidateFingerprints = valid.stream().map(value -> value.item().fingerprint().sha256())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return performanceMetrics.measureAsync(PerformanceOperation.DATABASE_INSERTION, () ->
                fingerprints.insertBatch(fingerprintEntities)
                .thenCompose(ignored -> sales.insertBatch(entities))
                .thenCompose(write -> completedSalesObserver.observe(entities).thenApply(ignored -> {
                    rememberPageHash(sourceKey, hash);
                    return transactionResult(page, receivedAt, invalid, hash, lastKey,
                            candidateFingerprints, write);
                })));
    }

    @Override
    public CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints) {
        requireOpen();
        Set<String> snapshot = Set.copyOf(Objects.requireNonNull(changedFingerprints, "changedFingerprints"));
        Instant now = Instant.now();
        return statisticsRefresh.refresh(snapshot, now).thenApply(changed -> {
            if (changed < 0 || changed > snapshot.size()) {
                throw new IllegalStateException("statistics refresh returned an invalid changed count");
            }
            return new ScanBatchResult(
                    snapshot.size(), changed, snapshot.size() - changed, 0,
                    changed > 0 ? snapshot : Set.of(), Set.of(), Optional.empty(), Optional.empty(), now
            );
        });
    }

    @Override
    public CompletableFuture<ScanBatchResult> runRetentionCleanup() {
        requireOpen();
        Instant now = Instant.now();
        return listings.deleteStaleInactiveBefore(
                        now.minus(retention.inactiveListingRetention()), retention.cleanupBatchLimit()
                )
                .thenCompose(deleted -> listings.clearStaleRawJsonBefore(
                        now.minus(retention.rawListingJsonRetention()), retention.cleanupBatchLimit()
                ).thenApply(cleared -> deleted + cleared))
                .thenApply(changed -> new ScanBatchResult(
                        changed, changed, 0, 0, Set.of(), Set.of(),
                        Optional.empty(), Optional.empty(), now
                ));
    }

    @Override
    public CompletableFuture<Void> flushPendingWrites() {
        return closed.get() ? CompletableFuture.completedFuture(null) : database.barrier();
    }

    @Override
    public CompletableFuture<Void> saveConfiguration() {
        return configurationSave.save();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            database.close();
        }
    }

    private Optional<NormalizedListing> normalizeListing(ApiAuctionListing raw, Instant receivedAt) {
        if (raw.item().isEmpty() || raw.price().isEmpty() || raw.price().orElseThrow().signum() <= 0) {
            return Optional.empty();
        }
        Optional<String> sellerIdentity = sellerIdentity(raw.seller());
        if (sellerIdentity.isEmpty()) {
            return Optional.empty();
        }
        Optional<NormalizedItem> item = normalizedItem(raw.item().orElseThrow());
        if (item.isEmpty()) {
            return Optional.empty();
        }
        NormalizedItem normalized = item.orElseThrow();
        int count = normalized.stackCount().orElse(0);
        if (count < 1) {
            return Optional.empty();
        }
        BigDecimal price = raw.price().orElseThrow();
        String key = StableMarketKeyFactory.listingKey(
                Optional.empty(), sellerIdentity.orElseThrow(), normalized.fingerprint().sha256(),
                price, count, Optional.empty(), Optional.empty()
        );
        ListingEntity entity = new ListingEntity(
                key, Optional.empty(), raw.seller().flatMap(ApiSeller::uuid),
                raw.seller().flatMap(ApiSeller::name), normalized.fingerprint().sha256(),
                normalized.itemId(), count, price, priceNormalizer.unitPrice(normalized, price),
                receivedAt, receivedAt, Optional.empty(), Optional.empty(), ListingState.ACTIVE,
                0, Optional.empty()
        );
        return Optional.of(new NormalizedListing(normalized, entity));
    }

    private Optional<NormalizedSale> normalizeSale(ApiCompletedTransaction raw, Instant receivedAt) {
        if (raw.item().isEmpty() || raw.price().isEmpty() || raw.price().orElseThrow().signum() <= 0
                || raw.soldAt().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> sellerIdentity = sellerIdentity(raw.seller());
        if (sellerIdentity.isEmpty()) {
            return Optional.empty();
        }
        Optional<NormalizedItem> item = normalizedItem(raw.item().orElseThrow());
        if (item.isEmpty()) {
            return Optional.empty();
        }
        NormalizedItem normalized = item.orElseThrow();
        int count = normalized.stackCount().orElse(0);
        if (count < 1) {
            return Optional.empty();
        }
        BigDecimal price = raw.price().orElseThrow();
        Instant soldAt = raw.soldAt().orElseThrow();
        String key = StableMarketKeyFactory.saleKey(
                Optional.empty(), sellerIdentity.orElseThrow(), "", normalized.fingerprint().sha256(),
                price, count, soldAt
        );
        SaleEntity entity = new SaleEntity(
                key, Optional.empty(), raw.seller().flatMap(ApiSeller::uuid),
                raw.seller().flatMap(ApiSeller::name), Optional.empty(), Optional.empty(),
                normalized.fingerprint().sha256(), normalized.itemId(), count, price,
                priceNormalizer.unitPrice(normalized, price), soldAt, receivedAt, Optional.empty()
        );
        return Optional.of(new NormalizedSale(normalized, entity));
    }

    private Optional<NormalizedItem> normalizedItem(ApiAuctionItem raw) {
        if (raw.id().isEmpty() || raw.count().isEmpty() || raw.count().getAsInt() < 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(itemNormalizer.normalize(descriptorMapper.map(raw)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> sellerIdentity(Optional<ApiSeller> seller) {
        return seller.flatMap(value -> value.uuid().filter(text -> !text.isBlank())
                .or(() -> value.name().filter(text -> !text.isBlank())));
    }

    private List<ItemFingerprintEntity> uniqueFingerprints(List<NormalizedItem> items, Instant createdAt) {
        Map<String, ItemFingerprintEntity> unique = new LinkedHashMap<>();
        for (NormalizedItem item : items) {
            unique.putIfAbsent(item.fingerprint().sha256(), fingerprintMapper.toEntity(item, createdAt));
        }
        return List.copyOf(unique.values());
    }

    private static String pageHash(List<String> keys) {
        List<String> fields = new ArrayList<>(keys.size() + 1);
        fields.add(Integer.toString(keys.size()));
        fields.addAll(keys);
        return HashingUtil.sha256Fields(fields);
    }

    private static ScanBatchResult transactionResult(
            ApiTransactionPage page,
            Instant receivedAt,
            int invalid,
            String hash,
            Optional<String> lastKey,
            Set<String> fingerprints,
            BatchWriteResult write
    ) {
        Set<String> changedFingerprints = write.inserted() > 0 ? fingerprints : Set.of();
        return new ScanBatchResult(
                page.transactions().size(), write.inserted(), write.updatedOrIgnored(), invalid,
                changedFingerprints, Set.of(), Optional.of(hash), lastKey, receivedAt
        );
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Market data ingestion service is closed");
        }
    }

    public synchronized int pageHashCacheSize() {
        return pageHashes.size();
    }

    public synchronized long estimatedPageHashCacheBytes() {
        return pageHashes.entrySet().stream()
                .mapToLong(entry -> 80L + 2L * (entry.getKey().length() + entry.getValue().length()))
                .sum();
    }

    private synchronized String previousPageHash(String sourceKey) {
        return pageHashes.get(sourceKey);
    }

    private synchronized void rememberPageHash(String sourceKey, String hash) {
        pageHashes.put(sourceKey, hash);
        while (pageHashes.size() > MAXIMUM_PAGE_HASH_ENTRIES) {
            pageHashes.remove(pageHashes.keySet().iterator().next());
        }
    }

    private record NormalizedListing(NormalizedItem item, ListingEntity entity) {
    }

    private record NormalizedSale(NormalizedItem item, SaleEntity entity) {
    }
}
