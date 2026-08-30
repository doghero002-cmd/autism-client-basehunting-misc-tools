package com.example.donutflipscanner.market.scanner;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ScanBatchResult(
        int receivedRecords,
        int changedRecords,
        int duplicateRecords,
        int invalidRecords,
        Set<String> changedFingerprints,
        Set<String> knownActiveListingKeys,
        Optional<String> pageHash,
        Optional<String> lastProcessedKey,
        Instant responseReceivedAt
) {
    public ScanBatchResult {
        if (receivedRecords < 0 || changedRecords < 0 || duplicateRecords < 0 || invalidRecords < 0
                || changedRecords + duplicateRecords + invalidRecords > receivedRecords) {
            throw new IllegalArgumentException("scan batch counts are invalid");
        }
        changedFingerprints = Set.copyOf(Objects.requireNonNull(changedFingerprints, "changedFingerprints"));
        knownActiveListingKeys = Set.copyOf(
                Objects.requireNonNull(knownActiveListingKeys, "knownActiveListingKeys")
        );
        pageHash = Objects.requireNonNullElse(pageHash, Optional.empty());
        lastProcessedKey = Objects.requireNonNullElse(lastProcessedKey, Optional.empty());
        Objects.requireNonNull(responseReceivedAt, "responseReceivedAt");
    }

    public static ScanBatchResult empty(Instant receivedAt) {
        return new ScanBatchResult(
                0, 0, 0, 0, Set.of(), Set.of(), Optional.empty(), Optional.empty(), receivedAt
        );
    }
}
