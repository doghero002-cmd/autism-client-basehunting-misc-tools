package com.example.donutflipscanner.database.entity;

import com.example.donutflipscanner.automation.model.TradeExecutionState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TradeExecutionTransitionEntity(
        long transitionId,
        String executionId,
        Optional<TradeExecutionState> previousState,
        TradeExecutionState newState,
        String message,
        Instant transitionedAt
) {
    public TradeExecutionTransitionEntity {
        if (transitionId < 0) {
            throw new IllegalArgumentException("transitionId must not be negative");
        }
        executionId = EntityChecks.text(executionId, "executionId");
        previousState = EntityChecks.optional(previousState);
        Objects.requireNonNull(newState, "newState");
        message = Objects.requireNonNullElse(message, "");
        Objects.requireNonNull(transitionedAt, "transitionedAt");
    }
}
