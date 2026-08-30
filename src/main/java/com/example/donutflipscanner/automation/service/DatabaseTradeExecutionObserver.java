package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.model.TradeExecutionState;
import com.example.donutflipscanner.database.TradeExecutionRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Non-blocking database observer. Persistence failures are exposed without driving the state machine. */
public final class DatabaseTradeExecutionObserver implements TradeExecutionObserver {
    private final TradeExecutionRepository repository;
    private final AtomicReference<String> lastPersistenceFailure = new AtomicReference<>("");

    public DatabaseTradeExecutionObserver(TradeExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void onTransition(
            TradeExecutionRequest request,
            TradeExecutionState previous,
            TradeExecutionState current,
            String message,
            Instant transitionedAt
    ) {
        repository.recordTransition(request, previous, current, message, transitionedAt)
                .exceptionally(failure -> {
                    lastPersistenceFailure.set(rootMessage(failure));
                    return null;
                });
    }

    public String lastPersistenceFailure() {
        return lastPersistenceFailure.get();
    }

    @Override
    public void onOutcome(TradeExecutionRequest request, TradeExecutionResult result, Instant completedAt) {
        repository.recordOutcome(
                request.executionId(), result.relistPlan().map(value -> value.listingPrice()),
                result.purchaseConfirmed(), result.listingConfirmed(), completedAt
        ).exceptionally(failure -> {
            lastPersistenceFailure.set(rootMessage(failure));
            return null;
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return Objects.requireNonNullElse(current.getMessage(), current.getClass().getSimpleName());
    }
}
