package com.example.donutflipscanner.packet;

import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.market.scanner.ScanBatchResult;
import com.example.donutflipscanner.market.scanner.MarketScanWork;
import com.example.donutflipscanner.service.MarketDataIngestionService;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Packet-based scanner work adapter. Reads auction house data intercepted from
 * Minecraft packets instead of calling the external DonutSMP API.
 */
public final class PacketMarketScanWork implements MarketScanWork {
    private final AuctionHouseDataCapture capture;
    private final MarketDataIngestionService ingestion;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PacketMarketScanWork(AuctionHouseDataCapture capture, MarketDataIngestionService ingestion) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    }

    @Override
    public CompletableFuture<ScanBatchResult> pollRecentlyListed() {
        if (closed.get()) {
            return closedFuture();
        }
        return readAndIngest("packet-recent-listings");
    }

    @Override
    public CompletableFuture<ScanBatchResult> pollCompletedTransactions() {
        if (closed.get()) {
            return closedFuture();
        }
        Instant now = Instant.now();
        return CompletableFuture.completedFuture(ScanBatchResult.empty(now));
    }

    @Override
    public CompletableFuture<ScanBatchResult> refreshActiveListings() {
        if (closed.get()) {
            return closedFuture();
        }
        return readAndIngest("packet-active-listings");
    }

    @Override
    public CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints) {
        if (closed.get()) {
            return closedFuture();
        }
        return ingestion.recalculateStatistics(changedFingerprints);
    }

    @Override
    public CompletableFuture<ScanBatchResult> runRetentionCleanup() {
        if (closed.get()) {
            return closedFuture();
        }
        return ingestion.runRetentionCleanup();
    }

    @Override
    public CompletableFuture<Void> flushPendingWrites() {
        return closed.get() ? CompletableFuture.completedFuture(null) : ingestion.flushPendingWrites();
    }

    @Override
    public CompletableFuture<Void> saveConfiguration() {
        return closed.get() ? CompletableFuture.completedFuture(null) : ingestion.saveConfiguration();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ingestion.close();
    }

    private CompletableFuture<ScanBatchResult> readAndIngest(String sourceKey) {
        Optional<ApiAuctionPage> page = capture.captureCurrentPage();
        if (page.isEmpty()) {
            Instant now = Instant.now();
            return CompletableFuture.completedFuture(ScanBatchResult.empty(now));
        }
        return ingestion.ingestListings(sourceKey, page.orElseThrow(), Instant.now());
    }

    private static <T> CompletableFuture<T> closedFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("Packet scan work is closed"));
    }
}
