package com.example.donutflipscanner.diagnostics;

import java.util.Map;
import java.util.Objects;

public record PerformanceSnapshot(
        Map<PerformanceOperation, TimingMetricSnapshot> timings,
        CacheStatistics caches,
        int databaseQueueSize,
        int opportunityQueueSize
) {
    public PerformanceSnapshot {
        timings = Map.copyOf(Objects.requireNonNull(timings, "timings"));
        Objects.requireNonNull(caches, "caches");
        if (databaseQueueSize < 0 || opportunityQueueSize < 0) {
            throw new IllegalArgumentException("queue sizes must not be negative");
        }
    }
}
