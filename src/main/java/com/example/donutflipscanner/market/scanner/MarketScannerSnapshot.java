package com.example.donutflipscanner.market.scanner;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record MarketScannerSnapshot(
        MarketScannerState state,
        ScannerPauseReason pauseReason,
        Optional<Instant> startedAt,
        Instant stateChangedAt,
        Optional<Instant> lastSuccessfulScan,
        Optional<Instant> lastFailedScan,
        Optional<String> lastSanitizedError,
        Map<ScannerActivity, Long> completedRuns,
        Map<ScannerActivity, Long> skippedOverlappingRuns,
        Map<ScannerActivity, Instant> responseTimestamps,
        Map<ScannerActivity, String> pageHashes,
        Optional<String> lastProcessedListing,
        Optional<String> lastProcessedTransaction,
        Set<String> knownActiveListingKeys,
        ScannerDataVersions dataVersions,
        int scheduledActivityCount,
        int inFlightActivityCount,
        int consecutiveTemporaryFailures
) {
    public MarketScannerSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pauseReason, "pauseReason");
        startedAt = Objects.requireNonNullElse(startedAt, Optional.empty());
        Objects.requireNonNull(stateChangedAt, "stateChangedAt");
        lastSuccessfulScan = Objects.requireNonNullElse(lastSuccessfulScan, Optional.empty());
        lastFailedScan = Objects.requireNonNullElse(lastFailedScan, Optional.empty());
        lastSanitizedError = Objects.requireNonNullElse(lastSanitizedError, Optional.empty());
        completedRuns = Map.copyOf(Objects.requireNonNull(completedRuns, "completedRuns"));
        skippedOverlappingRuns = Map.copyOf(
                Objects.requireNonNull(skippedOverlappingRuns, "skippedOverlappingRuns")
        );
        responseTimestamps = Map.copyOf(Objects.requireNonNull(responseTimestamps, "responseTimestamps"));
        pageHashes = Map.copyOf(Objects.requireNonNull(pageHashes, "pageHashes"));
        lastProcessedListing = Objects.requireNonNullElse(lastProcessedListing, Optional.empty());
        lastProcessedTransaction = Objects.requireNonNullElse(lastProcessedTransaction, Optional.empty());
        knownActiveListingKeys = Set.copyOf(
                Objects.requireNonNull(knownActiveListingKeys, "knownActiveListingKeys")
        );
        Objects.requireNonNull(dataVersions, "dataVersions");
        if (scheduledActivityCount < 0 || inFlightActivityCount < 0 || consecutiveTemporaryFailures < 0) {
            throw new IllegalArgumentException("scanner snapshot counts must not be negative");
        }
    }
}
