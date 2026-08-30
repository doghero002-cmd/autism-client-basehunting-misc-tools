package com.example.donutflipscanner.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ApiRequestScheduler implements AutoCloseable {
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ScheduledThreadPoolExecutor executor;

    public ApiRequestScheduler() {
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "donut-api-scheduler-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public CompletableFuture<Void> delay(Duration duration) {
        return schedule(() -> CompletableFuture.completedFuture(null), duration);
    }

    public <T> CompletableFuture<T> schedule(
            Supplier<? extends CompletionStage<T>> action,
            Duration delay
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(delay, "delay");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                action.get().whenComplete((value, error) -> {
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        try {
            executor.schedule(task, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
