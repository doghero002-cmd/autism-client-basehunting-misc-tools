package com.example.donutflipscanner.diagnostics;

import com.example.donutflipscanner.api.ApiConnectionState;
import com.example.donutflipscanner.market.scanner.MarketScannerState;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record DiagnosticsSnapshot(
        Instant generatedAt,
        DiagnosticsVersions versions,
        MarketScannerState scannerState,
        ApiConnectionState apiState,
        long attemptedApiRequests,
        long successfulApiRequests,
        long failedApiRequests,
        int requestsInCurrentWindow,
        Duration averageRequestLatency,
        DatabaseRecordCounts databaseRecords,
        int latestMigrationVersion,
        PerformanceSnapshot performance,
        Optional<String> lastSanitizedError
) {
    public DiagnosticsSnapshot {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(scannerState, "scannerState");
        Objects.requireNonNull(apiState, "apiState");
        Objects.requireNonNull(averageRequestLatency, "averageRequestLatency");
        Objects.requireNonNull(databaseRecords, "databaseRecords");
        Objects.requireNonNull(performance, "performance");
        lastSanitizedError = Objects.requireNonNullElse(lastSanitizedError, Optional.empty());
        if (attemptedApiRequests < 0 || successfulApiRequests < 0 || failedApiRequests < 0
                || requestsInCurrentWindow < 0 || latestMigrationVersion < 0) {
            throw new IllegalArgumentException("diagnostic counters must not be negative");
        }
    }
}
