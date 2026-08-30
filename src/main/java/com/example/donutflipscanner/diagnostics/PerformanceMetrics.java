package com.example.donutflipscanner.diagnostics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Low-overhead cumulative timings; it stores counters, never unbounded samples. */
public final class PerformanceMetrics {
    private final EnumMap<PerformanceOperation, Accumulator> accumulators =
            new EnumMap<>(PerformanceOperation.class);

    public PerformanceMetrics() {
        for (PerformanceOperation operation : PerformanceOperation.values()) {
            accumulators.put(operation, new Accumulator());
        }
    }

    public void record(PerformanceOperation operation, long elapsedNanos) {
        accumulators.get(Objects.requireNonNull(operation, "operation"))
                .record(Math.max(0L, elapsedNanos));
    }

    public <T> T measure(PerformanceOperation operation, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            record(operation, System.nanoTime() - started);
        }
    }

    public <T> CompletableFuture<T> measureAsync(
            PerformanceOperation operation,
            Supplier<CompletableFuture<T>> action
    ) {
        Objects.requireNonNull(action, "action");
        long started = System.nanoTime();
        try {
            return action.get().whenComplete((ignored, error) ->
                    record(operation, System.nanoTime() - started));
        } catch (RuntimeException error) {
            record(operation, System.nanoTime() - started);
            throw error;
        }
    }

    public PerformanceSnapshot snapshot(
            CacheStatistics caches,
            int databaseQueueSize,
            int opportunityQueueSize
    ) {
        Map<PerformanceOperation, TimingMetricSnapshot> values = new EnumMap<>(PerformanceOperation.class);
        accumulators.forEach((operation, accumulator) -> values.put(operation, accumulator.snapshot()));
        return new PerformanceSnapshot(values, caches, databaseQueueSize, opportunityQueueSize);
    }

    private static final class Accumulator {
        private final LongAdder samples = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maximumNanos = new AtomicLong();

        private void record(long nanos) {
            samples.increment();
            totalNanos.add(nanos);
            maximumNanos.accumulateAndGet(nanos, Math::max);
        }

        private TimingMetricSnapshot snapshot() {
            long count = samples.sum();
            long total = totalNanos.sum();
            return new TimingMetricSnapshot(
                    count,
                    Duration.ofNanos(count == 0 ? 0 : total / count),
                    Duration.ofNanos(maximumNanos.get())
            );
        }
    }
}
